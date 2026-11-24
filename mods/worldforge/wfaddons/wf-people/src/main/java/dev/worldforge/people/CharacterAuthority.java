package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** World-wide authority over map-local persistence partitions and immutable global person IDs. */
public final class CharacterAuthority {
    private final Map<String, CharacterIdentity> byGlobalId = new ConcurrentHashMap<>();
    private final Map<String, Set<CharacterIdentity>> byLegacyId = new ConcurrentHashMap<>();
    /** Family reverse indexes: estate succession must never scan every living Character per death. */
    /** Canonical undirected parent/child adjacency and directed spouse reverse projection. */
    private Map<String, Set<String>> familyGlobal = new HashMap<>();
    private Map<String, Set<String>> familyBySpouseGlobal = new HashMap<>();
    private final Map<String, CharacterRegistry> registries = new ConcurrentHashMap<>();
    private final Map<String, Long> indexedMembershipGenerations = new HashMap<>();
    private final Map<CharacterRegistry, String> registryMapIds = new IdentityHashMap<>();
    /** Immutable sorted identity snapshot reused by estate/succession queries until membership changes. */
    private List<CharacterIdentity> sortedIdentityCache;
    private long familyIndexDay = Long.MIN_VALUE;
    private volatile WorldId worldId;

    public synchronized void beginWorld(WorldId id) {
        WorldId requested = Objects.requireNonNull(id, "worldId");
        // Forge may announce the same save at more than one lifecycle phase.
        // Re-entering the same world must not detach or discard already registered partitions.
        if (requested.equals(worldId)) return;
        clear();
        worldId = requested;
    }

    public synchronized void clear() {
        // Detach every persistence partition before dropping the authority's references.
        // A registry may be reused by a newly created authority after server/world shutdown.
        for (CharacterRegistry registry : registries.values()) registry.unbindAuthority(this);
        registries.clear();
        registryMapIds.clear();
        byGlobalId.clear();
        byLegacyId.clear();
        familyGlobal.clear();
        familyBySpouseGlobal.clear();
        indexedMembershipGenerations.clear();
        sortedIdentityCache = null;
        familyIndexDay = Long.MIN_VALUE;
        worldId = null;
    }

    public WorldId worldId() { return worldId; }

    public synchronized void registerMap(String mapId, CharacterRegistry registry) {
        if (worldId == null) throw new IllegalStateException("World identity has not been initialized");
        if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("mapId is required");
        Objects.requireNonNull(registry, "registry");
        String key = mapId.trim();
        for (Map.Entry<String, CharacterRegistry> entry : registries.entrySet()) {
            if (entry.getValue() == registry && !entry.getKey().equals(key))
                throw new IllegalStateException("CharacterRegistry is already registered as map " + entry.getKey());
        }
        CharacterRegistry previous = registries.get(key);
        if (previous != null) {
            if (previous != registry)
                throw new IllegalStateException("A different CharacterRegistry is already registered for map " + key);
            refreshIndex();
            return;
        }

        // Bind first, then publish. If the registry belongs to another world authority,
        // bindAuthority fails without leaving a rejected partition in this authority.
        registry.bindAuthority(this);
        registries.put(key, registry);
        registryMapIds.put(registry, key);
        try {
            refreshIndex();
        } catch (RuntimeException ex) {
            // A rejected partition must not poison the live authority or discard its last valid index.
            registries.remove(key, registry);
            registryMapIds.remove(registry);
            registry.unbindAuthority(this);
            throw ex;
        }
    }

    /**
     * Incrementally publishes one append-only Character. Registry revision changes on every
     * append, but rebuilding every global/family index for each append made a population
     * creation run quadratic. Full rebuilds are now reserved for wholesale registry restore.
     */
    synchronized void characterCreated(CharacterRegistry registry, Character character) {
        String mapId = registryMapIds.get(registry);
        WorldId currentWorld = worldId;
        if (mapId == null || currentWorld == null) return;
        CharacterIdentity identity = new CharacterIdentity(currentWorld, mapId, character.id, character.globalId);
        CharacterIdentity existingGlobal = byGlobalId.putIfAbsent(character.globalId, identity);
        if (existingGlobal != null)
            throw new IllegalStateException("Duplicate global Character UUID " + character.globalId
                    + " between " + existingGlobal.mapId() + "/" + existingGlobal.characterId()
                    + " and " + mapId + "/" + character.id);
        byLegacyId.computeIfAbsent(character.id, ignored -> new LinkedHashSet<>()).add(identity);
        indexedMembershipGenerations.put(mapId, registry.membershipGeneration());
        sortedIdentityCache = null;
    }

