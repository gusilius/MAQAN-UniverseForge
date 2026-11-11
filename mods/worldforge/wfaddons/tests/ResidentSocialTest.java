package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.settle.ResidentSocial;
import java.util.Map;

public final class ResidentSocialTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL " + message);
        }
    }

    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();
        Character resident = registry.create("Mira", "", "", "f", 1);
        String player = "player-uuid";
        check(ResidentSocial.score(resident, player) == 0, "new relationship starts neutral");
        check(ResidentSocial.adjust(resident, player, 10) == 10, "gift increases relationship");
        check(ResidentSocial.adjust(resident, player, -4) == 6, "relationship supports positive and negative changes");
        check(ResidentSocial.adjust(resident, player, 500) == 100, "relationship upper bound is enforced");
        check(ResidentSocial.adjust(resident, player, -500) == -100, "relationship lower bound is enforced");
        check(ResidentSocial.score(resident, player) == -100, "score is read from Character properties");
        check(ResidentSocial.score(resident, "another-player") == 0, "relationships are per counterpart");

        CharacterRegistry restoredRegistry = new CharacterRegistry();
        restoredRegistry.fromJson(registry.toJson());
        Character restored = restoredRegistry.get(resident.id);
        check(ResidentSocial.score(restored, player) == -100, "relationship state persists with Character");
        check(ResidentSocial.adjust(restored, player, 10) == -90, "relationship remains mutable after Character restore");
        boolean invalidTargetRejected = false;
        try { ResidentSocial.adjust(resident, "", 1); }
        catch (IllegalArgumentException expected) { invalidTargetRejected = true; }
        check(invalidTargetRejected, "empty relationship target is rejected");

        Character kind = registry.create("Kind", "", "north", "m", 1);
        Character aggressive = registry.create("Rival", "", "south", "m", 1);
        kind.traits.add("kind");
        aggressive.traits.add("aggressive");
        check(ResidentSocial.interactionDelta(kind, resident) > 0,
                "kind personalities improve the outcome of social encounters");
        check(ResidentSocial.interactionDelta(aggressive, resident) < 0,
                "aggressive personalities and cultural difference can create rivalry");
        check(ResidentSocial.socialize(aggressive, resident,
                        ResidentSocial.interactionDelta(aggressive, resident)) < 0
                        && ResidentSocial.score(resident, aggressive.globalId) < 0,
                "negative resident encounters create reciprocal rivalry/enmity");


        CharacterRegistry migratedPartition = new CharacterRegistry();
        Character partitionPeer = migratedPartition.create("Partition Peer", "", "north", "f", 1);
        CharacterRegistry collidingPartition = new CharacterRegistry();
        Character collidingPeer = collidingPartition.create("Colliding Peer", "", "south", "m", 1);
        Map<String, Object> legacyRelationships = new java.util.LinkedHashMap<>();
        legacyRelationships.put(partitionPeer.id, 12);
        aggressive.props.put("social.relationships", legacyRelationships);
        ResidentSocial.socializeOnce(aggressive, partitionPeer, 3, "life:cross-partition:one");
        ResidentSocial.socializeOnce(aggressive, collidingPeer, 7, "life:cross-partition:two");
        check(ResidentSocial.score(aggressive, partitionPeer.globalId) == 3
                        && ResidentSocial.score(aggressive, collidingPeer.globalId) == 7
                        && ((Number) ((Map<?, ?>) aggressive.props.get("social.relationships")).get(partitionPeer.id)).intValue() == 12,
                "ambiguous legacy local relationship data stays untouched while new links use distinct global UUIDs");

        System.out.println("ResidentSocialTest: " + (failures == 0 ? "OK" : failures + " failure(s)"));
        if (failures > 0) throw new AssertionError("ResidentSocialTest failed: " + failures);
    }
}
