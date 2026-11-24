package dev.worldforge.people.storage;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conservative per-identity eviction fence.
 *
 * This class owns no Character objects and performs no I/O. The registry/storage
 * integration must acquire a lease for every exposed object and a transaction pin
 * for every mutation or persistence operation. A persistence receipt only marks the
 * exact captured generation clean; a concurrent later write keeps the record dirty.
 *
 * New identities start dirty and therefore cannot be evicted before their first
 * successful durable checkpoint. State entries are intentionally retained: removing
 * one while a stale lease/receipt exists would permit an ABA-style unsafe eviction.
 */
public final class CharacterEvictionGate {
    public enum Decision {
        ELIGIBLE,
        UNKNOWN_IDENTITY,
        DIRTY,
        NEVER_PERSISTED,
        ACTIVE_LEASE,
        ACTIVE_TRANSACTION
    }

    public record PersistenceTicket(String globalId, long generation) {
        public PersistenceTicket {
            Objects.requireNonNull(globalId, "globalId");
            if (globalId.isBlank() || generation < 1)
                throw new IllegalArgumentException("Invalid persistence ticket");
        }
    }

    private static final class State {
        long generation = 1;
        long persistedGeneration;
        int leases;
        int transactions;
    }

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    /** Registers an identity as dirty until its first durable write is acknowledged. */
    public void register(String globalId) {
        String id = requireId(globalId);
        states.putIfAbsent(id, new State());
    }

    /** Registers an identity whose current snapshot was already recovered from durable storage. */
    public void registerPersisted(String globalId) {
        String id = requireId(globalId);
        State state = states.computeIfAbsent(id, ignored -> new State());
        synchronized (state) {
            state.persistedGeneration = state.generation;
        }
    }

    /** Advances the identity generation. A prior write receipt can no longer clean it. */
    public long markDirty(String globalId) {
        State state = state(globalId);
        synchronized (state) {
            if (state.generation == Long.MAX_VALUE)
                throw new IllegalStateException("Character persistence generation exhausted: " + globalId);
            return ++state.generation;
        }
    }

    /** Returns the current unsaved generation, or null when this identity is clean. */
    public PersistenceTicket dirtyTicket(String globalId) {
        State state = state(globalId);
        synchronized (state) {
            return state.persistedGeneration == state.generation
                    ? null : new PersistenceTicket(globalId, state.generation);
        }
    }

    /** Captures the exact version a persistence operation is responsible for writing. */
    public PersistenceTicket capture(String globalId) {
        State state = state(globalId);
        synchronized (state) {
            return new PersistenceTicket(globalId, state.generation);
        }
    }

    /**
     * Acknowledge only after WAL/checkpoint durability has been confirmed.
     * Returns false when a newer mutation happened while the write was in flight.
     */
    public boolean markPersisted(PersistenceTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        State state = states.get(ticket.globalId());
        if (state == null) return false;
        synchronized (state) {
            if (ticket.generation() != state.generation) return false;
            state.persistedGeneration = ticket.generation();
            return true;
        }
    }

    public Pin acquireLease(String globalId) {
        return acquire(globalId, true);
    }

    public Pin beginTransaction(String globalId) {
        return acquire(globalId, false);
    }

    private Pin acquire(String globalId, boolean lease) {
        State state = state(globalId);
        synchronized (state) {
            if (lease) state.leases++;
            else state.transactions++;
        }
        return new Pin(state, lease);
    }

    public Decision decision(String globalId) {
        if (globalId == null || globalId.isBlank()) return Decision.UNKNOWN_IDENTITY;
        State state = states.get(globalId);
        if (state == null) return Decision.UNKNOWN_IDENTITY;
        synchronized (state) {
            if (state.leases != 0) return Decision.ACTIVE_LEASE;
            if (state.transactions != 0) return Decision.ACTIVE_TRANSACTION;
            // A newly registered identity (generation 1) has never had a write.
            // Once any mutation advances it, it is explicitly DIRTY even before its
            // first durable receipt; callers must not confuse changed data with untouched data.
            if (state.persistedGeneration != state.generation) {
                if (state.persistedGeneration == 0 && state.generation == 1)
                    return Decision.NEVER_PERSISTED;
                return Decision.DIRTY;
            }
            return Decision.ELIGIBLE;
        }
    }

    public boolean canEvict(String globalId) {
        return decision(globalId) == Decision.ELIGIBLE;
    }

    private State state(String globalId) {
        String id = requireId(globalId);
        State state = states.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown Character identity: " + id);
        return state;
    }

    private static String requireId(String globalId) {
        if (globalId == null || globalId.isBlank())
            throw new IllegalArgumentException("globalId is required");
        return globalId;
    }

    public static final class Pin implements AutoCloseable {
        private final State state;
        private final boolean lease;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Pin(State state, boolean lease) {
            this.state = state;
            this.lease = lease;
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (state) {
                if (lease) {
                    if (state.leases <= 0) throw new IllegalStateException("Character lease underflow");
                    state.leases--;
                } else {
                    if (state.transactions <= 0) throw new IllegalStateException("Character transaction underflow");
                    state.transactions--;
                }
            }
        }
    }
}
