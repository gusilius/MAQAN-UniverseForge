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
 * Batch append amortizes durable flushes while preserving per-shard sequence and CRC framing.
 */
public final class CharacterWal implements AutoCloseable {
    private static final int MAGIC = 0x57465043;
    private static final short VERSION = 1;
    private static final int HEADER = 24;
    private static final int MAX_ID = 128;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    public static final class Record {
        private final int shard;
        private final long sequence;
        private final String globalId;
        private final byte[] payload;

        /** Public construction always takes ownership through a defensive copy. */
        public Record(int shard, long sequence, String globalId, byte[] payload) {
            this(shard, sequence, globalId, payload, false);
        }

        private Record(int shard, long sequence, String globalId, byte[] payload, boolean takeOwnership) {
            this.shard = shard;
            this.sequence = sequence;
            this.globalId = Objects.requireNonNull(globalId, "globalId");
            this.payload = takeOwnership ? Objects.requireNonNull(payload, "payload") : Objects.requireNonNull(payload, "payload").clone();
        }

        public int shard() { return shard; }
        public long sequence() { return sequence; }
        public String globalId() { return globalId; }
        public byte[] payload() { return payload.clone(); }
        public String payloadUtf8() { return new String(payload, StandardCharsets.UTF_8); }
        byte[] payloadView() { return payload; }
    }
    @FunctionalInterface
    public interface ReplayConsumer {
        void accept(Record record) throws IOException;
    }
    @FunctionalInterface
    public interface CheckpointAction {
        void commit() throws IOException;
    }
    public record Write(String globalId, byte[] payload) {
        public Write {
            Objects.requireNonNull(globalId, "globalId");
            Objects.requireNonNull(payload, "payload");
            payload = payload.clone();
        }
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

    /** A successful return means every supplied frame is forced to stable storage. */
    public synchronized List<Record> appendBatch(List<Write> writes) throws IOException {
        ensureOpen();
        if (writes == null || writes.isEmpty()) return List.of();
        Map<Integer, List<Integer>> byShard = new TreeMap<>();
        List<byte[]> ids = new ArrayList<>(writes.size());
        List<byte[]> payloads = new ArrayList<>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            Write write = Objects.requireNonNull(writes.get(i), "write");
            byte[] id = write.globalId().getBytes(StandardCharsets.UTF_8);
            // Write already owns a defensive immutable copy; avoid cloning it again here.
            byte[] payload = write.payload;
            if (id.length == 0 || id.length > MAX_ID) throw new IllegalArgumentException("invalid globalId length");
            if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("invalid payload length");
            ids.add(id); payloads.add(payload);
            byShard.computeIfAbsent(shardFor(write.globalId()), ignored -> new ArrayList<>()).add(i);
        }

