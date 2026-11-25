package atc.control;

import atc.common.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks commands sent to the allocator that are still waiting for a reply, and whether the
 * allocator is currently believed reachable.
 *
 * Every allocator command (RESERVE, CONFIRM, COMPLETE, RELEASE, QUERY) is idempotent by
 * requestId, so the recovery strategy is uniform: on timeout, resend the SAME command. A
 * duplicate that arrives late is harmless at the allocator, and its reply is ignored here
 * because the key is no longer pending.
 *
 * Reachability starts as false ("unknown = unreachable") and changes on evidence: any reply or
 * heartbeat means reachable, and a timeout or no contact at all for 3 heartbeat periods means
 * unreachable.
 */
final class AllocatorLink {
    /** Sends one command; returns false if it could not even be queued. */
    interface Transport {
        boolean send(String key, Message command);
    }

    record Pending(String key, Message command, long deadline, int attempts) {
    }

    private final Transport transport;
    private final long timeoutMs;
    private final long heartbeatStaleMs;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private boolean reachable;
    /** Last evidence of life: a reply OR a heartbeat. */
    private long lastContact;

    AllocatorLink(Transport transport, long timeoutMs, long heartbeatStaleMs) {
        this.transport = transport;
        this.timeoutMs = timeoutMs;
        this.heartbeatStaleMs = heartbeatStaleMs;
    }

    /** Send (or re-send) a command and wait for its reply under {@code key}. */
    void send(String key, Message command, long now) {
        Pending previous = pending.get(key);
        transport.send(key, command);
        pending.put(key, new Pending(key, command, now + timeoutMs, previous == null ? 1 : previous.attempts() + 1));
    }

    /** A reply arrived: returns the command it answers, or null if not pending (late duplicate). */
    Message complete(String key) {
        Pending p = pending.remove(key);
        return p == null ? null : p.command();
    }

    /** Re-sends every command whose deadline passed and returns them (as they were before resending). */
    List<Pending> expire(long now) {
        List<Pending> expired = new ArrayList<>();
        for (Pending p : List.copyOf(pending.values())) {
            if (now >= p.deadline()) {
                expired.add(p);
                send(p.key(), p.command(), now);
            }
        }
        return expired;
    }

    /**
     * Evidence the allocator is up. Returns true if this is a transition from unreachable, in
     * which case everything pending is resent at once instead of waiting for its timeout.
     */
    boolean onContact(long now) {
        // Any contact counts, not only heartbeats: after a restart the reply channel can
        // reconnect before the heartbeat channel, and judging by the old incarnation's last
        // heartbeat made reachability flap (found in the milestone 5 live run).
        lastContact = now;
        if (reachable) {
            return false;
        }
        reachable = true;
        for (Pending p : List.copyOf(pending.values())) {
            send(p.key(), p.command(), now);
        }
        return true;
    }

    boolean onHeartbeat(long now) {
        return onContact(now);
    }

    /** Returns true if this is a transition from reachable. */
    boolean markUnreachable() {
        boolean changed = reachable;
        reachable = false;
        return changed;
    }

    /** No reply and no heartbeat for longer than 3 heartbeat periods. */
    boolean contactStale(long now) {
        return lastContact > 0 && now - lastContact > heartbeatStaleMs;
    }

    boolean reachable() {
        return reachable;
    }

    int pendingCount() {
        return pending.size();
    }

    boolean isPending(String key) {
        return pending.containsKey(key);
    }
}
