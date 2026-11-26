package dev.worldforge.mod.client;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.MaqanPerformanceConfig;
import net.minecraftforge.client.event.RenderTickEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Low-overhead client frame sampler. Reports whole-client frame pacing (not a
 * fabricated MAQAN-only FPS value); compare baseline and MAQAN-enabled runs.
 * Sampling is bounded and log formatting happens only once per reporting window.
 */
public final class ClientPerformanceTelemetry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAPACITY = 1200;
    private final long[] frameNanos = new long[CAPACITY];
    private int cursor;
    private int count;
    private long previousFrameNanos;
    private long windowStartedNanos = System.nanoTime();
    private long lastCpuNanos = processCpuNanos();
    private long lastCpuWallNanos = windowStartedNanos;
    private long framesInWindow;

    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !MaqanPerformanceConfig.performanceMetricsEnabled()) return;
        long now = System.nanoTime();
        if (previousFrameNanos != 0L) {
            long elapsed = now - previousFrameNanos;
            if (elapsed > 0L && elapsed < 5_000_000_000L) {
                frameNanos[cursor] = elapsed;
                cursor = (cursor + 1) % CAPACITY;
                if (count < CAPACITY) count++;
                framesInWindow++;
            }
        }
        previousFrameNanos = now;
        if (now - windowStartedNanos < 10_000_000_000L) return;

        long[] samples = new long[count];
        if (count < CAPACITY) {
            System.arraycopy(frameNanos, 0, samples, 0, count);
        } else {
            for (int i = 0; i < CAPACITY; i++) samples[i] = frameNanos[(cursor + i) % CAPACITY];
        }
        Arrays.sort(samples);
        long p50 = percentile(samples, 0.50);
        long p95 = percentile(samples, 0.95);
        long p99 = percentile(samples, 0.99);
        long cpuNow = processCpuNanos();
        long cpuWall = Math.max(1L, now - lastCpuWallNanos);
        double cpuPercent = cpuNow >= 0L && lastCpuNanos >= 0L
                ? Math.max(0.0, (cpuNow - lastCpuNanos) * 100.0 / cpuWall) : -1.0;
        Runtime runtime = Runtime.getRuntime();
        double averageFps = framesInWindow * 1_000_000_000.0 / Math.max(1L, now - windowStartedNanos);
        LOGGER.info("MAQAN_CLIENT_PERF frames={} avg_fps={} frame_ms_p50={} frame_ms_p95={} frame_ms_p99={} frame_ms_max={} process_cpu_percent={} heap_used_bytes={} heap_committed_bytes={} heap_max_bytes={}",
                framesInWindow, round(averageFps), millis(p50), millis(p95), millis(p99),
                millis(samples.length == 0 ? 0L : samples[samples.length - 1]), round(cpuPercent),
                runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory(), runtime.maxMemory());
        framesInWindow = 0L;
        count = 0;
        cursor = 0;
        windowStartedNanos = now;
        previousFrameNanos = now;
        lastCpuNanos = cpuNow;
        lastCpuWallNanos = now;
    }

    private static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) return 0L;
        int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1));
        return sorted[index];
    }

    private static double millis(long nanos) { return round(nanos / 1_000_000.0); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) return extended.getProcessCpuTime();
        return -1L;
    }
}