    /**
     * Rebinds the global authority after an incremental record replaces an existing
     * canonical Character. This is O(1) average and avoids leaving stale object references
     * in the world-wide identity map until a later full registry restore.
     */
    synchronized void characterReplaced(CharacterRegistry registry, Character previous, Character replacement) {
        String mapId = registryMapIds.get(registry);
        WorldId currentWorld = worldId;
        if (mapId == null || currentWorld == null) return;
        if (previous != null) {
            CharacterIdentity oldIdentity = new CharacterIdentity(
                    currentWorld, mapId, previous.id, previous.globalId);
            if (!previous.globalId.equals(replacement.globalId)) {
                byGlobalId.remove(previous.globalId, oldIdentity);
                Set<CharacterIdentity> legacy = byLegacyId.get(previous.id);
                if (legacy != null) {
                    legacy.remove(oldIdentity);
                    if (legacy.isEmpty()) byLegacyId.remove(previous.id, legacy);
                }
            }
        }
        CharacterIdentity identity = new CharacterIdentity(
                currentWorld, mapId, replacement.id, replacement.globalId);
        CharacterIdentity globalOwner = byGlobalId.putIfAbsent(replacement.globalId, identity);
        if (globalOwner != null && !globalOwner.equals(identity))
            throw new IllegalStateException("Global Character UUID collision " + replacement.globalId
                    + " between " + globalOwner.mapId() + "/" + globalOwner.characterId()
                    + " and " + mapId + "/" + replacement.id);

        byLegacyId.computeIfAbsent(replacement.id, ignored -> new LinkedHashSet<>()).add(identity);
        indexedMembershipGenerations.put(mapId, registry.membershipGeneration());
        sortedIdentityCache = null;
        // The reverse family projection is day-scoped and may contain edges from the old record.
        familyIndexDay = Long.MIN_VALUE;
    }

    /** Updates parent and spouse reverse projections after a canonical family mutation. */
    synchronized void familyChanged(Character person) {
        familyChanged(person, "", "");
    }

    /**
     * Applies a spouse-edge delta in O(1) average. The previous edge is removed before the
     * current one is published, so marriage, divorce and remarriage never require a world scan.
     */
    synchronized void familyChanged(Character person, String previousSpouseLegacy, String previousSpouseGlobal) {
        if (person == null) return;
        CharacterIdentity identity = byGlobalId.get(person.globalId);
        if (identity == null) return;
        String fatherGlobal = familyId(person, "family.father_global_id");
        String motherGlobal = familyId(person, "family.mother_global_id");
        if (fatherGlobal.isBlank()) fatherGlobal = legacyGlobalId(person.father, identity.mapId());
        if (motherGlobal.isBlank()) motherGlobal = legacyGlobalId(person.mother, identity.mapId());
        addFamilyEdge(familyGlobal, fatherGlobal, person.globalId);
        addFamilyEdge(familyGlobal, motherGlobal, person.globalId);

        String oldSpouseGlobal = previousSpouseGlobal == null || previousSpouseGlobal.isBlank()
                ? legacyGlobalId(previousSpouseLegacy, identity.mapId()) : previousSpouseGlobal;
        String currentSpouseGlobal = familyId(person, "family.spouse_global_id");
        if (currentSpouseGlobal.isBlank()) currentSpouseGlobal = legacyGlobalId(person.spouse, identity.mapId());
        removeFamilyIndex(familyBySpouseGlobal, oldSpouseGlobal, person.globalId);
        addFamilyIndex(familyBySpouseGlobal, currentSpouseGlobal, identity);
    }

    private static void removeFamilyIndex(Map<String, Set<String>> index, String key, String globalId) {
        if (key == null || key.isBlank() || globalId == null || globalId.isBlank()) return;
        Set<String> values = index.get(key);
        if (values == null) return;
        values.remove(globalId);
        if (values.isEmpty()) index.remove(key, values);
    }

