package atc.common;

/**
 * Aircraft -> control service. Retries reuse the same {@code requestId}.
 *
 * @param earliestStart optional ETA at the runway (epoch ms), forwarded to the allocator
 */
public record RunwayRequest(String requestId, String aircraftId, String airportId,
                            Operation operation, SizeClass sizeClass, boolean emergency, long ts,
                            Long earliestStart)
        implements Message {

    public RunwayRequest(String requestId, String aircraftId, String airportId,
                         Operation operation, SizeClass sizeClass, boolean emergency, long ts) {
        this(requestId, aircraftId, airportId, operation, sizeClass, emergency, ts, null);
    }
}
