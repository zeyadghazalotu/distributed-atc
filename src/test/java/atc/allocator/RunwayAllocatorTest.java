package atc.allocator;

import atc.common.ErrorMessage;
import atc.common.Heartbeat;
import atc.common.JsonCodec;
import atc.common.Message;
import atc.common.Reservation;
import atc.common.ReservationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static atc.allocator.TestFixtures.reserve;
import static atc.common.SizeClass.SMALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Socket-level smoke test: the real process loop over TCP on random local ports. */
class RunwayAllocatorTest {
    @TempDir
    Path dir;

    private ReservationLog log;
    private RunwayAllocator allocator;
    private Thread loop;
    private ZContext ctx;
    private ZMQ.Socket client;

    @BeforeEach
    void start() throws Exception {
        log = ReservationLog.open(dir.resolve("log.jsonl"));
        AllocatorCore core = new AllocatorCore(TestFixtures.config(), System::currentTimeMillis, log);
        allocator = new RunwayAllocator(core, 0, 0, 100, System::currentTimeMillis);
        loop = new Thread(allocator, "allocator-test-loop");
        loop.start();
        assertTrue(allocator.awaitBound(5000), "allocator did not bind");

        ctx = new ZContext();
        client = ctx.createSocket(SocketType.REQ);
        client.setReceiveTimeOut(5000);
        client.connect("tcp://127.0.0.1:" + allocator.routerPort());
    }

    @AfterEach
    void stop() throws Exception {
        ctx.close();
        allocator.stop();
        loop.join(5000);
        log.close();
    }

    private Message call(String json) throws Exception {
        client.send(json.getBytes(StandardCharsets.UTF_8));
        byte[] reply = client.recv();
        assertNotNull(reply, "no reply within timeout");
        return JsonCodec.decode(reply);
    }

    @Test
    void reservesOverTcpAndSurvivesMalformedInput() throws Exception {
        String req = JsonCodec.encode(reserve("r1", "AAA", SMALL));
        Reservation first = assertInstanceOf(Reservation.class, call(req));
        assertEquals(ReservationState.PENDING_ACK, first.state());

        assertInstanceOf(ErrorMessage.class, call("this is not json"));
        assertInstanceOf(ErrorMessage.class, call("{\"type\":\"NOPE\"}"));

        // Still alive, and the retry is deduplicated.
        assertEquals(first, call(req));
    }

    @Test
    void publishesHeartbeats() throws Exception {
        try (ZMQ.Socket sub = ctx.createSocket(SocketType.SUB)) {
            sub.subscribe(new byte[0]);
            sub.setReceiveTimeOut(3000);
            sub.connect("tcp://127.0.0.1:" + allocator.pubPort());
            // PUB drops messages until the subscription propagates; heartbeats repeat every 100 ms.
            byte[] msg = sub.recv();
            assertNotNull(msg, "no heartbeat received");
            Message m = JsonCodec.decode(msg);
            Heartbeat hb = assertInstanceOf(Heartbeat.class, m);
            assertEquals(RunwayAllocator.SERVICE, hb.service());
        }
    }
}
