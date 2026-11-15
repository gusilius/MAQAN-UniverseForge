package dev.worldforge.mod.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared revision vector for canonical WorldForge domain writes.
 * Writers commit a lane only after its authoritative mutation succeeds.
 * Scoped readers declare the exact domains represented by their immutable input.
 */
public final class DomainMutationRevisions {
    public enum Domain {
        CHARACTERS, ORIGINS, REALMS_POLITICS, SETTLEMENTS,
        ECONOMY_TRADE, ARMIES_WARS, INVENTORY_OWNERSHIP, EVENTS_ACTIONS
    }

    public record Snapshot(long epoch, List<Long> lanes) {
        public Snapshot {
            lanes = List.copyOf(lanes);
            if (lanes.size() != Domain.values().length)
                throw new IllegalArgumentException("Invalid domain revision vector width");
        }
        public long revision(Domain domain) { return lanes.get(Objects.requireNonNull(domain).ordinal()); }
    }

    public final class Mutation implements AutoCloseable {
        private final Domain domain;
        private boolean committed;
        private boolean closed;
        private Mutation(Domain domain) { this.domain = domain; }

        public void commit() {
            synchronized (DomainMutationRevisions.this) {
                if (closed) throw new IllegalStateException("Mutation scope is already closed");
                if (committed) throw new IllegalStateException("Mutation scope is already committed");
                revisions[domain.ordinal()] = Math.incrementExact(revisions[domain.ordinal()]);
                epoch = Math.incrementExact(epoch);
                committed = true;
            }
        }
        @Override public void close() {
            synchronized (DomainMutationRevisions.this) {
                if (closed) return;
                int index = domain.ordinal();
                if (activeWriters[index] <= 0) throw new IllegalStateException("Domain writer count underflow: " + domain);
                activeWriters[index]--;
                closed = true;
            }
        }
    }

    private final long[] revisions = new long[Domain.values().length];
    private final int[] activeWriters = new int[Domain.values().length];
    private long epoch;

    public synchronized Mutation begin(Domain domain) {
        Objects.requireNonNull(domain, "domain");
        int index = domain.ordinal();
        activeWriters[index] = Math.incrementExact(activeWriters[index]);
        return new Mutation(domain);
    }

    /** Global capture: unavailable while any canonical domain writer is active. */
    public synchronized Optional<Snapshot> snapshot() { return snapshot(EnumSet.allOf(Domain.class)); }

    /** Scoped capture ignores writers in domains absent from the immutable input projection. */
    public synchronized Optional<Snapshot> snapshot(Domain... dependencies) {
        EnumSet<Domain> selected = selectedDomains(dependencies);
        for (Domain domain : selected) if (activeWriters[domain.ordinal()] != 0) return Optional.empty();
        List<Long> lanes = new ArrayList<>(revisions.length);
        for (long revision : revisions) lanes.add(revision);
        return Optional.of(new Snapshot(epoch, lanes));
    }

    /** Global freshness check: any committed domain mutation invalidates the snapshot. */
    public synchronized boolean isCurrent(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return snapshot.epoch() == epoch && matchesLanes(snapshot, EnumSet.allOf(Domain.class));
    }

    /** Scoped freshness check: only named dependency lanes participate. */
    public synchronized boolean isCurrent(Snapshot snapshot, Domain... dependencies) {
        Objects.requireNonNull(snapshot, "snapshot");
        return matchesLanes(snapshot, selectedDomains(dependencies));
    }

    private boolean matchesLanes(Snapshot snapshot, EnumSet<Domain> selected) {
        if (snapshot.lanes().size() != revisions.length) return false;
        for (Domain domain : selected) {
            int index = domain.ordinal();
            if (activeWriters[index] != 0 || snapshot.lanes().get(index) != revisions[index]) return false;
        }
        return true;
    }

    private static EnumSet<Domain> selectedDomains(Domain... dependencies) {
        if (dependencies == null || dependencies.length == 0)
            throw new IllegalArgumentException("At least one revision dependency is required");
        EnumSet<Domain> selected = EnumSet.noneOf(Domain.class);
        for (Domain domain : dependencies) selected.add(Objects.requireNonNull(domain, "dependency"));
        return selected;
    }

    public synchronized void resetForTests() {
        for (int writers : activeWriters)
            if (writers != 0) throw new IllegalStateException("Cannot reset while mutations are active");
        java.util.Arrays.fill(revisions, 0L);
        epoch = 0L;
    }
}
