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
        List<String> problems = new ArrayList<>();

        int linked = 0, already = 0;
        Set<Integer> crownTitleIds = new HashSet<>();
        for (TitleData.Realm realm : map.realmList()) {
            String rulerId = String.valueOf(realm.props.getOrDefault("rulerCharacterId", "")).trim();
            if (rulerId.isBlank()) continue;
            Boolean rulerAlive = isAlive(people, rulerId);
            if (rulerAlive == null) {
                problems.add("Realm " + realm.id + " references missing ruler Character ID " + rulerId);
                continue;
            }
            if (!rulerAlive) {
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
            int result = reconcileHolder(title, rulerId, people, politics, "realm " + realm.id);
            if (result > 0) linked++;
            else if (result == 0) already++;
            else problems.add(politics.lastError);
        }

        // wf-people owns territorial offices. The projection stores UUIDs only:
        // no Character instance survives the short read lease used to inspect a post.
        if (roleDefs != null) {
            Map<String, List<Role>> rulerRolesByTier = new LinkedHashMap<>();
            for (Role role : roleDefs.all()) {
                if (!role.ruler || role.per == null || role.per.equals("realm")) continue;
                rulerRolesByTier.computeIfAbsent(role.per, ignored -> new ArrayList<>()).add(role);
            }

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
                List<String> officeHolders = new ArrayList<>();
                for (Role role : roles) officeHolders.addAll(livingOfficeHolders(people, role.id, place));
                officeHolders = officeHolders.stream().distinct().sorted().toList();
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
                    problems.add("Multiple living ruler office holders for title " + sourceTitle.key + ": " + officeHolders);
                    continue;
                }
                int result = reconcileHolder(politicalTitle, officeHolders.get(0), people, politics,
                        tierKey + " title " + sourceTitle.key);
                if (result > 0) linked++;
                else if (result == 0) already++;
                else problems.add(politics.lastError);
            }
        }
        return new Report(linked, already, problems);
    }

    /** Returns null for a missing UUID; a live/dead check hydrates only this one identity. */
    private static Boolean isAlive(CharacterRegistry registry, String globalId) {
        if (globalId == null || globalId.isBlank()) return null;
        try (dev.worldforge.people.CharacterAccessService.Lease lease = registry.acquireGlobal(globalId)) {
            return lease == null ? null : lease.get().alive;
        }
    }

    /** Reverse post index narrows the query to holders of this exact office; no population scan. */
    private static List<String> livingOfficeHolders(CharacterRegistry registry, String role, String place) {
        List<String> result = new ArrayList<>();
        for (String localId : registry.holderIds(role, place)) {
            try (dev.worldforge.people.CharacterAccessService.Lease lease = registry.acquire(localId)) {
                if (lease == null) continue;
                Character character = lease.get();
                if (character.alive && character.has(role, place) && character.globalId != null
                        && !character.globalId.isBlank()) result.add(character.globalId);
            }
        }
        return result;
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
    private static int reconcileHolder(Statecraft.Title title, String desiredId,
                                        CharacterRegistry registry, Statecraft politics,
                                        String scope) {
        String currentId = title.holderCharacterId == null ? "" : title.holderCharacterId;
        if (currentId.isBlank()) {
            if (politics.grantTitle(title.id, desiredId)) return 1;
            politics.lastError = "Could not assign " + scope + " ruler " + desiredId
                    + " to " + title.id + ": " + politics.lastError;
            return -1;
        }
        if (currentId.equals(desiredId)) return 0;
        if (Boolean.TRUE.equals(isAlive(registry, currentId))) {
            politics.lastError = "Conflicting living holder for " + scope + ": "
                    + currentId + " (requested " + desiredId + ")";
            return -1;
        }
        // A missing/deceased holder is a stale reference and may be repaired;
        // a different living canonical Character is a real political conflict.
        if (politics.grantTitle(title.id, desiredId)) return 1;
        politics.lastError = "Could not repair stale " + scope + " office holder "
                + desiredId + ": " + politics.lastError;
        return -1;
    }
}