    /**
     * Rebuild into temporary indexes and publish them only after every partition validates.
     * This is deliberately transactional: a late collision must never leave a half-empty
     * authority that appears current because its revision snapshot was already advanced.
     */
    private void refreshIndex() {
        WorldId currentWorld = worldId;
        if (currentWorld == null) return;

        // A wholesale restore is partition-local. Validate the incoming partition first,
        // then replace only its identity projection. Never build a second world-sized
        // (or partition-sized) collection of CharacterIdentity objects just to publish it.
        List<Map.Entry<String, CharacterRegistry>> changed = new ArrayList<>();
        for (Map.Entry<String, CharacterRegistry> entry : registries.entrySet()) {
            Long indexed = indexedMembershipGenerations.get(entry.getKey());
            if (indexed == null || indexed.longValue() != entry.getValue().membershipGeneration())
                changed.add(entry);
        }
        if (changed.isEmpty() && indexedMembershipGenerations.size() == registries.size()) return;

        // Pass 1: validate collisions without allocating a shadow identity map.
        // CharacterRegistry validates duplicate local IDs and UUIDs while decoding a
        // snapshot; incremental WAL replacement validates against its current UUID index.
        for (Map.Entry<String, CharacterRegistry> entry : changed) {
            String mapId = entry.getKey();
            for (Character character : entry.getValue().all()) {
                CharacterIdentity owner = byGlobalId.get(character.globalId);
                if (owner != null && !owner.mapId().equals(mapId))
                    throw new IllegalStateException("Global Character UUID collision " + character.globalId
                            + " between " + owner.mapId() + "/" + owner.characterId()
                            + " and " + mapId + "/" + character.id);
            }
        }

        // Pass 2: remove and republish only changed partitions. Unchanged partitions'
        // hash buckets, family state and UUID references are not copied or reallocated.
        for (Map.Entry<String, CharacterRegistry> entry : changed) {
            String mapId = entry.getKey();
            Iterator<Map.Entry<String, CharacterIdentity>> existing = byGlobalId.entrySet().iterator();
            while (existing.hasNext()) {
                CharacterIdentity identity = existing.next().getValue();
                if (!identity.mapId().equals(mapId)) continue;
                existing.remove();
                Set<CharacterIdentity> legacy = byLegacyId.get(identity.characterId());
                if (legacy != null) {
                    legacy.remove(identity);
                    if (legacy.isEmpty()) byLegacyId.remove(identity.characterId(), legacy);
                }
            }

            CharacterRegistry registry = entry.getValue();
            for (Character character : registry.all()) {
                CharacterIdentity identity = new CharacterIdentity(
                        currentWorld, mapId, character.id, character.globalId);
                CharacterIdentity collision = byGlobalId.putIfAbsent(identity.globalCharacterId(), identity);
                if (collision != null && !collision.equals(identity))
                    throw new IllegalStateException("Global Character UUID collision " + identity.globalCharacterId());
                byLegacyId.computeIfAbsent(identity.characterId(), ignored -> new LinkedHashSet<>()).add(identity);
            }
            indexedMembershipGenerations.put(mapId, registry.membershipGeneration());
        }

        indexedMembershipGenerations.keySet().removeIf(mapId -> !registries.containsKey(mapId));
        sortedIdentityCache = null;
        familyIndexDay = Long.MIN_VALUE;
    }

    /** Resolves an identity through its owning partition; the authority stores keys, not Character objects. */
    private Character resolve(CharacterIdentity identity) {
        if (identity == null || !identity.equals(byGlobalId.get(identity.globalCharacterId()))) return null;
        CharacterRegistry owner = registries.get(identity.mapId());
        return owner == null ? null : owner.get(identity.characterId());
    }

    public synchronized Character get(CharacterIdentity identity) {
        refreshIndex();
        return resolve(identity);
    }

    public synchronized Character getGlobal(String globalCharacterId) {
        if (globalCharacterId == null || globalCharacterId.isBlank()) return null;
        // The world index is a batch projection. During Genesis, characters are appended
        // in bounded batches and immediately attached to settlements. Rebuilding every
        // world-wide identity/family index for each newly appended resident turns that
        // materialization loop into quadratic work and can stall the server for minutes.
        // Existing indexed IDs are verified against their owning append-only partition;
        // newly appended IDs use the registry's O(1) local UUID map.
        CharacterIdentity identity = byGlobalId.get(globalCharacterId);
        if (identity != null) {
            CharacterRegistry owner = registries.get(identity.mapId());
            Character indexed = owner == null ? null : owner.getByGlobalId(globalCharacterId);
            if (indexed != null) return indexed;
        }
        for (CharacterRegistry registry : registries.values()) {
            Character person = registry.getByGlobalId(globalCharacterId);
            if (person != null) return person;
        }
        return null;
    }

