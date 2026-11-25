package atc.control;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exclusive taxiway allocation, local to one control service (taxiways are not shared between
 * airports, so they do not need the global allocator).
 *
 * Each taxiway has at most one holder (a requestId) by construction. Requests that find no free
 * taxiway queue FIFO and receive the next one released.
 */
final class TaxiwayManager {
    /** A queued request that has just been given a taxiway. */
    record Grant(String requestId, String taxiwayId) {
    }

    /** taxiway -> holding requestId (null = free); insertion order = preference order. */
    private final Map<String, String> holder = new LinkedHashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();

    TaxiwayManager(List<String> taxiways) {
        for (String t : taxiways) {
            holder.put(t, null);
        }
    }

    /** The taxiway this request holds, or a free one, or empty (request is then queued). */
    Optional<String> acquire(String requestId) {
        Optional<String> held = heldBy(requestId);
        if (held.isPresent()) {
            return held;
        }
        for (Map.Entry<String, String> e : holder.entrySet()) {
            if (e.getValue() == null) {
                e.setValue(requestId);
                queue.remove(requestId);
                return Optional.of(e.getKey());
            }
        }
        if (!queue.contains(requestId)) {
            queue.addLast(requestId);
        }
        return Optional.empty();
    }

    /** Frees whatever this request holds (or drops it from the queue); may grant the next waiter. */
    Optional<Grant> release(String requestId) {
        queue.remove(requestId);
        Optional<String> held = heldBy(requestId);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        String next = queue.pollFirst();
        holder.put(held.get(), next);
        return next == null ? Optional.empty() : Optional.of(new Grant(next, held.get()));
    }

    Optional<String> heldBy(String requestId) {
        return holder.entrySet().stream()
                .filter(e -> requestId.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** taxiway -> requestId or "" when free (no nulls, so it serializes cleanly). */
    Map<String, String> occupancy() {
        Map<String, String> copy = new LinkedHashMap<>();
        holder.forEach((t, r) -> copy.put(t, r == null ? "" : r));
        return copy;
    }

    List<String> queue() {
        return List.copyOf(queue);
    }
}
