package atc.allocator;

import atc.common.AtcConfig;
import atc.common.AtcConfig.RunwayConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Independent checks of the allocator's safety invariants over a set of records. Deliberately
 * simple (pairwise comparison) so it is obviously correct; used by tests and ScenarioRunner.
 * Each method returns human-readable violations; an empty list means PASS.
 */
public final class InvariantChecker {
    private InvariantChecker() {
    }

    /** Invariant 1: no two runway-blocking reservations overlap on one runway, buffers included. */
    public static List<String> overlapViolations(Collection<ReservationRecord> records, long bufferMs) {
        Map<String, List<ReservationRecord>> byRunway = records.stream()
                .filter(ReservationRecord::blocksRunway)
                .collect(Collectors.groupingBy(ReservationRecord::runwayId));
        List<String> violations = new ArrayList<>();
        for (List<ReservationRecord> list : byRunway.values()) {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    ReservationRecord a = list.get(i);
                    ReservationRecord b = list.get(j);
                    if (a.paddedInterval(bufferMs).overlaps(b.paddedInterval(bufferMs))) {
                        violations.add("overlap on " + a.runwayId() + ": " + a.requestId() + " and " + b.requestId());
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Invariant 1 over the WHOLE history in a log, not just the final state: no two aircraft
     * could ever have been on one runway at the same time.
     *
     * Each booking's physical occupancy is [slotStart, min(slotEnd, endedAt)), where endedAt
     * is when it stopped blocking (COMPLETED early, RELEASED, BUMPED, ...). Buffers are not
     * included: after a booking ends the allocator may legitimately book a new slot right
     * behind it. Final-state buffer separation is checked by {@link #overlapViolations}.
     *
     * @param history every record in log order (the same requestId appears once per change)
     */
    public static List<String> historicalOverlapViolations(List<ReservationRecord> history) {
        record Occupancy(String requestId, String runwayId, long start, long end) {
        }
        Map<String, ReservationRecord> firstBlocking = new java.util.LinkedHashMap<>();
        Map<String, Long> endedAt = new java.util.HashMap<>();
        for (ReservationRecord r : history) {
            if (r.blocksRunway()) {
                firstBlocking.putIfAbsent(r.requestId(), r);
            } else if (firstBlocking.containsKey(r.requestId())) {
                endedAt.putIfAbsent(r.requestId(), r.updatedAt());
            }
        }
        List<Occupancy> occupancies = new ArrayList<>();
        for (ReservationRecord r : firstBlocking.values()) {
            long end = Math.min(r.slotEnd(), endedAt.getOrDefault(r.requestId(), Long.MAX_VALUE));
            if (end > r.slotStart()) {
                occupancies.add(new Occupancy(r.requestId(), r.runwayId(), r.slotStart(), end));
            }
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < occupancies.size(); i++) {
            for (int j = i + 1; j < occupancies.size(); j++) {
                Occupancy a = occupancies.get(i);
                Occupancy b = occupancies.get(j);
                if (a.runwayId().equals(b.runwayId()) && a.start() < b.end() && b.start() < a.end()) {
                    violations.add("simultaneous occupancy of " + a.runwayId() + ": " + a.requestId() + " and " + b.requestId());
                }
            }
        }
        return violations;
    }

    /** Invariant 2: every record that names a runway names one its aircraft is eligible for. */
    public static List<String> eligibilityViolations(Collection<ReservationRecord> records, AtcConfig config) {
        List<String> violations = new ArrayList<>();
        for (ReservationRecord r : records) {
            if (r.runwayId() == null) {
                continue;
            }
            // The runway must exist at the requesting airport and fit the aircraft.
            RunwayConfig rw = config.airport(r.airportId()).stream()
                    .flatMap(a -> a.runways().stream())
                    .filter(x -> x.id().equals(r.runwayId()))
                    .findFirst().orElse(null);
            if (rw == null || !Eligibility.isEligible(rw, r.sizeClass())) {
                violations.add(r.requestId() + " (" + r.sizeClass() + ") assigned ineligible runway " + r.runwayId());
            }
        }
        return violations;
    }
}
