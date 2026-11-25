package atc.allocator;

import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.AtcConfig.RunwayConfig;
import atc.common.Operation;
import atc.common.Reserve;
import atc.common.SizeClass;

import java.util.List;

import static atc.common.SizeClass.SMALL;

/**
 * Small hand-made config so expected slots are easy to compute by hand.
 * Slot 60 s + buffer 30 s each side: consecutive slots on one runway are >= 120 s apart.
 */
public final class TestFixtures {
    public static final long SEC = 1000;
    public static final long T0 = 1_000_000_000_000L;

    /** AAA: one runway per class. BBB: a single small-only runway. */
    public static AtcConfig config() {
        AirportConfig aaa = new AirportConfig("AAA", 0, 0, 0, 5, List.of("AAA-A"), List.of(
                new RunwayConfig("AAA-L1", 3400, null),
                new RunwayConfig("AAA-M1", 2600, null),
                new RunwayConfig("AAA-S1", 1500, SMALL)));
        AirportConfig bbb = new AirportConfig("BBB", 0, 0, 0, 5, List.of("BBB-A"), List.of(
                new RunwayConfig("BBB-S1", 1800, SMALL)));
        return new AtcConfig(new AtcConfig.AllocatorConfig("localhost", 0, 0),
                new AtcConfig.Timing(60, 30, 300, 2000, 100), List.of(aaa, bbb));
    }

    public static Reserve reserve(String requestId, String airport, SizeClass size) {
        return new Reserve(requestId, "AC-" + requestId, airport, Operation.LANDING, size, false);
    }

    public static Reserve emergency(String requestId, String airport, SizeClass size) {
        return new Reserve(requestId, "AC-" + requestId, airport, Operation.LANDING, size, true);
    }

    private TestFixtures() {
    }
}
