package atc.allocator;

import atc.common.AtcConfig.RunwayConfig;
import atc.common.SizeClass;

/** Invariant 2: a runway is only ever offered to an aircraft that can safely use it. */
public final class Eligibility {
    private Eligibility() {
    }

    public static boolean isEligible(RunwayConfig runway, SizeClass sizeClass) {
        boolean longEnough = runway.lengthM() >= sizeClass.minRunwayLengthM();
        boolean withinMaxClass = runway.maxSizeClass() == null
                || sizeClass.ordinal() <= runway.maxSizeClass().ordinal();
        return longEnough && withinMaxClass;
    }
}
