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
 * Lossless UUID-only dirty work index for the canonical Character WAL pipeline.
 * The pending map is authoritative; deques are delivery hints only. There is no
 * fixed-capacity admission queue, so overload creates backlog rather than data loss.
 */
public final class CharacterDirtyQueue {
    public enum Priority { CRITICAL, BACKGROUND }

    public record Entry(String globalId, long generation, Priority priority) {
        public Entry {
            Objects.requireNonNull(globalId, "globalId");
            Objects.requireNonNull(priority, "priority");
            if (globalId.isBlank() || generation < 1) throw new IllegalArgumentException("Invalid dirty entry");
        }
    }

    private final Map<String, Entry> pending = new HashMap<>();
    private final ArrayDeque<String> criticalReady = new ArrayDeque<>();
    private final ArrayDeque<String> backgroundReady = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> inFlight = new HashSet<>();

    public synchronized void enqueue(String globalId, long generation) {
        enqueue(globalId, generation, Priority.BACKGROUND);
    }

    /** Coalesces by UUID and keeps the newest generation and strongest priority. */
    public synchronized void enqueue(String globalId, long generation, Priority priority) {
        Entry next = new Entry(globalId, generation, priority);
        Entry previous = pending.get(globalId);
        Priority effective = previous != null
                && previous.priority() == Priority.CRITICAL ? Priority.CRITICAL : priority;
        if (previous != null && previous.generation() > generation) generation = previous.generation();
        pending.put(globalId, new Entry(globalId, generation, effective));
        if (!inFlight.contains(globalId) && queued.add(globalId)) appendReady(globalId, effective);
        else if (queued.contains(globalId) && effective == Priority.CRITICAL) {
            backgroundReady.remove(globalId);
            if (!criticalReady.contains(globalId)) criticalReady.addLast(globalId);
        }
    }

    public synchronized void promote(String globalId) {
        Entry current = pending.get(globalId);
        if (current == null || current.priority() == Priority.CRITICAL) return;
        pending.put(globalId, new Entry(globalId, current.generation(), Priority.CRITICAL));
        if (queued.contains(globalId)) {
            backgroundReady.remove(globalId);
            if (!criticalReady.contains(globalId)) criticalReady.addLast(globalId);
        }
    }

    private void appendReady(String id, Priority priority) {
        if (priority == Priority.CRITICAL) criticalReady.addLast(id);
        else backgroundReady.addLast(id);
    }

    /** Takes at most maxCount entries; critical work is served before background work. */
    public synchronized List<Entry> takeBatch(int maxCount) {
        if (maxCount < 1) throw new IllegalArgumentException("maxCount must be positive");
        List<Entry> batch = new ArrayList<>(Math.min(maxCount, readyCount()));
        while (batch.size() < maxCount) {
            String id = !criticalReady.isEmpty() ? criticalReady.pollFirst() : backgroundReady.pollFirst();
            if (id == null) break;
            queued.remove(id);
            Entry entry = pending.get(id);
            if (entry == null || !inFlight.add(id)) continue;
            batch.add(entry);
        }
        return List.copyOf(batch);
    }

    /** Only a durable receipt for this or a newer generation may clear pending work. */
    public synchronized void acknowledge(String globalId, long durableGeneration) {
        Entry current = pending.get(globalId);
        if (current == null) return;
        if (current.generation() <= durableGeneration) {
            pending.remove(globalId);
            inFlight.remove(globalId);
            queued.remove(globalId);
            criticalReady.remove(globalId);
            backgroundReady.remove(globalId);
        } else {
            inFlight.remove(globalId);
            requeue(globalId);
        }
    }

    /** Failed/rejected work returns to the appropriate queue tail without losing its UUID. */
    public synchronized void retry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!pending.containsKey(entry.globalId())) return;
        inFlight.remove(entry.globalId());
        requeue(entry.globalId());
    }

    private void requeue(String id) {
        Entry current = pending.get(id);
        if (current != null && !inFlight.contains(id) && queued.add(id))
            appendReady(id, current.priority());
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int readyCount() { return criticalReady.size() + backgroundReady.size(); }
    public synchronized int inFlightCount() { return inFlight.size(); }
    public synchronized int criticalCount() {
        int count = 0;
        for (Entry entry : pending.values()) if (entry.priority() == Priority.CRITICAL) count++;
        return count;
    }
    public synchronized boolean contains(String globalId) { return pending.containsKey(globalId); }
    public synchronized long generation(String globalId) {
        Entry entry = pending.get(globalId);
        return entry == null ? 0L : entry.generation();
    }
}
