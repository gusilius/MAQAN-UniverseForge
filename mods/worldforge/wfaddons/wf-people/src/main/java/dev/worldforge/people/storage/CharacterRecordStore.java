package dev.worldforge.people.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Addressable, disk-resident checkpoint records for canonical Characters.
 *
 * The key is mapped to a deterministic SHA-256 path; no population-sized Java Map is
 * required to resolve one record. Enumeration is streaming and retains only one decoded
 * record at a time. CharacterWal remains the write-ahead log and source of crash recovery;
 * this store is the compacted/checkpoint representation, not a second journal.
 */
public final class CharacterRecordStore implements AutoCloseable {
    private static final int MAGIC = 0x57465243; // WFRC
    private static final short VERSION = 1;
    private static final int HEADER_BYTES = 16;
    private static final int MAX_ID_BYTES = 128;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    public static final class Record {
        private final String globalId;
        private final byte[] payload;

        /** Public construction takes a defensive copy because the caller retains the array. */
        public Record(String globalId, byte[] payload) {
            this(globalId, payload, false);
        }

        /** Decoding owns its freshly allocated payload and can adopt it without a second copy. */
        private Record(String globalId, byte[] payload, boolean takeOwnership) {
            this.globalId = Objects.requireNonNull(globalId, "globalId");
            Objects.requireNonNull(payload, "payload");
            this.payload = takeOwnership ? payload : payload.clone();
        }

        public String globalId() { return globalId; }
        public byte[] payload() { return payload.clone(); }
        public String payloadUtf8() { return new String(payload, StandardCharsets.UTF_8); }
    }

    @FunctionalInterface
    public interface RecordConsumer {
        void accept(Record record) throws IOException;
    }

    private final Path root;
    private boolean closed;

