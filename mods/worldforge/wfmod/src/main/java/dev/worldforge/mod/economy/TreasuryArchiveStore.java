package dev.worldforge.mod.economy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Canonical on-disk treasury store: immutable journal segments plus an atomically
 * published snapshot manifest. A legacy JSON ledger is read only for first migration;
 * after CURRENT.json exists it is never consulted again.
 */
public final class TreasuryArchiveStore {
    private final Path root;
    private final TreasuryJournalArchive archive;
    private final TreasuryCheckpointStore checkpoints;

    public TreasuryArchiveStore(Path root) throws IOException {
        if (root == null) throw new IllegalArgumentException("Treasury store root is required");
        this.root = root;
        Files.createDirectories(root);
        this.archive = new TreasuryJournalArchive(root.resolve("archive"), 1);
        this.checkpoints = new TreasuryCheckpointStore(root.resolve("checkpoints"));
    }

    /**
     * A committed checkpoint always wins over legacy JSON. If no manifest exists,
     * import the legacy ledger (or an empty new-world ledger), then commit and reload
     * through the exact same verification/replay path used on subsequent startups.
     */
    public synchronized TreasuryLedger loadOrMigrate(Path legacyJson) throws IOException {
        Path manifest = root.resolve("checkpoints").resolve("CURRENT.json");
        if (Files.isRegularFile(manifest)) {
            TreasuryCheckpointStore.Checkpoint checkpoint = checkpoints.loadOrRecover(archive);
            // The legacy file is only a recovery source for the old shadow-write window.
            // Once its exact size/mtime fingerprint has been reconciled, do not deserialize
            // a potentially huge JSON ledger again on every ordinary server startup.
            if (legacyNeedsReconciliation(legacyJson, manifest)) {
                TreasuryLedger legacy;
                try { legacy = readLegacyIfValid(legacyJson); }
                catch (IOException invalidLegacyBackup) { legacy = null; }
                if (legacy != null && legacy.nextSequence() > archive.nextSequence()) {
                    verifyLegacyPrefix(legacy, archive);
                    archive.appendAll(legacy.entriesFromSequence(archive.nextSequence()));
                    if (archive.nextSequence() != legacy.nextSequence())
                        throw new IOException("Recovered legacy treasury suffix did not reach its sequence boundary");
                    checkpoints.publish(TreasuryLedgerSnapshot.capture(legacy), archive);
                    checkpoint = checkpoints.load(archive);
                }
                markLegacyReconciled(legacyJson);
            }
            return TreasuryLedger.restoreFromCheckpoint(checkpoint, archive);
        }

        TreasuryLedger legacy = readLegacyIfValid(legacyJson);
        if (legacy == null) legacy = new TreasuryLedger();
        TreasuryArchiveMigration.migrate(legacy, archive, checkpoints);
        TreasuryCheckpointStore.Checkpoint committed = checkpoints.load(archive);
        markLegacyReconciled(legacyJson);
        return TreasuryLedger.restoreFromCheckpoint(committed, archive);
    }

    private Path legacyMarker() { return root.resolve("LEGACY_RECONCILED"); }

    private boolean legacyNeedsReconciliation(Path legacyJson, Path manifest) throws IOException {
        if (legacyJson == null || !Files.isRegularFile(legacyJson)) return false;
        String fingerprint = Files.size(legacyJson) + "\\n"
                + Files.getLastModifiedTime(legacyJson).toString();
        Path marker = legacyMarker();
        if (Files.isRegularFile(marker)
                && Files.readString(marker, StandardCharsets.UTF_8).equals(fingerprint)) return false;
        // A canonical checkpoint written after the legacy file is already newer than
        // the shadow backup. Equal timestamps remain conservative and trigger one check.
        if (Files.getLastModifiedTime(legacyJson).compareTo(Files.getLastModifiedTime(manifest)) < 0) {
            writeLegacyMarker(marker, fingerprint);
            return false;
        }
        return true;
    }

