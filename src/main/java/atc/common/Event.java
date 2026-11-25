package atc.common;

/** Something worth showing on the Monitor or keeping as evidence. */
public record Event(String service, String kind, String detail, long ts) implements Message {
}
