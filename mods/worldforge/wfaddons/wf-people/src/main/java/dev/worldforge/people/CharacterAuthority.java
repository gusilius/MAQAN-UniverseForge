package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** World-wide authority over map-local persistence partitions and immutable global person IDs. */
public final class CharacterAuthority {
    private final Map<CharacterIdentity, Character> byIdentity = new ConcurrentHashMap<>();
    private final Map<String, CharacterIdentity> byGlobalId = new ConcurrentHashMap<>();
    private final Map<String, Set<CharacterIdentity>> byLegacyId = new ConcurrentHashMap<>();
    private final Map<String, CharacterRegistry> registries = new ConcurrentHashMap<>();
    private final Map<String, Long> indexedRevisions = new HashMap<>();
    private volatile WorldId worldId;

    public synchronized void beginWorld(WorldId id) {
        clear();
        worldId = Objects.requireNonNull(id, "worldId");
    }

    public synchronized void clear() {
        byIdentity.clear();
        byGlobalId.clear();
        byLegacyId.clear();
        registries.clear();
        indexedRevisions.clear();
        worldId = null;
    }

    public WorldId worldId() { return worldId; }

    public synchronized void registerMap(String mapId, CharacterRegistry registry) {
        if (worldId == null) throw new IllegalStateException("World identity has not been initialized");
        if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("mapId is required");
        Objects.requireNonNull(registry, "registry");
        String key = mapId.trim();
        CharacterRegistry previous = registries.putIfAbsent(key, registry);
        if (previous != null && previous != registry)
            throw new IllegalStateException("A different CharacterRegistry is already registered for map " + key);
        try {
            refreshIndex();
        } catch (RuntimeException ex) {
            // A rejected partition must not poison the live authority or discard its last valid index.
            if (previous == null) registries.remove(key, registry);
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

        // Publish only a completely validated snapshot.
        byIdentity.clear();
        byIdentity.putAll(nextByIdentity);
        byGlobalId.clear();
        byGlobalId.putAll(nextByGlobalId);
        byLegacyId.clear();
        for (Map.Entry<String, Set<CharacterIdentity>> entry : nextByLegacyId.entrySet())
            byLegacyId.put(entry.getKey(), ConcurrentHashMap.newKeySet(entry.getValue()));
        indexedRevisions.clear();
        indexedRevisions.putAll(nextRevisions);
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
        List<CharacterIdentity> result = new ArrayList<>(byIdentity.keySet());
        result.sort(Comparator.comparing((CharacterIdentity id) -> id.worldId().toString())
                .thenComparing(CharacterIdentity::mapId)
                .thenComparing(CharacterIdentity::globalCharacterId));
        return Collections.unmodifiableList(result);
    }

    public synchronized int size() { refreshIndex(); return byIdentity.size(); }
    public synchronized int mapCount() { return registries.size(); }
}
