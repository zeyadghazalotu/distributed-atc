package atc.common;

/** Aircraft -> control service (pub/sub). {@code seq} is per aircraft and strictly increasing. */
public record PositionUpdate(String aircraftId, String airportId, long seq, Phase phase,
                             double x, double y, double altitudeFt, double speedKt, long ts)
        implements Message {
}
