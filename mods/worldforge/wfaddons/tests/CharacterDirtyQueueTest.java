package dev.worldforge.tests;

import dev.worldforge.people.storage.CharacterDirtyQueue;
import java.util.List;

/** Lossless coalescing, bounded delivery, retry and generation-aware acknowledgement. */
public final class CharacterDirtyQueueTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        CharacterDirtyQueue queue = new CharacterDirtyQueue();
        String a = "00000000-0000-0000-0000-000000000001";
        String b = "00000000-0000-0000-0000-000000000002";
        String c = "00000000-0000-0000-0000-000000000003";

        queue.enqueue(a, 1);
        queue.enqueue(a, 2);
        queue.enqueue(a, 3);
        queue.enqueue(b, 1);
        check(queue.pendingCount() == 2 && queue.readyCount() == 2 && queue.generation(a) == 3,
                "repeated dirty mutations coalesce to one latest-generation UUID");

        List<CharacterDirtyQueue.Entry> first = queue.takeBatch(1);
        check(first.size() == 1 && first.get(0).globalId().equals(a)
                        && first.get(0).generation() == 3 && queue.inFlightCount() == 1,
                "scheduler takes a bounded batch and marks its UUID in flight");

        queue.enqueue(a, 4);
        queue.enqueue(c, 1);
        check(queue.pendingCount() == 3 && queue.readyCount() == 2,
                "mutation during serialization updates generation without duplicating in-flight work");
        queue.acknowledge(a, 3);
        check(queue.contains(a) && queue.generation(a) == 4 && queue.readyCount() == 3,
                "stale durable receipt cannot erase a newer dirty generation");

        List<CharacterDirtyQueue.Entry> retryBatch = queue.takeBatch(2);
        check(retryBatch.size() == 2, "batch limit is respected after stale receipt requeue");
        queue.retry(retryBatch.get(0));
        queue.retry(retryBatch.get(1));
        check(queue.pendingCount() == 3 && queue.inFlightCount() == 0 && queue.readyCount() == 3,
                "failed or rejected work is requeued without losing UUIDs");

        List<CharacterDirtyQueue.Entry> all = queue.takeBatch(8);
        check(all.size() == 3, "all pending UUIDs eventually become deliverable");
        for (CharacterDirtyQueue.Entry entry : all)
            queue.acknowledge(entry.globalId(), entry.generation());
        check(queue.pendingCount() == 0 && queue.readyCount() == 0 && queue.inFlightCount() == 0,
                "only matching durable generations remove pending work");

        // There is deliberately no lossy fixed-capacity admission queue: overload is backlog.
        for (int i = 0; i < 4096; i++)
            queue.enqueue("00000000-0000-0000-0000-" + String.format("%012d", i + 10), 1);
        check(queue.pendingCount() == 4096 && queue.takeBatch(37).size() == 37,
                "large backlog retains every identity while each service pass remains bounded");

        CharacterDirtyQueue priority = new CharacterDirtyQueue();
        for (int i = 0; i < 12; i++)
            priority.enqueue("critical-" + i, 1, CharacterDirtyQueue.Priority.CRITICAL);
        priority.enqueue("background", 1, CharacterDirtyQueue.Priority.BACKGROUND);
        check(priority.criticalCount() == 12, "critical backlog metric is maintained without scanning pending UUIDs");
        List<CharacterDirtyQueue.Entry> priorityBatch = priority.takeBatch(12);
        check(priorityBatch.get(0).priority() == CharacterDirtyQueue.Priority.CRITICAL
                        && priorityBatch.get(8).globalId().equals("background"),
                "critical work leads, but background work is served after a bounded critical burst");
        priority.acknowledge("background", 1);
        check(priority.pendingCount() == 12 && priority.criticalCount() == 12,
                "acknowledging one class does not erase other pending priorities");
        System.out.println("CharacterDirtyQueueTest: OK");
    }
}
