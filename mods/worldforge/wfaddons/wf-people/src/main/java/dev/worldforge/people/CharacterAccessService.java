package dev.worldforge.people;

import dev.worldforge.people.storage.CharacterEvictionGate;
import dev.worldforge.people.storage.CharacterPersistenceCoordinator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

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

    public void replacePersistenceWriter(CharacterWalWriter writer) {
        CharacterPersistenceCoordinator persistence = coordinator();
        if (persistence != null) persistence.replaceWriter(writer);
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


    /**
     * Mutates several canonical Characters as one coordinated in-memory scope.
     * UUIDs are deduplicated and acquired in lexical order to avoid lock-order
     * inversions. Every participant stays leased and transaction-pinned until
     * all participants have been marked dirty. Domain rollback remains the
     * responsibility of the owning operation.
     */
    public static void mutateGlobals(CharacterAuthority authority, Collection<String> globalIds,
                                     Consumer<Map<String, Character>> mutation) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(globalIds, "globalIds");
        Objects.requireNonNull(mutation, "mutation");
        mutateGlobalsIfChanged(authority, globalIds, characters -> {
            mutation.accept(characters);
            return true;
        });
    }

    /**
     * Like mutateGlobals, but a successful no-op can return false and avoid advancing
     * dirty generations. If the callback throws, every participant is conservatively dirty.
     */
    public static boolean mutateGlobalsIfChanged(CharacterAuthority authority, Collection<String> globalIds,
                                                 Function<Map<String, Character>, Boolean> mutation) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(globalIds, "globalIds");
        Objects.requireNonNull(mutation, "mutation");
        TreeSet<String> orderedIds = new TreeSet<>();
        for (String id : globalIds) {
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("Character UUIDs must be non-blank");
            orderedIds.add(id);
        }
        if (orderedIds.isEmpty()) throw new IllegalArgumentException("At least one Character UUID is required");

        List<Lease> leases = new ArrayList<>(orderedIds.size());
        List<CharacterEvictionGate.Pin> transactions = new ArrayList<>(orderedIds.size());
        Map<String, Character> characters = new LinkedHashMap<>();
        Map<String, CharacterPersistenceCoordinator> coordinators = new LinkedHashMap<>();
        boolean callbackStarted = false;
        boolean changed = false;
        boolean callbackFailed = false;
        try {
            for (String id : orderedIds) {
                CharacterRegistry owner = authority.registryForGlobal(id);
                if (owner == null) throw new IllegalArgumentException("Unknown Character UUID: " + id);
                CharacterAccessService access = owner.access();
                CharacterPersistenceCoordinator persistence = access.coordinator();
                if (persistence == null)
                    throw new IllegalStateException("Character persistence coordinator is not attached for " + id);
                Lease lease = access.acquireGlobal(id);
                if (lease == null || authority.getGlobal(id) != lease.get()) {
                    if (lease != null) lease.close();
                    throw new IllegalStateException("Character identity changed while acquiring " + id);
                }
                leases.add(lease);
                characters.put(id, lease.get());
                coordinators.put(id, persistence);
            }
            for (String id : orderedIds)
                transactions.add(coordinators.get(id).beginTransaction(id));
            callbackStarted = true;
            try {
                changed = Boolean.TRUE.equals(mutation.apply(Collections.unmodifiableMap(characters)));
            } catch (RuntimeException | Error failure) {
                callbackFailed = true;
                throw failure;
            }
        } finally {
            if (callbackStarted) {
                RuntimeException dirtyFailure = null;
                if (changed || callbackFailed) for (String id : orderedIds) {
                    try { coordinators.get(id).markDirty(id); }
                    catch (RuntimeException failure) {
                        if (dirtyFailure == null) dirtyFailure = failure;
                        else dirtyFailure.addSuppressed(failure);
                    }
                }
                closePins(transactions);
                closeLeases(leases);
                if (dirtyFailure != null) throw dirtyFailure;
            } else {
                closePins(transactions);
                closeLeases(leases);
            }
        }
    }

    private static void closePins(List<CharacterEvictionGate.Pin> pins) {
        for (int i = pins.size() - 1; i >= 0; i--) pins.get(i).close();
    }

    private static void closeLeases(List<Lease> leases) {
        for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
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