    private void markLegacyReconciled(Path legacyJson) {
        if (legacyJson == null || !Files.isRegularFile(legacyJson)) return;
        try {
            String fingerprint = Files.size(legacyJson) + "\\n"
                    + Files.getLastModifiedTime(legacyJson).toString();
            writeLegacyMarker(legacyMarker(), fingerprint);
        } catch (IOException ignored) {
            // Marker loss costs a one-time legacy recheck, never correctness.
        }
    }

    private static void writeLegacyMarker(Path marker, String fingerprint) throws IOException {
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");
        Files.writeString(temporary, fingerprint, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static TreasuryLedger readLegacyIfValid(Path legacyJson) throws IOException {
        if (legacyJson == null || !Files.isRegularFile(legacyJson)) return null;
        try {
            return TreasuryLedger.fromJson(Files.readString(legacyJson));
        } catch (RuntimeException invalidLegacy) {
            throw new IOException("Legacy treasury JSON is invalid; refusing to replace it", invalidLegacy);
        }
    }

    private static void verifyLegacyPrefix(TreasuryLedger legacy, TreasuryJournalArchive archive) throws IOException {
        // A legacy shadow may already be compacted: it can contain only the unarchived
        // suffix, while the canonical archive owns the immutable earlier prefix.
        List<TreasuryLedger.Entry> legacyEntries = legacy.journal();
        long legacyNext = legacy.nextSequence();
        long legacyFirst = legacyEntries.isEmpty() ? legacyNext : legacyEntries.get(0).sequence();
        long expected = legacyFirst;
        for (TreasuryLedger.Entry entry : legacyEntries) {
            if (entry.sequence() != expected++)
                throw new IOException("Legacy treasury suffix has a sequence gap at " + (expected - 1));
        }
        if (expected != legacyNext)
            throw new IOException("Legacy treasury suffix does not reach its declared sequence");
        long archiveNext = archive.nextSequence();
        if (legacyFirst > archiveNext)
            throw new IOException("Legacy treasury suffix starts after the canonical archive cursor");
        if (legacyFirst == archiveNext) return;

        java.util.Iterator<TreasuryLedger.Entry> expectedEntries = legacyEntries.iterator();
        long[] compared = {0};
        try {
            archive.forEachEntryFrom(legacyFirst, entry -> {
                if (!expectedEntries.hasNext() || !expectedEntries.next().equals(entry))
                    throw new IllegalStateException("Legacy treasury history conflicts with the committed archive prefix");
                compared[0]++;
            });
        } catch (RuntimeException conflict) {
            throw new IOException("Legacy treasury history does not match the committed archive prefix", conflict);
        }
        if (compared[0] != archiveNext - legacyFirst)
            throw new IOException("Legacy treasury does not contain the complete overlapping archive range");
    }

    /** Appends only the not-yet-archived suffix and publishes CURRENT last. */
    public synchronized long save(TreasuryLedger ledger) throws IOException {
        if (ledger == null) throw new IllegalArgumentException("Treasury ledger is required");
        synchronized (ledger) {
            long archiveNext = archive.nextSequence();
            if (archiveNext > ledger.nextSequence())
                throw new IOException("Treasury archive is ahead of the live ledger");
            if (archiveNext < ledger.nextSequence())
                archive.appendAll(ledger.entriesFromSequence(archiveNext));
            if (archive.nextSequence() != ledger.nextSequence())
                throw new IOException("Treasury archive did not reach the live ledger sequence");

            String snapshot = TreasuryLedgerSnapshot.capture(ledger);
            // publish() validates the snapshot and archive prefix, and atomically commits CURRENT.
            // Reloading it immediately performs a second full archive-prefix verification and
            // snapshot decode on the tick thread; the just-published immutable checkpoint
            // is already the validated commit result.
            TreasuryCheckpointStore.Checkpoint published = checkpoints.publish(snapshot, archive);
            ledger.acknowledgeArchivedThrough(archive, archive.nextSequence());
            return ledger.mutationVersion();
        }
    }

    public long nextSequence() { return archive.nextSequence(); }
}
