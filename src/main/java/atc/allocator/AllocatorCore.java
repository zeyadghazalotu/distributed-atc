package atc.allocator;

import atc.common.AtcClock;
import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.AtcConfig.RunwayConfig;
import atc.common.Complete;
import atc.common.Confirm;
import atc.common.ErrorMessage;
import atc.common.Log;
import atc.common.Message;
import atc.common.Query;
import atc.common.Release;
import atc.common.Reserve;
import atc.common.ReservationState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static atc.common.ReservationState.BUMPED;
import static atc.common.ReservationState.COMPLETED;
import static atc.common.ReservationState.CONFIRMED;
import static atc.common.ReservationState.PENDING_ACK;
import static atc.common.ReservationState.REJECTED;
import static atc.common.ReservationState.RELEASED;
import static atc.common.ReservationState.UNCERTAIN;
import static atc.common.ReservationState.WAIT;

/**
 * The allocator's decision logic and state, with no networking.
 *
 * Not thread-safe on purpose: exactly one thread (the RunwayAllocator loop) calls
 * {@link #handle}. Processing requests one at a time makes "check for conflicts, then
 * reserve" atomic without locks. That is why invariant 1 holds even under simultaneous
 * requests from both airports.
 *
 * Write-ahead rule: every change is appended to the log (fsync) BEFORE it is applied in memory
 * and before a reply is built. If the append fails, memory is unchanged and the caller gets an
 * ERROR, so nothing a client sees can be lost in a crash.
 */
public final class AllocatorCore {
    private static final Log LOG = new Log("allocator");

    /** The reply to send, plus any reservations bumped by an emergency (to notify via events). */
    public record Outcome(Message reply, List<ReservationRecord> bumped) {
        static Outcome of(Message reply) {
            return new Outcome(reply, List.of());
        }
    }

    /** A possible slot for a request; {@code bumped} is non-empty only for emergencies. */
    private record Candidate(RunwayConfig runway, long start, List<ReservationRecord> bumped) {
    }

    /** Earliest slot first; then fewest bumps; then the shortest adequate runway (keeps long runways free for heavies). */
    private static final Comparator<Candidate> BEST_FIRST = Comparator
            .comparingLong(Candidate::start)
            .thenComparingInt(c -> c.bumped().size())
            .thenComparingInt(c -> c.runway().lengthM())
            .thenComparing(c -> c.runway().id());

    private final AtcConfig config;
    private final AtcClock clock;
    private final ReservationLog log;
    /** requestId -> latest record. Insertion-ordered so snapshots are stable for tests/reports. */
    private final Map<String, ReservationRecord> records = new LinkedHashMap<>();

    public AllocatorCore(AtcConfig config, AtcClock clock, ReservationLog log) throws IOException {
        this.config = config;
        this.clock = clock;
        this.log = log;
        recover();
    }

    /**
     * Invariant 6: rebuild state from the log. A PENDING_ACK reservation may or may not have
     * reached its aircraft before the crash, so it becomes UNCERTAIN: it keeps blocking its
     * runway until a control service reconciles it (QUERY, then CONFIRM or RELEASE).
     */
    private void recover() throws IOException {
        for (ReservationRecord r : log.recovered()) {
            records.put(r.requestId(), r);
        }
        long now = clock.nowMillis();
        int uncertain = 0;
        for (ReservationRecord r : List.copyOf(records.values())) {
            if (r.state() == PENDING_ACK) {
                commit(r.withState(UNCERTAIN, "allocator restarted before the ack was confirmed", now));
                uncertain++;
            }
        }
        if (!records.isEmpty()) {
            LOG.info("recovered %d requests from %s (%d marked UNCERTAIN)", records.size(), log.path(), uncertain);
        }
    }

