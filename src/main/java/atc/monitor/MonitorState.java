package atc.monitor;

import atc.common.CodecException;
import atc.common.ControlState;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.Instruction;
import atc.common.JsonCodec;
import atc.common.Message;
import atc.common.Reservation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the Monitor knows, built only from what services publish.
 *
 * The control services' STATE snapshots are the primary source: each one is complete, so a
 * Monitor that starts late or misses messages (pub/sub drops for slow joiners) is correct
 * again within one heartbeat. Individual events only feed the rolling event log.
 * Owned by the Monitor's single loop thread.
 */
public final class MonitorState {
    public record LoggedEvent(long receivedAt, String service, String kind, String text) {
    }

    static final int MAX_EVENTS = 12;

    private final Map<String, Long> lastHeartbeat = new HashMap<>();
    private final Map<String, ControlState> airportStates = new HashMap<>();
    private final Map<String, Long> airportStateReceivedAt = new HashMap<>();
    private final Deque<LoggedEvent> events = new ArrayDeque<>();
    private long messages;
    private long malformed;

    public void onMessage(Message m, long now) {
        messages++;
        if (m instanceof Heartbeat hb) {
            lastHeartbeat.put(hb.service(), now);
        } else if (m instanceof Event e) {
            onEvent(e, now);
        }
    }

    public void onMalformed() {
        malformed++;
    }

    private void onEvent(Event e, long now) {
        if ("METRICS".equals(e.kind())) {
            return; // resource samples are for the ScenarioRunner, not the event log
        }
        if ("STATE".equals(e.kind())) {
            try {
                ControlState s = JsonCodec.decode(e.detail(), ControlState.class);
                airportStates.put(s.airportId(), s);
                airportStateReceivedAt.put(s.airportId(), now);
            } catch (CodecException ex) {
                malformed++;
            }
            return;
        }
        events.addLast(new LoggedEvent(now, e.service(), e.kind(), summarize(e)));
        while (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    /** One readable line per event; JSON details are decoded into their key fields. */
    static String summarize(Event e) {
        String detail = e.detail() == null ? "" : e.detail();
        try {
            if ("INSTRUCTION".equals(e.kind()) && JsonCodec.decode(detail) instanceof Instruction i) {
                return i.aircraftId() + " " + i.status()
                        + (i.runwayId() == null ? "" : " " + i.runwayId())
                        + (i.taxiwayId() == null ? "" : " via " + i.taxiwayId())
                        + (i.reason() == null ? "" : " (" + i.reason() + ")");
            }
            if (("RESERVATION".equals(e.kind()) || "BUMPED".equals(e.kind()))
                    && JsonCodec.decode(detail) instanceof Reservation r) {
                return r.requestId() + " " + r.state() + (r.runwayId() == null ? "" : " " + r.runwayId())
                        + (r.reason() == null ? "" : " (" + r.reason() + ")");
            }
        } catch (CodecException ignored) {
            // fall through to the raw detail
        }
        return detail;
    }

    public Long lastHeartbeat(String service) {
        return lastHeartbeat.get(service);
    }

    public ControlState airport(String airportId) {
        return airportStates.get(airportId);
    }

    public Long airportStateReceivedAt(String airportId) {
        return airportStateReceivedAt.get(airportId);
    }

    public List<LoggedEvent> events() {
        return List.copyOf(events);
    }

    public long messages() {
        return messages;
    }

    public long malformed() {
        return malformed;
    }
}
