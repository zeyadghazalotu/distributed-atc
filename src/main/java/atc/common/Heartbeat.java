package atc.common;

/**
 * Published by every service once per second on its PUB socket.
 *
 * @param startedAt optional epoch ms the service process started. It changes when the service
 *                  restarts, so subscribers detect a restart even if it was too quick to miss
 *                  any heartbeats (the allocator uses it to trigger reconciliation).
 */
public record Heartbeat(String service, long ts, Long startedAt) implements Message {

    public Heartbeat(String service, long ts) {
        this(service, ts, null);
    }
}
