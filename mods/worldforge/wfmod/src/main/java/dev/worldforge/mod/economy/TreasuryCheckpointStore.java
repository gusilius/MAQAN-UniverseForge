package dev.worldforge.mod.economy;

import dev.worldforge.mod.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Publishes a snapshot only after its archive sequence has been verified by the caller.
 * A small CURRENT manifest is the commit point: an orphan snapshot after a crash is harmless.
 */
public final class TreasuryCheckpointStore {
    public static final String FORMAT = "wf-treasury-checkpoint/1";
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("snapshot-\\d{20}-[0-9a-f]{64}\\.json");
    private final Path directory;

    public record Checkpoint(String snapshotJson, long nextSequence, String checksum, String archiveChecksum) { }

    public TreasuryCheckpointStore(Path directory) throws IOException {
        if (directory == null) throw new IllegalArgumentException("Checkpoint directory is required");
        this.directory = directory;
        Files.createDirectories(directory);
    }

    /**
     * Atomically publishes the snapshot pointer after confirming that the journal archive
     * contains every sequence before snapshot.nextSequence. No history is deleted here.
     */
    public synchronized Checkpoint publish(String snapshotJson, TreasuryJournalArchive archive) throws IOException {
        if (snapshotJson == null || archive == null)
            throw new IllegalArgumentException("Snapshot and verified archive are required");
        TreasuryLedgerSnapshot.State state;
        try {
            state = TreasuryLedgerSnapshot.decode(snapshotJson);
        } catch (RuntimeException ex) {
            throw new IOException("Treasury checkpoint snapshot failed validation", ex);
        }
        String archiveDigest = archive.archiveDigestThrough(state.nextSequence());
        long archiveNext = archive.nextSequence();
        if (archiveNext < state.nextSequence())
            throw new IOException("Treasury archive is at next sequence " + archiveNext
                    + " but snapshot requires " + state.nextSequence());

        String checksum = sha256(snapshotJson);
        String filename = String.format(java.util.Locale.ROOT, "snapshot-%020d-%s.json",
                state.nextSequence(), checksum);
        Path snapshotPath = directory.resolve(filename);
        if (Files.exists(snapshotPath)) {
            if (!Files.readString(snapshotPath, StandardCharsets.UTF_8).equals(snapshotJson))
                throw new IOException("Treasury checkpoint snapshot filename collision");
        } else {
            atomicWrite(snapshotPath, snapshotJson);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("snapshot", filename);
        manifest.put("nextSequence", state.nextSequence());
        manifest.put("sha256", checksum);
        manifest.put("archiveSha256", archiveDigest);
        Path currentPath = directory.resolve("CURRENT.json");
        if (Files.isRegularFile(currentPath)) {
            // Preserve only a checkpoint that is already committed and archive-verified.
            load(archive);
            atomicWrite(directory.resolve("PREVIOUS.json"),
                    Files.readString(currentPath, StandardCharsets.UTF_8));
        }
        atomicWrite(currentPath, Json.write(manifest));
        return new Checkpoint(snapshotJson, state.nextSequence(), checksum, archiveDigest);
    }

    /** Loads a checkpoint only when the currently verified archive has the same immutable prefix through the checkpoint. */
    public synchronized Checkpoint load(TreasuryJournalArchive archive) throws IOException {
        if (archive == null) throw new IllegalArgumentException("Verified treasury archive is required");
        Checkpoint checkpoint = load();
        Object parsed = Json.parse(Files.readString(directory.resolve("CURRENT.json"), StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> manifest)
                || !(manifest.get("archiveSha256") instanceof String expected)
                || !expected.matches("[0-9a-f]{64}"))
            throw new IOException("Treasury checkpoint is missing its archive fingerprint");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                checkpoint.archiveChecksum().getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Treasury checkpoint archive fingerprint metadata changed");
        verify(checkpoint, archive);
        return checkpoint;
    }

    /**
     * Loads CURRENT when valid. If the pointer or its snapshot was damaged, scans
     * immutable snapshots, chooses the newest one whose checksum and archive prefix
     * both verify, and atomically republishes CURRENT. Journal entries after that
     * snapshot are replayed by TreasuryLedger.restoreFromCheckpoint.
     */
    public synchronized Checkpoint loadOrRecover(TreasuryJournalArchive archive) throws IOException {
        if (archive == null) throw new IllegalArgumentException("Verified treasury archive is required");
        IOException primaryFailure;
        try {
            return load(archive);
        } catch (IOException invalidCurrent) {
            primaryFailure = invalidCurrent;
        }

        Path previousPath = directory.resolve("PREVIOUS.json");
        if (Files.isRegularFile(previousPath)) {
            try {
                Checkpoint previous = loadManifest(previousPath, archive);
                // PREVIOUS is advanced before CURRENT, so it can only name a committed
                // checkpoint. Restore the pointer, then use the ordinary verified path.
                atomicWrite(directory.resolve("CURRENT.json"),
                        Files.readString(previousPath, StandardCharsets.UTF_8));
                return load(archive);
            } catch (IOException invalidPrevious) {
                primaryFailure.addSuppressed(invalidPrevious);
            }
        }
        throw new IOException("Neither CURRENT nor PREVIOUS treasury checkpoint is recoverable",
                primaryFailure);
    }

    /**
     * Verifies a checkpoint object independently of how it was obtained. Restore callers
     * must not be able to pair a valid snapshot with a different archive that happens to
     * end at the same sequence number.
     */
    public static void verify(Checkpoint checkpoint, TreasuryJournalArchive archive) throws IOException {
        if (checkpoint == null || archive == null)
            throw new IllegalArgumentException("Treasury checkpoint and archive are required");
        String actualSnapshotChecksum = sha256(checkpoint.snapshotJson());
        if (checkpoint.checksum() == null
                || !MessageDigest.isEqual(checkpoint.checksum().getBytes(StandardCharsets.US_ASCII),
                actualSnapshotChecksum.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Treasury checkpoint snapshot checksum mismatch");
        if (checkpoint.archiveChecksum() == null || !checkpoint.archiveChecksum().matches("[0-9a-f]{64}"))
            throw new IOException("Treasury checkpoint archive fingerprint is missing");
        TreasuryLedgerSnapshot.State state;
        try { state = TreasuryLedgerSnapshot.decode(checkpoint.snapshotJson()); }
        catch (RuntimeException invalid) { throw new IOException("Treasury checkpoint snapshot is invalid", invalid); }
        if (checkpoint.nextSequence() != state.nextSequence()
                || archive.nextSequence() < checkpoint.nextSequence())
            throw new IOException("Treasury checkpoint sequence does not match its archive");
        String actualArchiveChecksum = archive.archiveDigestThrough(checkpoint.nextSequence());
        if (!MessageDigest.isEqual(checkpoint.archiveChecksum().getBytes(StandardCharsets.US_ASCII),
                actualArchiveChecksum.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Treasury checkpoint does not match the verified archive contents");
    }

    /** Loads only the committed snapshot; invalid or missing data fails closed. */
    public synchronized Checkpoint load() throws IOException {
        return loadManifest(directory.resolve("CURRENT.json"), null);
    }

    private Checkpoint loadManifest(Path manifestPath, TreasuryJournalArchive archive) throws IOException {
        if (!Files.isRegularFile(manifestPath))
            throw new IOException("Treasury checkpoint manifest is missing");
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(manifestPath, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new IOException("Treasury checkpoint manifest is invalid", ex);
        }
        if (!(parsed instanceof Map<?, ?> manifest) || !FORMAT.equals(manifest.get("format")))
            throw new IOException("Unsupported treasury checkpoint manifest");
        Object nameValue = manifest.get("snapshot");
        if (!(nameValue instanceof String filename) || !SNAPSHOT_NAME.matcher(filename).matches())
            throw new IOException("Invalid treasury checkpoint snapshot filename");
        long nextSequence = requiredLong(manifest.get("nextSequence"));
        Object checksumValue = manifest.get("sha256");
        if (!(checksumValue instanceof String expected) || !expected.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid treasury checkpoint checksum metadata");
        Path snapshotPath = directory.resolve(filename).normalize();
        if (!snapshotPath.getParent().equals(directory.normalize()) || !Files.isRegularFile(snapshotPath))
            throw new IOException("Committed treasury checkpoint snapshot is missing");
        String snapshotJson = Files.readString(snapshotPath, StandardCharsets.UTF_8);
        String actual = sha256(snapshotJson);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Treasury checkpoint snapshot checksum mismatch");
        TreasuryLedgerSnapshot.State state;
        try {
            state = TreasuryLedgerSnapshot.decode(snapshotJson);
        } catch (RuntimeException ex) {
            throw new IOException("Committed treasury snapshot failed validation", ex);
        }
        if (state.nextSequence() != nextSequence
                || !filename.equals(String.format(java.util.Locale.ROOT, "snapshot-%020d-%s.json",
                nextSequence, actual)))
            throw new IOException("Treasury checkpoint manifest does not match snapshot metadata");
        Object archiveChecksumValue = manifest.get("archiveSha256");
        if (!(archiveChecksumValue instanceof String archiveChecksum)
                || !archiveChecksum.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid treasury checkpoint archive fingerprint");
        Checkpoint checkpoint = new Checkpoint(snapshotJson, nextSequence, actual, archiveChecksum);
        if (archive != null) verify(checkpoint, archive);
        return checkpoint;
    }

    private static void atomicWrite(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        Path temporary = Files.createTempFile(parent, ".treasury-checkpoint-", ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic treasury checkpoint publication is not supported by this filesystem", unsupported);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static long requiredLong(Object value) throws IOException {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < 1
                || n.doubleValue() >= Long.MAX_VALUE)
            throw new IOException("Invalid treasury checkpoint sequence");
        return n.longValue();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
