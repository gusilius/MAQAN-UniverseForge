package dev.worldforge.worldsim;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterAccessService;
import java.util.*;

/**
 * Compact emergent-population projection. It stores canonical UUIDs only, never
 * Character references, so retaining this index cannot prevent physical eviction.
 * Resolve an ID through CharacterAccessService.acquireGlobal() at the point of use.
 */
public final class EmergentPopulationIndex {
    public record Resident(String globalId, String localId, boolean alive, int birthYear,
                           String gender, int familyGroupIndex, String house,
                           String race, String culture, String father, String mother) {}
    private final Map<String, List<String>> livingByBand = new HashMap<>();
    private final Map<String, Resident> residentByGlobalId = new HashMap<>();
    private final Set<String> knownGlobalIds = new HashSet<>();
    private final List<String> emergent = new ArrayList<>();

    public EmergentPopulationIndex(Collection<Character> characters) {
        Objects.requireNonNull(characters, "characters");
        for (Character character : characters) add(character);
    }

    /**
     * Builds the projection through bounded UUID pages and one short lease at a time.
     * Unlike registry.all(), this never constructs a population-sized Character list.
     */
    public EmergentPopulationIndex(CharacterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        final int pageSize = 512;
        for (int offset = 0, count = registry.size(); offset < count; offset += pageSize) {
            for (String localId : registry.idPage(offset, pageSize)) {
                try (CharacterAccessService.Lease lease = registry.access().acquire(localId)) {
                    if (lease == null)
                        throw new IllegalStateException("Emergent identity could not be leased: " + localId);
                    add(lease.get());
                }
            }
        }
    }

    /** Returns an immutable UUID snapshot for this band, not live Character objects. */
    public List<String> livingResidents(String bandId) {
        return List.copyOf(livingByBand.getOrDefault(
                Objects.requireNonNull(bandId, "bandId"), List.of()));
    }

    /** Identity membership query; callers resolve through a scoped Character lease. */
    public boolean containsGlobalId(String globalId) {
        return globalId != null && !globalId.isBlank() && knownGlobalIds.contains(globalId);
    }

    /** UUID-only lookup compatibility name; null means the identity is not indexed. */
    public String byGlobalId(String globalId) {
        return containsGlobalId(globalId) ? globalId : null;
    }

    /** Immutable emergence-history UUIDs, including deceased residents. */
    public List<String> emergentResidents() { return List.copyOf(emergent); }

    public void add(Character character) {
        Objects.requireNonNull(character, "character");
        String globalId = character.globalId;
        if (globalId == null || globalId.isBlank())
            throw new IllegalArgumentException("Emergent Character must have a canonical global UUID");
        if (!knownGlobalIds.add(globalId)) return;
        Object rawBand = character.props.get("emergence.band_id");
        Object rawFamily = character.props.get("emergence.family_group_index");
        int familyIndex = rawFamily instanceof Number number ? number.intValue() : -1;
        Object rawRace = character.props.get("origin.race");
        residentByGlobalId.put(globalId, new Resident(globalId, character.id, character.alive,
                character.birthYear, character.gender == null ? "" : character.gender, familyIndex,
                character.house == null ? "" : character.house,
                rawRace == null ? "" : String.valueOf(rawRace),
                character.culture == null ? "" : character.culture,
                character.father == null ? "" : character.father,
                character.mother == null ? "" : character.mother));
        if (rawBand instanceof String bandId && !bandId.isBlank()) {
            emergent.add(globalId);
            if (character.alive)
                livingByBand.computeIfAbsent(bandId, ignored -> new ArrayList<>()).add(globalId);
        }
    }

    /** Compact immutable metadata captured while a short lease is held. */
    public Resident resident(String globalId) { return residentByGlobalId.get(globalId); }

    public List<Resident> residentMetadata(String bandId) {
        List<String> ids = livingByBand.getOrDefault(Objects.requireNonNull(bandId), List.of());
        List<Resident> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Resident resident = residentByGlobalId.get(id);
            if (resident != null) result.add(resident);
        }
        return List.copyOf(result);
    }

    public int retainedIdentityCount() { return knownGlobalIds.size(); }
}
