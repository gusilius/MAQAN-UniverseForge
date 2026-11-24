package dev.worldforge.people;

import dev.worldforge.people.storage.CharacterEvictionGate;
import dev.worldforge.people.storage.CharacterPersistenceCoordinator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Attaches the registry to its existing WAL/record-store coordinator. Existing IDs
     * are registered as not-yet-persisted; this deliberately does not claim they are clean.
     */
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

    private CharacterEvictionGate.Pin pin(Character character) {
        CharacterPersistenceCoordinator coordinator;
        synchronized (PERSISTENCE) { coordinator = PERSISTENCE.get(registry); }
        if (coordinator == null) return null;
        // Register lazily as a safe fallback for characters created after attachment.
        coordinator.register(character.globalId);
        return coordinator.acquireLease(character.globalId);
    }

    public Lease acquire(String localId) { return registry.acquire(localId); }
    public Lease acquireGlobal(String globalId) { return registry.acquireGlobal(globalId); }

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
            CharacterAccessService service = registry.access();
            this.persistencePin = service.pin(character);
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
