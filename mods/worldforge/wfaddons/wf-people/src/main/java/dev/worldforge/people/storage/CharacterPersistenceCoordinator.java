package dev.worldforge.people.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.LongAdder;

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
    private final CharacterDirtyQueue dirtyQueue = new CharacterDirtyQueue();
    private final LongAdder dirtyAccepted = new LongAdder();
    private final LongAdder dirtyFailed = new LongAdder();
    private final LongAdder dirtyBytes = new LongAdder();
    private final LongAdder dirtyServiceNanos = new LongAdder();
    /** Epoch invalidates checkpoint eligibility whenever any identity becomes dirty. */
    private final AtomicLong dirtyEpoch = new AtomicLong();
    /** -1 until a complete checkpoint has incorporated the current dirty epoch. */
    private volatile long checkpointEpoch = -1L;

    public record DirtyMetrics(int pending, int ready, int inFlight, int critical,
                               long accepted, long failed, long serializedBytes, long serviceNanos) { }

    public DirtyMetrics dirtyMetrics() {
        return new DirtyMetrics(dirtyQueue.pendingCount(), dirtyQueue.readyCount(),
                dirtyQueue.inFlightCount(), dirtyQueue.criticalCount(),
                dirtyAccepted.sum(), dirtyFailed.sum(), dirtyBytes.sum(), dirtyServiceNanos.sum());
    }

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

    public void register(String globalId) {
        evictionGate.register(globalId);
        CharacterEvictionGate.PersistenceTicket dirty = evictionGate.dirtyTicket(globalId);
        if (dirty != null) {
            dirtyEpoch.incrementAndGet();
            dirtyQueue.enqueue(globalId, dirty.generation());
        }
    }
    public void registerPersisted(String globalId) {
        evictionGate.registerPersisted(globalId);
    }
    public long markDirty(String globalId) {
        long generation = evictionGate.markDirty(globalId);
        dirtyEpoch.incrementAndGet();
        dirtyQueue.enqueue(globalId, generation, CharacterDirtyQueue.Priority.BACKGROUND);
        return generation;
    }
    public long markDirtyCritical(String globalId) {
        long generation = evictionGate.markDirty(globalId);
        dirtyEpoch.incrementAndGet();
        dirtyQueue.enqueue(globalId, generation, CharacterDirtyQueue.Priority.CRITICAL);
        return generation;
    }
    public CharacterDirtyQueue dirtyQueue() { return dirtyQueue; }

    /**
     * Services a bounded dirty batch through the same WAL coordinator. Snapshot creation
     * occurs after the generation ticket is captured. Failed, rejected and over-budget
     * entries are returned to the queue; only a durable matching receipt removes work.
     *
     * @return number of snapshots accepted by the WAL writer during this pass
     */
    public int persistDirtyBatch(Function<String, byte[]> snapshot, int maxCharacters, long maxBytes) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (maxCharacters < 1 || maxBytes < 1)
            throw new IllegalArgumentException("Dirty batch limits must be positive");
        long serviceStart = System.nanoTime();
        java.util.List<CharacterDirtyQueue.Entry> batch = dirtyQueue.takeBatch(maxCharacters);
        long[] used = {0L};
        int accepted = 0;
        for (int index = 0; index < batch.size(); index++) {
            CharacterDirtyQueue.Entry entry = batch.get(index);
            final CompletableFuture<CharacterWal.Record> future;
            try {
                future = persistSnapshot(entry.globalId(), () -> {
                    byte[] payload = Objects.requireNonNull(snapshot.apply(entry.globalId()), "snapshot payload");
                    if (payload.length > maxBytes - used[0])
                        throw new DirtyBatchBudgetExceededException("Character dirty batch byte budget exceeded");
                    used[0] += payload.length;
                    dirtyBytes.add(payload.length);
                    return payload;
                });
            } catch (RuntimeException | Error submissionFailure) {
                dirtyFailed.increment();
                dirtyQueue.retry(entry);
                for (int remaining = index + 1; remaining < batch.size(); remaining++)
                    dirtyQueue.retry(batch.get(remaining));
                break;
            }
            future.whenComplete((record, failure) -> {
                if (failure != null) {
                    dirtyFailed.increment();
                    dirtyQueue.retry(entry);
                } else {
                    dirtyAccepted.increment();
                }
            });
            if (future.isCompletedExceptionally()) {
                for (int remaining = index + 1; remaining < batch.size(); remaining++)
                    dirtyQueue.retry(batch.get(remaining));
                break;
            }
            accepted++;
        }
        dirtyServiceNanos.add(System.nanoTime() - serviceStart);
        return accepted;
    }

    public static final class DirtyBatchBudgetExceededException extends RuntimeException {
        public DirtyBatchBudgetExceededException(String message) { super(message); }
    }
    public CharacterEvictionGate.Pin acquireLease(String globalId) { return evictionGate.acquireLease(globalId); }
    public CharacterEvictionGate.Pin beginTransaction(String globalId) { return evictionGate.beginTransaction(globalId); }

    /** Persist a mutation result while pinning the identity before advancing its generation. */
    public CompletableFuture<CharacterWal.Record> persistMutation(String globalId, Supplier<byte[]> snapshot) {
        return persistScoped(globalId, snapshot, true);
    }

    /** Capture the generation before serialization; later mutations make this receipt stale. */
    public CompletableFuture<CharacterWal.Record> persistSnapshot(String globalId, Supplier<byte[]> snapshot) {
        return persistScoped(globalId, snapshot, false);
    }

    private CompletableFuture<CharacterWal.Record> persistScoped(
            String globalId, Supplier<byte[]> snapshot, boolean markDirtyFirst) {
        Objects.requireNonNull(snapshot, "snapshot");
        CharacterEvictionGate.Pin transaction = evictionGate.beginTransaction(globalId);
        final CharacterEvictionGate.PersistenceTicket ticket;
        try {
            if (markDirtyFirst) markDirtyCritical(globalId);
            ticket = evictionGate.capture(globalId);
        } catch (RuntimeException | Error failure) {
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
                dirtyQueue.acknowledge(globalId, ticket.generation());
                result.complete(record);
            } finally {
                transaction.close();
            }
        });

        try {
            byte[] payload = Objects.requireNonNull(snapshot.get(), "snapshot payload");
            writer.submit(globalId, payload, durable);
        } catch (RuntimeException | Error failure) {
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

    public long dirtyEpoch() { return dirtyEpoch.get(); }

    /**
     * Called only after the WAL has been compacted into the addressable record store.
     * Any concurrent mutation or unacknowledged dirty UUID invalidates the checkpoint.
     */
    public boolean markCheckpointCompleted(long expectedDirtyEpoch) {
        if (dirtyEpoch.get() != expectedDirtyEpoch
                || dirtyQueue.pendingCount() != 0 || dirtyQueue.inFlightCount() != 0) return false;
        checkpointEpoch = expectedDirtyEpoch;
        // Close the race with a mutation between the first epoch read and publication.
        if (dirtyEpoch.get() != expectedDirtyEpoch
                || dirtyQueue.pendingCount() != 0 || dirtyQueue.inFlightCount() != 0) {
            checkpointEpoch = -1L;
            return false;
        }
        return true;
    }

    public boolean canEvict(String globalId) {
        long currentEpoch = dirtyEpoch.get();
        return checkpointEpoch == currentEpoch
                && dirtyQueue.pendingCount() == 0 && dirtyQueue.inFlightCount() == 0
                && evictionGate.canEvict(globalId);
    }

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
