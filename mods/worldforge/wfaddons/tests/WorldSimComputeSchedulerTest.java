package dev.worldforge.tests;

import dev.worldforge.worldsim.WorldSimComputeScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldSimComputeSchedulerTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        try (WorldSimComputeScheduler scheduler = new WorldSimComputeScheduler()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            byte[] snapshot = new byte[]{1, 2, 3};
            byte[] emergence = new byte[]{4, 5};
            var ticket = new WorldSimComputeScheduler.Ticket("world-A", 42, 7);

            check(scheduler.submit(ticket, snapshot, emergence, (input, request) -> {
                calls.incrementAndGet();
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
                check(input[0] == 1 && request[0] == 4, "worker must receive original immutable request bytes");
                return new WorldSimComputeScheduler.Output(new byte[]{6}, new byte[]{7});
            }), "first request must be accepted");

            snapshot[0] = 99;
            emergence[0] = 99;
            check(started.await(5, TimeUnit.SECONDS), "worker must start");
            check(!scheduler.submit(ticket, new byte[]{1}, new byte[]{2},
                    (input, request) -> new WorldSimComputeScheduler.Output(new byte[]{3}, new byte[]{4})),
                    "scheduler must enforce single-flight backpressure");
            check(scheduler.poll() == null, "running work must not be exposed as completed");
            release.countDown();

            WorldSimComputeScheduler.Result result = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (result == null && System.nanoTime() < deadline) {
                result = scheduler.poll();
                Thread.yield();
            }
            check(result != null, "completed result must be retrievable");
            check(result.ticket().worldId().equals("world-A")
                    && result.ticket().revision() == 42
                    && result.ticket().lifecycleEpoch() == 7, "result must preserve revision fence ticket");
            check(result.snapshot()[0] == 1, "snapshot must be defensively copied");
            check(result.emergenceFrame()[0] == 6 && result.decisionFrame()[0] == 7,
                    "both compute outputs must be retained");
            check(calls.get() == 1, "one submitted request must execute exactly once");
            check(!scheduler.busy(), "slot must be released after poll");
        }
        System.out.println("WorldSimComputeSchedulerTest: OK");
    }
}
