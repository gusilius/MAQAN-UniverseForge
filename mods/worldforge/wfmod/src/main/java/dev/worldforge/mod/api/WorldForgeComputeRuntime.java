package dev.worldforge.mod.api;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Process-wide bounded compute runtime shared by every WorldForge addon.
 *
 * One immutable request may be queued or executing at a time. The runtime owns
 * execution only: callers retain ownership of snapshots, domain validation and
 * authoritative application on the server thread. A completed result keeps the
 * slot until its operation owner polls it, preventing cross-domain overlap or
 * silent loss under backpressure.
 */
public final class WorldForgeComputeRuntime {
    @FunctionalInterface
    public interface Operation {
        Output compute(byte[] primary, byte[] secondary) throws Exception;
    }

    public record Ticket(String worldId, String operationKey, long revision, long lifecycleEpoch,
                         int snapshotSchemaVersion, int nativeAbiVersion, long submittedNanos) {
        public Ticket(String worldId, String operationKey, long revision, long lifecycleEpoch,
                      int snapshotSchemaVersion, int nativeAbiVersion) {
            this(requireText(worldId, "worldId"), requireText(operationKey, "operationKey"),
                    revision, lifecycleEpoch, requirePositive(snapshotSchemaVersion, "snapshotSchemaVersion"),
                    requirePositive(nativeAbiVersion, "nativeAbiVersion"), System.nanoTime());
        }

        public Ticket {
            requireText(worldId, "worldId");
            requireText(operationKey, "operationKey");
            requirePositive(snapshotSchemaVersion, "snapshotSchemaVersion");
            requirePositive(nativeAbiVersion, "nativeAbiVersion");
        }

        public boolean matches(String expectedWorldId, String expectedOperationKey, long expectedRevision,
                               long expectedLifecycleEpoch, int expectedSchemaVersion, int expectedNativeAbiVersion) {
            return worldId.equals(expectedWorldId) && operationKey.equals(expectedOperationKey)
                    && revision == expectedRevision && lifecycleEpoch == expectedLifecycleEpoch
                    && snapshotSchemaVersion == expectedSchemaVersion && nativeAbiVersion == expectedNativeAbiVersion;
        }
    }

    public static final class Output {
        private final byte[] first;
        private final byte[] second;

        public Output(byte[] first, byte[] second) {
            this.first = copyRequired(first, "first output");
            this.second = copyRequired(second, "second output");
        }

        public byte[] first() { return first.clone(); }
        public byte[] second() { return second.clone(); }
    }

    public static final class Result {
        private final Ticket ticket;
        private final byte[] snapshot;
        private final Output output;
        private final long startedNanos;
        private final long completedNanos;

        private Result(Ticket ticket, byte[] snapshot, Output output, long startedNanos, long completedNanos) {
            this.ticket = ticket;
            this.snapshot = snapshot.clone();
            this.output = output;
            this.startedNanos = startedNanos;
            this.completedNanos = completedNanos;
        }

        public Ticket ticket() { return ticket; }
        public byte[] snapshot() { return snapshot.clone(); }
        public byte[] first() { return output.first(); }
        public byte[] second() { return output.second(); }
        public long queueWaitNanos() { return Math.max(0L, startedNanos - ticket.submittedNanos()); }
        public long computeNanos() { return Math.max(0L, completedNanos - startedNanos); }
    }

    private final ExecutorService executor;
    private Future<Result> active;
    private String activeOperationKey;

    WorldForgeComputeRuntime() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "worldforge-shared-compute");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * Submits one request to the process-wide slot. False means another operation
     * still owns the slot (including a completed result not yet collected).
     */
    public synchronized boolean submit(Ticket ticket, byte[] snapshot, byte[] secondary,
                                       Operation operation) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(operation, "operation");
        if (active != null) return false;
        byte[] immutableSnapshot = copyRequired(snapshot, "snapshot");
        byte[] immutableSecondary = copyRequired(secondary, "secondary");
        activeOperationKey = ticket.operationKey();
        active = executor.submit(() -> {
            long started = System.nanoTime();
            Output output = operation.compute(immutableSnapshot.clone(), immutableSecondary.clone());
            if (output == null) throw new IllegalStateException("Compute operation returned no output");
            return new Result(ticket, immutableSnapshot, output, started, System.nanoTime());
        });
        return true;
    }

    /** Only the operation owner may collect its completed frame. */
    public synchronized Result poll(String operationKey) {
        requireText(operationKey, "operationKey");
        if (active == null || !active.isDone() || !operationKey.equals(activeOperationKey)) return null;
        Future<Result> completed = active;
        active = null;
        activeOperationKey = null;
        try {
            return completed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting shared compute result", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IllegalStateException("Shared compute operation failed", cause);
        }
    }

    /** Includes completed-but-uncollected work; the slot is not reusable until poll. */
    public synchronized boolean busy() { return active != null; }

    public synchronized boolean busy(String operationKey) {
        return active != null && Objects.equals(activeOperationKey, operationKey);
    }

    private static byte[] copyRequired(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException(name + " must not be empty");
        return bytes.clone();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
