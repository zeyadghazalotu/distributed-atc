package atc.common;

/** Control service -> allocator: the aircraft acked, PENDING_ACK/UNCERTAIN becomes CONFIRMED. */
public record Confirm(String requestId) implements Message {
}
