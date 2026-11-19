package dev.worldforge.people.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Append-only, per-shard Character mutation journal.
 *
 * This is deliberately a storage primitive, not a second Character authority:
 * callers pass canonical global IDs and already-encoded immutable record payloads.
 * A record is acknowledged only after its frame is written and force(true) succeeds.
 * Recovery ignores and truncates an incomplete/corrupt tail, preserving earlier frames.
 */
public final class CharacterWal implements AutoCloseable {
    private static final int MAGIC = 0x57465043; // "WFPC"
    private static final short VERSION = 1;
    private static final int HEADER_BYTES = 24;
    private static final int MAX_ID_BYTES = 4096;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    public record Record(int shard, long sequence, String globalId, byte[] payload) {
        public Record { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private static final class Shard {
        final FileChannel channel;
        long nextSequence;
        Shard(FileChannel channel, long nextSequence) { this.channel = channel; this.nextSequence = nextSequence; }
    }

    private final Path directory;
    private final int shardCount;
    private final Shard[] shards;
    private boolean closed;

    public CharacterWal(Path directory, int shardCount) throws IOException {
        if (shardCount < 1 || shardCount > 4096) throw new IllegalArgumentException("shardCount must be 1..4096");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.shardCount = shardCount;
        Files.createDirectories(directory);
        this.shards = new Shard[shardCount];
        try {
            for (int i = 0; i < shardCount; i++) {
                Path path = directory.resolve(String.format(Locale.ROOT, "characters-%04d.wal", i));
                FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                long next = scanAndRepair(channel);
                shards[i] = new Shard(channel, next);
                channel.position(channel.size());
            }
        } catch (IOException | RuntimeException failure) {
            for (Shard shard : shards) if (shard != null) try { shard.channel.close(); } catch (IOException ignored) {}
            throw failure;
        }
    }

    public int shardFor(String globalId) {
        Objects.requireNonNull(globalId, "globalId");
        if (globalId.isBlank()) throw new IllegalArgumentException("globalId is blank");
        // Stable FNV-1a over UTF-8 bytes. Do not use String.hashCode or runtime-dependent hashes.
        int hash = 0x811c9dc5;
        for (byte b : globalId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x01000193;
        }
        return (hash & 0x7fffffff) % shardCount;
    }

    public synchronized Record append(String globalId, byte[] payload, boolean durable) throws IOException {
        ensureOpen();
        Objects.requireNonNull(payload, "payload");
        byte[] id = globalId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_ID_BYTES) throw new IllegalArgumentException("invalid globalId length");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload exceeds 16 MiB");
        int shardId = shardFor(globalId);
        Shard shard = shards[shardId];
        long sequence = shard.nextSequence;
        ByteBuffer frame = encode(sequence, id, payload);
        long start = shard.channel.size();
        shard.channel.position(start);
        try {
            writeFully(shard.channel, frame);
            if (durable) shard.channel.force(true);
        } catch (IOException failure) {
            // Never leave a partial frame before the next sequence. Roll back to the
            // previous frame boundary so subsequent appends and recovery remain valid.
            try {
                shard.channel.truncate(start);
                shard.channel.position(start);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        shard.nextSequence++;
        return new Record(shardId, sequence, globalId, payload);
    }

    public synchronized List<Record> replayAll() throws IOException {
        ensureOpen();
        List<Record> records = new ArrayList<>();
        for (int shardId = 0; shardId < shardCount; shardId++) {
            Shard shard = shards[shardId];
            records.addAll(readRecords(shard.channel, shardId, false));
        }
        records.sort(Comparator.comparingInt(Record::shard).thenComparingLong(Record::sequence));
        return records;
    }

    public synchronized long nextSequence(int shardId) {
        ensureOpen();
        return shards[shardId].nextSequence;
    }

    private long scanAndRepair(FileChannel channel) throws IOException {
        List<Record> valid = readRecords(channel, -1, true);
        return valid.isEmpty() ? 1L : valid.get(valid.size() - 1).sequence() + 1L;
    }

    private List<Record> readRecords(FileChannel channel, int shardId, boolean repair) throws IOException {
        List<Record> records = new ArrayList<>();
        long position = 0, size = channel.size(), expected = 1;
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        while (position < size) {
            header.clear();
            if (!readFully(channel, header, position)) break;
            header.flip();
            int magic = header.getInt();
            short version = header.getShort();
            short reserved = header.getShort();
            long sequence = header.getLong();
            int idLength = header.getInt();
            int payloadLength = header.getInt();
            if (magic != MAGIC || version != VERSION || reserved != 0 || sequence != expected
                    || idLength < 1 || idLength > MAX_ID_BYTES || payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) break;
            long frameLength = HEADER_BYTES + (long) idLength + payloadLength + Integer.BYTES;
            if (frameLength > size - position) break;
            ByteBuffer body = ByteBuffer.allocate(idLength + payloadLength + Integer.BYTES);
            if (!readFully(channel, body, position + HEADER_BYTES)) break;
            body.flip();
            byte[] id = new byte[idLength], payload = new byte[payloadLength];
            body.get(id); body.get(payload); int storedCrc = body.getInt();
            ByteBuffer crcInput = ByteBuffer.allocate(HEADER_BYTES - Integer.BYTES + idLength + payloadLength).order(ByteOrder.BIG_ENDIAN);
            crcInput.put(headerBytes(version, sequence, idLength, payloadLength));
            crcInput.put(id).put(payload);
            CRC32C crc = new CRC32C(); crc.update(crcInput.array(), 0, crcInput.position());
            if ((int) crc.getValue() != storedCrc) break;
            records.add(new Record(shardId, sequence, new String(id, StandardCharsets.UTF_8), payload));
            position += frameLength; expected++;
        }
        if (repair && position != size) {
            channel.truncate(position);
            channel.force(true);
        }
        return records;
    }

    private static ByteBuffer encode(long sequence, byte[] id, byte[] payload) {
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + id.length + payload.length + Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).putShort(VERSION).putShort((short) 0).putLong(sequence).putInt(id.length).putInt(payload.length);
        frame.put(id).put(payload);
        CRC32C crc = new CRC32C();
        crc.update(frame.array(), Integer.BYTES, frame.position() - Integer.BYTES);
        frame.putInt((int) crc.getValue()).flip();
        return frame;
    }

    private static byte[] headerBytes(short version, long sequence, int idLength, int payloadLength) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES - Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        b.putShort(version).putShort((short) 0).putLong(sequence).putInt(idLength).putInt(payloadLength);
        return b.array();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position);
            if (n < 0) return false;
            if (n == 0) continue;
            position += n;
        }
        return true;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("CharacterWal is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Shard shard : shards) if (shard != null) try { shard.channel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
