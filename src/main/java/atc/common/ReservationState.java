package atc.common;

/**
 * State of a runway reservation as tracked by the allocator.
 *
 * PENDING_ACK -> CONFIRMED -> COMPLETED is the normal path; RELEASED, BUMPED and UNCERTAIN are
 * the exceptional ones. REJECTED and WAIT are not reservations at all (no runway time is held)
 * but they are recorded per requestId so that a retried request gets the same answer
 * (invariant 3) instead of being re-evaluated.
 */
public enum ReservationState {
    PENDING_ACK,
    CONFIRMED,
    COMPLETED,
    RELEASED,
    BUMPED,
    /** Set on restart for PENDING_ACK reservations: we don't know if the aircraft was told. */
    UNCERTAIN,
    REJECTED,
    WAIT;

    /**
     * Does a reservation in this state occupy its runway slot? UNCERTAIN must block: the
     * aircraft may be acting on that slot, so it is only freed once reconciled.
     */
    public boolean blocksRunway() {
        return this == PENDING_ACK || this == CONFIRMED || this == UNCERTAIN;
    }
}
