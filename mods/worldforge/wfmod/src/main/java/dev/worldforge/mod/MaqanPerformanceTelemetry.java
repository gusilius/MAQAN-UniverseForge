package dev.worldforge.mod;

import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead runtime counters. All mutation is safe from addon call sites;
 * sampling/formatting is intentionally separated from hot loops.
 */
public final class MaqanPerformanceTelemetry {
    private MaqanPerformanceTelemetry() {}

    private static final LongAdder activeWorkerBodies = new LongAdder();
    private static final LongAdder activeTroopBodies = new LongAdder();
    private static final LongAdder workerSpawned = new LongAdder();
    private static final LongAdder workerDespawned = new LongAdder();
    private static final LongAdder troopSpawned = new LongAdder();
    private static final LongAdder troopDespawned = new LongAdder();
    private static final LongAdder simulationNanos = new LongAdder();
    private static final LongAdder rustComputeNanos = new LongAdder();
    private static volatile long lastSampleTick;
    private static volatile long lastSimulationNanos;
    private static volatile long lastRustComputeNanos;
    private static volatile long taskQueueSize;
    private static volatile long commandQueueSize;

    public static void workerSpawned() { workerSpawned.increment(); activeWorkerBodies.increment(); }
    public static void workerDespawned() { workerDespawned.increment(); activeWorkerBodies.add(-1); }
    public static void troopSpawned() { troopSpawned.increment(); activeTroopBodies.increment(); }
    public static void troopDespawned() { troopDespawned.increment(); activeTroopBodies.add(-1); }

    public static void recordSimulationNanos(long nanos) {
        if (nanos > 0) { simulationNanos.add(nanos); lastSimulationNanos = nanos; }
    }
    /** Call only around an actual native call; Java fallback time must not be labelled Rust time. */
    public static void recordRustComputeNanos(long nanos) {
        if (nanos > 0) { rustComputeNanos.add(nanos); lastRustComputeNanos = nanos; }
    }
    public static void setQueueSizes(long tasks, long commands) {
        taskQueueSize = Math.max(0, tasks);
        commandQueueSize = Math.max(0, commands);
    }
    public static void sample(long serverTick) { lastSampleTick = serverTick; }

    public static Snapshot snapshot() {
        Runtime runtime = Runtime.getRuntime();
        return new Snapshot(activeWorkerBodies.sum(), activeTroopBodies.sum(),
                workerSpawned.sum(), workerDespawned.sum(), troopSpawned.sum(), troopDespawned.sum(),
                lastSimulationNanos, simulationNanos.sum(), lastRustComputeNanos, rustComputeNanos.sum(),
                taskQueueSize, commandQueueSize,
                Math.max(0, runtime.totalMemory() - runtime.freeMemory()),
                runtime.maxMemory(), lastSampleTick);
    }

    public record Snapshot(long activeWorkerBodies, long activeTroopBodies,
                           long workerSpawned, long workerDespawned,
                           long troopSpawned, long troopDespawned,
                           long lastSimulationNanos, long totalSimulationNanos,
                           long lastRustComputeNanos, long totalRustComputeNanos,
                           long taskQueueSize, long commandQueueSize,
                           long usedHeapBytes, long maxHeapBytes, long sampleTick) {}
}
