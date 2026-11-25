package atc.common;

/**
 * Aircraft phases.
 * Departure: PARKED -> TAXI_OUT -> HOLDING_SHORT -> TAKEOFF_ROLL -> DEPARTED.
 * Arrival:   APPROACH -> LANDING -> TAXI_IN -> PARKED.
 */
public enum Phase {
    PARKED, TAXI_OUT, HOLDING_SHORT, TAKEOFF_ROLL, DEPARTED,
    APPROACH, LANDING, TAXI_IN
}
