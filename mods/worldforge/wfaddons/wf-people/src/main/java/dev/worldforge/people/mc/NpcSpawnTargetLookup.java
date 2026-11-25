package dev.worldforge.people.mc;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves physical NPC spawn candidates in one pass over the canonical registry. */
public final class NpcSpawnTargetLookup {
    public record RolePlace(String roleId, String place) { }
    public record Location(Role role, double x, double z) {
        public Location { Objects.requireNonNull(role, "role"); }
    }
    public record Candidate(String characterId, Role role, double x, double z) { }
    private NpcSpawnTargetLookup() { }

    public static List<Candidate> collect(CharacterRegistry registry, Map<RolePlace, Location> eligibleLocations) {
        if (registry == null || eligibleLocations == null || eligibleLocations.isEmpty()) return List.of();
        // Resolve only indexed holders of eligible role/place pairs. A world-sized
        // registry traversal here would rehydrate every evicted NPC every spawn pass.
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        for (RolePlace key : eligibleLocations.keySet())
            candidates.addAll(registry.holderIds(key.roleId(), key.place()));
        List<Candidate> result = new ArrayList<>();
        for (String characterId : candidates) {
            try (CharacterAccessService.Lease lease = registry.access().acquire(characterId)) {
                if (lease == null) continue;
                Character character = lease.get();
                if (!character.alive || character.posts.isEmpty()) continue;
                for (Character.Post post : character.posts) {
                    Location location = eligibleLocations.get(new RolePlace(post.role, post.place));
                    if (location != null)
                        result.add(new Candidate(character.id, location.role(), location.x(), location.z()));
                }
            }
        }
        return result;
    }
}
