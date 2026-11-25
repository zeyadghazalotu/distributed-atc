package atc.monitor;

import atc.common.AtcConfig;
import atc.common.AtcConfig.AirportConfig;
import atc.common.AtcConfig.RunwayConfig;
import atc.common.ControlState;
import atc.common.ControlState.RequestView;
import atc.common.PositionUpdate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link MonitorState} into one text frame. Pure (state + time in, text out), so the
 * layout is unit-tested without a terminal.
 */
public final class DashboardRenderer {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AtcConfig config;
    private final long heartbeatMs;

    public DashboardRenderer(AtcConfig config) {
        this.config = config;
        this.heartbeatMs = config.timing().heartbeatMs();
    }

    public String render(MonitorState state, long now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "=== Distributed ATC Monitor  %s  (SOFE4790U Group 6, synthetic data) ===", CLOCK.format(Instant.ofEpochMilli(now)));
        services(sb, state, now);
        for (AirportConfig airport : config.airports()) {
            airport(sb, state, airport, now);
        }
        eventLog(sb, state, now);
        return sb.toString();
    }

    // ------------------------------------------------------------------ sections

    private void services(StringBuilder sb, MonitorState state, long now) {
        line(sb, "");
        line(sb, "SERVICES");
        line(sb, "  %-13s %s", "allocator", health(state.lastHeartbeat("allocator"), now));
        for (AirportConfig a : config.airports()) {
            String service = "control-" + a.id();
            ControlState s = state.airport(a.id());
            String extra = s == null ? "" : String.format("   allocator reachable: %-3s   stale positions dropped: %d",
                    s.allocatorReachable() ? "yes" : "NO", s.stalePositionsDropped());
            line(sb, "  %-13s %s%s", service, health(state.lastHeartbeat(service), now), extra);
        }
    }

    /** UP while heartbeats arrive; DOWN after 3 missed (same rule the control services use). */
    private String health(Long lastHeartbeat, long now) {
        if (lastHeartbeat == null) {
            return String.format("%-5s %-18s", "DOWN", "never seen");
        }
        long age = now - lastHeartbeat;
        String status = age <= 3 * heartbeatMs ? "UP" : "DOWN";
        return String.format("%-5s %-18s", status, "heartbeat " + seconds(age) + " ago");
    }

    private void airport(StringBuilder sb, MonitorState state, AirportConfig airport, long now) {
        ControlState s = state.airport(airport.id());
        line(sb, "");
        if (s == null) {
            line(sb, "AIRPORT %s   (no snapshot yet: control-%s not heard from)", airport.id(), airport.id());
            return;
        }
        Long received = state.airportStateReceivedAt(airport.id());
        line(sb, "AIRPORT %s   (snapshot %s old)", airport.id(), received == null ? "?" : seconds(now - received));

        // Aircraft: union of those with positions and those with requests.
        Map<String, PositionUpdate> positions = new LinkedHashMap<>();
        s.aircraft().forEach(p -> positions.put(p.aircraftId(), p));
        Map<String, RequestView> latestRequest = new LinkedHashMap<>();
        s.requests().forEach(r -> latestRequest.put(r.aircraftId(), r)); // later = newer
        List<String> ids = new ArrayList<>(positions.keySet());
        latestRequest.keySet().stream().filter(id -> !positions.containsKey(id)).forEach(ids::add);
        ids.sort(String::compareTo);

        line(sb, "  %-10s %-14s %-15s %6s %5s  %-10s %-9s %-8s %-7s %s",
                "AIRCRAFT", "PHASE", "POSITION x,y m", "ALT ft", "KT", "REQUEST", "INSTR", "RUNWAY", "TAXIWAY", "SLOT");
        if (ids.isEmpty()) {
            line(sb, "  (no aircraft)");
        }
        for (String id : ids) {
            PositionUpdate p = positions.get(id);
            RequestView r = latestRequest.get(id);
            line(sb, "  %-10s %-14s %-15s %6s %5s  %-10s %-9s %-8s %-7s %s",
                    id,
                    p == null ? "?" : p.phase(),
                    p == null ? "" : String.format("%.0f,%.0f", p.x(), p.y()),
                    p == null ? "" : String.format("%.0f", p.altitudeFt()),
                    p == null ? "" : String.format("%.0f", p.speedKt()),
                    r == null ? "-" : r.status(),
                    r == null || r.lastInstruction() == null ? "-" : r.lastInstruction(),
                    r == null || r.runwayId() == null ? "-" : r.runwayId(),
                    r == null || r.taxiwayId() == null ? "-" : r.taxiwayId(),
                    r == null ? "" : slot(r, now));
        }

        // Runways in two columns.
        List<String> cells = new ArrayList<>();
        for (RunwayConfig rw : airport.runways()) {
            cells.add(String.format("%-8s %4dm %s", rw.id(), rw.lengthM(), runwayStatus(rw.id(), s, now)));
        }
        line(sb, "  RUNWAYS");
        for (int i = 0; i < cells.size(); i += 2) {
            line(sb, "    %-52s %s", cells.get(i), i + 1 < cells.size() ? cells.get(i + 1) : "");
        }

        // Taxiways: show the aircraft, not the requestId.
        Map<String, String> aircraftByRequest = new LinkedHashMap<>();
        s.requests().forEach(r -> aircraftByRequest.put(r.requestId(), r.aircraftId()));
        StringBuilder tw = new StringBuilder();
        s.taxiwayOccupancy().forEach((taxiway, requestId) -> tw.append(String.format("%s: %s   ", taxiway,
                requestId.isEmpty() ? "free" : aircraftByRequest.getOrDefault(requestId, requestId))));
        line(sb, "  TAXIWAYS  %s", tw.toString().trim());
        if (!s.taxiwayQueue().isEmpty()) {
            List<String> queued = s.taxiwayQueue().stream().map(q -> aircraftByRequest.getOrDefault(q, q)).toList();
            line(sb, "  TAXIWAY QUEUE  %s", String.join(", ", queued));
        }
    }

    /** Busy now, booked next, or free, from the requests that hold this runway. */
    private static String runwayStatus(String runwayId, ControlState s, long now) {
        RequestView active = null;
        RequestView next = null;
        for (RequestView r : s.requests()) {
            boolean holds = runwayId.equals(r.runwayId()) && r.slotStart() != null && r.slotEnd() != null
                    && ("ASSIGNED".equals(r.status()) || "ACKED".equals(r.status()));
            if (!holds || r.slotEnd() <= now) {
                continue;
            }
            if (r.slotStart() <= now) {
                active = r;
            } else if (next == null || r.slotStart() < next.slotStart()) {
                next = r;
            }
        }
        if (active != null) {
            return "IN USE " + active.aircraftId() + " (" + seconds(active.slotEnd() - now) + " left)";
        }
        if (next != null) {
            return "booked " + next.aircraftId() + " in " + seconds(next.slotStart() - now);
        }
        return "free";
    }

    private static String slot(RequestView r, long now) {
        if (r.slotStart() == null || r.slotEnd() == null) {
            return "";
        }
        if (now < r.slotStart()) {
            return "in " + seconds(r.slotStart() - now);
        }
        if (now < r.slotEnd()) {
            return "NOW (" + seconds(r.slotEnd() - now) + " left)";
        }
        return "done";
    }

    private void eventLog(StringBuilder sb, MonitorState state, long now) {
        line(sb, "");
        line(sb, "RECENT EVENTS  (%d messages received, %d malformed)", state.messages(), state.malformed());
        List<MonitorState.LoggedEvent> events = state.events();
        if (events.isEmpty()) {
            line(sb, "  (none yet)");
        }
        for (MonitorState.LoggedEvent e : events) {
            String text = e.text().length() > 80 ? e.text().substring(0, 77) + "..." : e.text();
            line(sb, "  %s %-12s %-21s %s", CLOCK.format(Instant.ofEpochMilli(e.receivedAt())), e.service(), e.kind(), text);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String seconds(long ms) {
        return String.format("%.1fs", Math.max(0, ms) / 1000.0);
    }

    private static void line(StringBuilder sb, String fmt, Object... args) {
        sb.append(args.length == 0 ? fmt : String.format(fmt, args)).append(System.lineSeparator());
    }
}
