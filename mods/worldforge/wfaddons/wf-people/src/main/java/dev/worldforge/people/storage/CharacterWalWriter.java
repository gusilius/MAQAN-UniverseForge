package dev.worldforge.people.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded single-writer queue. Accepted records are grouped into durable batches,
 * amortizing fsync while preserving backpressure and per-record completion receipts.
 */
public final class CharacterWalWriter implements AutoCloseable {
    private record Pending(String globalId, byte[] payload, CompletableFuture<CharacterWal.Record> durable) { }

    private static final int MAX_BATCH = 128;
    private final CharacterWal wal;
    private final ArrayBlockingQueue<Pending> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    // Counts every accepted record until its durable future has been completed or failed.
    // Unlike queue.size(), this includes the worker's batch-assembly and in-flight windows.
    private final AtomicInteger outstanding = new AtomicInteger();
    private final Thread worker;
    private volatile IOException failure;
    private volatile int inFlight;

    public CharacterWalWriter(CharacterWal wal, int capacity, String threadName) {
        this.wal = Objects.requireNonNull(wal, "wal");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        queue = new ArrayBlockingQueue<>(capacity);
        worker = new Thread(this::run, threadName == null ? "wf-character-wal" : threadName);
        worker.setDaemon(true);
        worker.start();
    }

    public boolean submit(String globalId, byte[] payload, CompletableFuture<CharacterWal.Record> durable) {
        Objects.requireNonNull(globalId, "globalId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(durable, "durable");
        IOException rejection = null;
        boolean accepted;
        synchronized (this) {
            if (!accepting.get() || failure != null) {
                rejection = failure != null ? failure : new IOException("Character WAL writer is closed");
                accepted = false;
            } else {
                outstanding.incrementAndGet();
                accepted = queue.offer(new Pending(globalId, payload.clone(), durable));
                if (!accepted) {
                    outstanding.decrementAndGet();
                    rejection = new IOException("Character WAL writer queue is full");
                }
            }
        }
        if (!accepted) durable.completeExceptionally(rejection);
        return accepted;
    }

    public int queued() { return queue.size(); }
    public int pending() { return outstanding.get(); }
    public int capacity() { return queue.size() + queue.remainingCapacity(); }
    public IOException failure() { return failure; }

    private void run() {
        try {
            while (accepting.get() || !queue.isEmpty()) {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<Pending> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                // A short group-commit window lets concurrent producers share one fsync.
                // Every receipt still completes only after appendBatch has forced the batch.
                long batchDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2);
                while (batch.size() < MAX_BATCH && !Thread.currentThread().isInterrupted()) {
                    queue.drainTo(batch, MAX_BATCH - batch.size());
                    long remaining = batchDeadline - System.nanoTime();
                    if (remaining <= 0) break;
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                }
                // Interrupts are handled by the outer loop only after this batch is either
                // durably committed or explicitly failed; no dequeued receipt can be stranded.
                inFlight = batch.size();
                try {
                    List<CharacterWal.Write> writes = batch.stream()
                            .map(item -> new CharacterWal.Write(item.globalId, item.payload)).toList();
                    List<CharacterWal.Record> receipts = wal.appendBatch(writes);
                    for (int i = 0; i < batch.size(); i++) batch.get(i).durable.complete(receipts.get(i));
                } catch (IOException | RuntimeException ex) {
                    IOException io = ex instanceof IOException value
                            ? value : new IOException("Character WAL append failed", ex);
                    failure = io;
                    for (Pending item : batch) item.durable.completeExceptionally(io);
                    accepting.set(false);
                    Pending queued;
                    while ((queued = queue.poll()) != null) {
                        queued.durable.completeExceptionally(io);
                        outstanding.decrementAndGet();
                    }
                    break;
                } finally {
                    outstanding.addAndGet(-batch.size());
                    inFlight = 0;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            IOException io = new IOException("Character WAL writer interrupted", ex);
            failure = io;
            Pending pending;
            while ((pending = queue.poll()) != null) {
                pending.durable.completeExceptionally(io);
                outstanding.decrementAndGet();
            }
        }
    }

    /**
     * Atomically closes only an idle writer. The monitor serializes this check against
     * submit(), so checkpoint cannot race a newly accepted record after observing idle.
     * Returns false without changing writer state when any accepted record is outstanding.
     */
    public synchronized boolean closeIfIdle() throws IOException {
        if (outstanding.get() != 0) return false;
        accepting.set(false);
        try { worker.join(); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing idle Character WAL writer", ex);
        }
        IOException error = failure;
        if (error != null) throw error;
        return true;
    }

    @Override public void close() throws IOException {
        // Stop producers while holding the same monitor used by submit(), then release
        // it before joining. Completion callbacks are allowed to call submit() and must
        // not deadlock the worker against a shutdown thread waiting in join().
        synchronized (this) {
            accepting.set(false);
        }
        try { worker.join(); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while draining Character WAL", ex);
        }
        IOException error = failure;
        if (error != null) throw error;
    }
}
