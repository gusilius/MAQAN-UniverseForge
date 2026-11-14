package dev.worldforge.worldsim;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;

/**
 * Bounded single-flight executor for immutable WorldSim compute requests.
 *
 * Exactly one request may be queued or executing. The caller must poll completed
 * results and validate the ticket on the authoritative server thread before apply.
 * This class never touches Minecraft or domain objects.
 */
public final class WorldSimComputeScheduler implements AutoCloseable {
    // Shared across scheduler instances so a native call that ignores interruption
    // cannot overlap a new world's compute after integrated-server restart.
    private static final Semaphore NATIVE_COMPUTE_LEASE = new Semaphore(1, true);
    @FunctionalInterface
    public interface ComputeOperation {
        Output compute(byte[] snapshot, byte[] emergenceRequest) throws Exception;
    }

    public static final class Ticket {
        private final String worldId;
        private final String operationKey;
        private final long revision;
        private final long lifecycleEpoch;
        private final int snapshotSchemaVersion;
        private final int nativeAbiVersion;
        private final long submittedNanos;

        public Ticket(String worldId, String operationKey, long revision, long lifecycleEpoch,
                      int snapshotSchemaVersion, int nativeAbiVersion) {
            if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId is required");
            if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey is required");
            if (snapshotSchemaVersion < 1) throw new IllegalArgumentException("snapshotSchemaVersion must be positive");
            if (nativeAbiVersion < 1) throw new IllegalArgumentException("nativeAbiVersion must be positive");
            this.worldId = worldId;
            this.operationKey = operationKey;
            this.revision = revision;
            this.lifecycleEpoch = lifecycleEpoch;
            this.snapshotSchemaVersion = snapshotSchemaVersion;
            this.nativeAbiVersion = nativeAbiVersion;
            this.submittedNanos = System.nanoTime();
        }
        public String worldId() { return worldId; }
        public String operationKey() { return operationKey; }
        public long revision() { return revision; }
        public long lifecycleEpoch() { return lifecycleEpoch; }
        public int snapshotSchemaVersion() { return snapshotSchemaVersion; }
        public int nativeAbiVersion() { return nativeAbiVersion; }
        public long submittedNanos() { return submittedNanos; }
        public boolean matches(String expectedWorldId, String expectedOperationKey, long expectedRevision,
                               long expectedLifecycleEpoch, int expectedSchemaVersion, int expectedNativeAbiVersion) {
            return worldId.equals(expectedWorldId) && operationKey.equals(expectedOperationKey) && revision == expectedRevision
                    && lifecycleEpoch == expectedLifecycleEpoch && snapshotSchemaVersion == expectedSchemaVersion
                    && nativeAbiVersion == expectedNativeAbiVersion;
        }
    }

    public static final class Output {
        private final byte[] emergenceFrame;
        private final byte[] decisionFrame;

        public Output(byte[] emergenceFrame, byte[] decisionFrame) {
            this.emergenceFrame = copyRequired(emergenceFrame, "emergenceFrame");
            this.decisionFrame = copyRequired(decisionFrame, "decisionFrame");
        }
        public byte[] emergenceFrame() { return emergenceFrame.clone(); }
        public byte[] decisionFrame() { return decisionFrame.clone(); }
    }

    public static final class Result {
        private final Ticket ticket;
        private final byte[] snapshot;
        private final Output output;
        private final long computeStartedNanos;
        private final long computeCompletedNanos;

        private Result(Ticket ticket, byte[] snapshot, Output output,
                       long computeStartedNanos, long computeCompletedNanos) {
            this.ticket = ticket;
            this.snapshot = snapshot.clone();
            this.output = output;
            this.computeStartedNanos = computeStartedNanos;
            this.computeCompletedNanos = computeCompletedNanos;
        }
        public Ticket ticket() { return ticket; }
        public byte[] snapshot() { return snapshot.clone(); }
        public byte[] emergenceFrame() { return output.emergenceFrame(); }
        public byte[] decisionFrame() { return output.decisionFrame(); }
        public long queueWaitNanos() { return Math.max(0L, computeStartedNanos - ticket.submittedNanos()); }
        public long computeNanos() { return Math.max(0L, computeCompletedNanos - computeStartedNanos); }
    }

    private final ExecutorService executor;
    private Future<Result> active;

    public WorldSimComputeScheduler() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "worldforge-worldsim-compute");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    /** Returns false while the sole slot is occupied; no request is silently queued or dropped. */
    public synchronized boolean submit(Ticket ticket, byte[] snapshot, byte[] emergenceRequest,
                                       ComputeOperation operation) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(operation, "operation");
        if (active != null) return false;
        byte[] immutableSnapshot = copyRequired(snapshot, "snapshot");
        byte[] immutableEmergence = copyRequired(emergenceRequest, "emergenceRequest");
        active = executor.submit(() -> {
            boolean acquired = false;
            try {
                NATIVE_COMPUTE_LEASE.acquire();
                acquired = true;
                long computeStarted = System.nanoTime();
                Output output = operation.compute(immutableSnapshot.clone(), immutableEmergence.clone());
                return new Result(ticket, immutableSnapshot, output, computeStarted, System.nanoTime());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the WorldSim native compute lease", interrupted);
            } finally {
                if (acquired) NATIVE_COMPUTE_LEASE.release();
            }
        });
        return true;
    }

    /** Returns null while work is running. Completed work is removed from the single slot exactly once. */
    public synchronized Result poll() {
        if (active == null || !active.isDone()) return null;
        Future<Result> completed = active;
        active = null;
        try {
            return completed.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting WorldSim compute result", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IllegalStateException("WorldSim compute operation failed", cause);
        }
    }

    public synchronized boolean busy() {
        return active != null;
    }

    @Override
    public synchronized void close() {
        if (active != null) active.cancel(true);
        active = null;
        executor.shutdownNow();
    }

    private static byte[] copyRequired(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException(name + " must not be empty");
        return bytes.clone();
    }
}
