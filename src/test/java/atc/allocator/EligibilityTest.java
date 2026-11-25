package atc.allocator;

import atc.common.AtcConfig.RunwayConfig;
import atc.common.SizeClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EligibilityTest {

    @ParameterizedTest(name = "{0} on {1} m (max {2}) -> {3}")
    @CsvSource({
            // size,  length, maxClass, eligible
            "SMALL,   1500,   ,         true",   // exactly the minimum
            "SMALL,   1499,   ,         false",
            "MEDIUM,  2500,   ,         true",
            "MEDIUM,  2499,   ,         false",
            "HEAVY,   3200,   ,         true",
            "HEAVY,   3199,   ,         false",
            "SMALL,   3400,   ,         true",   // long runway fits small aircraft
            "HEAVY,   3400,   MEDIUM,   false",  // long enough but capped by max class
            "MEDIUM,  3400,   MEDIUM,   true",
            "MEDIUM,  2600,   SMALL,    false",
            "SMALL,   2600,   SMALL,    true",
    })
    void lengthAndMaxClassBothApply(SizeClass size, int length, SizeClass max, boolean expected) {
        assertEquals(expected, Eligibility.isEligible(new RunwayConfig("R", length, max), size));
    }
}
