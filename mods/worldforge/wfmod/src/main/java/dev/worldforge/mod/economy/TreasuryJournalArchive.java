package dev.worldforge.mod.economy;

import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Durable append-only storage for immutable treasury journal segments.
 * Each file is checksummed and published by an atomic rename after its bytes are forced.
 * Existing segments are verified on open; gaps, overlaps and conflicting retries fail closed.
 */
public final class TreasuryJournalArchive {
    private static final Pattern FILE_NAME = Pattern.compile("segment-(\\d{20})-(\\d{20})\\.json");
    private final Path directory;
    private final long firstSequence;
    private long nextSequence;

    public TreasuryJournalArchive(Path directory, long firstExpectedSequence) throws IOException {
        if (directory == null || firstExpectedSequence < 1)
            throw new IllegalArgumentException("Archive directory and positive starting sequence are required");
        this.directory = directory;
        this.firstSequence = firstExpectedSequence;
        Files.createDirectories(directory);
        this.nextSequence = verifyExisting(firstExpectedSequence);
    }

    public synchronized long nextSequence() { return nextSequence; }
    /** Stable fingerprint of the complete currently verified archive. */
    public synchronized String archiveDigest() throws IOException {
        return archiveDigestThrough(nextSequence);
    }

    /**
     * Fingerprints exactly the archived entries with sequence less than nextSequence.
     * Later appended history is deliberately excluded, so older checkpoints remain valid.
     */
    public synchronized String archiveDigestThrough(long checkpointNextSequence) throws IOException {
        if (checkpointNextSequence < firstSequence || checkpointNextSequence > nextSequence)
            throw new IOException("Treasury archive does not contain checkpoint prefix in retained range ["
                    + firstSequence + ", " + nextSequence + "]");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long expected = firstSequence;
            for (Path path : segmentFiles()) {
                TreasuryJournalSegment.Decoded decoded = TreasuryJournalSegment.decode(
                        Files.readString(path, StandardCharsets.UTF_8));
                if (decoded.firstSequence() != expected)
                    throw new IOException("Treasury archive sequence gap while fingerprinting");
                if (decoded.firstSequence() >= checkpointNextSequence) break;
                List<TreasuryLedger.Entry> prefix = decoded.entries().stream()
                        .takeWhile(entry -> entry.sequence() < checkpointNextSequence).toList();
                if (!prefix.isEmpty()) {
                    TreasuryJournalSegment.Decoded prefixSegment = TreasuryJournalSegment.decode(
                            TreasuryJournalSegment.encode(prefix));
                    String line = prefixSegment.firstSequence() + ":" + prefixSegment.lastSequence()
                            + ":" + prefixSegment.checksum() + "\n";
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                }
                expected = decoded.lastSequence() + 1;
                if (decoded.lastSequence() >= checkpointNextSequence - 1) break;
            }
            if (expected < checkpointNextSequence)
                throw new IOException("Treasury archive is missing checkpoint prefix entries");
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (RuntimeException invalid) {
            throw new IOException("Treasury archive failed validation while fingerprinting", invalid);
        }
    }


