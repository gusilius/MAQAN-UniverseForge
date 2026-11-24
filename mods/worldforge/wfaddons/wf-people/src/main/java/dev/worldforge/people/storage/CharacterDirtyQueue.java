package dev.worldforge.people.storage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless, UUID-only dirty work index for the canonical Character WAL pipeline.
 * The pending map is authoritative; the deque is only a delivery hint. It has no
 * fixed capacity, so overload increases backlog rather than dropping an identity.
 */
public final class CharacterDirtyQueue {
    public record Entry(String globalId, long generation) {
        public Entry {
            Objects.requireNonNull(globalId, "globalId");
            if (globalId.isBlank() || generation < 1) throw new IllegalArgumentException("Invalid dirty entry");
        }
    }

    private final Map<String, Long> pending = new HashMap<>();
    private final ArrayDeque<String> ready = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> inFlight = new HashSet<>();

    /** Coalesces repeated mutations; only the newest generation is retained per UUID. */
    public synchronized void enqueue(String globalId, long generation) {
        Entry checked = new Entry(globalId, generation);
        Long previous = pending.put(globalId, generation);
        if (previous == null && !inFlight.contains(globalId) && queued.add(globalId))
            ready.addLast(globalId);
    }

    /** Takes at most maxCount identities. In-flight IDs cannot be delivered twice. */
    public synchronized List<Entry> takeBatch(int maxCount) {
        if (maxCount < 1) throw new IllegalArgumentException("maxCount must be positive");
        List<Entry> batch = new ArrayList<>(Math.min(maxCount, ready.size()));
        while (batch.size() < maxCount && !ready.isEmpty()) {
            String id = ready.removeFirst();
            queued.remove(id);
            Long generation = pending.get(id);
            if (generation == null || !inFlight.add(id)) continue;
            batch.add(new Entry(id, generation));
        }
        return List.copyOf(batch);
    }

    /**
     * Called only after the coordinator confirms the exact WAL generation durable.
     * A newer pending generation survives and is delivered again.
     */
    public synchronized void acknowledge(String globalId, long durableGeneration) {
        Long current = pending.get(globalId);
        if (current == null) return;
        if (current <= durableGeneration) {
            pending.remove(globalId);
            inFlight.remove(globalId);
            queued.remove(globalId);
            ready.remove(globalId);
        } else {
            inFlight.remove(globalId);
            requeue(globalId);
        }
    }

    /** Returns failed, rejected or over-budget work to the ready tail without losing it. */
    public synchronized void retry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!pending.containsKey(entry.globalId())) return;
        inFlight.remove(entry.globalId());
        requeue(entry.globalId());
    }

    private void requeue(String id) {
        if (pending.containsKey(id) && !inFlight.contains(id) && queued.add(id))
            ready.addLast(id);
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int readyCount() { return ready.size(); }
    public synchronized int inFlightCount() { return inFlight.size(); }
    public synchronized boolean contains(String globalId) { return pending.containsKey(globalId); }
    public synchronized long generation(String globalId) {
        Long value = pending.get(globalId);
        return value == null ? 0L : value;
    }
}
