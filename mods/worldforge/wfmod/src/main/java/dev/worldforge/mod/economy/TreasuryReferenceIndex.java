package dev.worldforge.mod.economy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;

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
    // Bucket completeness is validated once per archive generation, not on every lookup.
    // All mutations go through this synchronized instance and publish a new cursor last.
    private long validatedArchiveNext = Long.MIN_VALUE;

    TreasuryReferenceIndex(Path directory) throws IOException {
        this.directory = directory;
        this.cursorFile = directory.resolve("CURRENT");
        Files.createDirectories(directory);
    }

    synchronized boolean isCurrent(long archiveNext) throws IOException {
        if (validatedArchiveNext == archiveNext) return true;
        if (!Files.isRegularFile(cursorFile)) return false;
        try {
            long indexedNext = Long.parseLong(Files.readString(cursorFile, StandardCharsets.US_ASCII).trim());
            if (indexedNext != archiveNext) return false;
            long indexedRecords = 0;
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                Path path = bucketPath(bucket);
                if (!Files.exists(path)) continue;
                long bytes = Files.size(path);
                if (bytes % RECORD_BYTES != 0) return false;
                indexedRecords += bytes / RECORD_BYTES;
            }
            // One index record must exist for every canonical journal entry. A valid-looking
            // cursor alone is insufficient: a deleted whole bucket would otherwise cause
            // false negatives while all remaining buckets still look structurally sound.
            boolean complete = indexedRecords == archiveNext - 1;
            if (complete) validatedArchiveNext = archiveNext;
            return complete;
        } catch (RuntimeException invalid) { return false; }
    }

    synchronized void rebuild(TreasuryJournalArchive archive) throws IOException {
        clearBuckets();
        List<TreasuryLedger.Entry> batch = new ArrayList<>(4096);
        try {
            archive.forEachEntry(entry -> {
                batch.add(entry);
                if (batch.size() == 4096) {
                    try { appendRecords(batch); }
                    catch (IOException failure) { throw new IndexWriteFailure(failure); }
                    batch.clear();
                }
            });
            if (!batch.isEmpty()) appendRecords(batch);
            publishCursor(archive.nextSequence());
        } catch (IndexWriteFailure failure) {
            throw failure.cause;
        }
    }

    synchronized void append(List<TreasuryLedger.Entry> entries, long exclusiveNextSequence) throws IOException {
        long indexedNext = Files.isRegularFile(cursorFile)
                ? Long.parseLong(Files.readString(cursorFile, StandardCharsets.US_ASCII).trim()) : 1L;
        if (exclusiveNextSequence < indexedNext)
            throw new IOException("Treasury reference index cannot move backwards");
        long expected = indexedNext;
        for (TreasuryLedger.Entry entry : entries) {
            if (entry.sequence() != expected++)
                throw new IOException("Treasury reference-index append is not contiguous at " + (expected - 1));
        }
        if (expected != exclusiveNextSequence)
            throw new IOException("Treasury reference-index append does not reach its published cursor");
        appendRecords(entries);
        publishCursor(exclusiveNextSequence);
    }

    /** Groups records by bucket so a checkpoint writes at most 256 files, not one fsync per entry. */
    private void appendRecords(List<TreasuryLedger.Entry> entries) throws IOException {
        Map<Integer, ByteArrayOutputStream> batches = new HashMap<>(BUCKETS);
        // Reuse one fixed-width encoder for the entire append. Allocating a ByteBuffer
        // object and backing array for every journal row creates avoidable allocation
        // pressure during large checkpoint/index rebuild batches.
        byte[] record = new byte[RECORD_BYTES];
        for (TreasuryLedger.Entry entry : entries) {
            String key = String.valueOf(entry.accountId()) + "\\u0000" + entry.kind().name()
                    + "\\u0000" + (entry.reference() == null ? "" : entry.reference().trim());
            long fingerprint = fingerprint(key);
            int bucketId = (int) fingerprint & 0xff;
            ByteArrayOutputStream batch = batches.computeIfAbsent(bucketId, ignored -> new ByteArrayOutputStream());
            putLong(record, 0, fingerprint);
            putLong(record, Long.BYTES, entry.sequence());
            batch.write(record, 0, record.length);
        }
        for (Map.Entry<Integer, ByteArrayOutputStream> batch : batches.entrySet()) {
            try (FileChannel channel = FileChannel.open(bucketPath(batch.getKey()), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer bytes = ByteBuffer.wrap(batch.getValue().toByteArray());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(false);
            }
        }
    }

    private static final class IndexWriteFailure extends RuntimeException {
        final IOException cause;
        IndexWriteFailure(IOException cause) { super(cause); this.cause = cause; }
    }

    synchronized List<Long> find(String accountId, String kind, String reference) throws IOException {
        String key = String.valueOf(accountId) + "\\u0000" + String.valueOf(kind) + "\\u0000"
                + (reference == null ? "" : reference.trim());
        long fingerprint = fingerprint(key);
        Path bucket = bucketPath((int) fingerprint & 0xff);
        if (!Files.isRegularFile(bucket)) return List.of();
        List<Long> sequences = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(bucket, StandardOpenOption.READ)) {
            ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES);
            while (true) {
                record.clear();
                int read = 0;
                while (record.hasRemaining()) {
                    int count = channel.read(record);
                    if (count < 0) break;
                    if (count == 0) continue;
                    read += count;
                }
                if (read == 0) break;
                if (read != RECORD_BYTES)
                    throw new IOException("Treasury reference-index bucket ends with a partial record");
                record.flip();
                long candidate = record.getLong();
                long sequence = record.getLong();
                if (candidate == fingerprint) sequences.add(sequence);
            }
        }
        return List.copyOf(sequences);
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
            validatedArchiveNext = exclusiveNextSequence;
        } finally { Files.deleteIfExists(temp); }
    }

    private void clearBuckets() throws IOException {
        validatedArchiveNext = Long.MIN_VALUE;
        for (int bucket = 0; bucket < BUCKETS; bucket++) Files.deleteIfExists(bucketPath(bucket));
        Files.deleteIfExists(cursorFile);
    }

    private Path bucketPath(int bucket) {
        return directory.resolve(String.format(java.util.Locale.ROOT, "bucket-%02x.bin", bucket));
    }

    private static void putLong(byte[] target, int offset, long value) {
        for (int index = Long.BYTES - 1; index >= 0; index--) {
            target[offset + index] = (byte) value;
            value >>>= Byte.SIZE;
        }
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
