package atc.allocator;

import atc.common.Operation;
import atc.common.Reservation;
import atc.common.ReservationState;
import atc.common.SizeClass;

/**
 * Everything the allocator knows about one requestId. Immutable: every change produces a new
 * record, and that full snapshot is what gets appended to the log, so replay is simply
 * "last snapshot per requestId wins".
 *
 * @param slotStart epoch ms the runway operation starts (null when REJECTED)
 * @param slotEnd   epoch ms it ends; the buffer is applied around [slotStart, slotEnd)
 * @param updatedAt epoch ms of this snapshot
 */
public record ReservationRecord(String requestId, String aircraftId, String airportId,
                                Operation operation, SizeClass sizeClass, boolean emergency,
                                ReservationState state, String runwayId,
                                Long slotStart, Long slotEnd, String reason, long updatedAt) {

    public ReservationRecord withState(ReservationState newState, String newReason, long now) {
        return new ReservationRecord(requestId, aircraftId, airportId, operation, sizeClass, emergency,
                newState, runwayId, slotStart, slotEnd, newReason, now);
    }

    public boolean blocksRunway() {
        return state.blocksRunway();
    }

    /** Busy interval on the runway including the buffer on both sides, half-open [start, end). */
    public Interval paddedInterval(long bufferMs) {
        return new Interval(slotStart - bufferMs, slotEnd + bufferMs);
    }

    public Reservation toReply() {
        return new Reservation(requestId, state, runwayId, slotStart, slotEnd, reason);
    }
}
