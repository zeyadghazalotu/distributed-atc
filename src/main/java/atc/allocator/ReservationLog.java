package atc.allocator;

import atc.common.CodecException;
import atc.common.JsonCodec;
import atc.common.Log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log: one JSON {@link ReservationRecord} snapshot per line.
 *
 * Invariant 5: {@link #append} returns only after the bytes are forced to disk (fsync), and
 * the allocator replies only after append returns. So any decision a client has seen survives
 * a crash. The converse is fine: a decision that was logged but never replied to is found by
 * the client's retry (same requestId) and returned then.
 */
public final class ReservationLog implements Closeable {
    private static final Log LOG = new Log("allocator-log");

    private final Path path;
    private final FileChannel channel;
    private final List<ReservationRecord> recovered;
    private final boolean fsync;

    private ReservationLog(Path path, FileChannel channel, List<ReservationRecord> recovered, boolean fsync) {
        this.path = path;
        this.channel = channel;
        this.recovered = recovered;
        this.fsync = fsync;
    }

    public static ReservationLog open(Path path) throws IOException {
        return open(path, true);
    }

    /**
     * Opens (or creates) the log, recovering existing records first. A crash during append can
     * leave a torn last line without '\n'; it was never acknowledged (no fsync completed before
     * a reply), so it is cut off. Otherwise the next append would be glued onto it and a
     * durable record would become unreadable.
     */
    /**
     * @param fsync false ONLY for the load-test comparison of durability cost: appends are then
     *              not forced to disk, which breaks invariant 5 (a crash can lose acknowledged
     *              decisions). Never used in normal runs.
     */
    public static ReservationLog open(Path path, boolean fsync) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<ReservationRecord> recovered = new ArrayList<>();
        if (Files.exists(path)) {
            byte[] bytes = Files.readAllBytes(path);
            int validLength = lastNewline(bytes) + 1;
            if (validLength < bytes.length) {
                LOG.warn("truncating torn tail of %s (%d bytes)", path, bytes.length - validLength);
                try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    ch.truncate(validLength);
                    ch.force(true);
                }
            }
            String text = new String(bytes, 0, validLength, StandardCharsets.UTF_8);
            int lineNo = 0;
            for (String line : text.split("\n")) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    recovered.add(JsonCodec.decode(line.strip(), ReservationRecord.class));
                } catch (CodecException e) {
                    // A complete but unreadable line means outside corruption; skip it loudly.
                    LOG.error("skipping unreadable log line %d: %s", lineNo, e.getMessage());
                }
            }
        }
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return new ReservationLog(path, channel, List.copyOf(recovered), fsync);
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Records read when the log was opened, in file order. */
    public List<ReservationRecord> recovered() {
        return recovered;
    }

    /** Appends one record and fsyncs before returning. */
    public synchronized void append(ReservationRecord record) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap((JsonCodec.encode(record) + "\n").getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        // force(true) also syncs metadata (file length), which an append depends on.
        if (fsync) {
            channel.force(true);
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
