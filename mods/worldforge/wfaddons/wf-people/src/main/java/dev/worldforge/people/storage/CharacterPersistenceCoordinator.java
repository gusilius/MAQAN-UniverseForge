package dev.worldforge.people.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Single coordination point for the existing Character WAL, checkpoint store and
 * eviction fence. It introduces no second journal and retains no Character objects.
 *
 * Canonical mutation owners register identities, markDirty after each committed mutation,
 * and hold lease/transaction pins while exposing or changing a Character.
 */
public final class CharacterPersistenceCoordinator implements AutoCloseable {
    private final CharacterEvictionGate evictionGate;
    private final CharacterWal wal;
    private volatile CharacterWalWriter writer;
    private final CharacterRecordStore records;

    public CharacterPersistenceCoordinator(CharacterEvictionGate evictionGate,
                                            CharacterWal wal,
                                            CharacterWalWriter writer,
                                            CharacterRecordStore records) {
        this.evictionGate = Objects.requireNonNull(evictionGate, "evictionGate");
        this.wal = Objects.requireNonNull(wal, "wal");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.records = Objects.requireNonNull(records, "records");
    }

    /** Replace the writer after the server's coordinated checkpoint cycle rotates it. */
    public synchronized void replaceWriter(CharacterWalWriter replacement) {
        this.writer = Objects.requireNonNull(replacement, "replacement");
    }

    public void register(String globalId) { evictionGate.register(globalId); }
    public long markDirty(String globalId) { return evictionGate.markDirty(globalId); }
    public CharacterEvictionGate.Pin acquireLease(String globalId) { return evictionGate.acquireLease(globalId); }
    public CharacterEvictionGate.Pin beginTransaction(String globalId) { return evictionGate.beginTransaction(globalId); }

    /**
     * Persist a mutation result. The dirty generation is advanced before the snapshot
     * is captured, so callers that still mutate legacy public fields cannot accidentally
     * acknowledge an old generation as clean merely by requesting a write.
     */
    public CompletableFuture<CharacterWal.Record> persistMutation(String globalId, Supplier<byte[]> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        markDirty(globalId);
        return persistSnapshot(globalId, snapshot);
    }

    /** Capture the generation before serialization; later mutations make this receipt stale. */
    public CompletableFuture<CharacterWal.Record> persistSnapshot(String globalId, Supplier<byte[]> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        CharacterEvictionGate.Pin transaction = evictionGate.beginTransaction(globalId);
        final CharacterEvictionGate.PersistenceTicket ticket;
        try {
            ticket = evictionGate.capture(globalId);
        } catch (RuntimeException failure) {
            transaction.close();
            throw failure;
        }

        CompletableFuture<CharacterWal.Record> durable = new CompletableFuture<>();
        CompletableFuture<CharacterWal.Record> result = new CompletableFuture<>();
        durable.whenComplete((record, failure) -> {
            try {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                if (record == null || !ticket.globalId().equals(record.globalId())) {
                    result.completeExceptionally(new IOException("WAL receipt identity mismatch"));
                    return;
                }
                if (!evictionGate.markPersisted(ticket)) {
                    result.completeExceptionally(new StalePersistenceException(
                            "Character changed while its WAL frame was being written: " + globalId));
                    return;
                }
                result.complete(record);
            } finally {
                transaction.close();
            }
        });

        try {
            byte[] payload = Objects.requireNonNull(snapshot.get(), "snapshot payload");
            writer.submit(globalId, payload, durable);
        } catch (RuntimeException failure) {
            durable.completeExceptionally(new IOException("Could not prepare or enqueue Character WAL write", failure));
        }
        return result;
    }

    /** Compatibility overload; production code should use persistMutation/persistSnapshot. */
    public CompletableFuture<CharacterWal.Record> persist(String globalId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        return persistSnapshot(globalId, () -> payload);
    }

    /**
     * Streaming compaction: latest-per-global-UUID metadata is handled by the existing WAL
     * implementation; only one decoded record/payload is passed to the record store at a
     * time. The WAL monitor excludes appends until every checkpoint write has completed.
     */
    public void checkpointLatest() throws IOException {
        wal.checkpoint(() -> wal.replayLatestEach(records::put));
    }

    public CharacterRecordStore.Record load(String globalId) throws IOException {
        return records.get(globalId);
    }

    public CharacterEvictionGate.Decision evictionDecision(String globalId) {
        return evictionGate.decision(globalId);
    }

    public boolean canEvict(String globalId) { return evictionGate.canEvict(globalId); }

    @Override public void close() throws IOException {
        IOException failure = null;
        try { writer.close(); } catch (IOException ex) { failure = ex; }
        try { wal.close(); } catch (IOException ex) {
            if (failure == null) failure = ex; else failure.addSuppressed(ex);
        }
        try { records.close(); } catch (RuntimeException ex) {
            IOException wrapped = new IOException("Could not close Character record store", ex);
            if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
        }
        if (failure != null) throw failure;
    }

    public static final class StalePersistenceException extends IOException {
        public StalePersistenceException(String message) { super(message); }
    }
}
