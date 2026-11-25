package atc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    @Test
    void everyMessageTypeRoundTrips() throws Exception {
        List<Message> messages = List.of(
                new PositionUpdate("AC1", "YYZ", 7, Phase.APPROACH, 1.5, -2.25, 3000, 180, 1000L),
                new RunwayRequest("req-1", "AC1", "YYZ", Operation.LANDING, SizeClass.HEAVY, true, 1000L),
                new Instruction("req-1", "AC1", InstructionStatus.ASSIGNED, "YYZ-R01", "YYZ-A", 10L, 70L, null),
                new Ack("req-1", "AC1"),
                new Complete("req-1", "AC1"),
                new Reserve("req-1", "AC1", "YYZ", Operation.TAKEOFF, SizeClass.SMALL, false),
                new Reserve("req-2", "AC1", "YYZ", Operation.TAKEOFF, SizeClass.SMALL, false, 12345L),
                new RunwayRequest("req-3", "AC1", "YYZ", Operation.LANDING, SizeClass.MEDIUM, false, 1L, 99L),
                new Confirm("req-1"),
                new Release("req-1"),
                new Query("req-1"),
                new Reservation("req-1", ReservationState.PENDING_ACK, "YYZ-R01", 10L, 70L, null),
                new Heartbeat("allocator", 5L),
                new Event("allocator", "BUMPED", "detail", 6L),
                new ErrorMessage("bad"));
        for (Message m : messages) {
            assertEquals(m, JsonCodec.decode(JsonCodec.encodeBytes(m)), "round trip of " + m);
        }
    }

    @Test
    void typeFieldDiscriminatesAndNullsAreOmitted() {
        String json = JsonCodec.encode(new Reservation("r", ReservationState.REJECTED, null, null, null, "no runway"));
        assertTrue(json.contains("\"type\":\"RESERVATION\""), json);
        assertFalse(json.contains("runwayId"), json);
        assertFalse(json.contains("slotStart"), json);
    }

    @Test
    void unknownFieldsAreIgnored() throws Exception {
        Message m = JsonCodec.decode("{\"type\":\"CONFIRM\",\"requestId\":\"r1\",\"futureField\":42}");
        assertEquals(new Confirm("r1"), m);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not json",
            "{\"type\":\"CONFIRM\",",
            "{\"requestId\":\"r1\"}",
            "{\"type\":\"LAUNCH_MISSILES\"}",
            "{\"type\":\"RESERVE\",\"requestId\":\"r\",\"sizeClass\":\"JUMBO\"}",
            "[1,2,3]",
            "null"
    })
    void badInputThrowsCodecExceptionNotSomethingElse(String input) {
        assertThrows(CodecException.class, () -> JsonCodec.decode(input));
    }

    @Test
    void nonAsciiSurvivesRegardlessOfPlatformEncoding() throws Exception {
        // This machine defaults to Cp1252; the codec must always use UTF-8.
        Ack ack = new Ack("req-é-✈", "AC-Ω");
        byte[] bytes = JsonCodec.encodeBytes(ack);
        assertEquals(ack, JsonCodec.decode(bytes));
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("✈"));
    }

    @Test
    void shippedConfigLoadsAndMatchesInitialScale() throws Exception {
        AtcConfig config = AtcConfig.load(Path.of("config/airports.json"));
        assertEquals(2, config.airports().size());
        for (AtcConfig.AirportConfig a : config.airports()) {
            assertEquals(10, a.runways().size(), a.id());
            assertEquals(4, a.taxiways().size(), a.id());
            assertEquals(5, a.aircraftCount(), a.id());
        }
        assertEquals(5555, config.allocator().routerPort());
        assertEquals(60_000, config.timing().slotMs());
        assertEquals(30_000, config.timing().bufferMs());
        assertEquals(6_000, config.withTimeScale(10).timing().slotMs(), "time scale shortens slots");
        assertEquals(30_000, config.withTimeScale(10).timing().receiveTimeoutMs(), "but not network timeouts");
    }

    @Test
    void invalidConfigIsRejected() {
        AtcConfig.Timing t = new AtcConfig.Timing(60, 30, 300, 2000, 1000);
        AtcConfig.RunwayConfig rw = new AtcConfig.RunwayConfig("R1", 2000, null);
        AtcConfig.AirportConfig a = new AtcConfig.AirportConfig("A", 1, 2, 3, 1, List.of(), List.of(rw));
        AtcConfig.AirportConfig b = new AtcConfig.AirportConfig("B", 1, 2, 3, 1, List.of(), List.of(rw));
        AtcConfig dupRunway = new AtcConfig(new AtcConfig.AllocatorConfig("h", 1, 2), t, List.of(a, b));
        assertThrows(IllegalArgumentException.class, dupRunway::validate);
    }
}
