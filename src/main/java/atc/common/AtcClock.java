package atc.common;

/**
 * Source of "now" in epoch milliseconds. Components take a clock instead of calling
 * System.currentTimeMillis() so tests and scenarios can control time deterministically.
 */
@FunctionalInterface
public interface AtcClock {
    long nowMillis();

    AtcClock SYSTEM = System::currentTimeMillis;
}
