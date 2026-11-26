package dev.worldforge.mod;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Measures real dedicated/integrated server tick duration and process pressure.
 * Bounded rolling samples; no world scans, allocations per tick, or file I/O in
 * the hot path. This is whole-server cost, not a falsely attributed mod-only cost.
 */
public final class MaqanServerPerformanceTelemetry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CAPACITY = 1200;
    private final long[] tickNanos = new long[CAPACITY];
    private int cursor;
    private int count;
    private long tickStartedNanos;
    private long windowStartedNanos = System.nanoTime();
    private long ticksInWindow;
    private long lastCpuNanos = processCpuNanos();
    private long lastCpuWallNanos = windowStartedNanos;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (!MaqanPerformanceConfig.performanceMetricsEnabled()) return;
        if (event.phase == TickEvent.Phase.START) {
            tickStartedNanos = System.nanoTime();
            return;
        }
        long now = System.nanoTime();
        if (tickStartedNanos > 0L) {
            long elapsed = now - tickStartedNanos;
            if (elapsed > 0L && elapsed < 60_000_000_000L) {
                tickNanos[cursor] = elapsed;
                cursor = (cursor + 1) % CAPACITY;
                if (count < CAPACITY) count++;
                ticksInWindow++;
            }
        }
        if (now - windowStartedNanos < 10_000_000_000L) return;

        long[] samples = new long[count];
        if (count < CAPACITY) {
            System.arraycopy(tickNanos, 0, samples, 0, count);
        } else {
            for (int i = 0; i < CAPACITY; i++) samples[i] = tickNanos[(cursor + i) % CAPACITY];
        }
        Arrays.sort(samples);
        long total = 0L;
        for (long sample : samples) total = saturatingAdd(total, sample);
        double averageMs = samples.length == 0 ? 0.0 : total / (double) samples.length / 1_000_000.0;
        double measuredTps = Math.min(20.0, ticksInWindow * 1_000_000_000.0 / Math.max(1L, now - windowStartedNanos));
        long cpuNow = processCpuNanos();
        long cpuWall = Math.max(1L, now - lastCpuWallNanos);
        double cpuPercent = cpuNow >= 0L && lastCpuNanos >= 0L
                ? Math.max(0.0, (cpuNow - lastCpuNanos) * 100.0 / cpuWall) : -1.0;
        Runtime runtime = Runtime.getRuntime();
        LOGGER.info("MAQAN_SERVER_PERF ticks={} measured_tps={} tick_ms_avg={} tick_ms_p50={} tick_ms_p95={} tick_ms_p99={} tick_ms_max={} process_cpu_percent={} heap_used_bytes={} heap_committed_bytes={} heap_max_bytes={} players={} jvm_threads={}",
                ticksInWindow, round(measuredTps), round(averageMs), millis(percentile(samples, 0.50)),
                millis(percentile(samples, 0.95)), millis(percentile(samples, 0.99)),
                millis(samples.length == 0 ? 0L : samples[samples.length - 1]), round(cpuPercent),
                runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory(), runtime.maxMemory(),
                event.getServer().getPlayerList().getPlayerCount(), ManagementFactory.getThreadMXBean().getThreadCount());
        ticksInWindow = 0L;
        count = 0;
        cursor = 0;
        windowStartedNanos = now;
        lastCpuNanos = cpuNow;
        lastCpuWallNanos = now;
    }

    private static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) return 0L;
        int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(quantile * sorted.length) - 1));
        return sorted[index];
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static double millis(long nanos) { return round(nanos / 1_000_000.0); }
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) return extended.getProcessCpuTime();
        return -1L;
    }
}
