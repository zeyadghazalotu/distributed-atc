package atc.control;

import atc.common.PositionUpdate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control service's local, possibly lagging, view of aircraft positions.
 *
 * Invariant 4: pub/sub gives no ordering guarantee across reconnects and the simulator may
 * re-send, so an update is applied only if its per-aircraft seq is strictly greater than the
 * last one applied. Older or duplicate updates are dropped and counted.
 */
final class AircraftView {
    enum Result { ACCEPTED, STALE, WRONG_AIRPORT, INVALID }

    private final String airportId;
    private final Map<String, PositionUpdate> latest = new LinkedHashMap<>();
    private long staleDropped;

    AircraftView(String airportId) {
        this.airportId = airportId;
    }

    Result accept(PositionUpdate p) {
        if (p.aircraftId() == null || p.aircraftId().isBlank() || p.phase() == null) {
            return Result.INVALID;
        }
        if (!airportId.equals(p.airportId())) {
            return Result.WRONG_AIRPORT;
        }
        PositionUpdate previous = latest.get(p.aircraftId());
        if (previous != null && p.seq() <= previous.seq()) {
            staleDropped++;
            return Result.STALE;
        }
        latest.put(p.aircraftId(), p);
        return Result.ACCEPTED;
    }

    PositionUpdate get(String aircraftId) {
        return latest.get(aircraftId);
    }

    List<PositionUpdate> all() {
        return new ArrayList<>(latest.values());
    }

    long staleDropped() {
        return staleDropped;
    }
}
