package dev.worldforge.people;

import dev.worldforge.mod.identity.WorldId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World-wide authority/index over map-local persistence partitions.
 *
 * Existing cN identifiers remain untouched during the compatibility period. Every lookup
 * across maps must use CharacterIdentity; a bare legacy id is resolved only when unambiguous.
 */
public final class CharacterAuthority {
    private final Map<CharacterIdentity, Character> byIdentity = new ConcurrentHashMap<>();
    private final Map<String, Set<CharacterIdentity>> byLegacyId = new ConcurrentHashMap<>();
    private final Map<String, CharacterRegistry> registries = new ConcurrentHashMap<>();
    private volatile WorldId worldId;

    public synchronized void beginWorld(WorldId id) {
        clear();
        worldId = Objects.requireNonNull(id, "worldId");
    }

    public synchronized void clear() {
        byIdentity.clear();
        byLegacyId.clear();
        registries.clear();
        worldId = null;
    }

    public WorldId worldId() { return worldId; }

    public synchronized void registerMap(String mapId, CharacterRegistry registry) {
        WorldId currentWorld = worldId;
        if (currentWorld == null) throw new IllegalStateException("World identity has not been initialized");
        if (mapId == null || mapId.isBlank()) throw new IllegalArgumentException("mapId is required");
        Objects.requireNonNull(registry, "registry");
        String key = mapId.trim();
        CharacterRegistry previous = registries.putIfAbsent(key, registry);
        if (previous != null && previous != registry)
            throw new IllegalStateException("A different CharacterRegistry is already registered for map " + key);
        for (Character character : registry.all()) {
            CharacterIdentity identity = new CharacterIdentity(currentWorld, key, character.id);
            Character existing = byIdentity.putIfAbsent(identity, character);
            if (existing != null && existing != character)
                throw new IllegalStateException("Duplicate Character identity " + identity);
            byLegacyId.computeIfAbsent(character.id, ignored -> ConcurrentHashMap.newKeySet()).add(identity);
        }
    }

    public Character get(CharacterIdentity identity) {
        return identity == null ? null : byIdentity.get(identity);
    }

    /** Returns null for an ambiguous legacy cN id; callers must provide map identity. */
    public Character getLegacy(String characterId) {
        Set<CharacterIdentity> matches = byLegacyId.get(characterId);
        if (matches == null || matches.size() != 1) return null;
        return byIdentity.get(matches.iterator().next());
    }

    public CharacterRegistry registry(String mapId) { return registries.get(mapId); }

    public Collection<CharacterIdentity> identities() {
        return Collections.unmodifiableSet(new TreeSet<>(Comparator
                .comparing((CharacterIdentity id) -> id.worldId().toString())
                .thenComparing(CharacterIdentity::mapId)
                .thenComparing(CharacterIdentity::characterId)) {{ addAll(byIdentity.keySet()); }});
    }

    public int size() { return byIdentity.size(); }
    public int mapCount() { return registries.size(); }
}
