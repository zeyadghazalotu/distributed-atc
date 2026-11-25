package atc.common;

/** Aircraft -> control service: instruction received and accepted. */
public record Ack(String requestId, String aircraftId) implements Message {
}
