package dev.worldforge.people;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.storage.CharacterRecordStore;

import java.util.*;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Все персонажи мира и их должности. Сохраняется целиком в JSON вместе с миром. Аддоны подписываются на события
 * и спрашивают, что разрешает должность ({@link #unlocked}), не зная ничего о ролях друг друга.
 */
public final class CharacterRegistry {
    private static final AtomicLong GLOBAL_MEMBERSHIP_REVISION = new AtomicLong();

    /** Monotonic O(1) invalidation signal for cross-partition membership snapshots. */
    public static long globalMembershipRevision() { return GLOBAL_MEMBERSHIP_REVISION.get(); }
    public enum LifecycleKind { BORN, CHILD_BORN, MARRIED, MARRIAGE_DISSOLVED, LIFE_STAGE_CHANGED, POST_APPOINTED, POST_DISMISSED, PROFESSION_CHANGED,
        HIRED, FIRED, MIGRATED, DUTY_ASSIGNED, DUTY_REMOVED, DIED, RESTORED }

    public record LifecycleEvent(LifecycleKind kind, String characterId, String from, String to, int year) { }

    public interface Listener {
        default void appointed(Character c, String role, String place) { }
        default void dismissed(Character c, String role, String place) { }
        default void died(Character c) { }
        default void born(Character c) { }
        default void lifecycle(Character c, LifecycleEvent event) { }
    }

    /**
     * Canonical materialized objects. HashMap avoids LinkedHashMap's two extra per-entry
     * order pointers; insertion order is held by the compact ID directory below.
     */
    private Map<String, Character> byId = new HashMap<>();
    /** Append-only insertion-order directory. It stores IDs, never Character references. */
    private List<String> orderedIds = new ArrayList<>();
    /** Retained identity routing for evicted objects; values are compact strings only. */
    private final Map<String, String> globalIdByLocalId = new HashMap<>();
    /** Monotonic append counter; distinct from full identity-directory replacement. */
    private long membershipAppendGeneration;
    /** Changes only when restore replaces the ordered identity directory wholesale. */
    private long membershipResetGeneration;
    /** Sparse runtime pins: entries exist only while a caller holds an explicit lease. */
    private final Map<String, Integer> activeAccessLeases = new HashMap<>();
    /** Optional eviction work index; one compact token per recently released identity. */
    private static final int MAX_EVICTION_CANDIDATES = 65_536;
    private static final class EvictionCandidate {
        final String localId;
        final long eligibleAfterNanos;
        EvictionCandidate previous;
        EvictionCandidate next;
        EvictionCandidate(String localId, long eligibleAfterNanos) {
            this.localId = localId;
            this.eligibleAfterNanos = eligibleAfterNanos;
        }
    }
    private EvictionCandidate evictionCandidateHead;
    private EvictionCandidate evictionCandidateTail;
    private final Map<String, EvictionCandidate> evictionCandidateById = new HashMap<>();
    private final CharacterAccessService accessService = new CharacterAccessService(this);
    /** Direct lookup for canonical world-wide identities used by social/family event fan-out. */
    private final Map<String, String> byGlobalId = new HashMap<>();
    /** Runtime dispatch queue; each deceased Character stores its durable recipient list and cursor. */
    private final ArrayDeque<String> pendingBereavement = new ArrayDeque<>();
    /** Reverse index for durable physical-body UUIDs; avoids scanning every Character on entity join. */
    private final Map<String, String> entityOwnerByUuid = new HashMap<>();
    /** Reverse post index: succession and office queries must not scan the population. */
    private final Map<String, LinkedHashSet<String>> characterIdsByPostPlace = new HashMap<>();
    /** Exact role/place projection for bounded office-holder queries. */
    private final Map<String, LinkedHashSet<String>> characterIdsByRolePlace = new HashMap<>();

    /**
     * Copies only the requested insertion-order window by indexed ID lookup.
     * The returned batch is bounded by the caller's requested range.
     */
    public List<Character> range(int fromInclusive, int toExclusive) {
        int size = orderedIds.size();
        if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > size)
            throw new IndexOutOfBoundsException("range [" + fromInclusive + ", " + toExclusive + ") for size " + size);
        if (fromInclusive == toExclusive) return List.of();
        List<Character> result = new ArrayList<>(toExclusive - fromInclusive);
        for (int index = fromInclusive; index < toExclusive; index++) {
            String id = orderedIds.get(index);
            Character character = resolveMaterialized(id);
            if (character == null)
                throw new ConcurrentModificationException("Character identity could not be hydrated during range capture: " + id);
            result.add(character);
        }
        return result;
    }

    /**
     * Creates a short-lived insertion-order iterator positioned after a durable local ID.
     * Used only to recover a scheduler iterator invalidated by an append between ticks.
     */
    public Iterator<Character> iteratorAfter(String exclusiveId) {
        int start = 0;
        if (exclusiveId != null && !exclusiveId.isEmpty()) {
            int anchor = orderedIds.indexOf(exclusiveId);
            if (anchor < 0)
                throw new IllegalStateException("Population cursor anchor is no longer in this registry: " + exclusiveId);
            start = anchor + 1;
        }
        // Capture the synchronous traversal boundary. Appends do not invalidate this
        // iterator or change its insertion order; a later pass will observe the new tail.
        final int startIndex = start;
        final int end = orderedIds.size();
        return new Iterator<>() {
            private int index = startIndex;
            @Override public boolean hasNext() { return index < end; }
            @Override public Character next() {
                if (!hasNext()) throw new NoSuchElementException();
                String id = orderedIds.get(index++);
                Character character = resolveMaterialized(id);
                if (character == null)
                    throw new ConcurrentModificationException("Character identity could not be hydrated during registry traversal: " + id);
                return character;
            }
        };
    }

    /**
     * Temporary key-only iterator for one synchronous traversal. Do not retain it across
     * ticks: collection iterators can retain internal state. Long-lived population work
     * must use idPage()/idAt() instead of retaining this iterator.
     */
    public Iterator<String> idIteratorAfter(String exclusiveId) {
        int start = 0;
        if (exclusiveId != null && !exclusiveId.isEmpty()) {
            int anchor = orderedIds.indexOf(exclusiveId);
            if (anchor < 0)
                throw new IllegalStateException("Population ID cursor anchor is no longer in this registry: " + exclusiveId);
            start = anchor + 1;
        }
        final int startIndex = start;
        final int end = orderedIds.size();
        return new Iterator<>() {
            private int index = startIndex;
            @Override public boolean hasNext() { return index < end; }
            @Override public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                return orderedIds.get(index++);
            }
        };
    }

    /** Legacy compatibility snapshot; avoid for large populations because it is O(population) RAM. */
    @Deprecated
    public List<String> idSnapshot() {
        return new ArrayList<>(orderedIds);
    }

    /**
     * Copies at most {@code limit} IDs from the insertion-order directory in O(limit) time.
     * No full-population snapshot or Character reference is created.
     */
    public List<String> idPage(int fromInclusive, int limit) {
        if (fromInclusive < 0 || limit < 0 || fromInclusive > orderedIds.size())
            throw new IndexOutOfBoundsException("invalid ID page");
        int toExclusive = (int) Math.min((long) orderedIds.size(), (long) fromInclusive + limit);
        if (fromInclusive == toExclusive) return List.of();
        return new ArrayList<>(orderedIds.subList(fromInclusive, toExclusive));
    }

    /** O(1) insertion-order ID lookup for resumable scans without population-sized snapshots. */
    public String idAt(int index) {
        if (index < 0 || index >= orderedIds.size()) throw new IndexOutOfBoundsException(index);
        return orderedIds.get(index);
    }

    /**
     * Compatibility view over the ordered ID directory. Do not retain a second
     * population-sized list of Character references; each value is resolved on demand.
     */
    private final List<Character> orderedView = Collections.unmodifiableList(new AbstractList<Character>() {
        @Override public Character get(int index) {
            if (index < 0 || index >= orderedIds.size()) throw new IndexOutOfBoundsException(index);
            Character character = resolveMaterialized(orderedIds.get(index));
            if (character == null) throw new ConcurrentModificationException("Character directory is inconsistent");
            return character;
        }
        @Override public int size() { return orderedIds.size(); }
        @Override public Iterator<Character> iterator() {
            final int end = orderedIds.size();
            return new Iterator<>() {
                private int index;
                @Override public boolean hasNext() { return index < end; }
                @Override public Character next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    String id = orderedIds.get(index++);
                    Character character = resolveMaterialized(id);
                    if (character == null) throw new ConcurrentModificationException("Character directory is inconsistent");
                    return character;
                }
            };
        }
    });
    private final List<Listener> listeners = new ArrayList<>();
    private CharacterAuthority authority;
    private int next = 1;

    /** The global authority is attached by CharacterAuthority when this registry joins a world. */
    void bindAuthority(CharacterAuthority value) {
        Objects.requireNonNull(value);
        if (authority != null && authority != value)
            throw new IllegalStateException("CharacterRegistry is already attached to another world authority");
        authority = value;
    }

    void unbindAuthority(CharacterAuthority value) {
        if (authority == value) authority = null;
    }
    private long revision;
    /** Changes only when the ordered membership is replaced wholesale (e.g. save restore). */
    private long membershipGeneration;

    private static final ThreadLocal<MutationBatchState> MUTATION_BATCH = new ThreadLocal<>();

    private static final class MutationBatchState {
        final Deque<DomainMutationRevisions.Domain> scopes = new ArrayDeque<>();
        final EnumMap<DomainMutationRevisions.Domain, DomainMutationRevisions.Mutation> mutations =
                new EnumMap<>(DomainMutationRevisions.Domain.class);
        final EnumSet<DomainMutationRevisions.Domain> dirty =
                EnumSet.noneOf(DomainMutationRevisions.Domain.class);
        final Set<CharacterRegistry> membershipChanged = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * Coalesces nested canonical Character writes into one shared revision.
     * This is transaction metadata only; it does not own or duplicate Character state.
     */
    public static final class MutationBatch implements AutoCloseable {
        private final MutationBatchState state;
        private final DomainMutationRevisions.Domain domain;
        private boolean closed;
        private MutationBatch(MutationBatchState state, DomainMutationRevisions.Domain domain) {
            this.state = state;
            this.domain = domain;
        }
        @Override public void close() {
            if (closed) return;
            if (state.scopes.peek() != domain)
                throw new IllegalStateException("Character mutation scopes must close in LIFO order");
            closed = true;
            state.scopes.pop();
            if (state.scopes.isEmpty()) {
                MUTATION_BATCH.remove();
                try {
                    for (CharacterRegistry registry : state.membershipChanged) {
                        registry.revision++;
                        GLOBAL_MEMBERSHIP_REVISION.incrementAndGet();
                    }
                    for (DomainMutationRevisions.Domain dirtyDomain : state.dirty)
                        state.mutations.get(dirtyDomain).commit();
                } finally {
                    for (DomainMutationRevisions.Mutation mutation : state.mutations.values()) mutation.close();
                }
            }
        }
    }

    public static MutationBatch beginMutationBatch() {
        MutationBatchState state = MUTATION_BATCH.get();
        DomainMutationRevisions.Domain domain = state == null || state.scopes.isEmpty()
                ? DomainMutationRevisions.Domain.CHARACTERS : state.scopes.peek();
        return beginMutationBatch(domain);
    }

    public static MutationBatch beginMutationBatch(DomainMutationRevisions.Domain domain) {
        Objects.requireNonNull(domain, "domain");
        MutationBatchState state = MUTATION_BATCH.get();
        if (state == null) { state = new MutationBatchState(); MUTATION_BATCH.set(state); }
        state.mutations.computeIfAbsent(domain, key -> WorldForgeApi.revisions().begin(key));
        state.scopes.push(domain);
        return new MutationBatch(state, domain);
    }

    public static MutationBatch beginSocialMutationBatch() {
        return beginMutationBatch(DomainMutationRevisions.Domain.CHARACTER_SOCIAL);
    }

    /** Records one authoritative Character write, coalescing it inside an owning operation. */
    public static void recordCharacterMutation() { recordDomainMutation(); }

    /** Changes only when registry membership or identity records are replaced. */
    public long revision() { return revision; }

    /** Stable across appends; changes only when the registry's ordered population is replaced. */
    public long membershipGeneration() { return membershipGeneration; }

    /** Changes only when new identities are appended; existing cursor prefixes remain valid. */
    public long membershipAppendGeneration() { return membershipAppendGeneration; }

    /** Changes when a restore replaces the identity directory and invalidates all old offsets. */
    public long membershipResetGeneration() { return membershipResetGeneration; }

    public void addListener(Listener l) { listeners.add(l); }

    public Character get(String id) { return resolveMaterialized(id); }

    /** Internal non-hydrating lookup used by the storage/access boundary. */
    synchronized Character peekMaterialized(String localId) { return byId.get(localId); }

    public synchronized String globalIdForLocalId(String localId) { return globalIdByLocalId.get(localId); }

    /** Reverse UUID directory lookup; does not materialize or hydrate the Character object. */
    public synchronized String localIdForGlobalId(String globalId) { return byGlobalId.get(globalId); }

    private Character resolveMaterialized(String localId) {
        Character current = byId.get(localId);
        if (current != null) return current;
        if (!globalIdByLocalId.containsKey(localId)) return null;
        return accessService.hydrate(localId);
    }

    /**
     * Removes only the Java object. Identity directory and compact reverse indexes stay
     * resident so UUID routing, family links and insertion order remain stable.
     * Caller must have migrated the relevant operation to scoped leases before enabling
     * automatic eviction; legacy raw references cannot be revoked by Java.
     */
    public synchronized boolean evictMaterialized(String globalId) throws java.io.IOException {
        String localId = byGlobalId.get(globalId);
        if (localId == null || activeAccessLeases.containsKey(localId)) return false;
        Character current = byId.get(localId);
        if (current == null) return false;
        if (!accessService.canEvict(globalId)) return false;
        CharacterRecordStore.Record checkpoint = accessService.loadCheckpoint(globalId);
        if (checkpoint == null || !globalId.equals(checkpoint.globalId())) return false;
        if (!globalId.equals(current.globalId)) throw new java.io.IOException("Canonical Character UUID mismatch");
        return byId.remove(localId, current);
    }

    /**
     * Acquires a scoped strong reference for operations that may overlap future eviction.
     * Legacy get() remains for compatibility; new storage-aware code should prefer leases.
     */
    public synchronized CharacterAccessService.Lease acquire(String id) {
        Character character = resolveMaterialized(id);
        if (character == null) return null;
        // A newly active access invalidates any queued idle candidate. Stale deque
        // tokens are harmless and are discarded by identity comparison when polled.
        removeEvictionCandidate(id);
        activeAccessLeases.merge(id, 1, Integer::sum);
        return new CharacterAccessService.Lease(this, id, character);
    }

    /** Resolves and pins a world-wide UUID atomically within this registry. */
    public synchronized CharacterAccessService.Lease acquireGlobal(String globalId) {
        if (globalId == null || globalId.isBlank()) return null;
        String localId = byGlobalId.get(globalId);
        if (localId == null) return null;
        Character character = resolveMaterialized(localId);
        if (character == null) return null;
        removeEvictionCandidate(localId);
        activeAccessLeases.merge(localId, 1, Integer::sum);
        return new CharacterAccessService.Lease(this, localId, character);
    }

    public CharacterAccessService access() { return accessService; }

    synchronized void releaseAccess(String id) {
        Integer count = activeAccessLeases.get(id);
        if (count == null || count <= 0)
            throw new IllegalStateException("Character access lease underflow for " + id);
        if (count == 1) {
            activeAccessLeases.remove(id);
            enqueueEvictionCandidate(id, System.nanoTime());
        } else activeAccessLeases.put(id, count - 1);
    }

    private void unlinkEvictionCandidate(EvictionCandidate candidate) {
        if (candidate.previous == null) evictionCandidateHead = candidate.next;
        else candidate.previous.next = candidate.next;
        if (candidate.next == null) evictionCandidateTail = candidate.previous;
        else candidate.next.previous = candidate.previous;
        candidate.previous = null;
        candidate.next = null;
    }

    private void removeEvictionCandidate(String localId) {
        EvictionCandidate candidate = evictionCandidateById.remove(localId);
        if (candidate != null) unlinkEvictionCandidate(candidate);
    }

    private void enqueueEvictionCandidate(String localId, long nowNanos) {
        if (evictionCandidateById.containsKey(localId)
                || evictionCandidateById.size() >= MAX_EVICTION_CANDIDATES) return;
        EvictionCandidate candidate = new EvictionCandidate(localId, nowNanos);
        candidate.previous = evictionCandidateTail;
        if (evictionCandidateTail == null) evictionCandidateHead = candidate;
        else evictionCandidateTail.next = candidate;
        evictionCandidateTail = candidate;
        evictionCandidateById.put(localId, candidate);
    }

    /**
     * Returns at most {@code limit} identities idle for at least {@code minIdleNanos}.
     * Work is driven by lease-release events; this method never scans the population.
     * A full candidate index may omit hints safely: persistence/identity state is never lost.
     */
    public synchronized List<String> pollEvictionCandidates(int limit, long minIdleNanos) {
        if (limit < 1 || minIdleNanos < 0) throw new IllegalArgumentException("Invalid eviction candidate budget");
        List<String> result = new ArrayList<>(limit);
        long now = System.nanoTime();
        while (evictionCandidateHead != null && result.size() < limit) {
            EvictionCandidate candidate = evictionCandidateHead;
            if (now - candidate.eligibleAfterNanos < minIdleNanos)
                break; // FIFO timestamps: later candidates cannot be older.
            unlinkEvictionCandidate(candidate);
            evictionCandidateById.remove(candidate.localId, candidate);
            if (activeAccessLeases.containsKey(candidate.localId)) continue;
            result.add(candidate.localId);
        }
        return List.copyOf(result);
    }

    /** Requeues a candidate after a conservative eviction refusal. */
    public synchronized void requeueEvictionCandidate(String localId) {
        if (localId != null && !localId.isBlank() && !activeAccessLeases.containsKey(localId))
            enqueueEvictionCandidate(localId, System.nanoTime());
    }

    public synchronized int evictionCandidateCount() { return evictionCandidateById.size(); }

    public synchronized boolean isAccessPinned(String id) {
        return activeAccessLeases.containsKey(id);
    }

    public synchronized int activeAccessLeaseCount() {
        int total = 0;
        for (int count : activeAccessLeases.values()) total += count;
        return total;
    }

    /** Resolves a canonical global UUID without scanning the local registry. */
    public Character getByGlobalId(String globalId) { return characterByGlobalId(globalId); }

    private Character characterByGlobalId(String globalId) {
        if (globalId == null || globalId.isBlank()) return null;
        String localId = byGlobalId.get(globalId);
        return localId == null ? null : resolveMaterialized(localId);
    }

    /** Rebuilds the world-wide family projection once before a yearly life-cycle pass. */
    public void prepareKinIndex(long day) {
        CharacterAuthority current = authority;
        if (current != null) current.rebuildFamilyIndex(day);
    }

    /** Returns bounded family candidates; callers still apply the canonical kinship predicate. */
    public List<Character> kinCandidates(Character person) {
        CharacterAuthority current = authority;
        if (current == null) return List.of();
        return current.kinCandidates(person);
    }

    /**
     * Read-only append-only insertion-order view. Existing entries never move or disappear;
     * callers may retain this view and process a captured size in bounded batches.
     */
    public List<Character> all() { return orderedView; }

    public int size() { return orderedIds.size(); }

    /** Number of full Character objects currently resident in this registry. */
    public int materializedSize() { return byId.size(); }

    /** Returns living office holders at one place using the reverse post index. */
    public List<Character> holdersAtPlace(String place) {
        if (place == null || place.isBlank()) return List.of();
        Set<String> ids = characterIdsByPostPlace.get(place);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Character> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Character character = resolveMaterialized(id);
            if (character != null && character.alive) result.add(character);
        }
        return Collections.unmodifiableList(result);
    }

    private void indexPosts(Character character) {
        for (Character.Post post : character.posts) indexPost(character, post);
    }

    private void unindexPosts(Character character) {
        for (Character.Post post : character.posts) unindexPost(character, post);
    }


    private static String postIndexKey(String role, String place) {
        return role.length() + ":" + role + place;
    }

    private void indexPost(Character character, Character.Post post) {
        characterIdsByPostPlace.computeIfAbsent(post.place, ignored -> new LinkedHashSet<>()).add(character.id);
        characterIdsByRolePlace.computeIfAbsent(postIndexKey(post.role, post.place), ignored -> new LinkedHashSet<>()).add(character.id);
    }

    private void unindexPost(Character character, Character.Post post) {
        LinkedHashSet<String> byPlace = characterIdsByPostPlace.get(post.place);
        if (byPlace != null) { byPlace.remove(character.id); if (byPlace.isEmpty()) characterIdsByPostPlace.remove(post.place); }
        LinkedHashSet<String> byRolePlace = characterIdsByRolePlace.get(postIndexKey(post.role, post.place));
        if (byRolePlace != null) { byRolePlace.remove(character.id); if (byRolePlace.isEmpty()) characterIdsByRolePlace.remove(postIndexKey(post.role, post.place)); }
    }

    /** Returns living holders of one exact role/place pair without a population scan. */
    public List<Character> holders(String role, String place) {
        if (role == null || role.isBlank() || place == null || place.isBlank()) return List.of();
        Set<String> ids = characterIdsByRolePlace.get(postIndexKey(role, place));
        if (ids == null || ids.isEmpty()) return List.of();
        List<Character> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Character character = resolveMaterialized(id);
            if (character != null && character.alive && character.has(role, place)) result.add(character);
        }
        return Collections.unmodifiableList(result);
    }

    public Character create(String name, String house, String culture, String gender, int birthYear) {
        try (MutationBatch ignored = beginMutationBatch()) {
            return createBatched(name, house, culture, gender, birthYear);
        }
    }

    private Character createBatched(String name, String house, String culture, String gender, int birthYear) {
        while (byId.containsKey("c" + next)) {
            if (next == Integer.MAX_VALUE) throw new IllegalStateException("Исчерпаны ID персонажей");
            next++;
        }
        if (next == Integer.MAX_VALUE) throw new IllegalStateException("Исчерпаны ID персонажей");
        Character c = new Character("c" + next++, name);
        c.house = house == null ? "" : house;
        c.culture = culture == null ? "" : culture;
        c.gender = gender;
        c.birthYear = birthYear;
        // New Characters start at their creation day; they must not catch up before they existed.
        c.props.put("population.last_simulated_world_day", Long.toString(WorldForgeApi.clock().dayNumber()));
        byId.put(c.id, c);
        if (!globalIdByLocalId.containsKey(c.id)) orderedIds.add(c.id);
        byGlobalId.put(c.globalId, c.id);
        globalIdByLocalId.put(c.id, c.globalId);
        // Register the new identity directly; no population-wide scan is needed.
        accessService.registerCreated(c);
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) currentAuthority.characterCreated(this, c);
        markMembershipAppend();
        for (Listener l : listeners) l.born(c);
        publish(c, new LifecycleEvent(LifecycleKind.BORN, c.id, "", "", birthYear));
        return c;
    }

    public void appoint(String id, String role, String place) { appoint(id, role, place, 0); }

    public void appoint(String id, String role, String place, int year) {
        try (MutationBatch ignored = beginMutationBatch()) {
            appointBatched(id, role, place, year);
        }
    }

    private void appointBatched(String id, String role, String place, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " мёртв и не может занять должность");
        if (c.has(role, place)) return;
        Character.Post post = new Character.Post(role, place);
        c.posts.add(post);
        indexPost(c, post);
        for (Listener l : listeners) l.appointed(c, role, place);
        publish(c, new LifecycleEvent(LifecycleKind.POST_APPOINTED, c.id, "", role + "@" + place, year));
    }

    public void dismiss(String id, String role, String place) { dismiss(id, role, place, 0); }

    public void dismiss(String id, String role, String place, int year) {
        try (MutationBatch ignored = beginMutationBatch()) {
            dismissBatched(id, role, place, year);
        }
    }

    private void dismissBatched(String id, String role, String place, int year) {
        Character c = require(id);
        Character.Post removedPost = null;
        for (Character.Post post : c.posts)
            if (post.role.equals(role) && post.place.equals(place)) { removedPost = post; break; }
        if (removedPost != null && c.posts.remove(removedPost)) {
            unindexPost(c, removedPost);
            for (Listener l : listeners) l.dismissed(c, role, place);
            publish(c, new LifecycleEvent(LifecycleKind.POST_DISMISSED, c.id, role + "@" + place, "", year));
        }
    }

    /** Applies a precomputed stage while retaining canonical lifecycle publication semantics. */
    public void applyLifeStageResult(Character character, int age, String stage, int year) {
        Objects.requireNonNull(character, "character");
        String next = Objects.requireNonNull(stage, "stage").toLowerCase(Locale.ROOT);
        if (!Set.of("infant", "child", "adolescent", "adult", "elder").contains(next))
            throw new IllegalArgumentException("Invalid life stage: " + stage);
        try (MutationBatch ignored = beginMutationBatch()) {
            if (byId.get(character.id) != character || !character.alive)
                throw new IllegalStateException("Character is no longer a living member of this registry");
            boolean changed = false, lifecyclePublished = false;
            Object previous = character.props.put("population.life_stage", next);
            if (!Objects.equals(previous, next)) changed = true;
            if (previous instanceof String old && !old.equals(next)) {
                emit(character, LifecycleKind.LIFE_STAGE_CHANGED, old, next, year);
                lifecyclePublished = true;
            }
            Object previousAge = character.props.put("population.age_years", age);
            if (!Objects.equals(previousAge, age)) changed = true;
            if (changed && !lifecyclePublished) recordDomainMutation();
        }
    }

    /** Updates calendar-derived life stages and emits only real stage transitions. */
    public void refreshLifeStages(int year) {
        refreshLifeStages(orderedView, year);
    }

    /** Refreshes only the supplied stable batch; safe for resumable server-tick processing. */
    public void refreshLifeStages(Collection<Character> people, int year) {
        if (people == null || people.isEmpty()) return;
        try (MutationBatch ignored = beginMutationBatch()) { refreshLifeStagesBatched(people, year); }
    }

    private void refreshLifeStagesBatched(Collection<Character> people, int year) {
        boolean changed = false;
        boolean lifecyclePublished = false;
        for (Character character : people) {
            if (!character.alive) continue;
            String current = PopulationSimulation.lifeStage(character, year).name().toLowerCase(Locale.ROOT);
            Object previous = character.props.put("population.life_stage", current);
            if (!Objects.equals(previous, current)) changed = true;
            if (previous instanceof String old && !old.equals(current)) {
                emit(character, LifecycleKind.LIFE_STAGE_CHANGED, old, current, year);
                lifecyclePublished = true;
            }
            Object age = PopulationSimulation.age(character, year);
            Object previousAge = character.props.put("population.age_years", age);
            if (!Objects.equals(previousAge, age)) changed = true;
        }
        if (changed && !lifecyclePublished) recordDomainMutation();
    }

    public void changeProfession(String id, String professionId, int year) {
        try (MutationBatch ignored = beginMutationBatch()) { changeProfessionBatched(id, professionId, year); }
    }

    private void changeProfessionBatched(String id, String professionId, int year) {
        Character c = require(id);
        String previous = PopulationSimulation.profession(c);
        PopulationSimulation.assignProfession(c, professionId, year);
        String current = PopulationSimulation.profession(c);
        if (!previous.equals(current))
            emit(c, LifecycleKind.PROFESSION_CHANGED, previous, current, year);
    }

    /** Employment is an assignment on the existing person, never a new population record. */
    public void hire(String id, String employerId, String professionId, int year) {
        try (MutationBatch ignored = beginMutationBatch()) { hireBatched(id, employerId, professionId, year); }
    }

    private void hireBatched(String id, String employerId, String professionId, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " is dead");
        String employer = requireText(employerId, "employer");
        String profession = requireText(professionId, "profession");
        String previous = String.valueOf(c.props.getOrDefault("population.employer", ""));
        boolean changed = !employer.equals(previous)
                || !profession.equals(PopulationSimulation.profession(c))
                || !c.has("settlement_worker", employer)
                || !PopulationSimulation.hasDuty(c, PopulationSimulation.Duty.WORKER);
        if (!previous.isEmpty() && !previous.equals(employer)) fire(id, previous, year);
        changeProfession(id, profession, year);
        setDuty(id, PopulationSimulation.Duty.WORKER, true, year);
        c.props.put("population.employer", employer);
        appoint(id, "settlement_worker", employer, year);
        if (changed) emit(c, LifecycleKind.HIRED, previous, employer, year);
    }

    /** Releases employment without deleting the Character or any skills/family/history. */
    public void fire(String id, String employerId, int year) {
        try (MutationBatch ignored = beginMutationBatch()) { fireBatched(id, employerId, year); }
    }

    private void fireBatched(String id, String employerId, int year) {
        Character c = require(id);
        String employer = employerId == null || employerId.isBlank()
                ? String.valueOf(c.props.getOrDefault("population.employer", "")) : employerId.trim();
        boolean employed = !employer.isEmpty() || PopulationSimulation.hasDuty(c, PopulationSimulation.Duty.WORKER);
        if (!employer.isEmpty()) dismiss(id, "settlement_worker", employer, year);
        c.props.remove("population.employer");
        setDuty(id, PopulationSimulation.Duty.WORKER, false, year);
        if (employed) emit(c, LifecycleKind.FIRED, employer, "", year);
    }

    /** Moves the same person to another population scope and releases the old settlement post. */
    public void migrate(String id, String destinationId, int year) {
        try (MutationBatch ignored = beginMutationBatch()) { migrateBatched(id, destinationId, year); }
    }

    private void migrateBatched(String id, String destinationId, int year) {
        Character c = require(id);
        if (!c.alive) throw new IllegalStateException(c.name + " is dead");
        String destination = requireText(destinationId, "destination");
        String previous = String.valueOf(c.props.getOrDefault("population.location", ""));
        if (destination.equals(previous)) return;
        String employer = String.valueOf(c.props.getOrDefault("population.employer", ""));
        if (!employer.isEmpty()) fire(id, employer, year);
        c.props.put("population.location", destination);
        emit(c, LifecycleKind.MIGRATED, previous, destination, year);
    }

    public void setDuty(String id, PopulationSimulation.Duty duty, boolean assigned, int year) {
        try (MutationBatch ignored = beginMutationBatch()) { setDutyBatched(id, duty, assigned, year); }
    }

    private void setDutyBatched(String id, PopulationSimulation.Duty duty, boolean assigned, int year) {
        Character c = require(id);
        if (!c.alive && assigned) throw new IllegalStateException(c.name + " is dead");
        boolean before = PopulationSimulation.hasDuty(c, duty);
        if (before == assigned) return;
        if (assigned) PopulationSimulation.addDuty(c, duty);
        else PopulationSimulation.removeDuty(c, duty);
        emit(c, assigned ? LifecycleKind.DUTY_ASSIGNED : LifecycleKind.DUTY_REMOVED,
                duty.name().toLowerCase(Locale.ROOT), "", year);
    }

    /** Entity UUID is a replaceable body reference, scoped by body kind; Character.id remains authoritative. */
    public boolean bindEntity(String id, String bodyKind, String entityUuid) {
        try (MutationBatch ignored = beginMutationBatch()) {
            return bindEntityBatched(id, bodyKind, entityUuid);
        }
    }

    private boolean bindEntityBatched(String id, String bodyKind, String entityUuid) {
        Character c = require(id);
        if (!c.alive) return false;
        String kind = requireText(bodyKind, "body kind").toLowerCase(Locale.ROOT);
        String uuid = requireText(entityUuid, "entity UUID");
        String previousOwner = entityOwnerByUuid.get(uuid);
        if (previousOwner != null && !previousOwner.equals(c.id)) return false;
        Object raw = c.props.get("population.entity_bindings");
        Map<String, Object> bindings;
        if (raw instanceof Map<?, ?> map) {
            bindings = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (entry.getKey() instanceof String key) bindings.put(key, entry.getValue());
        } else bindings = new LinkedHashMap<>();
        if (Objects.equals(bindings.get(kind), uuid)) return true;
        Object previousUuid = bindings.put(kind, uuid);
        if (previousUuid instanceof String oldUuid && !oldUuid.equals(uuid)
                && !bindings.containsValue(oldUuid)) entityOwnerByUuid.remove(oldUuid, c.id);
        entityOwnerByUuid.put(uuid, c.id);
        c.props.put("population.entity_bindings", bindings);
        recordDomainMutation();
        return true;
    }

    public void unbindEntity(String id, String bodyKind, String entityUuid) {
        try (MutationBatch ignored = beginMutationBatch()) {
            unbindEntityBatched(id, bodyKind, entityUuid);
        }
    }

    private void unbindEntityBatched(String id, String bodyKind, String entityUuid) {
        Character c = require(id);
        String kind = requireText(bodyKind, "body kind").toLowerCase(Locale.ROOT);
        String uuid = requireText(entityUuid, "entity UUID");
        Object raw = c.props.get("population.entity_bindings");
        if (!(raw instanceof Map<?, ?> map)) return;
        Object current = map.get(kind);
        if (!Objects.equals(current, uuid)) return;
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (entry.getKey() instanceof String key && !key.equals(kind)) bindings.put(key, entry.getValue());
        if (!bindings.containsValue(uuid)) entityOwnerByUuid.remove(uuid, c.id);
        if (bindings.isEmpty()) c.props.remove("population.entity_bindings");
        else c.props.put("population.entity_bindings", bindings);
        recordDomainMutation();
    }

    /** Returns the canonical owner of a physical entity UUID, or null when it is unbound. */
    public String entityBindingOwner(String entityUuid) {
        String characterId = entityOwnerByUuid.get(entityUuid);
        Character character = characterId == null ? null : resolveMaterialized(characterId);
        return character == null ? null : character.globalId;
    }

    private void removeEntityBindings(Character character) {
        Object raw = character.props.get("population.entity_bindings");
        if (raw instanceof Map<?, ?> bindings)
            for (Object uuid : bindings.values()) if (uuid instanceof String value)
                entityOwnerByUuid.remove(value, character.id);
    }

    private void indexEntityBindings(Character character) {
        Object raw = character.props.get("population.entity_bindings");
        if (raw instanceof Map<?, ?> bindings)
            for (Object uuid : bindings.values()) if (uuid instanceof String value) {
                String owner = entityOwnerByUuid.putIfAbsent(value, character.id);
                if (owner != null && !owner.equals(character.id))
                    throw new IllegalArgumentException("Minecraft Entity UUID " + value
                            + " is bound to multiple Characters: " + owner + " and " + character.id);
            }
    }

    private static void addGlobalChild(Character parent, String childGlobalId) {
        Object raw = parent.props.get("family.children_global_ids");
        List<String> children = new ArrayList<>();
        if (raw instanceof List<?> list)
            for (Object value : list) if (value instanceof String id) {
                try { children.add(java.util.UUID.fromString(id).toString()); }
                catch (IllegalArgumentException ignored) { /* discard malformed legacy extension entry */ }
            }
        if (!children.contains(childGlobalId)) children.add(childGlobalId);
        parent.props.put("family.children_global_ids", children);
    }

    private void emit(Character c, LifecycleKind kind, String from, String to, int year) {
        publish(c, new LifecycleEvent(kind, c.id, from == null ? "" : from, to == null ? "" : to, year));
    }

    /**
     * Canonical, bounded life-event journal on Character.props. Other systems can consume the
     * same identity/history without inventing parallel NPC records.
     */
    private void publish(Character character, LifecycleEvent event) {
        Object raw = character.props.get("character.lifecycle.events");
        List<Object> history = new ArrayList<>();
        if (raw instanceof List<?> existing) history.addAll(existing);
        long sequence = character.props.get("character.lifecycle.sequence") instanceof Number n
                ? n.longValue() + 1 : 1;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sequence", sequence);
        entry.put("kind", event.kind().name());
        entry.put("from", event.from());
        entry.put("to", event.to());
        entry.put("year", event.year());
        history.add(entry);
        while (history.size() > 64) history.remove(0);
        character.props.put("character.lifecycle.sequence", sequence);
        character.props.put("character.lifecycle.events", history);
        recordDomainMutation();
        for (Listener listener : listeners) listener.lifecycle(character, event);
    }

    /** Records an append without invalidating already-processed insertion-order offsets. */
    private void markMembershipAppend() {
        membershipAppendGeneration++;
        markMembershipMutation();
    }

    /** Advances the shared lane after a canonical Character write has completed. */
    private void markMembershipMutation() {
        MutationBatchState batch = MUTATION_BATCH.get();
        if (batch != null) {
            batch.membershipChanged.add(this);
            recordDomainMutation(DomainMutationRevisions.Domain.CHARACTERS);
        } else { revision++; GLOBAL_MEMBERSHIP_REVISION.incrementAndGet(); }
    }

    private static void recordDomainMutation() {
        MutationBatchState batch = MUTATION_BATCH.get();
        if (batch != null && !batch.scopes.isEmpty()) { batch.dirty.add(batch.scopes.peek()); return; }
        commitDomainMutation();
    }

    private static void recordDomainMutation(DomainMutationRevisions.Domain domain) {
        MutationBatchState batch = MUTATION_BATCH.get();
        if (batch != null && !batch.scopes.isEmpty()) {
            batch.mutations.computeIfAbsent(domain, key -> WorldForgeApi.revisions().begin(key));
            batch.dirty.add(domain);
            return;
        }
        try (DomainMutationRevisions.Mutation mutation = WorldForgeApi.revisions().begin(domain)) { mutation.commit(); }
    }

    private static void commitDomainMutation() {
        try (DomainMutationRevisions.Mutation mutation =
                     WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.CHARACTERS)) {
            mutation.commit();
        }
    }

    public List<Map<String, Object>> recentLifecycle(String characterId, int limit) {
        Character character = get(characterId);
        if (character == null || limit <= 0) return List.of();
        Object raw = character.props.get("character.lifecycle.events");
        if (!(raw instanceof List<?> events)) return List.of();
        int start = Math.max(0, events.size() - Math.min(64, limit));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = start; i < events.size(); i++) if (events.get(i) instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet())
                if (e.getKey() instanceof String key) copy.put(key, e.getValue());
            result.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    /** Смерть освобождает все должности. Возвращает их список (чтобы назначить преемников). */
    public List<Character.Post> kill(String id) {
        String globalId = globalIdForLocalId(id);
        if (globalId == null) throw new IllegalArgumentException("Нет персонажа " + id);
        CharacterInventory.settleEstateById(authority, this, globalId);
        @SuppressWarnings("unchecked")
        List<Character.Post>[] result = new List[] { List.of() };
        CharacterAccessService.mutateScoped(authority, this, List.of(globalId), people -> {
            try (MutationBatch ignored = beginMutationBatch()) {
                result[0] = killBatched(id);
            }
            return true;
        });
        return result[0];
    }

    private List<Character.Post> killBatched(String id) {
        Character c = require(id);
        if (c.alive) c.alive = false;
        // A pending cross-registry transfer marker must be reconciled even when the estate
        // was previously marked settled: the recipient partition may have reached disk first.
        if (!Boolean.TRUE.equals(c.props.get("estate.settled"))
                || c.props.containsKey("estate.transfer_pending")) {
            CharacterInventory.settleEstate(authority, this, c);
        }
        if (Boolean.TRUE.equals(c.props.get("death.lifecycle.completed"))) return List.of();

        List<Character.Post> vacated = new ArrayList<>(c.posts);
        unindexPosts(c);
        c.posts.clear();
        for (Character.Post p : vacated) for (Listener l : listeners) l.dismissed(c, p.role, p.place);
        Object deathBindings = c.props.remove("population.entity_bindings");
        if (deathBindings instanceof Map<?, ?> bindings)
            for (Object uuid : bindings.values()) if (uuid instanceof String value)
                entityOwnerByUuid.remove(value, c.id);
        int deathYear = c.props.get("population.death_year") instanceof Number n ? n.intValue() : 0;
        recordBereavement(c, deathYear);
        for (Listener l : listeners) l.died(c);
        publish(c, new LifecycleEvent(LifecycleKind.DIED, c.id, "", "", deathYear));
        c.props.put("death.lifecycle.completed", true);
        return vacated;
    }

    /**
     * Death only prepares a bounded, durable recipient list. Applying memories is
     * deliberately deferred to processPendingBereavementBatch().
     */
    private void recordBereavement(Character deceased, int deathYear) {
        if (Boolean.TRUE.equals(deceased.props.get("death.bereavement.completed"))
                || deceased.props.containsKey("death.bereavement.recipients")) return;
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        addLivingCandidateGlobal(recipients, familyGlobalOrLegacy(deceased, "family.spouse_global_id", deceased.spouse));
        addLivingCandidateGlobal(recipients, familyGlobalOrLegacy(deceased, "family.father_global_id", deceased.father));
        addLivingCandidateGlobal(recipients, familyGlobalOrLegacy(deceased, "family.mother_global_id", deceased.mother));
        Object globalChildren = deceased.props.get("family.children_global_ids");
        if (globalChildren instanceof List<?> ids) for (Object value : ids) {
            if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
            if (value instanceof String globalId) addLivingCandidateGlobal(recipients, globalId);
        }
        for (String childId : deceased.children) {
            if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
            addLivingCandidate(recipients, childId);
        }
        String fatherGlobal = familyGlobalOrLegacy(deceased, "family.father_global_id", deceased.father);
        String motherGlobal = familyGlobalOrLegacy(deceased, "family.mother_global_id", deceased.mother);
        addLivingSiblings(recipients, fatherGlobal);
        if (recipients.size() < MAX_BEREAVEMENT_RECIPIENTS) addLivingSiblings(recipients, motherGlobal);

        Object rawRelationships = deceased.props.get("social.relationships");
        if (rawRelationships instanceof Map<?, ?> relationships) {
            int inspected = 0;
            for (Map.Entry<?, ?> entry : relationships.entrySet()) {
                if (inspected++ >= MAX_BEREAVEMENT_RECIPIENTS
                        || recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
                if (!(entry.getKey() instanceof String globalId)
                        || !(entry.getValue() instanceof Number score) || score.intValue() < 35) continue;
                CharacterRegistry friendRegistry = authority == null ? this : authority.registryForGlobal(globalId);
                if (friendRegistry == null) continue;
                String localId = friendRegistry.localIdForGlobalId(globalId);
                if (localId == null) continue;
                try (CharacterAccessService.Lease lease = friendRegistry.access().acquire(localId)) {
                    if (lease != null && lease.get().alive
                            && relationshipScore(lease.get(), deceased.globalId) >= 35)
                        recipients.add(globalId);
                }
            }
        }
        deceased.props.put("death.bereavement.recipients", new ArrayList<>(recipients));
        deceased.props.put("death.bereavement.cursor", 0);
        deceased.props.put("death.bereavement.year", deathYear);
        pendingBereavement.addLast(deceased.globalId);
        recordDomainMutation();
    }

    private void addLivingSiblings(Set<String> recipients, String parentGlobalId) {
        if (parentGlobalId == null || parentGlobalId.isBlank()
                || recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) return;
        CharacterRegistry parentRegistry = authority == null ? this : authority.registryForGlobal(parentGlobalId);
        if (parentRegistry == null) return;
        String parentLocalId = parentRegistry.localIdForGlobalId(parentGlobalId);
        if (parentLocalId == null) return;
        List<String> childGlobalIds = new ArrayList<>();
        try (CharacterAccessService.Lease lease = parentRegistry.access().acquire(parentLocalId)) {
            if (lease == null) return;
            for (String childLocalId : lease.get().children) {
                String childGlobalId = parentRegistry.globalIdForLocalId(childLocalId);
                if (childGlobalId != null) childGlobalIds.add(childGlobalId);
            }
            Object globalChildren = lease.get().props.get("family.children_global_ids");
            if (globalChildren instanceof List<?> ids)
                for (Object id : ids) if (id instanceof String value && !childGlobalIds.contains(value))
                    childGlobalIds.add(value);
        }
        for (String childGlobalId : childGlobalIds) {
            if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
            addLivingCandidateGlobal(recipients, childGlobalId);
        }
    }

    private static final int MAX_BEREAVEMENT_RECIPIENTS = 256;
    public static final int DEFAULT_BEREAVEMENT_BATCH_SIZE = 32;

    private void addLivingCandidate(Set<String> candidates, String localId) {
        addLivingCandidateGlobal(candidates, globalIdForLocalId(localId));
    }

    private void addLivingCandidateGlobal(Set<String> candidates, String globalId) {
        if (globalId == null || globalId.isBlank() || candidates.size() >= MAX_BEREAVEMENT_RECIPIENTS) return;
        CharacterRegistry owner = authority == null ? this : authority.registryForGlobal(globalId);
        if (owner == null) return;
        String localId = owner.localIdForGlobalId(globalId);
        if (localId == null) return;
        try (CharacterAccessService.Lease lease = owner.access().acquire(localId)) {
            if (lease != null && lease.get().alive) candidates.add(globalId);
        }
    }

    private String familyGlobalOrLegacy(Character character, String key, String legacyId) {
        Object value = character.props.get(key);
        if (value instanceof String globalId && !globalId.isBlank()) return globalId;
        String local = globalIdForLocalId(legacyId);
        return local != null ? local : authority == null ? null : authority.globalIdForLegacyId(legacyId);
    }

    /**
     * Applies a bounded number of death consequences. The recipient list and cursor
     * live in the deceased Character props, so WAL/checkpoint persistence resumes safely.
     * Per-recipient event IDs make replay idempotent if a crash precedes cursor persistence.
     */
    public int processPendingBereavementBatch(int maxRecipients) {
        if (maxRecipients < 1) throw new IllegalArgumentException("maxRecipients must be positive");
        int processed = 0;
        try (MutationBatch ignored = beginMutationBatch()) {
            while (processed < maxRecipients && !pendingBereavement.isEmpty()) {
                String deceasedGlobalId = pendingBereavement.peekFirst();
                final List<String> recipients;
                final int initialCursor;
                final int deathYear;
                final String deceasedName;
                final String deceasedLocalId = localIdForGlobalId(deceasedGlobalId);
                if (deceasedLocalId == null) {
                    pendingBereavement.removeFirst();
                    continue;
                }
                try (CharacterAccessService.Lease lease = access().acquire(deceasedLocalId)) {
                    if (lease == null) {
                        pendingBereavement.removeFirst();
                        continue;
                    }
                    Character deceased = lease.get();
                    Object rawRecipients = deceased.props.get("death.bereavement.recipients");
                    if (!(rawRecipients instanceof List<?> raw)) {
                        CharacterAccessService.mutateScoped(authority, this, List.of(deceasedGlobalId), people -> {
                            people.get(deceasedGlobalId).props.put("death.bereavement.completed", true);
                            return true;
                        });
                        pendingBereavement.removeFirst();
                        continue;
                    }
                    List<String> ids = new ArrayList<>(raw.size());
                    for (Object value : raw) if (value instanceof String id && !id.isBlank()) ids.add(id);
                    recipients = List.copyOf(ids);
                    initialCursor = deceased.props.get("death.bereavement.cursor") instanceof Number n
                            ? Math.max(0, n.intValue()) : 0;
                    deathYear = deceased.props.get("death.bereavement.year") instanceof Number n ? n.intValue() : 0;
                    deceasedName = deceased.name;
                }
                int cursor = initialCursor;
                long minute = Math.max(0L, (long) deathYear * 525600L);
                while (cursor < recipients.size() && processed < maxRecipients) {
                    String survivorGlobalId = recipients.get(cursor);
                    cursor++;
                    processed++;
                    final int committedCursor = cursor;
                    CharacterRegistry survivorRegistry = authority == null ? this : authority.registryForGlobal(survivorGlobalId);
                    String survivorLocalId = survivorRegistry == null ? null
                            : survivorRegistry.localIdForGlobalId(survivorGlobalId);
                    if (survivorLocalId == null || survivorGlobalId.equals(deceasedGlobalId)) {
                        CharacterAccessService.mutateScoped(authority, this, List.of(deceasedGlobalId), people -> {
                            people.get(deceasedGlobalId).props.put("death.bereavement.cursor", committedCursor);
                            return true;
                        });
                        continue;
                    }
                    CharacterAccessService.mutateScoped(authority, this,
                            List.of(deceasedGlobalId, survivorGlobalId), people -> {
                        Character deceased = people.get(deceasedGlobalId);
                        Character survivor = people.get(survivorGlobalId);
                        int intensity = 0;
                        if (survivor.alive && !survivor.globalId.equals(deceased.globalId)) {
                            boolean directFamily = deceased.globalId.equals(survivor.props.get("family.spouse_global_id"))
                                    || deceased.globalId.equals(survivor.props.get("family.father_global_id"))
                                    || deceased.globalId.equals(survivor.props.get("family.mother_global_id"))
                                    || survivor.globalId.equals(deceased.props.get("family.spouse_global_id"))
                                    || survivor.globalId.equals(deceased.props.get("family.father_global_id"))
                                    || survivor.globalId.equals(deceased.props.get("family.mother_global_id"))
                                    || listContainsGlobal(deceased.props.get("family.children_global_ids"), survivor.globalId)
                                    || listContainsGlobal(survivor.props.get("family.children_global_ids"), deceased.globalId);
                            if (directFamily) intensity = 4;
                            else {
                                boolean sibling = (!familyGlobalId(deceased, "family.father_global_id").isBlank()
                                        && familyGlobalId(deceased, "family.father_global_id").equals(familyGlobalId(survivor, "family.father_global_id")))
                                        || (!familyGlobalId(deceased, "family.mother_global_id").isBlank()
                                        && familyGlobalId(deceased, "family.mother_global_id").equals(familyGlobalId(survivor, "family.mother_global_id")));
                                if (sibling) intensity = 3;
                                else {
                                    int relationship = relationshipScore(survivor, deceased.globalId);
                                    if (relationship >= 70) intensity = 3;
                                    else if (relationship >= 35) intensity = 2;
                                }
                            }
                        }
                        if (intensity > 0) {
                            String eventId = "death:bereavement:" + deceased.globalId + ":" + survivor.globalId;
                            CharacterDevelopment.recordExperience(survivor, CharacterDevelopment.Experience.BEREAVEMENT,
                                    eventId, "lost " + deceasedName, minute, intensity);
                            CharacterDevelopment.remember(survivor, eventId + ":memory", "bereavement", deceased.globalId,
                                    "death of " + deceasedName, -intensity * 15, minute);
                        }
                        deceased.props.put("death.bereavement.cursor", committedCursor);
                        return true;
                    });
                }
                if (cursor >= recipients.size()) {
                    CharacterAccessService.mutateScoped(authority, this, List.of(deceasedGlobalId), people -> {
                        Character deceased = people.get(deceasedGlobalId);
                        deceased.props.put("death.bereavement.completed", true);
                        deceased.props.remove("death.bereavement.recipients");
                        deceased.props.remove("death.bereavement.cursor");
                        deceased.props.remove("death.bereavement.year");
                        return true;
                    });
                    pendingBereavement.removeFirst();
                } else break;
            }
        }
        return processed;
    }

    private static boolean listContainsGlobal(Object raw, String globalId) {
        return raw instanceof List<?> values && values.contains(globalId);
    }

    private static String familyGlobalId(Character character, String key) {
        Object value = character.props.get(key);
        return value instanceof String id ? id : "";
    }

    public int pendingBereavementEvents() { return pendingBereavement.size(); }

    private static int relationshipScore(Character observer,String subjectGlobalId) {
        Object raw=observer.props.get("social.relationships");
        if(!(raw instanceof Map<?,?> relationships)) return 0;
        Object value=relationships.get(subjectGlobalId);
        return value instanceof Number n?Math.max(-100,Math.min(100,n.intValue())):0;
    }

    public Character holder(String role, String place) {
        List<Character> l = holders(role, place);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Все ли условия выполнены: есть ли в этом месте живой носитель роли, разрешающей действие (например "diplomacy"). */
    public boolean unlocked(RoleDefs defs, String place, String capability) {
        for (Role r : defs.all()) {
            if (!r.unlocks.contains(capability)) continue;
            if (!holders(r.id, place).isEmpty()) return true;
        }
        return false;
    }

    public void marry(String a, String b) { marry(a, b, 0); }

    public void marry(String a, String b, int year) {
        try (MutationBatch ignored = beginMutationBatch()) {
            marryBatched(a, b, year);
        }
    }

    private void marryBatched(String a, String b, int year) {
        Character x = require(a), y = require(b);
        if (x == y) throw new IllegalArgumentException("Персонаж не может вступить в брак с самим собой");
        if (!x.alive || !y.alive) throw new IllegalStateException("Умерший персонаж не может вступить в брак");
        clearDeceasedSpouse(x);
        clearDeceasedSpouse(y);
        if (!x.spouse.isEmpty()) throw new IllegalStateException(x.name + " уже состоит в браке");
        if (!y.spouse.isEmpty()) throw new IllegalStateException(y.name + " уже состоит в браке");
        String oldXSpouse = x.spouse;
        String oldXSpouseGlobal = optionalFamilyId(x, "family.spouse_global_id");
        String oldYSpouse = y.spouse;
        String oldYSpouseGlobal = optionalFamilyId(y, "family.spouse_global_id");
        x.spouse = y.id;
        y.spouse = x.id;
        x.props.put("family.spouse_global_id", y.globalId);
        y.props.put("family.spouse_global_id", x.globalId);
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) {
            currentAuthority.familyChanged(x, oldXSpouse, oldXSpouseGlobal);
            currentAuthority.familyChanged(y, oldYSpouse, oldYSpouseGlobal);
        }
        emit(x, LifecycleKind.MARRIED, "", y.id, year);
        emit(y, LifecycleKind.MARRIED, "", x.id, year);
    }

    /** A deceased or missing spouse does not permanently prevent remarriage. */
    private void clearDeceasedSpouse(Character character) {
        if (character.spouse.isEmpty()) return;
        Character spouse = resolveMaterialized(character.spouse);
        if (spouse != null && spouse.alive) return;
        String previousSpouse = character.spouse;
        String previousSpouseGlobal = optionalFamilyId(character, "family.spouse_global_id");
        if (spouse != null && character.id.equals(spouse.spouse)) {
            String reciprocalPrevious = spouse.spouse;
            String reciprocalGlobal = optionalFamilyId(spouse, "family.spouse_global_id");
            spouse.spouse = "";
            spouse.props.remove("family.spouse_global_id");
            CharacterAuthority currentAuthority = authority;
            if (currentAuthority != null)
                currentAuthority.familyChanged(spouse, reciprocalPrevious, reciprocalGlobal);
        }
        character.spouse = "";
        character.props.remove("family.spouse_global_id");
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null)
            currentAuthority.familyChanged(character, previousSpouse, previousSpouseGlobal);
    }

    /** Ends a reciprocal marriage while preserving both Character identities and family links. */
    public boolean divorce(String firstId, String secondId, int year) {
        try (MutationBatch ignored = beginMutationBatch()) {
            return divorceBatched(firstId, secondId, year);
        }
    }

    private boolean divorceBatched(String firstId, String secondId, int year) {
        Character first = require(firstId), second = require(secondId);
        if (first == second || !first.id.equals(second.spouse) || !second.id.equals(first.spouse)) return false;
        String firstPreviousGlobal = optionalFamilyId(first, "family.spouse_global_id");
        String secondPreviousGlobal = optionalFamilyId(second, "family.spouse_global_id");
        String firstPrevious = first.spouse;
        String secondPrevious = second.spouse;
        first.spouse = "";
        second.spouse = "";
        first.props.remove("family.spouse_global_id");
        second.props.remove("family.spouse_global_id");
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) {
            currentAuthority.familyChanged(first, firstPrevious, firstPreviousGlobal);
            currentAuthority.familyChanged(second, secondPrevious, secondPreviousGlobal);
        }
        first.props.remove("family.spouse_name");
        second.props.remove("family.spouse_name");
        emit(first, LifecycleKind.MARRIAGE_DISSOLVED, second.id, "", year);
        emit(second, LifecycleKind.MARRIAGE_DISSOLVED, first.id, "", year);
        return true;
    }

    public Character addChild(Character father, Character mother, String name, String gender, int year) {
        try (MutationBatch ignored = beginMutationBatch()) {
            return addChildBatched(father, mother, name, gender, year);
        }
    }

    private Character addChildBatched(Character father, Character mother, String name, String gender, int year) {
        Character c = create(name, father.house, father.culture, gender, year);
        if (c.house.isEmpty()) {
            Object inheritedSurname = father.props.get("identity.surname");
            if (inheritedSurname instanceof String surname && !surname.isBlank())
                c.props.put("identity.surname", surname);
        }
        c.father = father.id;
        c.mother = mother.id;
        father.children.add(c.id);
        mother.children.add(c.id);
        c.props.put("family.father_global_id", father.globalId);
        c.props.put("family.mother_global_id", mother.globalId);
        addGlobalChild(father, c.globalId);
        addGlobalChild(mother, c.globalId);
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) currentAuthority.familyChanged(c);
        CharacterDevelopment.inheritPsychology(c, father, mother, Math.max(0L, year * 525600L));
        emit(c, LifecycleKind.CHILD_BORN, father.id, mother.id, year);
        return c;
    }

    private static String optionalFamilyId(Character character, String key) {
        Object value = character.props.get(key);
        return value instanceof String id ? id : "";
    }

    private Character require(String id) {
        Character c = resolveMaterialized(id);
        if (c == null) throw new IllegalArgumentException("Нет персонажа " + id);
        return c;
    }

    // ------------------------------------------------------------------ сохранение

    /**
     * Encode one canonical Character using the exact wf-people/3 record schema.
     * This is the record codec boundary used by incremental persistence; it deliberately
     * retains global identity, family links, posts, traits and every extension property.
     */
    public String toJsonRecord(Character c) {
        Objects.requireNonNull(c, "character");
        if (byId.get(c.id) != c) throw new IllegalArgumentException("Character does not belong to this registry: " + c.id);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", c.id); record.put("global_id", c.globalId); record.put("name", c.name);
        record.put("house", c.house); record.put("culture", c.culture); record.put("gender", c.gender);
        record.put("birth", c.birthYear); record.put("alive", c.alive);
        record.put("father", c.father); record.put("mother", c.mother); record.put("spouse", c.spouse);
        record.put("children", new ArrayList<Object>(c.children));
        List<Object> posts = new ArrayList<>();
        for (Character.Post post : c.posts) posts.add(new ArrayList<Object>(List.of(post.role, post.place)));
        record.put("posts", posts);
        record.put("traits", new ArrayList<Object>(c.traits));
        record.put("props", new LinkedHashMap<>(c.props));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-people-record/1");
        root.put("schema_version", 3);
        root.put("next", next);
        root.put("character", record);
        return Json.write(root);
    }

    /**
     * Restore/replace one record from the incremental journal. The normal wf-people/1..3
     * parser remains the sole validator/migrator, so the record codec cannot drift from
     * the legacy identity rules. Callers must replay records in per-shard sequence order.
     */
    public void applyJsonRecord(String json, String identityNamespace) {
        applyJsonRecord(json, identityNamespace, null);
    }

    /** Applies a WAL record while verifying its frame identity in the same parse pass. */
    public void applyJsonRecord(String json, String identityNamespace, String expectedGlobalId) {
        applyJsonRecord(json, identityNamespace, expectedGlobalId, false);
    }

    /**
     * Applies a checkpoint record only when the legacy snapshot has no record for its local ID.
     * The snapshot is committed before its addressable checkpoint, so an existing snapshot
     * member is at least as new as this record. WAL replay remains unconditional and runs later.
     */
    public void applyJsonRecordIfAbsent(String json, String identityNamespace, String expectedGlobalId) {
        applyJsonRecord(json, identityNamespace, expectedGlobalId, true);
    }

    private void applyJsonRecord(String json, String identityNamespace, String expectedGlobalId,
                                 boolean onlyIfLocalIdAbsent) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root) || !"wf-people-record/1".equals(root.get("format"))
                || !(root.get("character") instanceof Map<?, ?> rawCharacter))
            throw new IllegalArgumentException("Invalid incremental Character record");
        // A WAL/checkpoint frame contains exactly one Character. Cross-record ownership
        // is checked against the canonical registry indexes below, so allocating two
        // temporary owner HashMaps for every replayed NPC is redundant.
        Character restored = decodeCharacter(rawCharacter, identityNamespace, null, null);
        if (expectedGlobalId != null && !expectedGlobalId.equals(restored.globalId))
            throw new IllegalArgumentException("Character WAL identity mismatch: frame="
                    + expectedGlobalId + " payload=" + restored.globalId);

        if (onlyIfLocalIdAbsent && byId.containsKey(restored.id)) return;
        Character previous = byId.get(restored.id);
        String globalOwnerId = byGlobalId.get(restored.globalId);
        if (globalOwnerId != null && !globalOwnerId.equals(restored.id))
            throw new IllegalArgumentException("Duplicate canonical global Character identity: " + restored.globalId);
        Object rawBindings = restored.props.get("population.entity_bindings");
        if (rawBindings instanceof Map<?, ?> bindings) {
            for (Object rawUuid : bindings.values()) {
                if (!(rawUuid instanceof String uuid)) continue; // decoder already validates the shape
                String owner = entityOwnerByUuid.get(uuid);
                if (owner != null && !owner.equals(restored.id))
                    throw new IllegalArgumentException("Minecraft Entity UUID " + uuid
                            + " is already owned by Character " + owner);
            }
        }

        if (previous != null) {
            unindexPosts(previous);
            removeEntityBindings(previous);
            if (!previous.globalId.equals(restored.globalId)) {
                byGlobalId.remove(previous.globalId, previous.id);
                pendingBereavement.remove(previous.globalId);
            }
        }
        byId.put(restored.id, restored);
        if (!globalIdByLocalId.containsKey(restored.id)) orderedIds.add(restored.id);
        globalIdByLocalId.put(restored.id, restored.globalId);
        indexPosts(restored);
        byGlobalId.put(restored.globalId, restored.id);
        indexEntityBindings(restored);
        int restoredNumericId = numericCharacterId(restored.id);
        if (restoredNumericId >= 0 && restoredNumericId < Integer.MAX_VALUE)
            next = Math.max(next, restoredNumericId + 1);
        Object recipients = restored.props.get("death.bereavement.recipients");
        if (!Boolean.TRUE.equals(restored.props.get("death.bereavement.completed"))
                && recipients instanceof List<?> && !pendingBereavement.contains(restored.globalId))
            pendingBereavement.addLast(restored.globalId);
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) currentAuthority.characterReplaced(this, previous, restored);
        if (previous == null) markMembershipAppend();
        recordDomainMutation();
    }

    public String toJson() { return toJson(null); }

    /** Compatibility encoder for callers that explicitly require a String snapshot. */
    public String toJson(Set<String> only) {
        StringWriter output = new StringWriter();
        try { writeJson(output, only); }
        catch (IOException impossible) { throw new IllegalStateException("StringWriter unexpectedly failed", impossible); }
        return output.toString();
    }

    /** Streams a canonical partition snapshot with temporary memory bounded to one Character record. */
    public void writeJson(Writer writer) throws IOException { writeJson(writer, null); }

    /** Streams either the complete partition or a selected set of local Character IDs. */
    public void writeJson(Writer writer, Set<String> only) throws IOException {
        Objects.requireNonNull(writer, "writer");
        writer.write("{\"format\":\"wf-people/3\",\"schema_version\":3,\"next\":");
        Json.writeNumber(writer, next);
        writer.write(",\"characters\":[");
        boolean first = true;
        for (Character c : orderedView) {
            if (only != null && !only.contains(c.id)) continue;
            if (!first) writer.write(',');
            first = false;
            // Emit fields directly: do not allocate a per-Character Map or duplicate
            // children/posts/traits collections during a large checkpoint.
            writer.write('{');
            Json.writeString(writer, "id"); writer.write(':'); Json.writeString(writer, c.id); writer.write(',');
            Json.writeString(writer, "global_id"); writer.write(':'); Json.writeString(writer, c.globalId); writer.write(',');
            Json.writeString(writer, "name"); writer.write(':'); Json.writeString(writer, c.name); writer.write(',');
            Json.writeString(writer, "house"); writer.write(':'); Json.writeString(writer, c.house); writer.write(',');
            Json.writeString(writer, "culture"); writer.write(':'); Json.writeString(writer, c.culture); writer.write(',');
            Json.writeString(writer, "gender"); writer.write(':'); Json.writeString(writer, c.gender); writer.write(',');
            Json.writeString(writer, "birth"); writer.write(':'); Json.writeNumber(writer, c.birthYear); writer.write(',');
            Json.writeString(writer, "alive"); writer.write(':'); writer.write(Boolean.toString(c.alive)); writer.write(',');
            Json.writeString(writer, "father"); writer.write(':'); Json.writeString(writer, c.father); writer.write(',');
            Json.writeString(writer, "mother"); writer.write(':'); Json.writeString(writer, c.mother); writer.write(',');
            Json.writeString(writer, "spouse"); writer.write(':'); Json.writeString(writer, c.spouse); writer.write(',');
            Json.writeString(writer, "children"); writer.write(':'); Json.write(c.children, writer); writer.write(',');
            Json.writeString(writer, "posts"); writer.write(':'); writer.write('[');
            for (int postIndex = 0; postIndex < c.posts.size(); postIndex++) {
                if (postIndex > 0) writer.write(',');
                Character.Post post = c.posts.get(postIndex);
                writer.write('['); Json.writeString(writer, post.role); writer.write(',');
                Json.writeString(writer, post.place); writer.write(']');
            }
            writer.write(']'); writer.write(',');
            Json.writeString(writer, "traits"); writer.write(':'); writer.write('[');
            boolean firstTrait = true;
            for (String trait : c.traits) {
                if (!firstTrait) writer.write(',');
                firstTrait = false;
                Json.writeString(writer, trait);
            }
            writer.write(']'); writer.write(',');
            Json.writeString(writer, "props"); writer.write(':'); Json.write(c.props, writer);
            writer.write('}');
        }
        writer.write("]}");
    }

    /** Parses generated local IDs without compiling a regular expression per NPC. */
    private static int numericCharacterId(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'c') return -1;
        for (int i = 1; i < id.length(); i++) {
            char value = id.charAt(i);
            if (value < '0' || value > '9') return -1;
        }
        try { return Integer.parseInt(id.substring(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    /**
     * The single canonical record decoder shared by full snapshots and incremental WAL
     * replay. Keeping one parser prevents the two persistence paths from drifting.
     */
    private static Character decodeCharacter(Map<?, ?> m, String identityNamespace,
                                             Map<String, String> globalOwners,
                                             Map<String, String> entityOwners) {
        if (identityNamespace == null || identityNamespace.isBlank())
            throw new IllegalArgumentException("identityNamespace is required");
        String id = requiredString(m, "id");
        String name = requiredString(m, "name");
        if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Пустой ID или имя персонажа");
        String globalId = optionalString(m, "global_id");
        if (globalId.isBlank()) {
            globalId = java.util.UUID.nameUUIDFromBytes((identityNamespace + "|" + id)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        } else {
            try { globalId = java.util.UUID.fromString(globalId).toString(); }
            catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Некорректный global_id персонажа " + id, ex);
            }
        }
        if (globalOwners != null) {
            String previousGlobalOwner = globalOwners.putIfAbsent(globalId, id);
            if (previousGlobalOwner != null)
                throw new IllegalArgumentException("Повторный global_id персонажа: " + globalId
                        + " (" + previousGlobalOwner + ", " + id + ")");
        }
        Character character = new Character(id, globalId, name);
        character.house = optionalString(m, "house");
        character.culture = optionalString(m, "culture");
        character.gender = optionalString(m, "gender");
        if (character.gender.isBlank()) character.gender = "m";
        Object birth = m.get("birth");
        if (!(birth instanceof Number birthNumber)) throw new IllegalArgumentException("Нет года рождения: " + id);
        character.birthYear = birthNumber.intValue();
        Object alive = m.get("alive");
        if (!(alive instanceof Boolean)) throw new IllegalArgumentException("Некорректный статус жизни персонажа " + id);
        character.alive = (Boolean) alive;
        character.father = optionalString(m, "father");
        character.mother = optionalString(m, "mother");
        character.spouse = optionalString(m, "spouse");

        readStringList(m.get("children"), character.children, "children", id);
        Object rawPosts = m.get("posts");
        if (!(rawPosts instanceof List<?> posts)) throw new IllegalArgumentException("Повреждены должности персонажа " + id);
        for (Object post : posts) {
            if (!(post instanceof List<?> pair) || pair.size() != 2
                    || !(pair.get(0) instanceof String role) || role.isBlank()
                    || !(pair.get(1) instanceof String place) || place.isBlank())
                throw new IllegalArgumentException("Повреждена должность персонажа " + id);
            if (character.has(role, place)) throw new IllegalArgumentException("Повторная должность персонажа " + id);
            character.posts.add(new Character.Post(role, place));
        }
        Object rawTraits = m.get("traits");
        if (!(rawTraits instanceof List<?> traits)) throw new IllegalArgumentException("Повреждены черты персонажа " + id);
        // Character.traits is already a Set; use it for duplicate detection instead
        // of allocating a second temporary HashSet for every decoded NPC.
        for (Object trait : traits) {
            if (!(trait instanceof String value) || value.isBlank() || !character.traits.add(value))
                throw new IllegalArgumentException("Повреждена или повторяется черта персонажа " + id);
        }
        Object props = m.get("props");
        if (props != null) {
            if (!(props instanceof Map<?, ?> properties))
                throw new IllegalArgumentException("Повреждены свойства персонажа " + id);
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("Некорректный ключ свойства " + id);
                character.props.put(key, entry.getValue());
            }
        }
        Object bindingsRaw = character.props.get("population.entity_bindings");
        if (bindingsRaw != null && !(bindingsRaw instanceof Map<?, ?>))
            throw new IllegalArgumentException("Некорректная таблица привязок Minecraft Entity персонажа " + id);
        if (bindingsRaw instanceof Map<?, ?> bindings) {
            for (Object bindingValue : bindings.values()) {
                if (!(bindingValue instanceof String uuid) || uuid.isBlank())
                    throw new IllegalArgumentException("Повреждена привязка Minecraft Entity персонажа " + id);
                if (entityOwners != null) {
                    String previousOwner = entityOwners.putIfAbsent(uuid, id);
                    if (previousOwner != null && !previousOwner.equals(id))
                        throw new IllegalArgumentException("Minecraft Entity UUID " + uuid
                                + " привязан к двум персонажам: " + previousOwner + " и " + id);
                }
            }
        }
        return character;
    }

    public void fromJson(String json) { fromJson(json, "legacy-unscoped"); }

    /** Loads legacy snapshots without retaining a second, whole-population JSON object tree. */
    public void fromJson(String json, String identityNamespace) {
        fromJson(new java.io.StringReader(json == null ? "" : json), identityNamespace);
    }

    /**
     * Streaming restore path for server saves. Only one decoded JSON Character record is
     * temporary at a time; the canonical Character objects and indexes are staged separately
     * and published only after the complete file has been validated.
     */
    public void fromJson(java.io.Reader reader, String identityNamespace) {
        Objects.requireNonNull(reader, "reader");
        try (MutationBatch ignored = beginMutationBatch()) {
            fromJsonBatched(reader, identityNamespace);
        }
    }

    private void fromJsonBatched(java.io.Reader reader, String identityNamespace) {
        if (identityNamespace == null || identityNamespace.isBlank())
            throw new IllegalArgumentException("identityNamespace is required");

        // Stage the replacement directly in its final map and order directory.
        // Copying a LinkedHashMap into a HashMap plus a second key list doubled
        // population-sized references during restore at exactly the worst moment.
        Map<String, Character> loaded = new HashMap<>();
        List<String> loadedIds = new ArrayList<>();
        Map<String, String> globalOwners = new HashMap<>();
        Map<String, String> entityOwners = new HashMap<>();
        int[] maxNumericId = {0};
        Json.StreamedArray snapshot;
        try {
            snapshot = Json.readObjectArray(reader, "characters", value -> {
                if (!(value instanceof Map<?, ?> record))
                    throw new IllegalArgumentException("Повреждена запись персонажа");
                Character character = decodeCharacter(record, identityNamespace, globalOwners, entityOwners);
                if (loaded.putIfAbsent(character.id, character) != null)
                    throw new IllegalArgumentException("Повторный ID персонажа: " + character.id);
                loadedIds.add(character.id);
                int numericId = numericCharacterId(character.id);
                if (numericId >= 0) maxNumericId[0] = Math.max(maxNumericId[0], numericId);
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("Не удалось потоково прочитать сохранение персонажей", ex);
        }

        Map<String, Object> root = snapshot.fields();
        String format = root.get("format") instanceof String f ? f : "";
        if (!"wf-people/1".equals(format) && !"wf-people/2".equals(format) && !"wf-people/3".equals(format))
            throw new IllegalArgumentException("Неверный формат людей: " + format);
        Object rawNext = root.get("next");
        if (!(rawNext instanceof Number nextNumber))
            throw new IllegalArgumentException("Отсутствует счётчик персонажей");
        long requestedNext = nextNumber.longValue();
        if (requestedNext < 1 || requestedNext > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Недопустимый счётчик персонажей");
        if (!snapshot.present()) throw new IllegalArgumentException("Отсутствует список персонажей");
        if (maxNumericId[0] == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Исчерпаны ID персонажей");
        int safeNext = (int) Math.max(requestedNext, (long) maxNumericId[0] + 1L);

        // Upgrade legacy map-local family references to stable global UUID references.
        for (Character character : loaded.values()) {
            Character father = loaded.get(character.father);
            Character mother = loaded.get(character.mother);
            Character spouse = loaded.get(character.spouse);
            if (father != null) {
                character.props.putIfAbsent("family.father_global_id", father.globalId);
                addGlobalChild(father, character.globalId);
            }
            if (mother != null) {
                character.props.putIfAbsent("family.mother_global_id", mother.globalId);
                addGlobalChild(mother, character.globalId);
            }
            if (spouse != null) character.props.putIfAbsent("family.spouse_global_id", spouse.globalId);
        }

        // A wholesale identity replacement cannot overlap scoped users. Old leases
        // would still point at objects from the previous generation of this registry.
        if (!activeAccessLeases.isEmpty())
            throw new IllegalStateException("Cannot replace Character registry while scoped leases are active");
        // Candidate hints belong to the previous identity generation and must not survive restore.
        evictionCandidateHead = null;
        evictionCandidateTail = null;
        evictionCandidateById.clear();

        // Publish only after every record, identity and entity binding has passed validation.
        byId = loaded;
        orderedIds = loadedIds;
        globalIdByLocalId.clear();
        membershipResetGeneration++;
        characterIdsByPostPlace.clear();
        characterIdsByRolePlace.clear();
        for (Character character : loaded.values()) indexPosts(character);
        byGlobalId.clear();
        pendingBereavement.clear();
        for (Character character : loaded.values()) {
            byGlobalId.put(character.globalId, character.id);
            globalIdByLocalId.put(character.id, character.globalId);
            if (!Boolean.TRUE.equals(character.props.get("death.bereavement.completed"))
                    && character.props.get("death.bereavement.recipients") instanceof List<?>)
                pendingBereavement.addLast(character.globalId);
        }
        entityOwnerByUuid.clear();
        entityOwnerByUuid.putAll(entityOwners);
        membershipGeneration++;
        next = safeNext;
        markMembershipMutation();
        recordDomainMutation();
        for (Character character : orderedView) {
            LifecycleEvent restored = new LifecycleEvent(LifecycleKind.RESTORED, character.id, "", "", 0);
            for (Listener listener : listeners) listener.lifecycle(character, restored);
        }
    }

    private static String requiredString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static String optionalString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String s)) throw new IllegalArgumentException("Ожидалась строка " + key);
        return s;
    }

    private static void readStringList(Object raw, List<String> target, String field, String id) {
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("Повреждено поле " + field + " персонажа " + id);
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String s) || s.isBlank() || !unique.add(s))
                throw new IllegalArgumentException("Поврежден список " + field + " персонажа " + id);
            target.add(s);
        }
    }
}
