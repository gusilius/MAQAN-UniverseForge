package dev.worldforge.people;

import dev.worldforge.people.storage.CharacterEvictionGate;
import dev.worldforge.people.storage.CharacterPersistenceCoordinator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Scoped access to canonical Characters. A lease pins the exact materialized object
 * in the registry and the shared persistence eviction gate when one is attached.
 */
public final class CharacterAccessService {
    private static final Map<CharacterRegistry, CharacterPersistenceCoordinator> PERSISTENCE =
            new WeakHashMap<>();
    private final CharacterRegistry registry;

    public CharacterAccessService(CharacterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized void attachPersistenceCoordinator(CharacterPersistenceCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        synchronized (PERSISTENCE) {
            CharacterPersistenceCoordinator previous = PERSISTENCE.get(registry);
            if (previous != null && previous != coordinator)
                throw new IllegalStateException("CharacterRegistry already has a persistence coordinator");
            PERSISTENCE.put(registry, coordinator);
        }
        for (Character character : registry.all()) coordinator.register(character.globalId);
    }

    private CharacterPersistenceCoordinator coordinator() {
        synchronized (PERSISTENCE) { return PERSISTENCE.get(registry); }
    }

    private CharacterEvictionGate.Pin pin(Character character) {
        CharacterPersistenceCoordinator coordinator = coordinator();
        if (coordinator == null) return null;
        coordinator.register(character.globalId);
        return coordinator.acquireLease(character.globalId);
    }

    public Lease acquire(String localId) { return registry.acquire(localId); }
    public Lease acquireGlobal(String globalId) { return registry.acquireGlobal(globalId); }

    /**
     * Runs one canonical mutation under a strong lease and transaction pin.
     * The Character is conservatively marked dirty in finally, including when the
     * callback throws after partially changing state. The caller remains responsible
     * for domain rollback/atomicity; this method guarantees it cannot be evicted.
     */
    public void mutate(String localId, Consumer<Character> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        CharacterPersistenceCoordinator persistence = coordinator();
        if (persistence == null)
            throw new IllegalStateException("Character persistence coordinator is not attached");
        try (Lease lease = acquire(localId)) {
            if (lease == null) throw new IllegalArgumentException("Unknown Character: " + localId);
            Character character = lease.get();
            try (CharacterEvictionGate.Pin transaction = persistence.beginTransaction(character.globalId)) {
                try {
                    mutation.accept(character);
                } finally {
                    persistence.markDirty(character.globalId);
                }
            }
        }
    }

    /** UUID-addressed variant for cross-map family, estate and inventory operations. */
    public void mutateGlobal(String globalId, Consumer<Character> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        CharacterPersistenceCoordinator persistence = coordinator();
        if (persistence == null)
            throw new IllegalStateException("Character persistence coordinator is not attached");
        try (Lease lease = acquireGlobal(globalId)) {
            if (lease == null) throw new IllegalArgumentException("Unknown Character UUID: " + globalId);
            Character character = lease.get();
            try (CharacterEvictionGate.Pin transaction = persistence.beginTransaction(character.globalId)) {
                try {
                    mutation.accept(character);
                } finally {
                    persistence.markDirty(character.globalId);
                }
            }
        }
    }

    public static final class Lease implements AutoCloseable {
        private final CharacterRegistry registry;
        private final String localId;
        private final Character character;
        private final CharacterEvictionGate.Pin persistencePin;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(CharacterRegistry registry, String localId, Character character) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.localId = Objects.requireNonNull(localId, "localId");
            this.character = Objects.requireNonNull(character, "character");
            this.persistencePin = registry.access().pin(character);
        }

        public Character get() {
            if (closed.get()) throw new IllegalStateException("Character access lease is closed");
            return character;
        }
        public String localId() { return localId; }
        public String globalId() { return character.globalId; }
        public boolean isClosed() { return closed.get(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { registry.releaseAccess(localId); }
            finally { if (persistencePin != null) persistencePin.close(); }
        }
    }
}
