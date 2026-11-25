package atc.common;

/** Allocator -> control service: the (current) outcome for a requestId. */
public record Reservation(String requestId, ReservationState state, String runwayId,
                          Long slotStart, Long slotEnd, String reason)
        implements Message {
}