    /** Returns true after a new segment is durably published or an identical retry is verified. */
    public synchronized boolean append(List<TreasuryLedger.Entry> entries) throws IOException {
        String encoded = TreasuryJournalSegment.encode(entries);
        TreasuryJournalSegment.Decoded decoded = TreasuryJournalSegment.decode(encoded);
        Path destination = segmentPath(decoded.firstSequence(), decoded.lastSequence());
        if (Files.exists(destination)) {
            String existing = Files.readString(destination, StandardCharsets.UTF_8);
            TreasuryJournalSegment.decode(existing);
            if (existing.equals(encoded)) return true;
            throw new IOException("Conflicting treasury archive segment already exists: " + destination.getFileName());
        }
        if (decoded.firstSequence() != nextSequence)
            throw new IOException("Treasury archive expected sequence " + nextSequence
                    + " but received " + decoded.firstSequence());

        Path temporary = Files.createTempFile(directory, ".treasury-segment-", ".tmp");
        boolean published = false;
        try {
            byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic treasury segment publication is not supported by this filesystem", unsupported);
            }
            published = true;
            nextSequence = decoded.lastSequence() + 1;
            return true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    /** Streams the complete verified archive one segment at a time. */
    public synchronized void forEachEntry(Consumer<TreasuryLedger.Entry> consumer) throws IOException {
        forEachEntryFrom(firstSequence, consumer);
    }

    /**
     * Streams the archive suffix beginning at an exact global sequence.
     * No whole-history list is materialized; every segment and every sequence boundary is checked.
     */
    public synchronized void forEachEntryFrom(long requestedFirstSequence,
                                              Consumer<TreasuryLedger.Entry> consumer) throws IOException {
        if (consumer == null) throw new IllegalArgumentException("Entry consumer is required");
        if (requestedFirstSequence < firstSequence || requestedFirstSequence > nextSequence)
            throw new IOException("Requested treasury archive sequence is outside the retained range");
        long expected = requestedFirstSequence;
        if (requestedFirstSequence == nextSequence) return;
        for (Path path : segmentFiles()) {
            TreasuryJournalSegment.Decoded decoded;
            try {
                decoded = TreasuryJournalSegment.decode(Files.readString(path, StandardCharsets.UTF_8));
            } catch (RuntimeException invalid) {
                throw new IOException("Treasury archive segment failed validation: " + path.getFileName(), invalid);
            }
            if (decoded.lastSequence() < requestedFirstSequence) continue;
            if (decoded.firstSequence() > expected)
                throw new IOException("Treasury archive suffix has a sequence gap at " + expected);
            for (TreasuryLedger.Entry entry : decoded.entries()) {
                if (entry.sequence() < requestedFirstSequence) continue;
                if (entry.sequence() != expected)
                    throw new IOException("Treasury archive suffix expected sequence " + expected
                            + " but found " + entry.sequence());
                consumer.accept(entry);
                expected++;
            }
            if (expected == nextSequence) break;
        }
        if (expected != nextSequence)
            throw new IOException("Treasury archive suffix ended at " + (expected - 1)
                    + " but archive requires " + (nextSequence - 1));
    }

    /** A bounded account-history page; nextSequence is the exclusive resume cursor. */
    public record AccountPage(List<TreasuryLedger.Entry> entries, long nextSequence, boolean complete) {
        public AccountPage {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Reads account history directly from immutable segments without constructing an
     * account-sized in-memory index. The cursor advances over scanned global entries,
     * not just matches, so callers can resume without rescanning earlier history.
     */
    public synchronized AccountPage readAccountPage(String accountId, long firstSequence,
                                                     int maxEntries) throws IOException {
        if (accountId == null || accountId.isBlank())
            throw new IllegalArgumentException("Treasury account ID is required");
        if (maxEntries < 1 || maxEntries > 4096)
            throw new IllegalArgumentException("Treasury page size must be between 1 and 4096");
        if (firstSequence < this.firstSequence || firstSequence > nextSequence)
            throw new IOException("Requested treasury account page starts outside the retained archive");
        List<TreasuryLedger.Entry> matches = new ArrayList<>(Math.min(maxEntries, 128));
        long[] cursor = {firstSequence};
        boolean[] full = {false};
        if (firstSequence < nextSequence) {
            for (Path path : segmentFiles()) {
                TreasuryJournalSegment.Decoded decoded;
                try {
                    decoded = TreasuryJournalSegment.decode(Files.readString(path, StandardCharsets.UTF_8));
                } catch (RuntimeException invalid) {
                    throw new IOException("Treasury archive segment failed validation: " + path.getFileName(), invalid);
                }
                if (decoded.lastSequence() < cursor[0]) continue;
                if (decoded.firstSequence() > cursor[0])
                    throw new IOException("Treasury archive account page has a sequence gap at " + cursor[0]);
                for (TreasuryLedger.Entry entry : decoded.entries()) {
                    if (entry.sequence() < cursor[0]) continue;
                    if (entry.sequence() != cursor[0])
                        throw new IOException("Treasury archive account page expected sequence " + cursor[0]
                                + " but found " + entry.sequence());
                    cursor[0]++;
                    if (accountId.equals(entry.accountId())) matches.add(entry);
                    if (matches.size() == maxEntries) {
                        full[0] = true;
                        break;
                    }
                }
                if (full[0] || cursor[0] == nextSequence) break;
            }
            if (!full[0] && cursor[0] != nextSequence)
                throw new IOException("Treasury archive account page ended before the committed sequence");
        }
        return new AccountPage(matches, cursor[0], cursor[0] == nextSequence);
    }

    /**
     * Reads a bounded journal page by global sequence. The returned list never exceeds
     * maxEntries; callers can request the next page using last.sequence() + 1.
     */
    public synchronized List<TreasuryLedger.Entry> readPage(long firstSequence, int maxEntries) throws IOException {
        if (maxEntries < 1 || maxEntries > 4096)
            throw new IllegalArgumentException("Treasury page size must be between 1 and 4096");
        if (firstSequence < firstSequence() || firstSequence > nextSequence)
            throw new IOException("Requested treasury page starts outside the retained archive");
        if (firstSequence == nextSequence) return List.of();
        List<TreasuryLedger.Entry> page = new ArrayList<>(Math.min(maxEntries, TreasuryJournalSegment.MAX_ENTRIES));
        try {
            forEachEntryFrom(firstSequence, entry -> {
                page.add(entry);
                if (page.size() == maxEntries) throw PageLimitReached.INSTANCE;
            });
        } catch (PageLimitReached completePage) {
            // The requested bounded page is complete; later archive entries are intentionally unread.
        }
        return List.copyOf(page);
    }

    private long firstSequence() { return firstSequence; }

    private static final class PageLimitReached extends RuntimeException {
        private static final PageLimitReached INSTANCE = new PageLimitReached();
        private PageLimitReached() { super(null, null, false, false); }
    }

    /**
     * Appends an arbitrarily large ordered journal stream using bounded segment buffers.
     * Earlier chunks remain valid if a later chunk fails; retrying the same iterable is safe
     * because append() verifies already-published segments byte-for-byte.
     */
    public synchronized long appendAll(Iterable<TreasuryLedger.Entry> entries) throws IOException {
        if (entries == null) throw new IllegalArgumentException("Treasury entries are required");
        List<TreasuryLedger.Entry> batch = new ArrayList<>(TreasuryJournalSegment.MAX_ENTRIES);
        long appended = 0;
        for (TreasuryLedger.Entry entry : entries) {
            if (entry == null) throw new IllegalArgumentException("Treasury entry is required");
            batch.add(entry);
            if (batch.size() == TreasuryJournalSegment.MAX_ENTRIES) {
                append(batch);
                appended += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            append(batch);
            appended += batch.size();
        }
        return appended;
    }

    public synchronized List<TreasuryLedger.Entry> readAll() throws IOException {
        List<TreasuryLedger.Entry> entries = new ArrayList<>();
        forEachEntry(entries::add);
        return List.copyOf(entries);
    }

    private long verifyExisting(long expected) throws IOException {
        long next = expected;
        for (Path path : segmentFiles()) {
            Matcher matcher = FILE_NAME.matcher(path.getFileName().toString());
            if (!matcher.matches()) throw new IOException("Invalid treasury segment filename: " + path.getFileName());
            long namedFirst = parseSequence(matcher.group(1));
            long namedLast = parseSequence(matcher.group(2));
            TreasuryJournalSegment.Decoded decoded;
            try {
                decoded = TreasuryJournalSegment.decode(Files.readString(path, StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                throw new IOException("Treasury archive segment failed validation: " + path.getFileName(), ex);
            }
            if (namedFirst != decoded.firstSequence() || namedLast != decoded.lastSequence())
                throw new IOException("Treasury segment filename does not match its payload: " + path.getFileName());
            if (decoded.firstSequence() != next)
                throw new IOException("Treasury archive sequence gap/overlap: expected " + next
                        + " but found " + decoded.firstSequence());
            next = decoded.lastSequence() + 1;
        }
        return next;
    }

    private List<Path> segmentFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "segment-*.json")) {
            for (Path path : stream) files.add(path);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private Path segmentPath(long first, long last) {
        return directory.resolve(String.format(java.util.Locale.ROOT,
                "segment-%020d-%020d.json", first, last));
    }

    private static long parseSequence(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IOException("Invalid treasury segment sequence in filename", ex); }
    }
}