    public Outcome handle(Message message) {
        try {
            if (message instanceof Reserve r) {
                return reserve(r);
            } else if (message instanceof Confirm c) {
                return transition(c.requestId(), CONFIRMED, EnumSet.of(PENDING_ACK, UNCERTAIN));
            } else if (message instanceof Complete c) {
                // Completion proves the aircraft used the slot, so it is accepted from any blocking state.
                return transition(c.requestId(), COMPLETED, EnumSet.of(PENDING_ACK, CONFIRMED, UNCERTAIN));
            } else if (message instanceof Release r) {
                return transition(r.requestId(), RELEASED, EnumSet.of(PENDING_ACK, CONFIRMED, UNCERTAIN));
            } else if (message instanceof Query q) {
                return query(q.requestId());
            }
            return Outcome.of(new ErrorMessage("unsupported message for allocator: "
                    + (message == null ? "null" : message.getClass().getSimpleName())));
        } catch (IOException e) {
            LOG.error("log append failed, request not applied: %s", e);
            return Outcome.of(new ErrorMessage("storage failure: " + e));
        }
    }

    private Outcome reserve(Reserve req) throws IOException {
        String invalid = validate(req);
        if (invalid != null) {
            return Outcome.of(new ErrorMessage(invalid));
        }
        // Invariant 3: a retry (same requestId) gets the current outcome, never a new reservation.
        ReservationRecord existing = records.get(req.requestId());
        if (existing != null) {
            if (!existing.aircraftId().equals(req.aircraftId())) {
                LOG.warn("requestId %s reused by %s (originally %s); returning original outcome",
                        req.requestId(), req.aircraftId(), existing.aircraftId());
            }
            return Outcome.of(existing.toReply());
        }

        AirportConfig airport = config.airport(req.airportId()).orElseThrow();
        long now = clock.nowMillis();
        List<RunwayConfig> eligible = airport.runways().stream()
                .filter(rw -> Eligibility.isEligible(rw, req.sizeClass()))
                .toList();
        if (eligible.isEmpty()) {
            String reason = "no runway at " + airport.id() + " is eligible for size class " + req.sizeClass();
            return Outcome.of(commit(newRecord(req, REJECTED, null, null, reason, now)).toReply());
        }
        // Never before now, nor before the aircraft can physically be at the runway.
        long notBefore = req.earliestStart() == null ? now : Math.max(now, req.earliestStart());
        return req.emergency() ? reserveEmergency(req, eligible, now, notBefore) : reserveNormal(req, eligible, now, notBefore);
    }

    private Outcome reserveNormal(Reserve req, List<RunwayConfig> eligible, long now, long notBefore) throws IOException {
        long slotMs = config.timing().slotMs();
        long bufferMs = config.timing().bufferMs();
        Candidate best = eligible.stream()
                .map(rw -> new Candidate(rw, SlotFinder.earliestStart(busyIntervals(rw.id(), r -> true),
                        notBefore, slotMs, bufferMs), List.of()))
                .min(BEST_FIRST)
                .orElseThrow();

        // Delay measured from when the aircraft could be there, not from now.
        long waitMs = best.start() - notBefore;
        if (waitMs > config.timing().waitThresholdMs()) {
            // Too far out: answer with an estimate instead of holding runway time. The aircraft
            // asks again later with a NEW requestId; this one stays WAIT for dedup.
            String reason = "earliest slot is " + (waitMs / 1000) + " s away";
            return Outcome.of(commit(newRecord(req, WAIT, best.runway().id(), best.start(), reason, now)).toReply());
        }
        return Outcome.of(commit(newRecord(req, PENDING_ACK, best.runway().id(), best.start(), null, now)).toReply());
    }

