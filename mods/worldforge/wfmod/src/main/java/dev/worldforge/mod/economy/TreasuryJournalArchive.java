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
    private long nextSequence;

    public TreasuryJournalArchive(Path directory, long firstExpectedSequence) throws IOException {
        if (directory == null || firstExpectedSequence < 1)
            throw new IllegalArgumentException("Archive directory and positive starting sequence are required");
        this.directory = directory;
        Files.createDirectories(directory);
        this.nextSequence = verifyExisting(firstExpectedSequence);
    }

    public synchronized long nextSequence() { return nextSequence; }

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
                Files.move(temporary, destination);
            }
            published = true;
            nextSequence = decoded.lastSequence() + 1;
            return true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    /** Streams verified entries one segment at a time; callers need not retain the whole archive. */
    public synchronized void forEachEntry(Consumer<TreasuryLedger.Entry> consumer) throws IOException {
        if (consumer == null) throw new IllegalArgumentException("Entry consumer is required");
        for (Path path : segmentFiles()) {
            TreasuryJournalSegment.Decoded decoded = TreasuryJournalSegment.decode(
                    Files.readString(path, StandardCharsets.UTF_8));
            for (TreasuryLedger.Entry entry : decoded.entries()) consumer.accept(entry);
        }
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
