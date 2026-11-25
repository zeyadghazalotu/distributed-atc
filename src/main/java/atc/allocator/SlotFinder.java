package atc.allocator;

import java.util.Comparator;
import java.util.List;

/**
 * Finds the earliest start time for a new slot on one runway.
 *
 * Each slot occupies [start - buffer, start + slotLen + buffer). A new slot is valid when that
 * padded interval overlaps no existing padded interval (invariant 1). Because existing
 * intervals never overlap each other, sorting them by start and pushing the candidate past
 * every conflict in one pass yields the earliest valid start, including gaps between slots.
 */
public final class SlotFinder {
    private SlotFinder() {
    }

    /**
     * @param busy      padded intervals already occupying the runway
     * @param notBefore the slot may not start before this (normally "now")
     */
    public static long earliestStart(List<Interval> busy, long notBefore, long slotMs, long bufferMs) {
        List<Interval> sorted = busy.stream().sorted(Comparator.comparingLong(Interval::start)).toList();
        long start = notBefore;
        for (Interval b : sorted) {
            Interval candidate = new Interval(start - bufferMs, start + slotMs + bufferMs);
            if (candidate.overlaps(b)) {
                // Move so our leading buffer begins exactly where the busy interval ends.
                start = b.end() + bufferMs;
            }
        }
        return start;
    }
}
