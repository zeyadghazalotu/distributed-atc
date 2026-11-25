package atc.allocator;

/** Half-open time interval [start, end) in epoch ms. Touching intervals do not overlap. */
public record Interval(long start, long end) {
    public boolean overlaps(Interval other) {
        return start < other.end && other.start < end;
    }
}
