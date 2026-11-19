package dev.worldforge.realms;

import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;

import java.util.*;

/** Deterministic bridge from canonical wf-people courts to Statecraft title holders. */
public final class PoliticalIdentitySynchronizer {
    private PoliticalIdentitySynchronizer() {}

    public record Report(int linked, int alreadyLinked, List<String> problems) {
        public Report { problems = List.copyOf(problems); }
    }

    /** Backwards-compatible crown-only entry point. */
    public static Report synchronizeRulers(String mapId, TitleData map,
                                            CharacterRegistry people, Statecraft politics) {
        return synchronizeRulers(mapId, map, people, politics, null);
    }

    public static Report synchronizeRulers(String mapId, TitleData map,
                                            CharacterRegistry people, Statecraft politics,
                                            RoleDefs roleDefs) {
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
        Set<Integer> crownTitleIds = new HashSet<>();
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
            TitleData.Title crown = crown(map, realm);
            if (crown == null) {
                problems.add("Realm " + realm.id + " has no canonical crown title key");
                continue;
            }
            crownTitleIds.add(crown.id);
            Statecraft.Title title = politics.title(Statecraft.mapTitleId(mapId, crown.key));
            if (title == null) {
                problems.add("Statecraft title was not imported: " + Statecraft.mapTitleId(mapId, crown.key));
                continue;
            }
            int result = reconcileHolder(title, ruler, byGlobalId, politics, "realm " + realm.id);
            if (result > 0) linked++;
            else if (result == 0) already++;
            else problems.add(politics.lastError);
        }

        // wf-people owns territorial offices. Match each ruler role to the actual map
        // tier key, then bind its living office holder to that exact canonical title.
        if (roleDefs != null) {
            Map<String, List<Role>> rulerRolesByTier = new LinkedHashMap<>();
            for (Role role : roleDefs.all()) {
                if (!role.ruler || role.per == null || role.per.equals("realm")) continue;
                rulerRolesByTier.computeIfAbsent(role.per, ignored -> new ArrayList<>()).add(role);
            }
            Set<String> rulerRoleIds = rulerRolesByTier.values().stream().flatMap(Collection::stream)
                    .map(role -> role.id).collect(java.util.stream.Collectors.toSet());
            Map<String, List<Character>> officeHoldersByPost = new HashMap<>();
            for (Character character : people.all()) {
                if (!character.alive) continue;
                for (Character.Post post : character.posts) {
                    if (rulerRoleIds.contains(post.role))
                        officeHoldersByPost.computeIfAbsent(post.place + "#" + post.role,
                                ignored -> new ArrayList<>()).add(character);
                }
            }
            for (List<Character> holders : officeHoldersByPost.values())
                holders.sort(Comparator.comparing(character -> character.globalId));
            for (TitleData.Title sourceTitle : map.allTitles()) {
                if (crownTitleIds.contains(sourceTitle.id)) continue;
                if (sourceTitle.tier < 0 || sourceTitle.tier >= map.tiers.size()) continue;
                TitleData.Title province = sourceTitle;
                while (province != null && province.tier > 0)
                    province = province.parent > 0 ? map.get(province.parent) : null;
                TitleData.Realm owner = province == null ? null : map.realmOf(province);
                if (owner == null) continue;
                String tierKey = map.tiers.get(sourceTitle.tier).key;
                List<Role> roles = rulerRolesByTier.getOrDefault(tierKey, List.of());
                if (roles.isEmpty()) continue;
                String place = "title:" + sourceTitle.id;
                List<Character> officeHolders = new ArrayList<>();
                for (Role role : roles)
                    officeHolders.addAll(officeHoldersByPost.getOrDefault(place + "#" + role.id, List.of()));
                officeHolders = officeHolders.stream().distinct()
                        .sorted(Comparator.comparing(character -> character.globalId)).toList();
                Statecraft.Title politicalTitle = politics.title(Statecraft.mapTitleId(mapId, sourceTitle.key));
                if (politicalTitle == null) {
                    problems.add("Owned map title was not imported into Statecraft: " + sourceTitle.key);
                    continue;
                }
                if (officeHolders.isEmpty()) {
                    problems.add("No living wf-people ruler office holder for " + tierKey + " title " + sourceTitle.key);
                    continue;
                }
                if (officeHolders.size() > 1) {
                    problems.add("Multiple living ruler office holders for title " + sourceTitle.key
                            + ": " + officeHolders.stream().map(character -> character.globalId).toList());
                    continue;
                }
                int result = reconcileHolder(politicalTitle, officeHolders.get(0), byGlobalId, politics,
                        tierKey + " title " + sourceTitle.key);
                if (result > 0) linked++;
                else if (result == 0) already++;
                else problems.add(politics.lastError);
            }
        }
        return new Report(linked, already, problems);
    }

    private static TitleData.Title crown(TitleData map, TitleData.Realm realm) {
        TitleData.Title crown = null;
        for (String key : realm.titleKeys) {
            TitleData.Title candidate = map.byKey(key);
            if (candidate == null) continue;
            if (crown == null || candidate.tier > crown.tier
                    || (candidate.tier == crown.tier && candidate.key.compareTo(crown.key) < 0))
                crown = candidate;
        }
        return crown;
    }

    /** 1=linked, 0=already correct, -1=conflict/error. */
    private static int reconcileHolder(Statecraft.Title title, Character desired,
                                        Map<String, Character> byGlobalId, Statecraft politics,
                                        String scope) {
        String currentId = title.holderCharacterId == null ? "" : title.holderCharacterId;
        if (currentId.isBlank()) {
            if (politics.grantTitle(title.id, desired.globalId)) return 1;
            politics.lastError = "Could not assign " + scope + " ruler " + desired.globalId
                    + " to " + title.id + ": " + politics.lastError;
            return -1;
        }
        if (currentId.equals(desired.globalId)) return 0;
        Character current = byGlobalId.get(currentId);
        if (current != null && current.alive) {
            politics.lastError = "Conflicting living holder for " + scope + ": "
                    + currentId + " (requested " + desired.globalId + ")";
            return -1;
        }
        // A missing/deceased holder is a stale reference and may be repaired;
        // a different living canonical Character is a real political conflict.
        if (politics.grantTitle(title.id, desired.globalId)) return 1;
        politics.lastError = "Could not repair stale " + scope + " office holder "
                + desired.globalId + ": " + politics.lastError;
        return -1;
    }
}
