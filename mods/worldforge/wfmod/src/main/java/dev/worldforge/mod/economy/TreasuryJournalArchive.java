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
import java.util.LinkedHashMap;
import java.util.Map;
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
    // Segment files are immutable and this archive has one synchronized writer.
    // Keep the ordered directory snapshot for the lifetime of the archive instance.
    private List<Path> segmentFilesCache;
    private record SegmentMeta(long firstSequence, long lastSequence, String checksum, Path path) { }
    // Populated while segments are checksum-verified on open and extended only after
    // an atomic append. Save-time digesting never reparses old 65k-entry JSON segments.
    private List<SegmentMeta> segmentMetadataCache;
    // Immutable archive prefixes never change. Reuse their verified digest during the
    // publish -> previous-checkpoint validation -> round-trip validation sequence.
    private final Map<Long, String> digestCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
            return size() > 8;
        }
    };
    private final TreasuryReferenceIndex referenceIndex;

    public TreasuryJournalArchive(Path directory, long firstExpectedSequence) throws IOException {
        if (directory == null || firstExpectedSequence < 1)
            throw new IllegalArgumentException("Archive directory and positive starting sequence are required");
        this.directory = directory;
        this.firstSequence = firstExpectedSequence;
        Files.createDirectories(directory);
        this.nextSequence = verifyExisting(firstExpectedSequence);
        this.referenceIndex = new TreasuryReferenceIndex(directory.resolve("reference-index"));
        if (!referenceIndex.isCurrent(this.nextSequence)) referenceIndex.rebuild(this);
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
        String cached = digestCache.get(checkpointNextSequence);
        if (cached != null) return cached;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long expected = firstSequence;
            List<SegmentMeta> metadata = segmentMetadataCache;
            if (metadata == null) throw new IOException("Treasury archive segment metadata is unavailable");
            for (SegmentMeta segment : metadata) {
                if (segment.firstSequence() != expected)
                    throw new IOException("Treasury archive sequence gap while fingerprinting");
                if (segment.firstSequence() >= checkpointNextSequence) break;
                String prefixChecksum = segment.checksum();
                long prefixLast = segment.lastSequence();
                if (segment.lastSequence() >= checkpointNextSequence) {
                    // Only arbitrary mid-segment checkpoints need a partial-prefix decode.
                    TreasuryJournalSegment.Decoded decoded = TreasuryJournalSegment.decode(
                            Files.readString(segment.path(), StandardCharsets.UTF_8));
                    List<TreasuryLedger.Entry> prefix = decoded.entries().stream()
                            .takeWhile(entry -> entry.sequence() < checkpointNextSequence).toList();
                    if (prefix.isEmpty()) break;
                    TreasuryJournalSegment.Decoded partial = TreasuryJournalSegment.decode(
                            TreasuryJournalSegment.encode(prefix));
                    prefixChecksum = partial.checksum();
                    prefixLast = partial.lastSequence();
                }
                String line = segment.firstSequence() + ":" + prefixLast + ":" + prefixChecksum + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                expected = prefixLast + 1;
                if (prefixLast >= checkpointNextSequence - 1) break;
            }
            if (expected < checkpointNextSequence)
                throw new IOException("Treasury archive is missing checkpoint prefix entries");
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
            String result = hex.toString();
            digestCache.put(checkpointNextSequence, result);
            return result;
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
            if (segmentFilesCache != null) {
                List<Path> updatedSegments = new ArrayList<>(segmentFilesCache.size() + 1);
                updatedSegments.addAll(segmentFilesCache);
                updatedSegments.add(destination);
                segmentFilesCache = List.copyOf(updatedSegments);
            }
            if (segmentMetadataCache != null) {
                List<SegmentMeta> updatedMetadata = new ArrayList<>(segmentMetadataCache.size() + 1);
                updatedMetadata.addAll(segmentMetadataCache);
                updatedMetadata.add(new SegmentMeta(decoded.firstSequence(), decoded.lastSequence(),
                        decoded.checksum(), destination));
                segmentMetadataCache = List.copyOf(updatedMetadata);
            }
            nextSequence = decoded.lastSequence() + 1;
            // A previous crash may have published a segment but failed before advancing
            // the index cursor. Rebuild from the now-canonical archive before appending,
            // otherwise the stale cursor would permanently reject the next segment.
            if (!referenceIndex.isCurrent(decoded.firstSequence()))
                referenceIndex.rebuild(this);
            else
                referenceIndex.append(decoded.entries(), nextSequence);
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
        List<Path> segments = segmentFiles();
        for (int i = firstSegmentAtOrAfter(segments, requestedFirstSequence); i < segments.size(); i++) {
            Path path = segments.get(i);
            // Binary-search immutable filename bounds before opening or decoding segments.
            // A resumed page therefore does not walk the entire archived prefix.
            if (segmentLastSequence(path) < requestedFirstSequence) continue;
            TreasuryJournalSegment.Decoded decoded;
            try {
                decoded = TreasuryJournalSegment.decode(Files.readString(path, StandardCharsets.UTF_8));
            } catch (RuntimeException invalid) {
                throw new IOException("Treasury archive segment failed validation: " + path.getFileName(), invalid);
            }
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

    /** Exact-reference lookup via the compact disk index; hash collisions are checked against journal rows. */
    public synchronized List<TreasuryLedger.Entry> readReferenceEntries(String accountId, String kind,
                                                                          String reference) throws IOException {
        if (accountId == null || accountId.isBlank() || kind == null || kind.isBlank())
            throw new IllegalArgumentException("Treasury reference lookup requires account and kind");
        if (!referenceIndex.isCurrent(nextSequence)) referenceIndex.rebuild(this);
        List<TreasuryLedger.Entry> matches = new ArrayList<>();
        for (long sequence : referenceIndex.find(accountId, kind, reference)) {
            List<TreasuryLedger.Entry> page = readPage(sequence, 1);
            if (page.isEmpty()) continue;
            TreasuryLedger.Entry entry = page.get(0);
            if (accountId.equals(entry.accountId()) && kind.equals(entry.kind().name())
                    && (reference == null ? "" : reference.trim()).equals(entry.reference()))
                matches.add(entry);
        }
        matches.sort(Comparator.comparingLong(TreasuryLedger.Entry::sequence));
        return List.copyOf(matches);
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
            List<Path> segments = segmentFiles();
            for (int i = firstSegmentAtOrAfter(segments, cursor[0]); i < segments.size(); i++) {
                Path path = segments.get(i);
                // Resume directly at the first segment whose filename range can contain
                // the requested cursor; do not walk or deserialize earlier segments.
                if (segmentLastSequence(path) < cursor[0]) continue;
                TreasuryJournalSegment.Decoded decoded;
                try {
                    decoded = TreasuryJournalSegment.decode(Files.readString(path, StandardCharsets.UTF_8));
                } catch (RuntimeException invalid) {
                    throw new IOException("Treasury archive segment failed validation: " + path.getFileName(), invalid);
                }
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
        List<SegmentMeta> verifiedMetadata = new ArrayList<>();
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
            verifiedMetadata.add(new SegmentMeta(decoded.firstSequence(), decoded.lastSequence(),
                    decoded.checksum(), path));
            next = decoded.lastSequence() + 1;
        }
        segmentMetadataCache = List.copyOf(verifiedMetadata);
        return next;
    }

    private List<Path> segmentFiles() throws IOException {
        if (segmentFilesCache != null) return segmentFilesCache;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "segment-*.json")) {
            for (Path path : stream) files.add(path);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        segmentFilesCache = List.copyOf(files);
        return segmentFilesCache;
    }

    /** Finds the first immutable segment whose inclusive end can contain the requested sequence. */
    private static int firstSegmentAtOrAfter(List<Path> segments, long sequence) throws IOException {
        int low = 0;
        int high = segments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (segmentLastSequence(segments.get(middle)) < sequence) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private Path segmentPath(long first, long last) {
        return directory.resolve(String.format(java.util.Locale.ROOT,
                "segment-%020d-%020d.json", first, last));
    }

    private static long segmentLastSequence(Path path) throws IOException {
        String name = path.getFileName().toString();
        int separator = name.indexOf('-', "segment-".length());
        if (!name.startsWith("segment-") || separator < 0 || !name.endsWith(".json"))
            throw new IOException("Invalid treasury segment filename: " + name);
        return parseSequence(name.substring(separator + 1, name.length() - ".json".length()));
    }

    private static long parseSequence(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IOException("Invalid treasury segment sequence in filename", ex); }
    }
}
