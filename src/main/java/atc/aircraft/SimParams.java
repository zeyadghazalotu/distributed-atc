package atc.aircraft;

import atc.common.AtcConfig.Timing;

/**
 * Aircraft behaviour timings in SIMULATED seconds (scaled to real time through
 * {@link Timing#scaledMs}), plus real-time networking settings.
 *
 * @param taxiSec          gate <-> runway taxi time
 * @param approachLeadSec  how far ahead an arriving aircraft asks to land (its ETA)
 * @param turnaroundMinSec time parked between landing and requesting departure (min)
 * @param turnaroundMaxSec ... (max)
 * @param cruiseMinSec     time away after departing before returning as an arrival (min)
 * @param cruiseMaxSec     ... (max)
 * @param retryMs          REAL ms without a reply before resending (lazy pirate). Must exceed
 *                         the control service's allocator timeout, otherwise every slow
 *                         allocator answer causes redundant aircraft retries.
 * @param positionPeriodMs REAL ms between position updates per aircraft
 */
public record SimParams(Timing timing, double taxiSec, double approachLeadSec,
                        double turnaroundMinSec, double turnaroundMaxSec,
                        double cruiseMinSec, double cruiseMaxSec,
                        long retryMs, long positionPeriodMs) {

    public static SimParams defaults(Timing timing) {
        // 1.5x the control service's timeout so the control service gets to answer HELD first.
        long retryMs = timing.receiveTimeoutMs() * 3L / 2;
        return new SimParams(timing, 60, 120, 60, 120, 60, 180, retryMs, 1000);
    }

    public SimParams withRetryMs(long newRetryMs) {
        return new SimParams(timing, taxiSec, approachLeadSec, turnaroundMinSec, turnaroundMaxSec,
                cruiseMinSec, cruiseMaxSec, newRetryMs, positionPeriodMs);
    }

    long ms(double simSeconds) {
        return timing.scaledMs(simSeconds);
    }
}
