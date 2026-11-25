package atc.common;

/**
 * Aircraft -> control service: runway operation finished. The control service forwards the
 * same message to the allocator, which marks the reservation COMPLETED and frees the runway.
 */
public record Complete(String requestId, String aircraftId) implements Message {
}
