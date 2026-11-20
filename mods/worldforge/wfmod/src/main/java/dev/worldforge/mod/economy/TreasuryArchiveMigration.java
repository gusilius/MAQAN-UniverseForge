package dev.worldforge.mod.economy;

import java.io.IOException;
import java.util.List;

/**
 * One-way, retryable migration of a legacy in-memory/full-JSON ledger into the
 * immutable journal archive plus a committed checkpoint. The legacy ledger remains
 * authoritative; callers may switch load/save authority only in a later, separately
 * verified change.
 */
public final class TreasuryArchiveMigration {
    public record Result(long nextSequence, long archivedEntries, long mutationVersion,
                         String snapshotChecksum) { }

    private TreasuryArchiveMigration() { }

    /**
     * Captures a consistent ledger view, archives its complete sequence from 1, and
     * publishes CURRENT only after the archive prefix and snapshot have both validated.
     * A crash after any segment leaves a retryable archive prefix; a crash before CURRENT
     * leaves the old save authoritative.
     */
    public static Result migrate(TreasuryLedger ledger, TreasuryJournalArchive archive,
                                 TreasuryCheckpointStore checkpoints) throws IOException {
        if (ledger == null || archive == null || checkpoints == null)
            throw new IllegalArgumentException("Ledger, archive and checkpoint store are required");

        synchronized (ledger) {
            String snapshotJson = TreasuryLedgerSnapshot.capture(ledger);
            TreasuryLedgerSnapshot.State snapshot = TreasuryLedgerSnapshot.decode(snapshotJson);
            List<TreasuryLedger.Entry> entries = List.copyOf(ledger.journal());

            if (entries.size() != snapshot.nextSequence() - 1)
                throw new IOException("Legacy treasury journal is not a complete sequence from 1");
            long expected = 1;
            for (TreasuryLedger.Entry entry : entries) {
                if (entry.sequence() != expected++)
                    throw new IOException("Legacy treasury journal has a sequence gap at " + (expected - 1));
            }
            if (expected != snapshot.nextSequence())
                throw new IOException("Legacy treasury snapshot sequence does not match its journal");

            long archived = archive.appendAll(entries);
            if (archive.nextSequence() != snapshot.nextSequence())
                throw new IOException("Treasury archive does not end at the captured ledger sequence");

            TreasuryCheckpointStore.Checkpoint committed = checkpoints.publish(snapshotJson, archive);
            TreasuryCheckpointStore.Checkpoint verified = checkpoints.load(archive);
            if (verified.nextSequence() != snapshot.nextSequence()
                    || !verified.snapshotJson().equals(snapshotJson)
                    || !committed.checksum().equals(verified.checksum()))
                throw new IOException("Treasury migration checkpoint did not round-trip exactly");

            return new Result(snapshot.nextSequence(), archived, snapshot.mutationVersion(), verified.checksum());
        }
    }
}
