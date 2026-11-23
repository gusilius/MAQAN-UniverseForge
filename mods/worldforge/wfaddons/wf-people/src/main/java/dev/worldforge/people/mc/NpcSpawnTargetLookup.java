package dev.worldforge.people.mc;

import dev.worldforge.people.Character;
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
    public record Candidate(Character character, Role role, double x, double z) { }
    private NpcSpawnTargetLookup() { }

    public static List<Candidate> collect(CharacterRegistry registry, Map<RolePlace, Location> eligibleLocations) {
        if (registry == null || eligibleLocations == null || eligibleLocations.isEmpty()) return List.of();
        List<Candidate> result = new ArrayList<>();
        for (Character character : registry.all()) {
            if (character == null || !character.alive || character.posts.isEmpty()) continue;
            for (Character.Post post : character.posts) {
                Location location = eligibleLocations.get(new RolePlace(post.role, post.place));
                if (location != null) result.add(new Candidate(character, location.role(), location.x(), location.z()));
            }
        }
        return result;
    }
}
