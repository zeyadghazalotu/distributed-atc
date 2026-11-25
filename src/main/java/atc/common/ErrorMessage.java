package atc.common;

/** Reply to a malformed, unknown or invalid message. Services never crash on bad input. */
public record ErrorMessage(String reason) implements Message {
}
