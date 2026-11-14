package dev.worldforge.worldsim;

import dev.worldforge.people.Character;
import java.util.*;

/** Ephemeral reconciliation index over canonical Character objects; never a second identity store. */
public final class EmergentPopulationIndex {
    private final Map<String, List<Character>> livingByBand = new HashMap<>();
    private final Map<String, Character> byGlobalId = new HashMap<>();
    private final List<Character> emergent = new ArrayList<>();
    public EmergentPopulationIndex(Collection<Character> characters) {
        Objects.requireNonNull(characters, "characters");
        for (Character character : characters) add(character);
    }
    public List<Character> livingResidents(String bandId) {
        return livingByBand.computeIfAbsent(Objects.requireNonNull(bandId, "bandId"), ignored -> new ArrayList<>());
    }
    public Character byGlobalId(String globalId) {
        return globalId == null || globalId.isBlank() ? null : byGlobalId.get(globalId);
    }
    public List<Character> emergentResidents() { return Collections.unmodifiableList(emergent); }
    public void add(Character character) {
        Objects.requireNonNull(character, "character");
        byGlobalId.put(character.globalId, character);
        Object rawBand = character.props.get("emergence.band_id");
        if (rawBand instanceof String bandId && !bandId.isBlank()) {
            emergent.add(character);
            if (character.alive) livingResidents(bandId).add(character);
        }
    }
}
