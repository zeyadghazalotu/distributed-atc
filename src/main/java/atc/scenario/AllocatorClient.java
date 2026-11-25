package atc.scenario;

import atc.common.CodecException;
import atc.common.JsonCodec;
import atc.common.Message;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

import java.nio.charset.StandardCharsets;

/**
 * Talks to the allocator exactly like a control service does: DEALER, frames [key, payload],
 * replies [key, reply]. Used by scenarios that exercise the allocator directly.
 */
final class AllocatorClient implements AutoCloseable {
    record Reply(String key, Message message, long receivedNanos) {
    }

    private final ZContext ctx;
    private final String endpoint;
    private ZMQ.Socket socket;
    private ZMQ.Poller poller;

    AllocatorClient(ZContext ctx, String endpoint) {
        this.ctx = ctx;
        this.endpoint = endpoint;
        this.socket = newSocket();
    }

    /** Same receive discipline as AircraftClient: Poller + DONTWAIT, never a timed blocking recv. */
    private ZMQ.Socket newSocket() {
        if (poller != null) {
            poller.close();
        }
        ZMQ.Socket s = ctx.createSocket(SocketType.DEALER);
        s.setLinger(0);
        s.setSndHWM(100_000); // the load test queues many requests
        s.setRcvHWM(100_000);
        s.connect(endpoint);
        poller = ctx.createPoller(1);
        poller.register(s, ZMQ.Poller.POLLIN);
        return s;
    }

    ZMQ.Socket socket() {
        return socket;
    }

    /** Lazy pirate: drop the socket (and any reply in flight to it) and start a new one. */
    void reconnect() {
        ctx.destroySocket(socket);
        socket = newSocket();
    }

    void send(String key, Message m) {
        socket.send(key.getBytes(StandardCharsets.UTF_8), ZMQ.SNDMORE);
        socket.send(JsonCodec.encodeBytes(m), 0);
    }

    /** Next reply within the timeout, or null. */
    Reply poll(long timeoutMs) throws CodecException {
        Reply r = pollNow();
        if (r == null && poller.poll(Math.max(1, timeoutMs)) > 0) {
            r = pollNow();
        }
        return r;
    }

    /** Non-blocking variant for the load generator. */
    Reply pollNow() throws CodecException {
        ZMsg msg = ZMsg.recvMsg(socket, ZMQ.DONTWAIT);
        if (msg == null || msg.size() < 2) {
            return null;
        }
        String key = msg.popString();
        return new Reply(key, JsonCodec.decode(msg.getLast().getData()), System.nanoTime());
    }

    /** Synchronous call: send and wait for the reply with the same key. */
    Message call(String key, Message m, long timeoutMs) throws CodecException {
        send(key, m);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Reply r = poll(Math.max(1, deadline - System.currentTimeMillis()));
            if (r != null && r.key().equals(key)) {
                return r.message();
            }
        }
        return null;
    }

    @Override
    public void close() {
        poller.close();
        ctx.destroySocket(socket);
    }
}