        Map<Integer, Long> startOffsets = new LinkedHashMap<>();
        Map<Integer, Long> firstSequences = new LinkedHashMap<>();
        try {
            for (Map.Entry<Integer, List<Integer>> group : byShard.entrySet()) {
                int shardId = group.getKey();
                Shard shard = shards[shardId];
                long start = shard.channel.size();
                startOffsets.put(shardId, start);
                firstSequences.put(shardId, shard.next);
                shard.channel.position(start);
                for (int index : group.getValue()) {
                    ByteBuffer frame = encode(shard.next++, ids.get(index), payloads.get(index));
                    while (frame.hasRemaining()) shard.channel.write(frame);
                }
            }
            // One durable flush per touched shard, rather than one fsync per character.
            for (int shardId : byShard.keySet()) shards[shardId].channel.force(true);
        } catch (IOException | RuntimeException failure) {
            for (Map.Entry<Integer, Long> start : startOffsets.entrySet()) {
                Shard shard = shards[start.getKey()];
                try {
                    shard.channel.truncate(start.getValue());
                    shard.channel.position(start.getValue());
                    shard.channel.force(true);
                    shard.next = firstSequences.get(start.getKey());
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }

        Map<Integer, Long> offsets = new HashMap<>(firstSequences);
        List<Record> result = new ArrayList<>(writes.size());
        for (int i = 0; i < writes.size(); i++) {
            int shardId = shardFor(writes.get(i).globalId());
            long sequence = offsets.get(shardId);
            offsets.put(shardId, sequence + 1);
            result.add(new Record(shardId, sequence, writes.get(i).globalId(), payloads.get(i)));
        }
        return List.copyOf(result);
    }

    public synchronized Record append(String globalId, byte[] payload) throws IOException {
        return appendBatch(List.of(new Write(globalId, payload))).get(0);
    }

    /**
     * Replays records in deterministic shard/sequence order without retaining the complete
     * journal in a temporary List. The consumer must finish processing each record before
     * returning; this is the recovery path for large partitions.
     */
    public synchronized void replayEach(ReplayConsumer consumer) throws IOException {
        ensureOpen();
        Objects.requireNonNull(consumer, "consumer");
        for (int i = 0; i < shards.length; i++)
            readValid(shards[i].channel, i, false, consumer);
    }

    /**
     * Emits only the last durable frame per global Character ID. Two streaming passes keep
     * only ID-to-sequence metadata in memory; payload byte arrays are never retained for the
     * whole dirty population. Every ID is deterministically assigned to one shard.
     */
    public synchronized void replayLatestEach(ReplayConsumer consumer) throws IOException {
        ensureOpen();
        Objects.requireNonNull(consumer, "consumer");
        Map<String, Long> latestSequence = new HashMap<>();
        replayEach(record -> latestSequence.put(record.globalId(), record.sequence()));
        replayEach(record -> {
            Long latest = latestSequence.get(record.globalId());
            if (latest != null && latest.longValue() == record.sequence()) consumer.accept(record);
        });
    }

    /** Compatibility helper for small tests/tools; production recovery should use replayEach. */
    public synchronized List<Record> replayAll() throws IOException {
        ensureOpen();
        List<Record> result = new ArrayList<>();
        replayEach(result::add);
        return result;
    }

    /**
     * Runs the durable compaction action while excluding concurrent appends, then retires
     * the journal. If the action fails, every WAL frame remains available for recovery.
     */
    public synchronized void checkpoint(CheckpointAction action) throws IOException {
        ensureOpen();
        Objects.requireNonNull(action, "action");
        action.commit();
        for (Shard shard : shards) {
            shard.channel.truncate(0);
            shard.channel.position(0);
            shard.channel.force(true);
            shard.next = 1;
        }
    }

    /** Call only after every legacy snapshot was durably committed. */
    public synchronized void checkpoint() throws IOException {
        checkpoint(() -> { });
    }

    private long repairAndFindNext(FileChannel channel) throws IOException {
        long[] lastSequence = {0L};
        readValid(channel, 0, true, record -> lastSequence[0] = record.sequence());
        return lastSequence[0] + 1;
    }

    private void readValid(FileChannel channel, int shard, boolean repair, ReplayConsumer consumer) throws IOException {
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
            // Read ID and payload into their final owned arrays. A combined body buffer would
            // temporarily retain a second payload-sized byte array during large-record replay.
            ByteBuffer idBuffer = ByteBuffer.allocate(idLength);
            ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
            ByteBuffer crcBuffer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
            if (!readFully(channel, idBuffer, position + HEADER)
                    || !readFully(channel, payloadBuffer, position + HEADER + idLength)
                    || !readFully(channel, crcBuffer, position + HEADER + (long) idLength + payloadLength)) break;
            byte[] id = idBuffer.array();
            byte[] payload = payloadBuffer.array();
            crcBuffer.flip();
            int stored = crcBuffer.getInt();
            // CRC covers the original frame header (excluding magic) and body (excluding stored CRC).
            // Update directly from the final ID and payload arrays without a duplicate body buffer.
            CRC32C crc = new CRC32C();
            crc.update(header.array(), Integer.BYTES, HEADER - Integer.BYTES);
            crc.update(id, 0, id.length);
            crc.update(payload, 0, payload.length);
            if ((int) crc.getValue() != stored) throw new IOException("Character WAL checksum mismatch at byte " + position);
            consumer.accept(new Record(shard, sequence, new String(id, StandardCharsets.UTF_8), payload, true));
            position += length; expected++;
        }
        if (repair && position != size) { channel.truncate(position); channel.force(true); }
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
