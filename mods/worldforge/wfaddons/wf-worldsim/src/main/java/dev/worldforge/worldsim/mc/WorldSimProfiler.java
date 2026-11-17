package dev.worldforge.worldsim.mc;

import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bounded, low-overhead measurements for the autonomous WorldSim pipeline.
 *
 * The profiler never throttles or skips simulation work. It stores only a rolling
 * sample window while preserving lifetime count, total and maximum durations.
 * Measurements are nanoseconds; synchronized recording accepts both server-thread and compute-worker samples.
 */
final class WorldSimProfiler {
    private static final int SAMPLE_CAPACITY = 4096;
    private final Map<String, Metric> metrics = new TreeMap<>();
    private final Map<String, Long> gauges = new TreeMap<>();

    Scope begin(String name) {
        return new Scope(this, name, System.nanoTime());
    }

    synchronized void record(String name, long elapsedNanos) {
        metrics.computeIfAbsent(name, ignored -> new Metric()).add(Math.max(0L, elapsedNanos));
    }

    synchronized void setGauge(String name, long value) {
        gauges.put(name, value);
    }

    synchronized JsonObject snapshot() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        root.addProperty("clock", "System.nanoTime");
        root.addProperty("sample_capacity_per_metric", SAMPLE_CAPACITY);
        JsonObject measurements = new JsonObject();
        for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
            Metric metric = entry.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("count", metric.count);
            value.addProperty("total_ns", metric.totalNanos);
            value.addProperty("max_ns", metric.maxNanos);
            value.addProperty("p95_ns", metric.percentile(0.95));
            value.addProperty("p99_ns", metric.percentile(0.99));
            value.addProperty("samples_retained", Math.min(metric.count, SAMPLE_CAPACITY));
            measurements.add(entry.getKey(), value);
        }
        JsonObject currentGauges = new JsonObject();
        gauges.forEach(currentGauges::addProperty);
        root.add("measurements", measurements);
        root.add("gauges", currentGauges);
        return root;
    }

    static final class Scope implements AutoCloseable {
        private final WorldSimProfiler owner;
        private final String name;
        private final long started;
        private boolean closed;

        private Scope(WorldSimProfiler owner, String name, long started) {
            this.owner = owner;
            this.name = name;
            this.started = started;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            owner.record(name, System.nanoTime() - started);
        }
    }

    private static final class Metric {
        private final long[] samples = new long[SAMPLE_CAPACITY];
        private long count;
        private long totalNanos;
        private long maxNanos;

        private void add(long value) {
            samples[(int) (count % SAMPLE_CAPACITY)] = value;
            count++;
            totalNanos = saturatingAdd(totalNanos, value);
            maxNanos = Math.max(maxNanos, value);
        }

        private long percentile(double percentile) {
            int size = (int) Math.min(count, SAMPLE_CAPACITY);
            if (size == 0) return 0L;
            long[] ordered = Arrays.copyOf(samples, size);
            Arrays.sort(ordered);
            int index = Math.max(0, (int) Math.ceil(percentile * size) - 1);
            return ordered[Math.min(index, size - 1)];
        }

        private static long saturatingAdd(long left, long right) {
            if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
            return left + right;
        }
    }
}
