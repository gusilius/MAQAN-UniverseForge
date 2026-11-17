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
            var ticket = new WorldSimComputeScheduler.Ticket("world-A", "worldsim.compute.cycle", 42, 7, 1, 1);

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
            check(result.ticket().matches("world-A", "worldsim.compute.cycle", 42, 7, 1, 1), "matching world/revision/epoch must be accepted");
            check(!result.ticket().matches("world-B", "worldsim.compute.cycle", 42, 7, 1, 1), "results from another world must be rejected");
            check(!result.ticket().matches("world-A", "worldsim.compute.cycle", 43, 7, 1, 1), "stale revisions must be rejected");
            check(!result.ticket().matches("world-A", "worldsim.compute.cycle", 42, 8, 1, 1), "results from an earlier lifecycle must be rejected");
            check(!result.ticket().matches("world-A", "worldsim.other", 42, 7, 1, 1), "results from another operation must be rejected");
            check(!result.ticket().matches("world-A", "worldsim.compute.cycle", 42, 7, 2, 1),
                    "results from another snapshot schema must be rejected");
            check(!result.ticket().matches("world-A", "worldsim.compute.cycle", 42, 7, 1, 2),
                    "results from another native ABI must be rejected");
            check(result.snapshot()[0] == 1, "snapshot must be defensively copied");
            check(result.queueWaitNanos() >= 0 && result.computeNanos() >= 0,
                    "queue wait and compute duration must be measurable");
            check(result.emergenceFrame()[0] == 6 && result.decisionFrame()[0] == 7,
                    "both compute outputs must be retained");
            check(calls.get() == 1, "one submitted request must execute exactly once");
            check(!scheduler.busy(), "slot must be released after poll");
        }
        // A native call may ignore interruption during server shutdown. A replacement
        // scheduler must wait for that call to leave JNI before starting another one.
        try (WorldSimComputeScheduler oldWorld = new WorldSimComputeScheduler();
             WorldSimComputeScheduler newWorld = new WorldSimComputeScheduler()) {
            CountDownLatch oldStarted = new CountDownLatch(1);
            CountDownLatch finishOld = new CountDownLatch(1);
            CountDownLatch newStarted = new CountDownLatch(1);
            check(oldWorld.submit(new WorldSimComputeScheduler.Ticket("old", "worldsim.compute.cycle", 1, 1, 1, 1),
                    new byte[]{1}, new byte[]{1}, (input, request) -> {
                        oldStarted.countDown();
                        boolean done = false;
                        while (!done) {
                            try { done = finishOld.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException ignored) { /* simulate JNI that cannot be cancelled */ }
                        }
                        return new WorldSimComputeScheduler.Output(new byte[]{1}, new byte[]{1});
                    }), "old world task must start");
            check(oldStarted.await(5, TimeUnit.SECONDS), "old world native task must acquire lease");
            oldWorld.close();
            check(!newWorld.submit(new WorldSimComputeScheduler.Ticket("new", "worldsim.compute.cycle", 2, 2, 1, 1),
                    new byte[]{2}, new byte[]{2}, (input, request) -> {
                        newStarted.countDown();
                        return new WorldSimComputeScheduler.Output(new byte[]{2}, new byte[]{2});
                    }), "the shared process-wide slot must reject a second domain while old JNI is active");
            check(!newStarted.await(100, TimeUnit.MILLISECONDS),
                    "new world must not overlap a native call from the previous server lifecycle");
            finishOld.countDown();
            WorldSimComputeScheduler.Result oldResult = null;
            long oldDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (oldResult == null && System.nanoTime() < oldDeadline) {
                oldResult = newWorld.poll();
                Thread.yield();
            }
            check(oldResult != null && oldResult.ticket().worldId().equals("old"),
                    "new lifecycle can collect and reject the completed old-world result without losing the shared slot");
            check(newWorld.submit(new WorldSimComputeScheduler.Ticket("new", "worldsim.compute.cycle", 2, 2, 1, 1),
                    new byte[]{2}, new byte[]{2}, (input, request) -> {
                        newStarted.countDown();
                        return new WorldSimComputeScheduler.Output(new byte[]{2}, new byte[]{2});
                    }), "shared slot becomes reusable only after the old result is collected");
            check(newStarted.await(5, TimeUnit.SECONDS), "new world task starts after old JNI call exits");
        }
        System.out.println("WorldSimComputeSchedulerTest: OK");
    }
}
