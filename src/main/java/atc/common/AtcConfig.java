package atc.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Contents of config/airports.json. Validated on load so bad config fails fast at startup. */
public record AtcConfig(AllocatorConfig allocator, Timing timing, List<AirportConfig> airports) {

    public record AllocatorConfig(String host, int routerPort, int pubPort) {
    }

    /**
     * @param slotSeconds          runway occupancy per operation (simulated seconds)
     * @param bufferSeconds        separation before and after every slot
     * @param waitThresholdSeconds earliest slot further out than this -> WAIT instead of a reservation
     * @param receiveTimeoutMs     request/reply timeout; a timeout means UNCERTAIN, never rejected
     * @param heartbeatMs          heartbeat period on every PUB socket
     * @param timeScale            simulated seconds per real second (1 = real time; 10 = a 60 s
     *                             slot lasts 6 real seconds). Scales slot, buffer, wait threshold
     *                             and simulator durations. Network timeouts and heartbeats are
     *                             real time and NOT scaled. 0/missing means 1.
     */
    public record Timing(int slotSeconds, int bufferSeconds, int waitThresholdSeconds,
                         int receiveTimeoutMs, int heartbeatMs, int timeScale) {

        public Timing(int slotSeconds, int bufferSeconds, int waitThresholdSeconds,
                      int receiveTimeoutMs, int heartbeatMs) {
            this(slotSeconds, bufferSeconds, waitThresholdSeconds, receiveTimeoutMs, heartbeatMs, 1);
        }

        public int scale() { return Math.max(1, timeScale); }

        /** Real milliseconds for a duration given in simulated seconds. */
        public long scaledMs(double simSeconds) { return Math.round(simSeconds * 1000.0 / scale()); }

        public long slotMs() { return scaledMs(slotSeconds); }
        public long bufferMs() { return scaledMs(bufferSeconds); }
        public long waitThresholdMs() { return scaledMs(waitThresholdSeconds); }

        public Timing withTimeScale(int newScale) {
            return new Timing(slotSeconds, bufferSeconds, waitThresholdSeconds, receiveTimeoutMs, heartbeatMs, newScale);
        }
    }

    /** Same config with a different time scale (command-line override). */
    public AtcConfig withTimeScale(int newScale) {
        return new AtcConfig(allocator, timing.withTimeScale(newScale), airports);
    }

    public record AirportConfig(String id, int controlRouterPort, int positionSubPort, int eventPubPort,
                                int aircraftCount, List<String> taxiways, List<RunwayConfig> runways) {
    }

    /** @param maxSizeClass largest class allowed, or null for "no limit beyond length" */
    public record RunwayConfig(String id, int lengthM, SizeClass maxSizeClass) {
    }

    public static AtcConfig load(Path path) throws IOException {
        AtcConfig config = JsonCodec.mapper().readValue(Files.readString(path), AtcConfig.class);
        config.validate();
        return config;
    }

    public Optional<AirportConfig> airport(String airportId) {
        return airports.stream().filter(a -> a.id().equals(airportId)).findFirst();
    }

    void validate() {
        if (allocator == null || timing == null || airports == null || airports.isEmpty()) {
            throw new IllegalArgumentException("config needs allocator, timing and at least one airport");
        }
        if (timing.slotSeconds() <= 0 || timing.bufferSeconds() < 0) {
            throw new IllegalArgumentException("slotSeconds must be > 0 and bufferSeconds >= 0");
        }
        Set<String> airportIds = new HashSet<>();
        Set<String> runwayIds = new HashSet<>();
        for (AirportConfig a : airports) {
            if (!airportIds.add(a.id())) {
                throw new IllegalArgumentException("duplicate airport " + a.id());
            }
            if (a.runways() == null || a.runways().isEmpty()) {
                throw new IllegalArgumentException("airport " + a.id() + " has no runways");
            }
            for (RunwayConfig r : a.runways()) {
                // Runway ids are global so a reservation names its runway unambiguously.
                if (!runwayIds.add(r.id())) {
                    throw new IllegalArgumentException("duplicate runway id " + r.id());
                }
                if (r.lengthM() <= 0) {
                    throw new IllegalArgumentException("runway " + r.id() + " needs a positive length");
                }
            }
        }
    }
}
