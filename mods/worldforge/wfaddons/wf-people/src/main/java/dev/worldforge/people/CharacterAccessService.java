package dev.worldforge.people;

import dev.worldforge.people.storage.CharacterEvictionGate;
import dev.worldforge.people.storage.CharacterPersistenceCoordinator;
import dev.worldforge.people.storage.CharacterWalWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.List;
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
    /** Thread-scoped active leases let legacy mutation helpers dirty their exact participants. */
    private static final ThreadLocal<Set<Lease>> ACTIVE_LEASES =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private final CharacterRegistry registry;
    /** IDs created before persistence attaches (bootstrap); never retains Character objects. */
    private final Set<String> pendingRegistrationIds = new HashSet<>();

    public CharacterAccessService(CharacterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public synchronized void attachPersistenceCoordinator(CharacterPersistenceCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        synchronized (PERSISTENCE) {
            CharacterPersistenceCoordinator previous = PERSISTENCE.get(registry);
            if (previous != null && previous != coordinator)
                throw new IllegalStateException("CharacterRegistry already has a persistence coordinator");
            if (previous == coordinator) return;
            // Publish and seed under the same lock used by registerCreated(): a concurrent
            // create is either in the bootstrap snapshot or sees the attached coordinator.
            PERSISTENCE.put(registry, coordinator);
            synchronized (pendingRegistrationIds) {
                // Only identities created before attachment need eager dirty admission.
                // Snapshot-loaded identities are registered as already durable on first
                // lease/transaction, avoiding one runtime fence object per NPC at startup.
                for (String globalId : pendingRegistrationIds) coordinator.register(globalId);
                pendingRegistrationIds.clear();
            }
        }
    }

    /** Registers newly-created canonical identities without scanning the registry. */
    void attachCharacter(Character character) {
        Objects.requireNonNull(character, "character").attachAccessService(this);
    }

    public void registerCreated(Character character) {
        Objects.requireNonNull(character, "character");
        attachCharacter(character);
        synchronized (PERSISTENCE) {
            CharacterPersistenceCoordinator persistence = PERSISTENCE.get(registry);
            if (persistence != null) persistence.register(character.globalId);
            else synchronized (pendingRegistrationIds) { pendingRegistrationIds.add(character.globalId); }
        }
    }

    /** Marks one canonical identity dirty without retaining its Character object. */
    void markDirty(String globalId) {
        if (globalId == null || globalId.isBlank()) throw new IllegalArgumentException("Character UUID is required");
        synchronized (PERSISTENCE) {
            CharacterPersistenceCoordinator persistence = PERSISTENCE.get(registry);
            if (persistence != null) persistence.markDirty(globalId);
            else synchronized (pendingRegistrationIds) { pendingRegistrationIds.add(globalId); }
        }
    }

    /** Detaches the closed world's coordinator before this reusable registry starts a new server. */
    public void detachPersistenceCoordinator() {
        synchronized (PERSISTENCE) { PERSISTENCE.remove(registry); }
    }

    public void replacePersistenceWriter(CharacterWalWriter writer) {
        CharacterPersistenceCoordinator persistence = coordinator();
        if (persistence != null) persistence.replaceWriter(writer);
    }

    public CharacterPersistenceCoordinator persistenceCoordinator() {
        return coordinator();
    }

    private CharacterPersistenceCoordinator coordinator() {
        synchronized (PERSISTENCE) { return PERSISTENCE.get(registry); }
    }

    private CharacterEvictionGate.Pin pin(Character character) {
        CharacterPersistenceCoordinator coordinator = coordinator();
        if (coordinator == null) return null;
        return coordinator.acquireLease(character.globalId);
    }

    public Lease acquire(String localId) { return registry.acquire(localId); }
    public Lease acquireGlobal(String globalId) { return registry.acquireGlobal(globalId); }
    /** A long-lived physical-body pin; deliberately excluded from mutation ThreadLocal scopes. */
    public Lease acquirePresenceGlobal(String globalId) { return registry.acquirePresenceGlobal(globalId); }

    /**
     * Conservatively marks every Character currently leased by this thread as dirty.
     * ACTIVE_LEASES is thread-confined and markDirty() does not mutate that set, so
     * iterate it directly. Copying it for every Character mutation made large batch
     * application quadratic in the number of simultaneously held leases and allocated
     * a temporary array/list on the server thread for every mutation.
     */
    static void markCurrentLeasesDirty() {
        Set<Lease> active = ACTIVE_LEASES.get();
        if (active.isEmpty()) return;
        for (Lease lease : active) {
            if (!lease.isClosed()) lease.markDirty();
        }
    }

    /**
     * Synchronous lazy hydration under the registry monitor. The checkpoint store is
     * authoritative here: a merely WAL-durable record is not evictable until compaction
     * has produced its addressable checkpoint. The record codec validates the UUID again.
     */
    Character hydrate(String localId) {
        synchronized (registry) {
            Character existing = registry.peekMaterialized(localId);
            if (existing != null) return existing;
            String globalId = registry.globalIdForLocalId(localId);
            if (globalId == null) return null;
            CharacterPersistenceCoordinator persistence = coordinator();
            if (persistence == null || !persistence.canEvict(globalId)) return null;
            try {
                dev.worldforge.people.storage.CharacterRecordStore.Record record = persistence.load(globalId);
                if (record == null || !globalId.equals(record.globalId())) return null;
                registry.applyJsonRecord(record.payloadUtf8(), "wf-lazy-hydration", globalId);
                Character restored = registry.peekMaterialized(localId);
                if (restored == null || !globalId.equals(restored.globalId))
                    throw new IllegalStateException("Hydrated Character identity does not match its directory entry");
                return restored;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not hydrate Character " + globalId, failure);
            }
        }
    }

    CharacterEvictionGate.Pin beginEviction(String globalId) {
        CharacterPersistenceCoordinator persistence = coordinator();
        return persistence == null ? null : persistence.beginEviction(globalId);
    }

    boolean completeEviction(String globalId, CharacterEvictionGate.Pin reservation) {
        CharacterPersistenceCoordinator persistence = coordinator();
        return persistence != null && persistence.completeEviction(globalId, reservation);
    }

    boolean canEvict(String globalId) {
        CharacterPersistenceCoordinator persistence = coordinator();
        return persistence != null && persistence.canEvict(globalId);
    }

    boolean hasAddressableCheckpoint(String globalId) throws java.io.IOException {
        CharacterPersistenceCoordinator persistence = coordinator();
        return persistence != null && persistence.hasAddressableRecord(globalId);
    }

    dev.worldforge.people.storage.CharacterRecordStore.Record loadCheckpoint(String globalId)
            throws java.io.IOException {
        CharacterPersistenceCoordinator persistence = coordinator();
        return persistence == null ? null : persistence.load(globalId);
    }

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
        return changed;
    }

    /**
     * UUID-ordered scoped transaction for lifecycle work that must also run in isolated
     * registries before a persistence coordinator is attached. Production coordinators
     * are pinned and marked dirty; tests/bootstrap still receive canonical leases.
     */
    static boolean mutateScoped(CharacterAuthority authority, CharacterRegistry fallback,
                                Collection<String> globalIds,
                                Function<Map<String, Character>, Boolean> mutation) {
        Objects.requireNonNull(globalIds, "globalIds");
        Objects.requireNonNull(mutation, "mutation");
        TreeSet<String> ordered = new TreeSet<>();
        for (String id : globalIds) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Character UUID is required");
            ordered.add(id);
        }
        if (ordered.isEmpty()) throw new IllegalArgumentException("At least one Character UUID is required");

        List<Lease> leases = new ArrayList<>(ordered.size());
        List<CharacterEvictionGate.Pin> pins = new ArrayList<>(ordered.size());
        Map<String, Character> participants = new LinkedHashMap<>();
        Map<String, CharacterPersistenceCoordinator> coordinators = new LinkedHashMap<>();
        boolean callbackStarted = false;
        boolean changed = false;
        boolean callbackFailed = false;
        RuntimeException dirtyFailure = null;
        try {
            for (String id : ordered) {
                CharacterRegistry owner = authority == null ? fallback : authority.registryForGlobal(id);
                if (owner == null) throw new IllegalArgumentException("Unknown Character UUID: " + id);
                String localId = owner.localIdForGlobalId(id);
                if (localId == null) throw new IllegalArgumentException("Unknown Character UUID: " + id);
                Lease lease = owner.access().acquire(localId);
                if (lease == null || !id.equals(lease.get().globalId)
                        || (authority != null && authority.getGlobal(id) != lease.get())) {
                    if (lease != null) lease.close();
                    throw new IllegalStateException("Character identity changed while acquiring " + id);
                }
                leases.add(lease);
                participants.put(id, lease.get());
                CharacterPersistenceCoordinator persistence = owner.access().coordinator();
                coordinators.put(id, persistence);
            }
            for (String id : ordered) {
                CharacterPersistenceCoordinator persistence = coordinators.get(id);
                if (persistence != null) pins.add(persistence.beginTransaction(id));
            }
            callbackStarted = true;
            try {
                changed = Boolean.TRUE.equals(mutation.apply(Collections.unmodifiableMap(participants)));
            } catch (RuntimeException | Error failure) {
                callbackFailed = true;
                throw failure;
            }
        } finally {
            if (callbackStarted && (changed || callbackFailed)) {
                for (String id : ordered) {
                    CharacterPersistenceCoordinator persistence = coordinators.get(id);
                    if (persistence == null) continue;
                    try { persistence.markDirty(id); }
                    catch (RuntimeException failure) {
                        if (dirtyFailure == null) dirtyFailure = failure;
                        else dirtyFailure.addSuppressed(failure);
                    }
                }
            }
            closePins(pins);
            closeLeases(leases);
            if (dirtyFailure != null) throw dirtyFailure;
        }
        return changed;
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
        private final boolean mutationScope;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(CharacterRegistry registry, String localId, Character character) {
            this(registry, localId, character, true);
        }

        Lease(CharacterRegistry registry, String localId, Character character, boolean mutationScope) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.localId = Objects.requireNonNull(localId, "localId");
            this.character = Objects.requireNonNull(character, "character");
            this.persistencePin = registry.access().pin(character);
            this.mutationScope = mutationScope;
            if (mutationScope) ACTIVE_LEASES.get().add(this);
        }

        public Character get() {
            if (closed.get()) throw new IllegalStateException("Character access lease is closed");
            return character;
        }
        public String localId() { return localId; }
        public String globalId() { return character.globalId; }
        public boolean isClosed() { return closed.get(); }

        /**
         * Marks a mutation performed inside this lease for the bounded durable-save queue.
         * The lease itself remains the active pin until close; dirty state then prevents eviction.
         */
        public void markDirty() {
            if (closed.get()) throw new IllegalStateException("Character access lease is closed");
            registry.access().markDirty(character.globalId);
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (mutationScope) {
                Set<Lease> active = ACTIVE_LEASES.get();
                active.remove(this);
                if (active.isEmpty()) ACTIVE_LEASES.remove();
            }
            try { registry.releaseAccess(localId); }
            finally { if (persistencePin != null) persistencePin.close(); }
        }
    }
}
