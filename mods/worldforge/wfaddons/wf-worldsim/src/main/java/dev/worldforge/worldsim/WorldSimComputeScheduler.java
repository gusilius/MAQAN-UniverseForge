package dev.worldforge.worldsim;

import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.WorldForgeComputeRuntime;

import java.util.Objects;

/**
 * WorldSim compatibility facade over the single WorldForge-wide compute runtime.
 *
 * It deliberately owns no executor, queue, semaphore or native worker. Closing a
 * WorldSim lifecycle only invalidates its caller-side lifecycle epoch; an in-flight
 * native call retains the shared slot until it actually exits and its result is
 * collected by the next WorldSim lifecycle.
 */
public final class WorldSimComputeScheduler implements AutoCloseable {
    @FunctionalInterface
    public interface ComputeOperation {
        Output compute(byte[] snapshot, byte[] emergenceRequest) throws Exception;
    }

    public static final class Ticket {
        private final WorldForgeComputeRuntime.Ticket shared;

        public Ticket(String worldId, String operationKey, long revision, long lifecycleEpoch,
                      int snapshotSchemaVersion, int nativeAbiVersion) {
            shared = new WorldForgeComputeRuntime.Ticket(worldId, operationKey, revision, lifecycleEpoch,
                    snapshotSchemaVersion, nativeAbiVersion);
        }

        private Ticket(WorldForgeComputeRuntime.Ticket shared) { this.shared = shared; }
        public String worldId() { return shared.worldId(); }
        public String operationKey() { return shared.operationKey(); }
        public long revision() { return shared.revision(); }
        public long lifecycleEpoch() { return shared.lifecycleEpoch(); }
        public int snapshotSchemaVersion() { return shared.snapshotSchemaVersion(); }
        public int nativeAbiVersion() { return shared.nativeAbiVersion(); }
        public long submittedNanos() { return shared.submittedNanos(); }
        public boolean matches(String worldId, String operationKey, long revision, long epoch,
                               int schema, int abi) {
            return shared.matches(worldId, operationKey, revision, epoch, schema, abi);
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
        private final WorldForgeComputeRuntime.Result shared;
        private Result(WorldForgeComputeRuntime.Result shared) { this.shared = shared; }
        public Ticket ticket() { return new Ticket(shared.ticket()); }
        public byte[] snapshot() { return shared.snapshot(); }
        public byte[] emergenceFrame() { return shared.first(); }
        public byte[] decisionFrame() { return shared.second(); }
        public long queueWaitNanos() { return shared.queueWaitNanos(); }
        public long computeNanos() { return shared.computeNanos(); }
    }

    private static final String OPERATION_KEY = "worldsim.compute.cycle";
    private final WorldForgeComputeRuntime runtime = WorldForgeApi.computeRuntime();
    private boolean closed;

    public synchronized boolean submit(Ticket ticket, byte[] snapshot, byte[] emergenceRequest,
                                        ComputeOperation operation) {
        if (closed) return false;
        Objects.requireNonNull(operation, "operation");
        return runtime.submit(ticket.shared, snapshot, emergenceRequest, (primary, secondary) -> {
            Output output = operation.compute(primary, secondary);
            if (output == null) throw new IllegalStateException("WorldSim compute returned no output");
            return new WorldForgeComputeRuntime.Output(output.emergenceFrame(), output.decisionFrame());
        });
    }

    public Result poll() {
        WorldForgeComputeRuntime.Result result = runtime.poll(OPERATION_KEY);
        return result == null ? null : new Result(result);
    }

    /** Global backpressure: WorldSim yields while any domain owns the shared worker. */
    public boolean busy() { return runtime.busy(); }

    @Override
    public synchronized void close() {
        // Do not interrupt or release the process-wide slot: native code may ignore
        // interruption. The lifecycle epoch on Ticket makes its eventual result stale.
        closed = true;
    }

    private static byte[] copyRequired(byte[] bytes, String name) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException(name + " must not be empty");
        return bytes.clone();
    }
}
