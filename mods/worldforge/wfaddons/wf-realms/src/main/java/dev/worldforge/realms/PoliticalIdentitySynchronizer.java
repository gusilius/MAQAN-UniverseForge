package dev.worldforge.realms;

import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;

import java.util.*;

/**
 * Shared, deterministic bridge between map-generated court rulers and canonical Statecraft title holders.
 * It never creates Characters, never replaces an established holder, and reports unresolved references.
 */
public final class PoliticalIdentitySynchronizer {
    private PoliticalIdentitySynchronizer() {}

    public record Report(int linked, int alreadyLinked, List<String> problems) {
        public Report { problems = List.copyOf(problems); }
    }

    public static Report synchronizeRulers(String mapId, TitleData map,
                                            CharacterRegistry people, Statecraft politics) {
        Objects.requireNonNull(mapId, "map id");
        Objects.requireNonNull(map, "title map");
        Objects.requireNonNull(people, "character registry");
        Objects.requireNonNull(politics, "statecraft");
        Map<String, Character> byGlobalId = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Character character : people.all()) {
            if (character.globalId == null || character.globalId.isBlank()) {
                problems.add("Character " + character.id + " has no global ID");
                continue;
            }
            Character previous = byGlobalId.putIfAbsent(character.globalId, character);
            if (previous != null && previous != character)
                problems.add("Duplicate global Character ID " + character.globalId);
        }
        int linked = 0, already = 0;
        for (TitleData.Realm realm : map.realmList()) {
            String rulerId = String.valueOf(realm.props.getOrDefault("rulerCharacterId", "")).trim();
            if (rulerId.isBlank()) continue;
            Character ruler = byGlobalId.get(rulerId);
            if (ruler == null) {
                problems.add("Realm " + realm.id + " references missing ruler Character ID " + rulerId);
                continue;
            }
            if (!ruler.alive) {
                problems.add("Realm " + realm.id + " references deceased ruler " + rulerId);
                continue;
            }
            TitleData.Title crown = null;
            for (String key : realm.titleKeys) {
                TitleData.Title candidate = map.byKey(key);
                if (candidate == null) continue;
                if (crown == null || candidate.tier > crown.tier
                        || (candidate.tier == crown.tier && candidate.key.compareTo(crown.key) < 0))
                    crown = candidate;
            }
            if (crown == null) {
                problems.add("Realm " + realm.id + " has no canonical crown title key");
                continue;
            }
            String titleId = Statecraft.mapTitleId(mapId, crown.key);
            Statecraft.Title title = politics.title(titleId);
            if (title == null) {
                problems.add("Statecraft title was not imported: " + titleId);
                continue;
            }
            if (title.holderCharacterId == null || title.holderCharacterId.isBlank()) {
                if (politics.grantTitle(titleId, rulerId)) linked++;
                else problems.add("Could not assign ruler " + rulerId + " to " + titleId + ": " + politics.lastError);
            } else if (title.holderCharacterId.equals(rulerId)) {
                already++;
            } else if (!byGlobalId.containsKey(title.holderCharacterId)) {
                problems.add("Title " + titleId + " has orphan holder Character ID " + title.holderCharacterId);
            }
            // A title stores exactly one holder. Existing holders are intentionally not overwritten:
            // inheritance, conquest and player grants must survive subsequent synchronization ticks.
        }
        return new Report(linked, already, problems);
    }
}
