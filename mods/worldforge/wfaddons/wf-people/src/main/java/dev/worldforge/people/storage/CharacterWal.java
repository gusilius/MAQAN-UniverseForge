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
 * Append-only sharded journal for canonical Character records.
 *
 * This is a persistence log, not a second source of Character identity. Records contain
 * the canonical global UUID and the versioned payload emitted by CharacterRegistry.
 */
public final class CharacterWal implements AutoCloseable {
    private static final int MAGIC = 0x57465043;
    private static final short VERSION = 1;
    private static final int HEADER = 24;
    private static final int MAX_ID = 128;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    public record Record(int shard, long sequence, String globalId, byte[] payload) {
        public Record { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    private static final class Shard {
        final FileChannel channel;
        long next;
        Shard(FileChannel channel, long next) { this.channel = channel; this.next = next; }
    }

    private final Shard[] shards;
    private boolean closed;

    public CharacterWal(Path directory, int shardCount) throws IOException {
        if (shardCount < 1 || shardCount > 4096) throw new IllegalArgumentException("shardCount must be 1..4096");
        Files.createDirectories(directory);
        shards = new Shard[shardCount];
        try {
            for (int i = 0; i < shardCount; i++) {
                Path file = directory.resolve(String.format(Locale.ROOT, "characters-%04d.wal", i));
                FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                long next = repairAndFindNext(channel);
                channel.position(channel.size());
                shards[i] = new Shard(channel, next);
            }
        } catch (IOException | RuntimeException ex) {
            for (Shard shard : shards) if (shard != null) try { shard.channel.close(); } catch (IOException ignored) { }
            throw ex;
        }
    }

    public int shardFor(String globalId) {
        Objects.requireNonNull(globalId, "globalId");
        if (globalId.isBlank()) throw new IllegalArgumentException("globalId is blank");
        int hash = 0x811c9dc5;
        for (byte b : globalId.getBytes(StandardCharsets.UTF_8)) { hash ^= b & 0xff; hash *= 0x01000193; }
        return (hash & 0x7fffffff) % shards.length;
    }

    /** A successful return means the complete frame has been forced to stable storage. */
    public synchronized Record append(String globalId, byte[] payload) throws IOException {
        ensureOpen();
        byte[] id = globalId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_ID) throw new IllegalArgumentException("invalid globalId length");
        if (payload == null || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("invalid payload length");
        int shardId = shardFor(globalId);
        Shard shard = shards[shardId];
        long start = shard.channel.size();
        ByteBuffer frame = encode(shard.next, id, payload);
        shard.channel.position(start);
        try {
            while (frame.hasRemaining()) shard.channel.write(frame);
            shard.channel.force(true);
        } catch (IOException failure) {
            try { shard.channel.truncate(start); shard.channel.position(start); shard.channel.force(true); }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        return new Record(shardId, shard.next++, globalId, payload);
    }

    public synchronized List<Record> replayAll() throws IOException {
        ensureOpen();
        List<Record> result = new ArrayList<>();
        for (int i = 0; i < shards.length; i++) result.addAll(readValid(shards[i].channel, i, false));
        result.sort(Comparator.comparingInt(Record::shard).thenComparingLong(Record::sequence));
        return result;
    }

    /**
     * Call only after every legacy snapshot was durably committed. If the process crashes
     * before truncation, replaying the old records is idempotent and safe.
     */
    public synchronized void checkpoint() throws IOException {
        ensureOpen();
        for (Shard shard : shards) {
            shard.channel.truncate(0);
            shard.channel.position(0);
            shard.channel.force(true);
            shard.next = 1;
        }
    }

    private long repairAndFindNext(FileChannel channel) throws IOException {
        List<Record> valid = readValid(channel, 0, true);
        return valid.isEmpty() ? 1 : valid.get(valid.size() - 1).sequence() + 1;
    }

    private List<Record> readValid(FileChannel channel, int shard, boolean repair) throws IOException {
        List<Record> result = new ArrayList<>();
        long position = 0, size = channel.size(), expected = 1;
        ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.BIG_ENDIAN);
        while (position < size) {
            header.clear();
            if (!readFully(channel, header, position)) break;
            header.flip();
            int magic = header.getInt(); short version = header.getShort(); short reserved = header.getShort();
            long sequence = header.getLong(); int idLength = header.getInt(); int payloadLength = header.getInt();
            if (magic != MAGIC || version != VERSION || reserved != 0 || sequence != expected
                    || idLength < 1 || idLength > MAX_ID || payloadLength < 0 || payloadLength > MAX_PAYLOAD)
                throw new IOException("Corrupt Character WAL frame header at byte " + position);
            
            long length = HEADER + (long) idLength + payloadLength + Integer.BYTES;
            if (length > size - position) break;
            ByteBuffer body = ByteBuffer.allocate(idLength + payloadLength + Integer.BYTES);
            if (!readFully(channel, body, position + HEADER)) break;
            body.flip();
            byte[] id = new byte[idLength], payload = new byte[payloadLength];
            body.get(id); body.get(payload); int stored = body.getInt();
            ByteBuffer crcData = ByteBuffer.allocate(HEADER - 4 + idLength + payloadLength).order(ByteOrder.BIG_ENDIAN);
            crcData.putShort(version).putShort(reserved).putLong(sequence).putInt(idLength).putInt(payloadLength);
            crcData.put(id).put(payload);
            CRC32C crc = new CRC32C(); crc.update(crcData.array(), 0, crcData.position());
            if ((int) crc.getValue() != stored)
                throw new IOException("Character WAL checksum mismatch at byte " + position);
            result.add(new Record(shard, sequence, new String(id, StandardCharsets.UTF_8), payload));
            position += length; expected++;
        }
        if (repair && position != size) { channel.truncate(position); channel.force(true); }
        return result;
    }

    private static ByteBuffer encode(long sequence, byte[] id, byte[] payload) {
        ByteBuffer frame = ByteBuffer.allocate(HEADER + id.length + payload.length + 4).order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).putShort(VERSION).putShort((short) 0).putLong(sequence).putInt(id.length).putInt(payload.length);
        frame.put(id).put(payload);
        CRC32C crc = new CRC32C(); crc.update(frame.array(), 4, frame.position() - 4);
        frame.putInt((int) crc.getValue()).flip();
        return frame;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position);
            if (count < 0) return false;
            if (count == 0) continue;
            position += count;
        }
        return true;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("CharacterWal is closed"); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Shard shard : shards) if (shard != null) try { shard.channel.close(); }
        catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        if (failure != null) throw failure;
    }
}
