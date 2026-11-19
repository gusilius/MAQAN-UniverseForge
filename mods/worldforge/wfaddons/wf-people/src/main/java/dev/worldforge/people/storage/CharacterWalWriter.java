package dev.worldforge.people.storage;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded single-writer queue for CharacterWal. submit never waits for disk.
 * A false return means the caller must keep the Character dirty and retry;
 * a completed future means the WAL frame was forced to stable storage.
 */
public final class CharacterWalWriter implements AutoCloseable {
    private record Pending(String globalId, byte[] payload, CompletableFuture<CharacterWal.Record> durable) { }

    private final CharacterWal wal;
    private final ArrayBlockingQueue<Pending> queue;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Thread worker;
    private volatile IOException failure;

    public CharacterWalWriter(CharacterWal wal, int capacity, String threadName) {
        this.wal = Objects.requireNonNull(wal, "wal");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        queue = new ArrayBlockingQueue<>(capacity);
        worker = new Thread(this::run, threadName == null ? "wf-character-wal" : threadName);
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Enqueues a defensive copy without blocking. On false, no record was accepted.
     * Callers must retain dirty state until the returned future completes successfully.
     */
    public boolean submit(String globalId, byte[] payload, CompletableFuture<CharacterWal.Record> durable) {
        Objects.requireNonNull(globalId, "globalId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(durable, "durable");
        if (!accepting.get() || failure != null) return false;
        return queue.offer(new Pending(globalId, payload.clone(), durable));
    }

    public int queued() { return queue.size(); }
    public int capacity() { return queue.size() + queue.remainingCapacity(); }
    public IOException failure() { return failure; }

    private void run() {
        try {
            while (accepting.get() || !queue.isEmpty()) {
                Pending pending = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pending == null) continue;
                try {
                    pending.durable.complete(wal.append(pending.globalId, pending.payload));
                } catch (IOException | RuntimeException ex) {
                    IOException io = ex instanceof IOException value
                            ? value : new IOException("Character WAL append failed", ex);
                    failure = io;
                    pending.durable.completeExceptionally(io);
                    accepting.set(false);
                    Pending queued;
                    while ((queued = queue.poll()) != null) queued.durable.completeExceptionally(io);
                    break;
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

    /** Stops accepting writes and drains accepted records before returning. */
    @Override public void close() throws IOException {
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