    public CharacterRecordStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    /** Atomically replaces one record. A successful return follows a forced file write. */
    public synchronized void put(String globalId, byte[] payload) throws IOException {
        ensureOpen();
        Objects.requireNonNull(payload, "payload");
        byte[] id = encodeId(globalId);
        if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Character payload exceeds 16 MiB");
        Path destination = pathFor(globalId);
        Path directory = destination.getParent();
        Files.createDirectories(directory);

        ByteBuffer frame = encode(id, payload);
        Path temporary = Files.createTempFile(directory, ".character-", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (frame.hasRemaining()) channel.write(frame);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /**
     * Persists a WAL record without making another payload-sized copy. The record is immutable
     * and this method consumes its private bytes synchronously without retaining the array.
     */
    public synchronized void put(CharacterWal.Record record) throws IOException {
        Objects.requireNonNull(record, "record");
        put(record.globalId(), record.payloadView());
    }

    /**
     * Checks whether the atomically published addressable record exists without decoding
     * its payload. A successful put follows a forced file write and atomic replacement;
     * checksum and UUID are validated when the record is read during hydration/recovery.
     */
    public synchronized boolean exists(String globalId) throws IOException {
        ensureOpen();
        encodeId(globalId);
        return Files.isRegularFile(pathFor(globalId));
    }

    /** Full integrity check for callers that need the record contents. */
    public synchronized boolean contains(String globalId) throws IOException {
        return get(globalId) != null;
    }

    /** Returns null when the key is absent; checksum or key/path mismatches fail closed. */
    public synchronized Record get(String globalId) throws IOException {
        ensureOpen();
        encodeId(globalId); // validate the external key before deriving a path
        Path file = pathFor(globalId);
        if (!Files.exists(file)) return null;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HEADER_BYTES + 1 + Integer.BYTES
                    || size > HEADER_BYTES + MAX_ID_BYTES + MAX_PAYLOAD_BYTES + Integer.BYTES)
                throw new IOException("Invalid Character record size: " + file);
            ByteBuffer frame = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.BIG_ENDIAN);
            while (frame.hasRemaining()) {
                if (channel.read(frame) < 0) throw new IOException("Truncated Character record: " + file);
            }
            frame.flip();
            Record record = decode(frame, file);
            if (!globalId.equals(record.globalId()))
                throw new IOException("Character record key/hash mismatch: expected " + globalId
                        + " but found " + record.globalId());
            return record;
        }
    }

    public synchronized boolean delete(String globalId) throws IOException {
        ensureOpen();
        encodeId(globalId);
        Path file = pathFor(globalId);
        boolean removed = Files.deleteIfExists(file);
        if (removed) forceDirectory(file.getParent());
        return removed;
    }

    /**
     * Forces shard directory entries once after a complete WAL checkpoint batch.
     * Individual record contents are force(true)'d before atomic rename; deferring
     * directory fsync from every Character write to the checkpoint boundary removes
     * one expensive metadata flush per resident while the WAL remains the crash source.
     */
    public synchronized void forceDirectories() throws IOException {
        ensureOpen();
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(root)) {
            for (Path shard : shards) if (Files.isDirectory(shard)) forceDirectory(shard);
        }
    }

    /** Enumerates records without retaining the directory listing or decoded population. */
    public synchronized void forEach(RecordConsumer consumer) throws IOException {
        ensureOpen();
        Objects.requireNonNull(consumer, "consumer");
        try (DirectoryStream<Path> shards = Files.newDirectoryStream(root)) {
            for (Path shard : shards) {
                if (!Files.isDirectory(shard)) continue;
                try (DirectoryStream<Path> records = Files.newDirectoryStream(shard, "*.rec")) {
                    for (Path file : records) {
                        Record record;
                        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                            long size = channel.size();
                            if (size < HEADER_BYTES + 1 + Integer.BYTES
                                    || size > HEADER_BYTES + MAX_ID_BYTES + MAX_PAYLOAD_BYTES + Integer.BYTES)
                                throw new IOException("Invalid Character record size: " + file);
                            ByteBuffer frame = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.BIG_ENDIAN);
                            while (frame.hasRemaining()) {
                                if (channel.read(frame) < 0) throw new IOException("Truncated Character record: " + file);
                            }
                            frame.flip();
                            record = decode(frame, file);
                        }
                        if (!file.equals(pathFor(record.globalId())))
                            throw new IOException("Character record stored under the wrong key path: " + file);
                        consumer.accept(record);
                    }
                }
            }
        }
    }

    private Path pathFor(String globalId) throws IOException {
        byte[] digest;
        try { digest = MessageDigest.getInstance("SHA-256").digest(globalId.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable", impossible); }
        String hex = HexFormat.of().formatHex(digest);
        return root.resolve(hex.substring(0, 2)).resolve(hex + ".rec");
    }

    private static byte[] encodeId(String globalId) {
        if (globalId == null || globalId.isBlank()) throw new IllegalArgumentException("globalId is required");
        byte[] id = globalId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_ID_BYTES) throw new IllegalArgumentException("Invalid globalId length");
        return id;
    }

    private static ByteBuffer encode(byte[] id, byte[] payload) {
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + id.length + payload.length + Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).putShort(VERSION).putShort((short) 0).putInt(id.length).putInt(payload.length);
        frame.put(id).put(payload);
        CRC32C crc = new CRC32C();
        crc.update(frame.array(), Integer.BYTES, frame.position() - Integer.BYTES);
        frame.putInt((int) crc.getValue()).flip();
        return frame;
    }

    private static Record decode(ByteBuffer frame, Path file) throws IOException {
        if (frame.remaining() < HEADER_BYTES + 1 + Integer.BYTES)
            throw new IOException("Truncated Character record header: " + file);
        int magic = frame.getInt();
        short version = frame.getShort();
        short reserved = frame.getShort();
        int idLength = frame.getInt();
        int payloadLength = frame.getInt();
        if (magic != MAGIC || version != VERSION || reserved != 0
                || idLength < 1 || idLength > MAX_ID_BYTES
                || payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES
                || frame.remaining() != idLength + payloadLength + Integer.BYTES)
            throw new IOException("Corrupt Character record header: " + file);
        byte[] id = new byte[idLength];
        byte[] payload = new byte[payloadLength];
        frame.get(id).get(payload);
        int storedCrc = frame.getInt();
        // The decoded frame is heap-backed. Verify the exact encoded CRC range in place
        // instead of allocating and filling a second payload-sized buffer.
        CRC32C crc = new CRC32C();
        crc.update(frame.array(), Integer.BYTES,
                HEADER_BYTES - Integer.BYTES + idLength + payloadLength);
        if ((int) crc.getValue() != storedCrc)
            throw new IOException("Character record checksum mismatch: " + file);
        String globalId = new String(id, StandardCharsets.UTF_8);
        if (globalId.isBlank()) throw new IOException("Blank Character record identity: " + file);
        return new Record(globalId, payload, true);
    }

    private static void forceDirectory(Path directory) throws IOException {
        // Windows does not expose directory handles through FileChannel on all supported
        // providers. The record itself is already force(true)'d before its atomic rename.
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException ignored) {
            // Provider does not expose directory channels.
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("CharacterRecordStore is closed");
    }

    @Override public synchronized void close() {
        closed = true;
    }
}
