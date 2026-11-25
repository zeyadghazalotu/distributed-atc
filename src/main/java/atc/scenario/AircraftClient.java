package atc.scenario;

import atc.common.CodecException;
import atc.common.Instruction;
import atc.common.InstructionStatus;
import atc.common.JsonCodec;
import atc.common.Message;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A scripted aircraft: a DEALER to a control service, like the simulator uses, but driven
 * step by step by a scenario. Keeps every message it received with its arrival time.
 */
final class AircraftClient implements AutoCloseable {
    record Received(Message message, long atMillis) {
    }

    final String aircraftId;
    private final ZContext ctx;
    private final String endpoint;
    private ZMQ.Socket socket;
    private ZMQ.Poller poller;
    final List<Received> received = new ArrayList<>();

    AircraftClient(ZContext ctx, String endpoint, String aircraftId) {
        this.ctx = ctx;
        this.endpoint = endpoint;
        this.aircraftId = aircraftId;
        this.socket = newSocket();
    }

    /*
     * Receiving waits on a Poller and then reads with DONTWAIT; it never uses a receive timeout
     * (RCVTIMEO) on a blocking recv. In scenario 5 one client socket that had made many timed
     * blocking receives wedged forever inside JeroMQ's timed-recv path on Windows (100% CPU in
     * Signaler.recv). Poller + non-blocking recv is also how the services receive, and they never
     * showed the problem.
     */
    private ZMQ.Socket newSocket() {
        if (poller != null) {
            poller.close();
        }
        ZMQ.Socket s = ctx.createSocket(SocketType.DEALER);
        s.setLinger(0);
        s.connect(endpoint);
        poller = ctx.createPoller(1);
        poller.register(s, ZMQ.Poller.POLLIN);
        return s;
    }

    ZMQ.Socket socket() {
        return socket;
    }

    void send(Message m) {
        socket.send(JsonCodec.encodeBytes(m), 0);
    }

    /** Lazy pirate: abandon the socket (a reply in flight to it is lost) and use a new one. */
    void reconnect() {
        ctx.destroySocket(socket);
        socket = newSocket();
    }

    /** Next message within the timeout (recorded), or null. */
    Message recv(long timeoutMs) throws CodecException {
        byte[] data = socket.recv(ZMQ.DONTWAIT); // something already queued?
        if (data == null && poller.poll(Math.max(1, timeoutMs)) > 0) {
            data = socket.recv(ZMQ.DONTWAIT);
        }
        if (data == null) {
            return null;
        }
        Message m = JsonCodec.decode(data);
        received.add(new Received(m, System.currentTimeMillis()));
        return m;
    }

    /** Receives until an instruction matching the predicate arrives (or null on timeout). */
    Instruction await(Predicate<Instruction> wanted, long timeoutMs) throws CodecException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message m = recv(deadline - System.currentTimeMillis());
            if (m instanceof Instruction i && wanted.test(i)) {
                return i;
            }
        }
        return null;
    }

    /** First instruction that is a decision (anything but HELD). */
    Instruction awaitDecision(long timeoutMs) throws CodecException {
        return await(i -> i.status() != InstructionStatus.HELD, timeoutMs);
    }

    /** Drain whatever is queued within a short window (recorded). */
    void drain(long windowMs) throws CodecException {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            if (recv(deadline - System.currentTimeMillis()) == null) {
                return;
            }
        }
    }

    List<Instruction> instructions() {
        return received.stream().filter(r -> r.message() instanceof Instruction)
                .map(r -> (Instruction) r.message()).toList();
    }

    @Override
    public void close() {
        poller.close();
        ctx.destroySocket(socket);
    }
}
