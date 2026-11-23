package dev.worldforge.people;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;

import java.util.*;
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

    private final Map<String, Character> byId = new LinkedHashMap<>();
    /** Direct lookup for canonical world-wide identities used by social/family event fan-out. */
    private final Map<String, Character> byGlobalId = new HashMap<>();
    /** Runtime dispatch queue; each deceased Character stores its durable recipient list and cursor. */
    private final ArrayDeque<String> pendingBereavement = new ArrayDeque<>();
    /** Reverse index for durable physical-body UUIDs; avoids scanning every Character on entity join. */
    private final Map<String, String> entityOwnerByUuid = new HashMap<>();
    private final List<Character> ordered = new ArrayList<>();
    private final List<Character> orderedView = Collections.unmodifiableList(ordered);
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
        int depth;
        boolean dirty;
        final Set<CharacterRegistry> membershipChanged = Collections.newSetFromMap(new IdentityHashMap<>());
        final DomainMutationRevisions.Mutation mutation =
                WorldForgeApi.revisions().begin(DomainMutationRevisions.Domain.CHARACTERS);
    }

    /**
     * Coalesces nested canonical Character writes into one shared revision.
     * This is transaction metadata only; it does not own or duplicate Character state.
     */
    public static final class MutationBatch implements AutoCloseable {
        private final MutationBatchState state;
        private boolean closed;
        private MutationBatch(MutationBatchState state) { this.state = state; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (--state.depth == 0) {
                MUTATION_BATCH.remove();
                try {
                    for (CharacterRegistry registry : state.membershipChanged) {
                        registry.revision++;
                        GLOBAL_MEMBERSHIP_REVISION.incrementAndGet();
                    }
                    if (state.dirty) state.mutation.commit();
                } finally {
                    state.mutation.close();
                }
            }
        }
    }

    public static MutationBatch beginMutationBatch() {
        MutationBatchState state = MUTATION_BATCH.get();
        if (state == null) {
            state = new MutationBatchState();
            MUTATION_BATCH.set(state);
        }
        state.depth++;
        return new MutationBatch(state);
    }

    /** Records one authoritative Character write, coalescing it inside an owning operation. */
    public static void recordCharacterMutation() { recordDomainMutation(); }

    /** Changes only when registry membership or identity records are replaced. */
    public long revision() { return revision; }

    /** Stable across appends; changes only when the registry's ordered population is replaced. */
    public long membershipGeneration() { return membershipGeneration; }

    public void addListener(Listener l) { listeners.add(l); }

    public Character get(String id) { return byId.get(id); }

    /** Resolves a canonical global UUID without scanning the local registry. */
    public Character getByGlobalId(String globalId) {
        return globalId == null || globalId.isBlank() ? null : byGlobalId.get(globalId);
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

    public int size() { return ordered.size(); }

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
        byGlobalId.put(c.globalId, c);
        ordered.add(c);
        CharacterAuthority currentAuthority = authority;
        if (currentAuthority != null) currentAuthority.characterCreated(this, c);
        markMembershipMutation();
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
        c.posts.add(new Character.Post(role, place));
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
        if (c.posts.removeIf(p -> p.role.equals(role) && p.place.equals(place))) {
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
        refreshLifeStages(byId.values(), year);
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
        Character character = characterId == null ? null : byId.get(characterId);
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

    /** Advances the shared lane after a canonical Character write has completed. */
    private void markMembershipMutation() {
        MutationBatchState batch = MUTATION_BATCH.get();
        if (batch != null) batch.membershipChanged.add(this);
        else {
            revision++;
            GLOBAL_MEMBERSHIP_REVISION.incrementAndGet();
        }
    }

    private static void recordDomainMutation() {
        MutationBatchState batch = MUTATION_BATCH.get();
        if (batch != null) {
            batch.dirty = true;
            return;
        }
        commitDomainMutation();
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
        try (MutationBatch ignored = beginMutationBatch()) {
            return killBatched(id);
        }
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
        c.posts.clear();
        for (Character.Post p : vacated) for (Listener l : listeners) l.dismissed(c, p.role, p.place);
        Object deathBindings = c.props.remove("population.entity_bindings");
        if (deathBindings instanceof Map<?, ?> bindings)
            for (Object uuid : bindings.values()) if (uuid instanceof String value)
                entityOwnerByUuid.remove(value, c.id);
        int deathYear = c.props.get("population.death_year") instanceof Number n ? n.intValue() : 0;
        recordBereavement(c, deathYear);
        processPendingBereavementBatch(DEFAULT_BEREAVEMENT_BATCH_SIZE);
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
        addLivingCandidate(recipients, deceased.spouse);
        addLivingCandidate(recipients, deceased.father);
        addLivingCandidate(recipients, deceased.mother);
        for (String childId : deceased.children) {
            if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
            addLivingCandidate(recipients, childId);
        }
        Character father = deceased.father.isBlank() ? null : byId.get(deceased.father);
        Character mother = deceased.mother.isBlank() ? null : byId.get(deceased.mother);
        if (father != null) for (String siblingId : father.children) {
            if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
            addLivingCandidate(recipients, siblingId);
        }
        if (mother != null && recipients.size() < MAX_BEREAVEMENT_RECIPIENTS)
            for (String siblingId : mother.children) {
                if (recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
                addLivingCandidate(recipients, siblingId);
            }
        Object rawRelationships = deceased.props.get("social.relationships");
        if (rawRelationships instanceof Map<?, ?> relationships) {
            int inspected = 0;
            for (Map.Entry<?, ?> entry : relationships.entrySet()) {
                if (inspected++ >= MAX_BEREAVEMENT_RECIPIENTS
                        || recipients.size() >= MAX_BEREAVEMENT_RECIPIENTS) break;
                if (!(entry.getKey() instanceof String globalId)
                        || !(entry.getValue() instanceof Number score) || score.intValue() < 35) continue;
                Character friend = byGlobalId.get(globalId);
                if (friend != null && friend.alive && friend != deceased
                        && relationshipScore(friend, deceased.globalId) >= 35)
                    recipients.add(friend.globalId);
            }
        }
        deceased.props.put("death.bereavement.recipients", new ArrayList<>(recipients));
        deceased.props.put("death.bereavement.cursor", 0);
        deceased.props.put("death.bereavement.year", deathYear);
        pendingBereavement.addLast(deceased.globalId);
        recordDomainMutation();
    }

    private static final int MAX_BEREAVEMENT_RECIPIENTS = 256;
    public static final int DEFAULT_BEREAVEMENT_BATCH_SIZE = 32;

    private void addLivingCandidate(Set<String> candidates, String localId) {
        if (localId == null || localId.isBlank() || candidates.size() >= MAX_BEREAVEMENT_RECIPIENTS) return;
        Character candidate = byId.get(localId);
        if (candidate != null && candidate.alive) candidates.add(candidate.globalId);
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
                Character deceased = byGlobalId.get(deceasedGlobalId);
                if (deceased == null) {
                    pendingBereavement.removeFirst();
                    continue;
                }
                Object rawRecipients = deceased.props.get("death.bereavement.recipients");
                if (!(rawRecipients instanceof List<?> recipients)) {
                    deceased.props.put("death.bereavement.completed", true);
                    pendingBereavement.removeFirst();
                    recordCharacterMutation();
                    continue;
                }
                int cursor = deceased.props.get("death.bereavement.cursor") instanceof Number n
                        ? Math.max(0, n.intValue()) : 0;
                int deathYear = deceased.props.get("death.bereavement.year") instanceof Number n
                        ? n.intValue() : 0;
                long minute = Math.max(0L, (long) deathYear * 525600L);
                while (cursor < recipients.size() && processed < maxRecipients) {
                    Object rawGlobalId = recipients.get(cursor++);
                    processed++;
                    if (!(rawGlobalId instanceof String survivorGlobalId)) continue;
                    Character survivor = byGlobalId.get(survivorGlobalId);
                    if (survivor == null || !survivor.alive || survivor == deceased) continue;
                    int intensity = 0;
                    boolean directFamily = deceased.spouse.equals(survivor.id)
                            || deceased.father.equals(survivor.id) || deceased.mother.equals(survivor.id)
                            || survivor.father.equals(deceased.id) || survivor.mother.equals(deceased.id)
                            || deceased.children.contains(survivor.id) || survivor.children.contains(deceased.id);
                    if (directFamily) intensity = 4;
                    else {
                        boolean sibling = (!deceased.father.isBlank() && deceased.father.equals(survivor.father))
                                || (!deceased.mother.isBlank() && deceased.mother.equals(survivor.mother));
                        if (sibling) intensity = 3;
                        else {
                            int relationship = relationshipScore(survivor, deceased.globalId);
                            if (relationship >= 70) intensity = 3;
                            else if (relationship >= 35) intensity = 2;
                        }
                    }
                    if (intensity == 0) continue;
                    String eventId = "death:bereavement:" + deceased.globalId + ":" + survivor.globalId;
                    CharacterDevelopment.recordExperience(survivor, CharacterDevelopment.Experience.BEREAVEMENT,
                            eventId, "lost " + deceased.name, minute, intensity);
                    CharacterDevelopment.remember(survivor, eventId + ":memory", "bereavement", deceased.globalId,
                            "death of " + deceased.name, -intensity * 15, minute);
                }
                deceased.props.put("death.bereavement.cursor", cursor);
                recordCharacterMutation();
                if (cursor >= recipients.size()) {
                    deceased.props.put("death.bereavement.completed", true);
                    deceased.props.remove("death.bereavement.recipients");
                    deceased.props.remove("death.bereavement.cursor");
                    deceased.props.remove("death.bereavement.year");
                    pendingBereavement.removeFirst();
                    recordCharacterMutation();
                } else break;
            }
        }
        return processed;
    }

    public int pendingBereavementEvents() { return pendingBereavement.size(); }

    private static int relationshipScore(Character observer,String subjectGlobalId) {
        Object raw=observer.props.get("social.relationships");
        if(!(raw instanceof Map<?,?> relationships)) return 0;
        Object value=relationships.get(subjectGlobalId);
        return value instanceof Number n?Math.max(-100,Math.min(100,n.intValue())):0;
    }

    public List<Character> holders(String role, String place) {
        List<Character> out = new ArrayList<>();
        for (Character c : byId.values()) if (c.alive && c.has(role, place)) out.add(c);
        return out;
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
        x.spouse = y.id;
        y.spouse = x.id;
        x.props.put("family.spouse_global_id", y.globalId);
        y.props.put("family.spouse_global_id", x.globalId);
        emit(x, LifecycleKind.MARRIED, "", y.id, year);
        emit(y, LifecycleKind.MARRIED, "", x.id, year);
    }

    /** A deceased or missing spouse does not permanently prevent remarriage. */
    private void clearDeceasedSpouse(Character character) {
        if (character.spouse.isEmpty()) return;
        Character spouse = byId.get(character.spouse);
        if (spouse != null && spouse.alive) return;
        if (spouse != null && character.id.equals(spouse.spouse)) {
            spouse.spouse = "";
            spouse.props.remove("family.spouse_global_id");
        }
        character.spouse = "";
        character.props.remove("family.spouse_global_id");
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
        first.spouse = "";
        second.spouse = "";
        first.props.remove("family.spouse_global_id");
        second.props.remove("family.spouse_global_id");
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

    private Character require(String id) {
        Character c = byId.get(id);
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
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root) || !"wf-people-record/1".equals(root.get("format"))
                || !(root.get("character") instanceof Map<?, ?> rawCharacter))
            throw new IllegalArgumentException("Invalid incremental Character record");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("format", "wf-people/3");
        snapshot.put("schema_version", 3);
        snapshot.put("next", root.get("next") instanceof Number n ? n : next);
        snapshot.put("characters", List.of(rawCharacter));
        CharacterRegistry decoded = new CharacterRegistry();
        decoded.fromJson(Json.write(snapshot), identityNamespace);
        Character restored = decoded.all().iterator().next();
        Object restoredBindings = restored.props.get("population.entity_bindings");
        if (restoredBindings instanceof Map<?, ?> bindings) for (Object uuid : bindings.values())
            if (uuid instanceof String value) {
                String owner = entityOwnerByUuid.get(value);
                if (owner != null && !owner.equals(restored.id))
                    throw new IllegalArgumentException("Minecraft Entity UUID " + value + " is already owned by Character " + owner);
            }
        Character previous = byId.get(restored.id);
        if (previous != null) removeEntityBindings(previous);
        previous = byId.put(restored.id, restored);
        indexEntityBindings(restored);
        if (previous == null) ordered.add(restored);
        else {
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index) == previous) { ordered.set(index, restored); break; }
            }
        }
        if (restored.id.matches("c[0-9]+")) {
            try { next = Math.max(next, Math.addExact(Integer.parseInt(restored.id.substring(1)), 1)); }
            catch (NumberFormatException ignored) { /* non-numeric legacy id */ }
        }
        if (previous == null) markMembershipMutation();
        recordDomainMutation();
    }

    public String toJson() { return toJson(null); }

    /** Только перечисленные персонажи (null - все): для окон на клиенте, которым не нужен весь мир. */
    public String toJson(Set<String> only) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-people/3");
        root.put("schema_version", 3);
        root.put("next", next);
        List<Object> list = new ArrayList<>();
        for (Character c : byId.values()) {
            if (only != null && !only.contains(c.id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id); m.put("global_id", c.globalId); m.put("name", c.name); m.put("house", c.house); m.put("culture", c.culture); m.put("gender", c.gender);
            m.put("birth", c.birthYear); m.put("alive", c.alive); m.put("father", c.father); m.put("mother", c.mother); m.put("spouse", c.spouse);
            m.put("children", new ArrayList<Object>(c.children));
            List<Object> posts = new ArrayList<>();
            for (Character.Post p : c.posts) posts.add(new ArrayList<Object>(List.of(p.role, p.place)));
            m.put("posts", posts);
            m.put("traits", new ArrayList<Object>(c.traits));
            m.put("props", c.props);
            list.add(m);
        }
        root.put("characters", list);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) { fromJson(json, "legacy-unscoped"); }

    /** Loads old map-local saves and deterministically assigns stable IDs within the supplied world/map namespace. */
    public void fromJson(String json, String identityNamespace) {
        try (MutationBatch ignored = beginMutationBatch()) {
            fromJsonBatched(json, identityNamespace);
        }
    }

    private void fromJsonBatched(String json, String identityNamespace) {
        if (identityNamespace == null || identityNamespace.isBlank()) throw new IllegalArgumentException("identityNamespace is required");
        // Parse and validate into an isolated snapshot. Never destroy the live registry
        // until the entire save has been accepted.
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Повреждён корень сохранения людей");
        String format = raw.get("format") instanceof String f ? f : "";
        if (!"wf-people/1".equals(format) && !"wf-people/2".equals(format) && !"wf-people/3".equals(format))
            throw new IllegalArgumentException("Неверный формат людей: " + format);
        // wf-people/1 is migrated in memory without regenerating IDs, family links or existing props.
        // Missing Phase-VII extension fields are intentionally defaulted by their accessors.
        Object rawNext = raw.get("next");
        if (!(rawNext instanceof Number nextNumber)) throw new IllegalArgumentException("Отсутствует счётчик персонажей");
        long requestedNext = nextNumber.longValue();
        if (requestedNext < 1 || requestedNext > Integer.MAX_VALUE) throw new IllegalArgumentException("Недопустимый счётчик персонажей");
        if (!(raw.get("characters") instanceof List<?> entries)) throw new IllegalArgumentException("Отсутствует список персонажей");

        Map<String, Character> loaded = new LinkedHashMap<>();
        Map<String, String> globalOwners = new HashMap<>();
        Map<String, String> entityOwners = new HashMap<>();
        int maxNumericId = 0;
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("Повреждена запись персонажа");
            String id = requiredString(m, "id");
            String name = requiredString(m, "name");
            if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Пустой ID или имя персонажа");
            String globalId = optionalString(m, "global_id");
            if (globalId.isBlank()) {
                globalId = java.util.UUID.nameUUIDFromBytes((identityNamespace + "|" + id)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            } else {
                try { globalId = java.util.UUID.fromString(globalId).toString(); }
                catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректный global_id персонажа " + id, ex); }
            }
            String previousGlobalOwner = globalOwners.putIfAbsent(globalId, id);
            if (previousGlobalOwner != null)
                throw new IllegalArgumentException("Повторный global_id персонажа: " + globalId + " (" + previousGlobalOwner + ", " + id + ")");
            Character c = new Character(id, globalId, name);
            c.house = optionalString(m, "house");
            c.culture = optionalString(m, "culture");
            c.gender = optionalString(m, "gender");
            if (c.gender.isBlank()) c.gender = "m";
            Object birth = m.get("birth");
            if (!(birth instanceof Number birthNumber)) throw new IllegalArgumentException("Нет года рождения: " + id);
            c.birthYear = birthNumber.intValue();
            Object alive = m.get("alive");
            if (!(alive instanceof Boolean)) throw new IllegalArgumentException("Некорректный статус жизни персонажа " + id);
            c.alive = (Boolean) alive;
            c.father = optionalString(m, "father");
            c.mother = optionalString(m, "mother");
            c.spouse = optionalString(m, "spouse");

            readStringList(m.get("children"), c.children, "children", id);
            Object rawPosts = m.get("posts");
            if (!(rawPosts instanceof List<?> posts)) throw new IllegalArgumentException("Повреждены должности персонажа " + id);
            for (Object post : posts) {
                if (!(post instanceof List<?> pair) || pair.size() != 2
                        || !(pair.get(0) instanceof String role) || role.isBlank()
                        || !(pair.get(1) instanceof String place) || place.isBlank())
                    throw new IllegalArgumentException("Повреждена должность персонажа " + id);
                if (c.has(role, place)) throw new IllegalArgumentException("Повторная должность персонажа " + id);
                c.posts.add(new Character.Post(role, place));
            }
            Object rawTraits = m.get("traits");
            if (!(rawTraits instanceof List<?> traits)) throw new IllegalArgumentException("Повреждены черты персонажа " + id);
            Set<String> uniqueTraits = new HashSet<>();
            for (Object trait : traits) {
                if (!(trait instanceof String s) || s.isBlank() || !uniqueTraits.add(s))
                    throw new IllegalArgumentException("Повреждена или повторяется черта персонажа " + id);
                c.traits.add(s);
            }
            Object props = m.get("props");
            if (props != null) {
                if (!(props instanceof Map<?, ?> p)) throw new IllegalArgumentException("Повреждены свойства персонажа " + id);
                for (Map.Entry<?, ?> entry : p.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Некорректный ключ свойства " + id);
                    c.props.put(key, entry.getValue());
                }
            }
            Object bindingsRaw = c.props.get("population.entity_bindings");
            if (bindingsRaw != null && !(bindingsRaw instanceof Map<?, ?>))
                throw new IllegalArgumentException("Некорректная таблица привязок Minecraft Entity персонажа " + id);
            if (bindingsRaw instanceof Map<?, ?> bindings) {
                for (Object bindingValue : bindings.values()) {
                    if (!(bindingValue instanceof String uuid) || uuid.isBlank())
                        throw new IllegalArgumentException("Повреждена привязка Minecraft Entity персонажа " + id);
                    String previousOwner = entityOwners.putIfAbsent(uuid, id);
                    if (previousOwner != null && !previousOwner.equals(id))
                        throw new IllegalArgumentException("Minecraft Entity UUID " + uuid + " привязан к двум персонажам: " + previousOwner + " и " + id);
                }
            }
            if (loaded.putIfAbsent(id, c) != null) throw new IllegalArgumentException("Повторный ID персонажа: " + id);
            if (id.matches("c[0-9]+")) {
                try { maxNumericId = Math.max(maxNumericId, Integer.parseInt(id.substring(1))); }
                catch (NumberFormatException ignored) { /* non-allocatable legacy identifier */ }
            }
        }
        if (maxNumericId == Integer.MAX_VALUE) throw new IllegalArgumentException("Исчерпаны ID персонажей");
        int safeNext = (int) Math.max(requestedNext, (long) maxNumericId + 1L);
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
        byId.clear();
        byId.putAll(loaded);
        byGlobalId.clear();
        pendingBereavement.clear();
        for (Character character : loaded.values()) {
            byGlobalId.put(character.globalId, character);
            if (!Boolean.TRUE.equals(character.props.get("death.bereavement.completed"))
                    && character.props.get("death.bereavement.recipients") instanceof List<?>)
                pendingBereavement.addLast(character.globalId);
        }
        entityOwnerByUuid.clear();
        entityOwnerByUuid.putAll(entityOwners);
        ordered.clear();
        ordered.addAll(loaded.values());
        membershipGeneration++;
        next = safeNext;
        markMembershipMutation();
        recordDomainMutation();
        for (Character character : byId.values()) {
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
