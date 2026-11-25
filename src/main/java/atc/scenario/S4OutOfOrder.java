package atc.scenario;

import atc.common.CodecException;
import atc.common.ControlState;
import atc.common.Event;
import atc.common.JsonCodec;
import atc.common.Phase;
import atc.common.PositionUpdate;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scenario 4: delayed, duplicated and out-of-order position updates (invariant 4). One aircraft's
 * updates are shuffled, partly duplicated, and an old batch arrives late; another aircraft's
 * arrive in order. The control service must keep exactly the newest update per aircraft and drop
 * exactly the stale ones.
 */
final class S4OutOfOrder implements Scenario {
    static final int UPDATES = 60;

    @Override
    public String id() {
        return "s4-out-of-order";
    }

    @Override
    public String title() {
        return "Delayed / duplicated / out-of-order position updates";
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        ctx.startControl("YYZ", 5000, false); // positions don't involve the allocator

        ZMQ.Socket pub = ctx.zmq.createSocket(SocketType.PUB);
        pub.setLinger(1000);
        pub.connect(ctx.controlPositions("YYZ"));
        ZMQ.Socket states = ctx.subscriber(ctx.controlEvents("YYZ"));

        // Warm up until the control service demonstrably receives our updates (PUB/SUB slow joiner).
        long warmSeq = 0;
        long deadline = System.currentTimeMillis() + 10_000;
        boolean warm = false;
        while (!warm && System.currentTimeMillis() < deadline) {
            pub.send(JsonCodec.encodeBytes(update("S4-WARMUP", ++warmSeq)));
            ControlState s = nextState(states);
            warm = s != null && s.aircraft().stream().anyMatch(p -> p.aircraftId().equals("S4-WARMUP"));
        }
        ctx.check("-", "position feed connected", warm, "");

        // Build the hostile sequence.
        Random rnd = new Random(7);
        List<Long> shuffled = new ArrayList<>();
        for (long s = 1; s <= UPDATES; s++) {
            shuffled.add(s);
        }
        Collections.shuffle(shuffled, rnd);
        List<PositionUpdate> sequence = new ArrayList<>();
        int inOrder = 1;
        for (int i = 0; i < shuffled.size(); i++) {
            sequence.add(update("S4-OOO", shuffled.get(i)));
            if (i % 5 == 0) {
                sequence.add(update("S4-OOO", shuffled.get(rnd.nextInt(i + 1)))); // duplicate of something sent
            }
            sequence.add(update("S4-INORDER", inOrder++));
        }
        for (long s = 1; s <= 10; s++) {
            sequence.add(update("S4-OOO", s)); // a delayed batch of old updates arrives last
        }

        // What a correct seq filter must do, computed independently.
        Map<String, Long> maxSeen = new HashMap<>();
        int expectedDropped = 0;
        try (PrintWriter w = ctx.csv("sequence.csv", "order,aircraft,seq,expected")) {
            for (int i = 0; i < sequence.size(); i++) {
                PositionUpdate p = sequence.get(i);
                long max = maxSeen.getOrDefault(p.aircraftId(), 0L);
                boolean accept = p.seq() > max;
                if (accept) {
                    maxSeen.put(p.aircraftId(), p.seq());
                } else {
                    expectedDropped++;
                }
                w.printf("%d,%s,%d,%s%n", i + 1, p.aircraftId(), p.seq(), accept ? "accept" : "drop");
            }
        }
        for (int i = 0; i < sequence.size(); i++) {
            pub.send(JsonCodec.encodeBytes(sequence.get(i)));
            if (i % 10 == 9) {
                Thread.sleep(5);
            }
        }

        // Wait for a snapshot that has seen everything.
        int expected = expectedDropped;
        ControlState last = null;
        deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ControlState s = nextState(states);
            if (s != null) {
                last = s;
                if (s.stalePositionsDropped() >= expected && seqOf(s, "S4-OOO") == UPDATES && seqOf(s, "S4-INORDER") == UPDATES) {
                    Thread.sleep(1200); // one more snapshot, in case anything is still arriving
                    ControlState after = nextState(states);
                    last = after != null ? after : s;
                    break;
                }
            }
        }
        states.close();
        pub.close();

        PositionUpdate ooo = find(last, "S4-OOO");
        ctx.check("4", "shuffled aircraft ends on its newest update (seq " + UPDATES + ")",
                ooo != null && ooo.seq() == UPDATES && ooo.x() == UPDATES * 10.0, ooo == null ? "missing" : "seq=" + ooo.seq() + " x=" + ooo.x());
        ctx.check("4", "in-order aircraft ends on seq " + UPDATES, seqOf(last, "S4-INORDER") == UPDATES, "seq=" + seqOf(last, "S4-INORDER"));
        long dropped = last == null ? -1 : last.stalePositionsDropped();
        ctx.check("4", "exactly the stale/duplicate updates were dropped", dropped == expectedDropped,
                "dropped " + dropped + ", expected " + expectedDropped + " of " + sequence.size() + " sent");
    }

    private static PositionUpdate update(String aircraft, long seq) {
        // x encodes the seq so the test can tell which update the control service kept.
        return new PositionUpdate(aircraft, "YYZ", seq, Phase.APPROACH, seq * 10.0, 0, 3000, 160, System.currentTimeMillis());
    }

    private static ControlState nextState(ZMQ.Socket sub) throws CodecException {
        byte[] data = sub.recv();
        if (data != null && JsonCodec.decode(data) instanceof Event e && e.kind().equals("STATE")) {
            return JsonCodec.decode(e.detail(), ControlState.class);
        }
        return null;
    }

    private static PositionUpdate find(ControlState s, String aircraft) {
        return s == null ? null : s.aircraft().stream().filter(p -> p.aircraftId().equals(aircraft)).findFirst().orElse(null);
    }

    private static long seqOf(ControlState s, String aircraft) {
        PositionUpdate p = find(s, aircraft);
        return p == null ? -1 : p.seq();
    }
}
