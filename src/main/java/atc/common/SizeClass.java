package atc.common;

/**
 * Aircraft size class. Declaration order matters: a runway's "max size class" admits every
 * class whose ordinal is less than or equal to it.
 */
public enum SizeClass {
    SMALL(1500),
    MEDIUM(2500),
    HEAVY(3200);

    private final int minRunwayLengthM;

    SizeClass(int minRunwayLengthM) {
        this.minRunwayLengthM = minRunwayLengthM;
    }

    public int minRunwayLengthM() {
        return minRunwayLengthM;
    }
}
