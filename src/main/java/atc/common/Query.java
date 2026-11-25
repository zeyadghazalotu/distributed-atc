package atc.common;

/** Control service -> allocator: what is the current state of this request? */
public record Query(String requestId) implements Message {
}
