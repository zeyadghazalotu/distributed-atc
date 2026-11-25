package atc.common;

/** Control service -> allocator: give the slot back (cancelled, or reconciled as never acked). */
public record Release(String requestId) implements Message {
}