    /**
     * Emergency: earliest slot on any eligible runway, treating only reservations that are
     * "active" (CONFIRMED / UNCERTAIN, or another emergency) as fixed. Conflicting PENDING_ACK
     * reservations are bumped; their aircraft were not yet committed to the slot. No WAIT.
     */
    private Outcome reserveEmergency(Reserve req, List<RunwayConfig> eligible, long now, long notBefore) throws IOException {
        long slotMs = config.timing().slotMs();
        long bufferMs = config.timing().bufferMs();
        List<Candidate> candidates = new ArrayList<>();
        for (RunwayConfig rw : eligible) {
            long start = SlotFinder.earliestStart(busyIntervals(rw.id(), r -> !isBumpable(r)), notBefore, slotMs, bufferMs);
            Interval padded = new Interval(start - bufferMs, start + slotMs + bufferMs);
            List<ReservationRecord> bumped = blockingOn(rw.id()).stream()
                    .filter(this::isBumpable)
                    .filter(r -> r.paddedInterval(bufferMs).overlaps(padded))
                    .toList();
            candidates.add(new Candidate(rw, start, bumped));
        }
        Candidate best = candidates.stream().min(BEST_FIRST).orElseThrow();

        List<ReservationRecord> bumped = new ArrayList<>();
        for (ReservationRecord victim : best.bumped()) {
            bumped.add(commit(victim.withState(BUMPED, "bumped by emergency request " + req.requestId(), now)));
            LOG.info("emergency %s bumped %s (%s) from %s", req.requestId(), victim.requestId(),
                    victim.aircraftId(), victim.runwayId());
        }
        ReservationRecord rec = commit(newRecord(req, PENDING_ACK, best.runway().id(), best.start(), "emergency", now));
        return new Outcome(rec.toReply(), bumped);
    }

    private boolean isBumpable(ReservationRecord r) {
        return r.state() == PENDING_ACK && !r.emergency();
    }

    private Outcome transition(String requestId, ReservationState target, Set<ReservationState> allowedFrom)
            throws IOException {
        if (requestId == null || requestId.isBlank()) {
            return Outcome.of(new ErrorMessage("requestId is required"));
        }
        ReservationRecord current = records.get(requestId);
        if (current == null) {
            return Outcome.of(new ErrorMessage("unknown requestId: " + requestId));
        }
        if (!allowedFrom.contains(current.state())) {
            // Repeats (CONFIRM twice) and stale commands (CONFIRM after BUMPED) are answered with
            // the current state unchanged; the caller decides what that means for its aircraft.
            return Outcome.of(current.toReply());
        }
        return Outcome.of(commit(current.withState(target, null, clock.nowMillis())).toReply());
    }

    private Outcome query(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Outcome.of(new ErrorMessage("requestId is required"));
        }
        ReservationRecord current = records.get(requestId);
        // Unknown means the allocator never durably decided it: safe to resend RESERVE.
        return Outcome.of(current == null ? new ErrorMessage("unknown requestId: " + requestId) : current.toReply());
    }

    private String validate(Reserve r) {
        if (r.requestId() == null || r.requestId().isBlank()) return "requestId is required";
        if (r.aircraftId() == null || r.aircraftId().isBlank()) return "aircraftId is required";
        if (r.airportId() == null || r.airportId().isBlank()) return "airportId is required";
        if (r.operation() == null) return "operation is required";
        if (r.sizeClass() == null) return "sizeClass is required";
        if (config.airport(r.airportId()).isEmpty()) return "unknown airport: " + r.airportId();
        return null;
    }

    private ReservationRecord newRecord(Reserve r, ReservationState state, String runwayId, Long start,
                                        String reason, long now) {
        Long end = start == null ? null : start + config.timing().slotMs();
        return new ReservationRecord(r.requestId(), r.aircraftId(), r.airportId(), r.operation(), r.sizeClass(),
                r.emergency(), state, runwayId, start, end, reason, now);
    }

    /** Log first (durable), then memory. */
    private ReservationRecord commit(ReservationRecord record) throws IOException {
        log.append(record);
        records.put(record.requestId(), record);
        return record;
    }

    private List<ReservationRecord> blockingOn(String runwayId) {
        return records.values().stream()
                .filter(ReservationRecord::blocksRunway)
                .filter(r -> runwayId.equals(r.runwayId()))
                .toList();
    }

    private List<Interval> busyIntervals(String runwayId, java.util.function.Predicate<ReservationRecord> include) {
        long bufferMs = config.timing().bufferMs();
        return blockingOn(runwayId).stream().filter(include).map(r -> r.paddedInterval(bufferMs)).toList();
    }

    public Optional<ReservationRecord> get(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    /** Copy of all records, for invariant checks, scenarios and the monitor. */
    public List<ReservationRecord> snapshot() {
        return List.copyOf(records.values());
    }
}
