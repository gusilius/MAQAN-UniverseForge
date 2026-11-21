package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** World-wide authority over map-local persistence partitions and immutable global person IDs. */
public final class CharacterAuthority {
    private final Map<CharacterIdentity, Character> byIdentity = new ConcurrentHashMap<>();
    private final Map<String, CharacterIdentity> byGlobalId = new ConcurrentHashMap<>();
    private final Map<String, Set<CharacterIdentity>> byLegacyId = new ConcurrentHashMap<>();
    /** Family reverse indexes: estate succession must never scan every living Character per death. */
    private Map<String, Set<CharacterIdentity>> familyByParentGlobal = new HashMap<>();
    private Map<String, Set<CharacterIdentity>> familyByParentLegacy = new HashMap<>();
    private Map<String, Set<CharacterIdentity>> familyByChildGlobal = new HashMap<>();
    private Map<String, Set<CharacterIdentity>> familyByChildLegacy = new HashMap<>();
    private Map<String, Set<CharacterIdentity>> familyBySpouseGlobal = new HashMap<>();
    private Map<String, Set<CharacterIdentity>> familyBySpouseLegacy = new HashMap<>();
    private final Map<String, CharacterRegistry> registries = new ConcurrentHashMap<>();
    private final Map<String, Long> indexedRevisions = new HashMap<>();
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
        byIdentity.clear();
        byGlobalId.clear();
        byLegacyId.clear();
        familyByParentGlobal.clear();
        familyByParentLegacy.clear();
        familyByChildGlobal.clear();
        familyByChildLegacy.clear();
        familyBySpouseGlobal.clear();
        familyBySpouseLegacy.clear();
        indexedRevisions.clear();
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
        try {
            refreshIndex();
        } catch (RuntimeException ex) {
            // A rejected partition must not poison the live authority or discard its last valid index.
            registries.remove(key, registry);
            registry.unbindAuthority(this);
            throw ex;
        }
    }

    /**
     * Rebuild into temporary indexes and publish them only after every partition validates.
     * This is deliberately transactional: a late collision must never leave a half-empty
     * authority that appears current because its revision snapshot was already advanced.
     */
    private void refreshIndex() {
        WorldId currentWorld = worldId;
        if (currentWorld == null) return;
        boolean changed = indexedRevisions.size() != registries.size();
        if (!changed) for (Map.Entry<String, CharacterRegistry> entry : registries.entrySet()) {
            if (!Objects.equals(indexedRevisions.get(entry.getKey()), entry.getValue().revision())) {
                changed = true;
                break;
            }
        }
        if (!changed) return;

        Map<CharacterIdentity, Character> nextByIdentity = new HashMap<>();
        Map<String, CharacterIdentity> nextByGlobalId = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextByLegacyId = new HashMap<>();
        Map<String, Long> nextRevisions = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyByParentGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyByParentLegacy = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyByChildGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyByChildLegacy = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyBySpouseGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> nextFamilyBySpouseLegacy = new HashMap<>();

        for (Map.Entry<String, CharacterRegistry> entry : registries.entrySet()) {
            String mapId = entry.getKey();
            CharacterRegistry registry = entry.getValue();
            nextRevisions.put(mapId, registry.revision());
            for (Character character : registry.all()) {
                CharacterIdentity identity = new CharacterIdentity(
                        currentWorld, mapId, character.id, character.globalId);
                CharacterIdentity globalOwner = nextByGlobalId.putIfAbsent(character.globalId, identity);
                if (globalOwner != null && !globalOwner.equals(identity))
                    throw new IllegalStateException("Global Character UUID collision " + character.globalId
                            + " between " + globalOwner.mapId() + "/" + globalOwner.characterId()
                            + " and " + mapId + "/" + character.id);
                Character existing = nextByIdentity.putIfAbsent(identity, character);
                if (existing != null && existing != character)
                    throw new IllegalStateException("Duplicate Character identity " + identity);
                nextByLegacyId.computeIfAbsent(character.id, ignored -> new HashSet<>()).add(identity);
            }
        }

        // Build relationship indexes from the same validated population snapshot.
        // Parent fields and child lists are both indexed because old saves may contain only one side.
        for (Map.Entry<CharacterIdentity, Character> entry : nextByIdentity.entrySet()) {
            CharacterIdentity identity = entry.getKey();
            Character person = entry.getValue();
            addFamilyIndex(nextFamilyByParentLegacy, person.father, identity);
            addFamilyIndex(nextFamilyByParentLegacy, person.mother, identity);
            addFamilyIndex(nextFamilyByParentGlobal, familyId(person, "family.father_global_id"), identity);
            addFamilyIndex(nextFamilyByParentGlobal, familyId(person, "family.mother_global_id"), identity);
            addFamilyIndex(nextFamilyBySpouseLegacy, person.spouse, identity);
            addFamilyIndex(nextFamilyBySpouseGlobal, familyId(person, "family.spouse_global_id"), identity);

            for (String childId : person.children) {
                Set<CharacterIdentity> children = nextByLegacyId.get(childId);
                if (children != null) for (CharacterIdentity child : children) {
                    addFamilyIndex(nextFamilyByParentLegacy, person.id, child);
                    addFamilyIndex(nextFamilyByParentGlobal, person.globalId, child);
                    addFamilyIndex(nextFamilyByChildLegacy, childId, identity);
                    addFamilyIndex(nextFamilyByChildGlobal, child.globalCharacterId(), identity);
                }
            }
            Object globalChildren = person.props.get("family.children_global_ids");
            if (globalChildren instanceof List<?> ids) for (Object rawId : ids) {
                if (!(rawId instanceof String childId) || childId.isBlank()) continue;
                CharacterIdentity child = nextByGlobalId.get(childId);
                if (child == null) continue;
                addFamilyIndex(nextFamilyByParentLegacy, person.id, child);
                addFamilyIndex(nextFamilyByParentGlobal, person.globalId, child);
                addFamilyIndex(nextFamilyByChildLegacy, nextByIdentity.get(child).id, identity);
                addFamilyIndex(nextFamilyByChildGlobal, childId, identity);
            }
        }

        // Publish only a completely validated snapshot.
        byIdentity.clear();
        byIdentity.putAll(nextByIdentity);
        byGlobalId.clear();
        byGlobalId.putAll(nextByGlobalId);
        byLegacyId.clear();
        for (Map.Entry<String, Set<CharacterIdentity>> entry : nextByLegacyId.entrySet())
            byLegacyId.put(entry.getKey(), new HashSet<>(entry.getValue()));
        familyByParentGlobal = nextFamilyByParentGlobal;
        familyByParentLegacy = nextFamilyByParentLegacy;
        familyByChildGlobal = nextFamilyByChildGlobal;
        familyByChildLegacy = nextFamilyByChildLegacy;
        familyBySpouseGlobal = nextFamilyBySpouseGlobal;
        familyBySpouseLegacy = nextFamilyBySpouseLegacy;
        indexedRevisions.clear();
        indexedRevisions.putAll(nextRevisions);
        sortedIdentityCache = null;
        familyIndexDay = Long.MIN_VALUE;
    }

    public synchronized Character get(CharacterIdentity identity) {
        refreshIndex();
        return identity == null ? null : byIdentity.get(identity);
    }

    public synchronized Character getGlobal(String globalCharacterId) {
        refreshIndex();
        CharacterIdentity identity = byGlobalId.get(globalCharacterId);
        return identity == null ? null : byIdentity.get(identity);
    }

    public synchronized CharacterIdentity identityOf(String globalCharacterId) {
        refreshIndex();
        return byGlobalId.get(globalCharacterId);
    }

    public synchronized CharacterRegistry registryForGlobal(String globalCharacterId) {
        refreshIndex();
        CharacterIdentity identity = byGlobalId.get(globalCharacterId);
        return identity == null ? null : registries.get(identity.mapId());
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
        Character person = byIdentity.get(identity);
        CharacterRegistry owner = registries.get(identity.mapId());
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
        return byIdentity.get(matches.iterator().next());
    }

    public synchronized CharacterRegistry registry(String mapId) { return registries.get(mapId); }

    public synchronized Collection<CharacterIdentity> identities() {
        refreshIndex();
        if (sortedIdentityCache == null) {
            List<CharacterIdentity> result = new ArrayList<>(byIdentity.keySet());
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
        Map<String, Set<CharacterIdentity>> parentGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> parentLegacy = new HashMap<>();
        Map<String, Set<CharacterIdentity>> childGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> childLegacy = new HashMap<>();
        Map<String, Set<CharacterIdentity>> spouseGlobal = new HashMap<>();
        Map<String, Set<CharacterIdentity>> spouseLegacy = new HashMap<>();

        for (Map.Entry<CharacterIdentity, Character> entry : byIdentity.entrySet()) {
            CharacterIdentity identity = entry.getKey();
            Character person = entry.getValue();
            String fatherGlobal = familyId(person, "family.father_global_id");
            String motherGlobal = familyId(person, "family.mother_global_id");
            addFamilyIndex(parentLegacy, person.father, identity);
            addFamilyIndex(parentLegacy, person.mother, identity);
            addFamilyIndex(parentGlobal, fatherGlobal, identity);
            addFamilyIndex(parentGlobal, motherGlobal, identity);
            addFamilyIndex(spouseLegacy, person.spouse, identity);
            addFamilyIndex(spouseGlobal, familyId(person, "family.spouse_global_id"), identity);

            // Parent-side pointers are authoritative even in older saves that lack child lists.
            Set<CharacterIdentity> fathers = byLegacyId.get(person.father);
            if (fathers != null) childLegacy.computeIfAbsent(person.id, ignored -> new LinkedHashSet<>()).addAll(fathers);
            Set<CharacterIdentity> mothers = byLegacyId.get(person.mother);
            if (mothers != null) childLegacy.computeIfAbsent(person.id, ignored -> new LinkedHashSet<>()).addAll(mothers);
            CharacterIdentity fatherIdentity = byGlobalId.get(fatherGlobal);
            if (fatherIdentity != null) addFamilyIndex(childGlobal, person.globalId, fatherIdentity);
            CharacterIdentity motherIdentity = byGlobalId.get(motherGlobal);
            if (motherIdentity != null) addFamilyIndex(childGlobal, person.globalId, motherIdentity);

            for (String childId : person.children) {
                Set<CharacterIdentity> children = byLegacyId.get(childId);
                if (children != null) for (CharacterIdentity child : children) {
                    addFamilyIndex(parentLegacy, person.id, child);
                    addFamilyIndex(parentGlobal, person.globalId, child);
                    addFamilyIndex(childLegacy, childId, identity);
                    addFamilyIndex(childGlobal, child.globalCharacterId(), identity);
                }
            }
            Object globalChildren = person.props.get("family.children_global_ids");
            if (globalChildren instanceof List<?> ids) for (Object rawId : ids) {
                if (!(rawId instanceof String childId) || childId.isBlank()) continue;
                CharacterIdentity child = byGlobalId.get(childId);
                if (child == null) continue;
                addFamilyIndex(parentLegacy, person.id, child);
                addFamilyIndex(parentGlobal, person.globalId, child);
                addFamilyIndex(childLegacy, byIdentity.get(child).id, identity);
                addFamilyIndex(childGlobal, childId, identity);
            }
        }
        familyByParentGlobal = parentGlobal;
        familyByParentLegacy = parentLegacy;
        familyByChildGlobal = childGlobal;
        familyByChildLegacy = childLegacy;
        familyBySpouseGlobal = spouseGlobal;
        familyBySpouseLegacy = spouseLegacy;
        familyIndexDay = day;
    }

    /**
     * Returns only possible relatives of a Character, preserving local-registry priority.
     * The index is rebuilt transactionally with registry membership changes, not per death.
     */
    public synchronized List<Character> kinCandidates(Character person) {
        refreshIndex();
        if (person == null) return List.of();
        LinkedHashSet<CharacterIdentity> matches = new LinkedHashSet<>();
        addFamilyMatches(matches, familyByParentLegacy, person.father);
        addFamilyMatches(matches, familyByParentLegacy, person.mother);
        addFamilyMatches(matches, familyByParentGlobal, familyId(person, "family.father_global_id"));
        addFamilyMatches(matches, familyByParentGlobal, familyId(person, "family.mother_global_id"));
        addFamilyMatches(matches, familyByParentLegacy, person.id);
        addFamilyMatches(matches, familyByParentGlobal, person.globalId);
        addFamilyMatches(matches, familyByChildLegacy, person.id);
        addFamilyMatches(matches, familyByChildGlobal, person.globalId);
        addFamilyMatches(matches, familyBySpouseLegacy, person.id);
        addFamilyMatches(matches, familyBySpouseGlobal, person.globalId);
        Object globalChildren = person.props.get("family.children_global_ids");
        if (globalChildren instanceof List<?> ids) for (Object rawId : ids)
            if (rawId instanceof String id) {
                CharacterIdentity identity = byGlobalId.get(id);
                if (identity != null) matches.add(identity);
            }
        for (String childId : person.children) {
            Set<CharacterIdentity> identities = byLegacyId.get(childId);
            if (identities != null) matches.addAll(identities);
        }

        CharacterIdentity owner = byGlobalId.get(person.globalId);
        String localMap = owner == null ? "" : owner.mapId();
        List<CharacterIdentity> ordered = new ArrayList<>(matches);
        ordered.removeIf(identity -> !byIdentity.containsKey(identity));
        ordered.sort(Comparator
                .comparingInt((CharacterIdentity identity) -> identity.mapId().equals(localMap) ? 0 : 1)
                .thenComparing(CharacterIdentity::mapId)
                .thenComparingInt(identity -> legacySequence(identity.characterId()))
                .thenComparing(CharacterIdentity::globalCharacterId));
        List<Character> result = new ArrayList<>(ordered.size());
        for (CharacterIdentity identity : ordered) {
            Character candidate = byIdentity.get(identity);
            if (candidate != null) result.add(candidate);
        }
        return Collections.unmodifiableList(result);
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

    private static void addFamilyIndex(Map<String, Set<CharacterIdentity>> index,
                                       String key, CharacterIdentity identity) {
        if (key != null && !key.isBlank())
            index.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(identity);
    }

    private static void addFamilyMatches(Set<CharacterIdentity> target,
                                         Map<String, Set<CharacterIdentity>> index, String key) {
        if (key == null || key.isBlank()) return;
        Set<CharacterIdentity> found = index.get(key);
        if (found != null) target.addAll(found);
    }

    public synchronized int size() { refreshIndex(); return byIdentity.size(); }
    public synchronized int mapCount() { return registries.size(); }
}