    public synchronized CharacterIdentity identityOf(String globalCharacterId) {
        refreshIndex();
        return byGlobalId.get(globalCharacterId);
    }

    public synchronized CharacterRegistry registryForGlobal(String globalCharacterId) {
        if (globalCharacterId == null || globalCharacterId.isBlank()) return null;
        CharacterIdentity identity = byGlobalId.get(globalCharacterId);
        if (identity != null) {
            CharacterRegistry owner = registries.get(identity.mapId());
            if (owner != null && owner.getByGlobalId(globalCharacterId) != null) return owner;
        }
        // Do not force a global index rebuild for an ID that may have been appended
        // moments ago by the same bounded Genesis materialization batch.
        for (CharacterRegistry registry : registries.values()) {
            if (registry.getByGlobalId(globalCharacterId) != null) return registry;
        }
        return null;
    }

    /**
     * Resolves the canonical Character UUID bound to a physical entity without scanning
     * materialized Characters or building the sorted world identity snapshot.
     *
     * Cost is proportional to the number of registered partitions, not population size.
     * A null result means no partition currently owns this entity binding.
     */
    public synchronized String globalIdForEntityBinding(String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) return null;
        String match = null;
        for (CharacterRegistry registry : registries.values()) {
            String owner = registry.entityBindingOwner(entityUuid);
            if (owner == null) continue;
            if (match != null && !match.equals(owner))
                throw new IllegalStateException("Physical entity UUID is bound to multiple Characters: " + entityUuid);
            match = owner;
        }
        return match;
    }

    /**
     * Binds a physical Minecraft body to its canonical person while enforcing entity UUID
     * uniqueness across every map partition in this world, not just the owning map.
     */
    public synchronized boolean bindEntity(String globalCharacterId, String bodyKind, String entityUuid) {
        if (globalCharacterId == null || globalCharacterId.isBlank())
            throw new IllegalArgumentException("globalCharacterId is required");
        if (bodyKind == null || bodyKind.isBlank()) throw new IllegalArgumentException("bodyKind is required");
        if (entityUuid == null || entityUuid.isBlank()) throw new IllegalArgumentException("entityUuid is required");
        refreshIndex();
        CharacterIdentity identity = byGlobalId.get(globalCharacterId);
        if (identity == null) return false;
        CharacterRegistry owner = registries.get(identity.mapId());
        Character person = owner == null ? null : owner.get(identity.characterId());
        if (person == null || owner == null || !person.alive) return false;

        // Check one reverse-index entry per partition, independent of population size.
        for (CharacterRegistry registry : registries.values()) {
            String existingOwner = registry.entityBindingOwner(entityUuid);
            if (existingOwner != null && !globalCharacterId.equals(existingOwner)) return false;
        }
        return owner.bindEntity(person.id, bodyKind, entityUuid);
    }

    /** Returns null for an ambiguous legacy cN id; callers must provide map identity. */
    public synchronized Character getLegacy(String characterId) {
        refreshIndex();
        Set<CharacterIdentity> matches = byLegacyId.get(characterId);
        if (matches == null || matches.size() != 1) return null;
        CharacterIdentity identity = matches.iterator().next();
        CharacterRegistry owner = registries.get(identity.mapId());
        return owner == null ? null : owner.get(identity.characterId());
    }

    public synchronized CharacterRegistry registry(String mapId) { return registries.get(mapId); }

    public synchronized Collection<CharacterIdentity> identities() {
        refreshIndex();
        if (sortedIdentityCache == null) {
            List<CharacterIdentity> result = new ArrayList<>(byGlobalId.values());
            result.sort(Comparator.comparing((CharacterIdentity id) -> id.worldId().toString())
                    .thenComparing(CharacterIdentity::mapId)
                    .thenComparing(CharacterIdentity::globalCharacterId));
            sortedIdentityCache = Collections.unmodifiableList(result);
        }
        return sortedIdentityCache;
    }

    /**
     * Refreshes family edges once before a yearly lifecycle pass. Family links can be updated
     * directly by legacy-compatible systems, so membership revisions alone are not sufficient
     * to keep this projection current. Never call this from an individual death/estate lookup.
     */
    public synchronized void rebuildFamilyIndex(long day) {
        refreshIndex();
        if (familyIndexDay == day) return;
        Map<String, Set<String>> parentChildGlobal = new HashMap<>();
        Map<String, Set<String>> spouseGlobal = new HashMap<>();

        for (CharacterIdentity identity : byGlobalId.values()) {
            CharacterRegistry owner = registries.get(identity.mapId());
            Character person = owner == null ? null : owner.get(identity.characterId());
            if (person == null) continue;
            String mapId = identity.mapId();
            String fatherGlobal = familyId(person, "family.father_global_id");
            String motherGlobal = familyId(person, "family.mother_global_id");
            String spouseGlobalId = familyId(person, "family.spouse_global_id");

            // Upgrade map-local legacy pointers to canonical UUID edges while the owning
            // partition is known. Ambiguous cN IDs in other maps are never consulted.
            if (fatherGlobal.isBlank()) fatherGlobal = legacyGlobalId(person.father, mapId);
            if (motherGlobal.isBlank()) motherGlobal = legacyGlobalId(person.mother, mapId);
            if (spouseGlobalId.isBlank()) spouseGlobalId = legacyGlobalId(person.spouse, mapId);
            addFamilyEdge(parentChildGlobal, fatherGlobal, person.globalId);
            addFamilyEdge(parentChildGlobal, motherGlobal, person.globalId);

            for (String childId : person.children)
                addFamilyEdge(parentChildGlobal, person.globalId, legacyGlobalId(childId, mapId));
            Object globalChildren = person.props.get("family.children_global_ids");
            if (globalChildren instanceof List<?> ids) for (Object rawId : ids) {
                if (rawId instanceof String childId && !childId.isBlank())
                    addFamilyEdge(parentChildGlobal, person.globalId, childId);
            }
            addFamilyIndex(spouseGlobal, spouseGlobalId, identity);
        }
        familyGlobal = parentChildGlobal;
        familyBySpouseGlobal = spouseGlobal;
        familyIndexDay = day;
    }

    /**
     * Returns only possible relatives of a Character, preserving local-registry priority.
     * The index is rebuilt transactionally with registry membership changes, not per death.
     */
    public synchronized List<Character> kinCandidates(Character person) {
        // Membership-generation checks are O(partitions): ordinary appends are already
        // indexed, while wholesale restore safely triggers one transactional rebuild.
        refreshIndex();
        if (person == null) return List.of();
        LinkedHashSet<CharacterIdentity> matches = new LinkedHashSet<>();
        String fatherGlobal = familyId(person, "family.father_global_id");
        String motherGlobal = familyId(person, "family.mother_global_id");
        String spouseGlobal = familyId(person, "family.spouse_global_id");
        boolean hasGlobalChildren = person.props.get("family.children_global_ids") instanceof List<?>;

        // Global UUID links are authoritative. Legacy cN IDs are map-local and can collide
        // across partitions; consult their reverse indexes only when the corresponding
        // canonical link is absent, otherwise unrelated people can become false relatives.
        CharacterIdentity personIdentity = byGlobalId.get(person.globalId);
        String localMap = personIdentity == null ? "" : personIdentity.mapId();
        if (fatherGlobal.isBlank()) fatherGlobal = legacyGlobalId(person.father, localMap);
        if (motherGlobal.isBlank()) motherGlobal = legacyGlobalId(person.mother, localMap);
        if (!fatherGlobal.isBlank()) {
            addFamilyMatches(matches, familyGlobal, fatherGlobal);
            addGlobalIdentityMatch(matches, fatherGlobal);
        }
        if (!motherGlobal.isBlank()) {
            addFamilyMatches(matches, familyGlobal, motherGlobal);
            addGlobalIdentityMatch(matches, motherGlobal);
        }

        // Undirected canonical adjacency covers parents, children and siblings.
        addFamilyMatches(matches, familyGlobal, person.globalId);
        if (hasGlobalChildren) {
            Object globalChildren = person.props.get("family.children_global_ids");
            for (Object rawId : (List<?>) globalChildren) if (rawId instanceof String id)
                addGlobalIdentityMatch(matches, id);
        }
        for (String childId : person.children)
            addLegacyIdentityMatchLocal(matches, childId, localMap);

        if (spouseGlobal.isBlank()) spouseGlobal = legacyGlobalId(person.spouse, localMap);
        if (!spouseGlobal.isBlank()) {
            addFamilyMatches(matches, familyBySpouseGlobal, spouseGlobal);
            addGlobalIdentityMatch(matches, spouseGlobal);
        }
        // A one-sided spouse pointer may exist in old saves even when this Character has
        // no spouse field of its own. Preserve that reverse relationship by canonical UUID.
        addFamilyMatches(matches, familyBySpouseGlobal, person.globalId);

        List<CharacterIdentity> ordered = new ArrayList<>(matches);
        ordered.removeIf(identity -> !identity.equals(byGlobalId.get(identity.globalCharacterId())));
        ordered.sort(Comparator
                .comparingInt((CharacterIdentity identity) -> identity.mapId().equals(localMap) ? 0 : 1)
                .thenComparing(CharacterIdentity::mapId)
                .thenComparingInt(identity -> legacySequence(identity.characterId()))
                .thenComparing(CharacterIdentity::globalCharacterId));
        List<Character> result = new ArrayList<>(ordered.size());
        for (CharacterIdentity identity : ordered) {
            CharacterRegistry ownerRegistry = registries.get(identity.mapId());
            Character candidate = ownerRegistry == null ? null : ownerRegistry.get(identity.characterId());
            if (candidate != null) result.add(candidate);
        }
        return Collections.unmodifiableList(result);
    }

    private void addLegacyIdentityMatchLocal(Set<CharacterIdentity> target, String legacyId, String mapId) {
        CharacterIdentity identity = legacyIdentity(legacyId, mapId);
        if (identity != null) target.add(identity);
    }

    private CharacterIdentity legacyIdentity(String legacyId, String mapId) {
        if (legacyId == null || legacyId.isBlank() || mapId == null || mapId.isBlank()) return null;
        Set<CharacterIdentity> found = byLegacyId.get(legacyId);
        if (found == null) return null;
        for (CharacterIdentity identity : found)
            if (mapId.equals(identity.mapId())) return identity;
        return null;
    }

    private String legacyGlobalId(String legacyId, String mapId) {
        CharacterIdentity identity = legacyIdentity(legacyId, mapId);
        return identity == null ? "" : identity.globalCharacterId();
    }

    private void addGlobalIdentityMatch(Set<CharacterIdentity> target, String globalId) {
        if (globalId == null || globalId.isBlank()) return;
        CharacterIdentity identity = byGlobalId.get(globalId);
        if (identity != null) target.add(identity);
    }

    private static int legacySequence(String id) {
        if (id != null && id.length() > 1 && id.charAt(0) == 'c') {
            try { return Integer.parseInt(id.substring(1)); }
            catch (NumberFormatException ignored) { }
        }
        return Integer.MAX_VALUE;
    }

    private static String familyId(Character person, String key) {
        Object value = person.props.get(key);
        return value instanceof String id && !id.isBlank() ? id : "";
    }

    private static void addFamilyEdge(Map<String, Set<String>> index, String first, String second) {
        if (first == null || first.isBlank() || second == null || second.isBlank() || first.equals(second)) return;
        index.computeIfAbsent(first, ignored -> new LinkedHashSet<>()).add(second);
        index.computeIfAbsent(second, ignored -> new LinkedHashSet<>()).add(first);
    }

    /** Family projections store canonical global IDs instead of repeated identity objects. */
    private static void addFamilyIndex(Map<String, Set<String>> index, String key, CharacterIdentity identity) {
        if (key != null && !key.isBlank() && identity != null)
            index.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(identity.globalCharacterId());
    }

    private static void addIdentityIds(Set<String> target, Set<CharacterIdentity> identities) {
        for (CharacterIdentity identity : identities) target.add(identity.globalCharacterId());
    }

    private void addFamilyMatches(Set<CharacterIdentity> target, Map<String, Set<String>> index, String key) {
        if (key == null || key.isBlank()) return;
        Set<String> found = index.get(key);
        if (found == null) return;
        for (String globalId : found) {
            CharacterIdentity identity = byGlobalId.get(globalId);
            if (identity != null) target.add(identity);
        }
    }

    public synchronized int size() { refreshIndex(); return byGlobalId.size(); }
    public synchronized int mapCount() { return registries.size(); }
}
