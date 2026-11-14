package dev.worldforge.mod.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared revision vector for canonical WorldForge domain writes.
 *
 * Writers open a scope before mutating authoritative state and commit it only
 * after the mutation succeeds. A snapshot cannot be captured while any write
 * scope is open. This is a coordination primitive, not a second copy of domain data.
 */
public final class DomainMutationRevisions {
    public enum Domain {
        CHARACTERS,
        ORIGINS,
        REALMS_POLITICS,
        SETTLEMENTS,
        ECONOMY_TRADE,
        ARMIES_WARS,
        INVENTORY_OWNERSHIP,
        EVENTS_ACTIONS
    }

    public record Snapshot(long epoch, List<Long> lanes) {
        public Snapshot {
            lanes = List.copyOf(lanes);
            if (lanes.size() != Domain.values().length)
                throw new IllegalArgumentException("Invalid domain revision vector width");
        }

        public long revision(Domain domain) {
            return lanes.get(Objects.requireNonNull(domain).ordinal());
        }
    }

    public final class Mutation implements AutoCloseable {
        private final Domain domain;
        private boolean committed;
        private boolean closed;

        private Mutation(Domain domain) {
            this.domain = domain;
        }

        /** Call exactly once, after the authoritative write has succeeded. */
        public void commit() {
            synchronized (DomainMutationRevisions.this) {
                if (closed) throw new IllegalStateException("Mutation scope is already closed");
                if (committed) throw new IllegalStateException("Mutation scope is already committed");
                revisions[domain.ordinal()] = Math.incrementExact(revisions[domain.ordinal()]);
                epoch = Math.incrementExact(epoch);
                committed = true;
            }
        }

        @Override
        public void close() {
            synchronized (DomainMutationRevisions.this) {
                if (closed) return;
                activeWriters--;
                closed = true;
            }
        }
    }

    private final long[] revisions = new long[Domain.values().length];
    private long epoch;
    private int activeWriters;

    public synchronized Mutation begin(Domain domain) {
        Objects.requireNonNull(domain, "domain");
        activeWriters = Math.incrementExact(activeWriters);
        return new Mutation(domain);
    }

    /** Empty means a canonical write is in progress; callers must retry, never guess. */
    public synchronized Optional<Snapshot> snapshot() {
        if (activeWriters != 0) return Optional.empty();
        List<Long> lanes = new ArrayList<>(revisions.length);
        for (long revision : revisions) lanes.add(revision);
        return Optional.of(new Snapshot(epoch, lanes));
    }

    public synchronized boolean isCurrent(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (activeWriters != 0 || snapshot.epoch() != epoch) return false;
        if (snapshot.lanes().size() != revisions.length) return false;
        for (int i = 0; i < revisions.length; i++)
            if (snapshot.lanes().get(i) != revisions[i]) return false;
        return true;
    }

    /** Isolated test reset; never call while a world is active. */
    public synchronized void resetForTests() {
        if (activeWriters != 0) throw new IllegalStateException("Cannot reset while mutations are active");
        java.util.Arrays.fill(revisions, 0L);
        epoch = 0L;
    }
}
