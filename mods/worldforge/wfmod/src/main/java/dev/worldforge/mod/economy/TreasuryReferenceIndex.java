package dev.worldforge.mod.economy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact disk-backed exact-reference candidate index. Each fixed-width record stores
 * a 64-bit key fingerprint and global journal sequence. 256 buckets bound lookup scans;
 * collisions are resolved against the authoritative journal entry.
 */
final class TreasuryReferenceIndex {
    private static final int BUCKETS = 256;
    private static final int RECORD_BYTES = 16;
    private final Path directory;
    private final Path cursorFile;

    TreasuryReferenceIndex(Path directory) throws IOException {
        this.directory = directory;
        this.cursorFile = directory.resolve("CURRENT");
        Files.createDirectories(directory);
    }

    synchronized boolean isCurrent(long archiveNext) throws IOException {
        if (!Files.isRegularFile(cursorFile)) return false;
        try {
            long indexedNext = Long.parseLong(Files.readString(cursorFile, StandardCharsets.US_ASCII).trim());
            if (indexedNext != archiveNext) return false;
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                Path path = bucketPath(bucket);
                if (Files.exists(path) && Files.size(path) % RECORD_BYTES != 0) return false;
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    synchronized void rebuild(TreasuryJournalArchive archive) throws IOException {
        clearBuckets();
        archive.forEachEntry(entry -> appendRecord(entry.accountId(), entry.kind().name(),
                entry.reference(), entry.sequence()));
        publishCursor(archive.nextSequence());
    }

    synchronized void append(List<TreasuryLedger.Entry> entries, long exclusiveNextSequence) throws IOException {
        for (TreasuryLedger.Entry entry : entries)
            appendRecord(entry.accountId(), entry.kind().name(), entry.reference(), entry.sequence());
        publishCursor(exclusiveNextSequence);
    }

    synchronized List<Long> find(String accountId, String kind, String reference) throws IOException {
        String key = String.valueOf(accountId) + "\u0000" + String.valueOf(kind) + "\u0000"
                + (reference == null ? "" : reference.trim());
        long fingerprint = fingerprint(key);
        Path bucket = bucketPath((int) fingerprint & 0xff);
        if (!Files.isRegularFile(bucket)) return List.of();
        List<Long> sequences = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(bucket, StandardOpenOption.READ)) {
            ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES);
            while (channel.read(record) == RECORD_BYTES) {
                record.flip();
                long candidate = record.getLong();
                long sequence = record.getLong();
                if (candidate == fingerprint) sequences.add(sequence);
                record.clear();
            }
        }
        return List.copyOf(sequences);
    }

    private void appendRecord(String accountId, String kind, String reference, long sequence) throws IOException {
        String key = String.valueOf(accountId) + "\u0000" + String.valueOf(kind) + "\u0000"
                + (reference == null ? "" : reference.trim());
        long fingerprint = fingerprint(key);
        Path bucket = bucketPath((int) fingerprint & 0xff);
        try (FileChannel channel = FileChannel.open(bucket, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).putLong(fingerprint).putLong(sequence);
            record.flip();
            while (record.hasRemaining()) channel.write(record);
            channel.force(false);
        }
    }

    private void publishCursor(long exclusiveNextSequence) throws IOException {
        Path temp = Files.createTempFile(directory, ".index-cursor-", ".tmp");
        try {
            Files.writeString(temp, Long.toString(exclusiveNextSequence), StandardCharsets.US_ASCII,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, cursorFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic treasury index cursor publication is unavailable", unsupported);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private void clearBuckets() throws IOException {
        for (int bucket = 0; bucket < BUCKETS; bucket++) Files.deleteIfExists(bucketPath(bucket));
        Files.deleteIfExists(cursorFile);
    }

    private Path bucketPath(int bucket) {
        return directory.resolve(String.format(java.util.Locale.ROOT, "bucket-%02x.bin", bucket));
    }

    private static long fingerprint(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= valueByte & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
