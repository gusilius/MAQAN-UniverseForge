package dev.worldforge.people.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
                accepted = queue.offer(new Pending(globalId, payload.clone(), durable));
                if (!accepted) rejection = new IOException("Character WAL writer queue is full");
            }
        }
        if (!accepted) durable.completeExceptionally(rejection);
        return accepted;
    }

    public int queued() { return queue.size(); }
    public int pending() { return queue.size() + inFlight; }
    public int capacity() { return queue.size() + queue.remainingCapacity(); }
    public IOException failure() { return failure; }

    private void run() {
        try {
            while (accepting.get() || !queue.isEmpty()) {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<Pending> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
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
                    while ((queued = queue.poll()) != null) queued.durable.completeExceptionally(io);
                    break;
                } finally {
                    inFlight = 0;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            IOException io = new IOException("Character WAL writer interrupted", ex);
            failure = io;
            Pending pending;
            while ((pending = queue.poll()) != null) pending.durable.completeExceptionally(io);
        }
    }

    @Override public synchronized void close() throws IOException {
        accepting.set(false);
        try { worker.join(); }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while draining Character WAL", ex);
        }
        IOException error = failure;
        if (error != null) throw error;
    }
}
