package atc.common;

/**
 * Control service -> allocator: ask for a runway slot. Idempotent by {@code requestId}.
 *
 * @param earliestStart optional epoch ms before which the slot must not start (the aircraft's
 *                      ETA at the runway: approach time for arrivals, taxi time for departures);
 *                      null means "now"
 */
public record Reserve(String requestId, String aircraftId, String airportId,
                      Operation operation, SizeClass sizeClass, boolean emergency, Long earliestStart)
        implements Message {

    public Reserve(String requestId, String aircraftId, String airportId,
                   Operation operation, SizeClass sizeClass, boolean emergency) {
        this(requestId, aircraftId, airportId, operation, sizeClass, emergency, null);
    }
}
