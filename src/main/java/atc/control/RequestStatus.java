package atc.control;

/** Control-service-local status of an aircraft's runway request. */
public enum RequestStatus {
    /** Sent to the allocator, no answer yet (and not yet timed out). */
    FORWARDED,
    /** Allocator did not answer in time / is unreachable: outcome unknown, aircraft told to hold. */
    HELD,
    ASSIGNED,
    /** Aircraft acked its assignment; CONFIRM sent to the allocator. */
    ACKED,
    WAIT,
    REJECTED,
    COMPLETED,
    /** Superseded by a newer request from the same aircraft (e.g. it missed its slot); booking released. */
    CANCELLED
}
