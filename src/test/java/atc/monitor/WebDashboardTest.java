package atc.monitor;

import atc.allocator.TestFixtures;
import atc.common.AtcClock;
import atc.common.ControlState;
import atc.common.ControlState.RequestView;
import atc.common.Event;
import atc.common.Heartbeat;
import atc.common.JsonCodec;
import atc.common.Operation;
import atc.common.Phase;
import atc.common.PositionUpdate;
import atc.common.SizeClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDashboardTest {
    private static final long NOW = 1_000_000_000_000L;

    private static JsonNode json(MonitorState state, long now) throws Exception {
        return JsonCodec.mapper().readTree(WebDashboard.snapshot(TestFixtures.config(), state, now));
    }

    @Test
    void emptyStateIsAllDownWithNoSnapshots() throws Exception {
        JsonNode d = json(new MonitorState(), NOW);
        assertEquals(NOW, d.get("now").asLong());
        assertEquals("allocator", d.get("services").get(0).get("name").asText());
        d.get("services").forEach(s -> assertFalse(s.get("up").asBoolean()));
        d.get("airports").forEach(a -> {
            assertTrue(a.get("state") == null || a.get("state").isNull());
            assertTrue(a.get("runways").size() > 0);
        });
        assertEquals(0, d.get("events").size());
    }

    @Test
    void snapshotCarriesHealthStateAndEvents() throws Exception {
        MonitorState state = new MonitorState(WebDashboard.MAX_EVENTS);
        state.onMessage(new Heartbeat("allocator", NOW), NOW - 50); // heartbeat 100 ms in fixtures
        ControlState cs = new ControlState("control-AAA", "AAA", false,
                List.of(new PositionUpdate("AC1", "AAA", 5, Phase.LANDING, 1200, 0, 0, 90, NOW)),
                List.of(new RequestView("r1", "AC1", Operation.LANDING, true, "ACKED", "AAA-M1", "AAA-A",
                        NOW - 1000, NOW + 5000, "ASSIGNED")),
                Map.of("AAA-A", "r1"), List.of(), 2, NOW);
        state.onMessage(new Event("control-AAA", "STATE", JsonCodec.encode(cs), NOW), NOW - 200);
        state.onMessage(new Event("allocator", "RECONCILE", "2 uncertain", NOW), NOW - 10);

        JsonNode d = json(state, NOW);
        assertTrue(d.get("services").get(0).get("up").asBoolean());
        JsonNode airport = d.get("airports").get(0);
        assertEquals("AAA", airport.get("id").asText());
        assertEquals(200, airport.get("stateAgeMs").asLong());
        assertFalse(airport.get("state").get("allocatorReachable").asBoolean());
        assertEquals("AAA-M1", airport.get("state").get("requests").get(0).get("runwayId").asText());
        assertTrue(airport.get("state").get("requests").get(0).get("emergency").asBoolean());
        assertEquals("RECONCILE", d.get("events").get(0).get("kind").asText());
    }

    @Test
    void sizeClassSurvivesTheStateSnapshot() throws Exception {
        RequestView r = new RequestView("r1", "AC1", Operation.TAKEOFF, false, "ASSIGNED", "AAA-M1", null,
                NOW, NOW + 1000, "ASSIGNED", SizeClass.HEAVY);
        ControlState cs = new ControlState("control-AAA", "AAA", true, List.of(), List.of(r), Map.of(), List.of(), 0, NOW);
        ControlState back = JsonCodec.decode(JsonCodec.encode(cs), ControlState.class);
        assertEquals(SizeClass.HEAVY, back.requests().get(0).sizeClass());
    }

    @Test
    void readsTheCommittedSampleResults() throws Exception {
        JsonNode d = JsonCodec.mapper().readTree(WebDashboard.results(Path.of("results/sample/scenarios")));
        assertTrue(d.get("available").asBoolean());
        assertEquals(71, d.get("checks").size());
        d.get("checks").forEach(c -> assertEquals("PASS", c.get("result").asText()));
        assertEquals(10, d.get("load").size());
        assertTrue(d.get("notes").get("s5-allocator-restart").get(0).asText().contains("outage"));
    }

    @Test
    void missingResultsAreEmptyNotAnError() throws Exception {
        JsonNode d = JsonCodec.mapper().readTree(WebDashboard.results(Path.of("does-not-exist")));
        assertFalse(d.get("available").asBoolean());
        assertEquals(0, d.get("load").size());
    }

    @Test
    void csvLinesHandleQuotesAndCommas() {
        assertEquals(List.of("s1", "1", "a, \"b\"", "PASS", ""),
                WebDashboard.parseCsvLine("s1,1,\"a, \"\"b\"\"\",PASS,"));
    }

    @Test
    void servesPageAndStateOverHttp() throws Exception {
        WebDashboard web = new WebDashboard(TestFixtures.config(), List.of(), AtcClock.SYSTEM);
        HttpServer http = web.startHttp("127.0.0.1", 0);
        try {
            int port = http.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Distributed ATC"));
            HttpResponse<String> api = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/state")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, api.statusCode());
            assertTrue(JsonCodec.mapper().readTree(api.body()).has("airports"));
            HttpResponse<String> missing = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        } finally {
            http.stop(0);
        }
    }
}
