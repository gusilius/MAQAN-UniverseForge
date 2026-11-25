package dev.worldforge.people.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lossless UUID-only dirty work index for the canonical Character WAL pipeline.
 * The pending map is authoritative. Intrusive ready lists provide O(1) enqueue,
 * promotion, dequeue and acknowledgement without scanning a population-sized backlog.
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

    private static final class ReadyNode {
        final String id;
        final Priority priority;
        ReadyNode previous;
        ReadyNode next;
        ReadyNode(String id, Priority priority) { this.id = id; this.priority = priority; }
    }

    private static final class ReadyList {
        ReadyNode first;
        ReadyNode last;
        int size;

        void addLast(ReadyNode node) {
            node.previous = last;
            node.next = null;
            if (last == null) first = node;
            else last.next = node;
            last = node;
            size++;
        }

        ReadyNode removeFirst() {
            ReadyNode node = first;
            if (node != null) remove(node);
            return node;
        }

        void remove(ReadyNode node) {
            if (node.previous == null) first = node.next;
            else node.previous.next = node.next;
            if (node.next == null) last = node.previous;
            else node.next.previous = node.previous;
            node.previous = null;
            node.next = null;
            size--;
        }
    }

    private final Map<String, Entry> pending = new HashMap<>();
    private final Map<String, ReadyNode> readyById = new HashMap<>();
    private final ReadyList criticalReady = new ReadyList();
    private final ReadyList backgroundReady = new ReadyList();
    /*
     * Keep the exact Entry object returned to a worker. A delayed duplicate retry
     * from an older attempt must never release a newer in-flight claim for this UUID.
     */
    private final Map<String, Entry> inFlightEntries = new HashMap<>();
    private int consecutiveCriticalDeliveries;
    private int criticalPending;

    /** Coalesces repeated mutations to the newest generation and strongest priority. */
    public synchronized void enqueue(String globalId, long generation) {
        enqueue(globalId, generation, Priority.BACKGROUND);
    }

    public synchronized void enqueue(String globalId, long generation, Priority priority) {
        Entry next = new Entry(globalId, generation, priority);
        Entry previous = pending.get(globalId);
        Priority effective = previous != null && previous.priority() == Priority.CRITICAL
                ? Priority.CRITICAL : priority;
        if (previous != null && previous.generation() > generation) generation = previous.generation();
        pending.put(globalId, new Entry(globalId, generation, effective));

        if (previous == null && effective == Priority.CRITICAL) criticalPending++;
        else if (previous != null && previous.priority() != Priority.CRITICAL
                && effective == Priority.CRITICAL) criticalPending++;

        if (inFlightEntries.containsKey(globalId)) return;
        ReadyNode queued = readyById.get(globalId);
        if (queued == null) appendReady(globalId, effective);
        else if (queued.priority != effective) {
            removeReady(globalId);
            appendReady(globalId, effective);
        }
    }

    public synchronized void promote(String globalId) {
        Entry current = pending.get(globalId);
        if (current == null || current.priority() == Priority.CRITICAL) return;
        pending.put(globalId, new Entry(globalId, current.generation(), Priority.CRITICAL));
        criticalPending++;
        if (!inFlightEntries.containsKey(globalId)) {
            ReadyNode queued = readyById.get(globalId);
            if (queued != null && queued.priority != Priority.CRITICAL) {
                removeReady(globalId);
                appendReady(globalId, Priority.CRITICAL);
            }
        }
    }

    private void appendReady(String id, Priority priority) {
        if (readyById.containsKey(id)) return;
        ReadyNode node = new ReadyNode(id, priority);
        readyById.put(id, node);
        list(priority).addLast(node);
    }

    private void removeReady(String id) {
        ReadyNode node = readyById.remove(id);
        if (node != null) list(node.priority).remove(node);
    }

    private ReadyList list(Priority priority) {
        return priority == Priority.CRITICAL ? criticalReady : backgroundReady;
    }

    /** Takes at most maxCount entries; a bounded critical burst prevents background starvation. */
    public synchronized List<Entry> takeBatch(int maxCount) {
        if (maxCount < 1) throw new IllegalArgumentException("maxCount must be positive");
        List<Entry> batch = new ArrayList<>(Math.min(maxCount, readyById.size()));
        while (batch.size() < maxCount && readyById.size() > 0) {
            boolean takeCritical = criticalReady.size > 0
                    && (backgroundReady.size == 0 || consecutiveCriticalDeliveries < 8);
            ReadyNode node;
            if (takeCritical) {
                node = criticalReady.removeFirst();
                consecutiveCriticalDeliveries++;
            } else {
                node = backgroundReady.removeFirst();
                consecutiveCriticalDeliveries = 0;
            }
            readyById.remove(node.id, node);
            Entry entry = pending.get(node.id);
            if (entry == null || entry.priority() != node.priority || inFlightEntries.containsKey(node.id)) continue;
            inFlightEntries.put(node.id, entry);
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
            inFlightEntries.remove(globalId);
            removeReady(globalId);
            if (current.priority() == Priority.CRITICAL) criticalPending--;
        } else {
            inFlightEntries.remove(globalId);
            requeue(globalId);
        }
    }

    /**
     * Retries only the exact claim returned by takeBatch. A delayed duplicate retry
     * cannot release a later claim for the same Character UUID.
     */
    public synchronized void retry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        if (inFlightEntries.get(entry.globalId()) != entry) return;
        inFlightEntries.remove(entry.globalId());
        requeue(entry.globalId());
    }

    private void requeue(String id) {
        Entry current = pending.get(id);
        if (current != null && !inFlightEntries.containsKey(id) && !readyById.containsKey(id))
            appendReady(id, current.priority());
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int readyCount() { return readyById.size(); }
    public synchronized int inFlightCount() { return inFlightEntries.size(); }
    public synchronized int criticalCount() { return criticalPending; }
    public synchronized boolean contains(String globalId) { return pending.containsKey(globalId); }
    public synchronized long generation(String globalId) {
        Entry entry = pending.get(globalId);
        return entry == null ? 0L : entry.generation();
    }
}
