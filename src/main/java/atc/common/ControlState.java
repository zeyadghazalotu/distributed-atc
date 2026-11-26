package atc.common;

import java.util.List;
import java.util.Map;

/**
 * Full snapshot of one control service, published as the detail of an {@code EVENT kind=STATE}
 * once per heartbeat. The Monitor rebuilds its view from these snapshots instead of replaying
 * individual events, so a missed pub/sub message (slow joiner, dropped packet) heals by itself
 * one second later.
 */
public record ControlState(String service, String airportId, boolean allocatorReachable,
                           List<PositionUpdate> aircraft, List<RequestView> requests,
                           Map<String, String> taxiwayOccupancy, List<String> taxiwayQueue,
                           long stalePositionsDropped, long ts) {

    /**
     * One runway request as the control service sees it.
     *
     * @param sizeClass the aircraft's size class (null in snapshots from older control services)
     */
    public record RequestView(String requestId, String aircraftId, Operation operation, boolean emergency,
                              String status, String runwayId, String taxiwayId, Long slotStart, Long slotEnd,
                              String lastInstruction, SizeClass sizeClass) {

        public RequestView(String requestId, String aircraftId, Operation operation, boolean emergency,
                           String status, String runwayId, String taxiwayId, Long slotStart, Long slotEnd,
                           String lastInstruction) {
            this(requestId, aircraftId, operation, emergency, status, runwayId, taxiwayId, slotStart, slotEnd,
                    lastInstruction, null);
        }
    }
}
