package dev.worldforge.relations;

import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterIdentity;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.mc.PeopleMod;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Autonomous NPC-to-NPC social life. Relationships are stored in the existing
 * wf-relations Bond store using immutable global Character UUIDs; marriage and
 * children remain authoritative in the canonical CharacterRegistry/CourtSim.
 */
public final class NpcSocialSimulation {
    private NpcSocialSimulation() { }

    public record Contact(String characterId, int relationship, String organizationId,
                          boolean canCommunicate, boolean sameOrganization) { }

    private static final int MAX_GROUPS_PER_DAY = 256;
    private static final int PAIRS_PER_GROUP_PER_DAY = 2;

    public static int advance(Relations relations, int day, int year) {
        if (relations == null || !PeopleMod.identityPartitionsReady()) return 0;
        Map<String, CharacterRegistry> registries = new TreeMap<>();
        for (CharacterIdentity identity : PeopleMod.authority().identities()) {
            if ("__players__".equals(identity.mapId())) continue;
            CharacterRegistry registry = PeopleMod.authority().registry(identity.mapId());
            if (registry != null) registries.put(identity.mapId(), registry);
        }
        long seed = WorldForgeMod.worldId() == null ? 0L : WorldForgeMod.worldId().toString().hashCode();
        int interactions = 0, groupsVisited = 0;
        for (Map.Entry<String, CharacterRegistry> partition : registries.entrySet()) {
            CharacterRegistry registry = partition.getValue();
            Map<String, List<Character>> groups = new TreeMap<>();
            for (Character person : registry.all()) {
                if (!person.alive || year - person.birthYear < 18
                        || !person.props.containsKey("emergence.polity_id")) continue;
                String group = String.valueOf(person.props.getOrDefault("emergence.band_id",
                        person.props.getOrDefault("emergence.polity_id", "")));
                if (!group.isBlank()) groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(person);
            }
            for (Map.Entry<String, List<Character>> group : groups.entrySet()) {
                if (groupsVisited++ >= MAX_GROUPS_PER_DAY) return interactions;
                List<Character> members = group.getValue();
                if (members.size() < 2) continue;
                members.sort(Comparator.comparing(person -> person.globalId));
                for (int slot = 0; slot < PAIRS_PER_GROUP_PER_DAY; slot++) {
                    long hash = mix(seed ^ ((long) day << 32) ^ group.getKey().hashCode() ^ slot * 0x9E3779B97F4A7C15L);
                    int first = Math.floorMod((int) hash, members.size());
                    int second = Math.floorMod((int) (hash >>> 32), members.size() - 1);
                    if (second >= first) second++;
                    Character a = members.get(first), b = members.get(second);
                    if (a == b || !a.alive || !b.alive) continue;
                    interact(relations, registry, a, b, group.getKey(), members, day, year);
                    interactions++;
                }
            }
        }
        return interactions;
    }

    private static void interact(Relations relations, CharacterRegistry registry, Character a, Character b,
                                 String groupId, List<Character> witnesses, int day, int year) {
        String aKey = "npc:" + a.globalId, bKey = "npc:" + b.globalId;
        Relations.Bond ab = relations.bond(aKey, bKey);
        Relations.Bond ba = relations.bond(bKey, aKey);
        int oldAffinity = ab.affinity;
        Set<String> ta = new HashSet<>(CharacterStats.traits(a));
        Set<String> tb = new HashSet<>(CharacterStats.traits(b));
        int delta = 1;
        if (ta.contains("gregarious") || tb.contains("gregarious")) delta++;
        if (ta.contains("generous") && tb.contains("generous")) delta++;
        if (ta.contains("loyal") && tb.contains("loyal")) delta++;
        if ((ta.contains("cruel") && tb.contains("just")) || (tb.contains("cruel") && ta.contains("just"))) delta -= 3;
        if ((ta.contains("deceitful") && tb.contains("honorable")) || (tb.contains("deceitful") && ta.contains("honorable"))) delta -= 2;
        if (ta.contains("wroth") && tb.contains("wroth")) delta--;
        delta = Math.max(-5, Math.min(5, delta));
        ab.affinity = clamp(ab.affinity + delta, -100, 100);
        ba.affinity = clamp(ba.affinity + delta, -100, 100);
        ab.lastContact = ba.lastContact = day;
        ab.met++; ba.met++;
        ab.talks++; ba.talks++;

        boolean eligibleRomance = !a.gender.equals(b.gender) && a.spouse.isEmpty() && b.spouse.isEmpty()
                && year - a.birthYear >= 18 && year - b.birthYear >= 18
                && year - a.birthYear <= 45 && year - b.birthYear <= 55;
        if (eligibleRomance && ab.affinity >= 25) {
            int loveDelta = ta.contains("shy") || tb.contains("shy") ? 1 : 2;
            if (ta.contains("gregarious") || tb.contains("gregarious")) loveDelta++;
            ab.love = clamp(ab.love + loveDelta, 0, 100);
            ba.love = clamp(ba.love + loveDelta, 0, 100);
            if (ab.affinity >= 40 && ab.love >= 35) { ab.dating = true; ba.dating = true; }
            long marriageRoll = mix(a.globalId.hashCode() * 31L + b.globalId.hashCode() + day + groupId.hashCode());
            if (ab.affinity >= 65 && ab.love >= 60 && Math.floorMod(marriageRoll, 24) == 0) {
                try {
                    registry.marry(a.id, b.id, year);
                    ab.married = ba.married = true;
                    ab.dating = ba.dating = false;
                    ab.affinity = ba.affinity = Math.max(70, ab.affinity);
                    String polity = polityOf(a);
                    String message = "Автономный брак: " + a.name + " и " + b.name;
                    relations.history.add(message);
                    trimHistory(relations);
                    WorldForgeApi.postEvent("people", message,
                            polity.isBlank() ? List.of() : List.of(polity), Math.max(0L, (long) day * 1440L));
                    reputation(a, polity, 4, day, "marriage", witnesses);
                    reputation(b, polity, 4, day, "marriage", witnesses);
                } catch (RuntimeException ignored) {
                    // A concurrent court/settlement marriage wins; the canonical registry remains authoritative.
                }
            }
        }

        if (oldAffinity < 45 && ab.affinity >= 45) {
            String polity = polityOf(a);
            String message = a.name + " и " + b.name + " стали друзьями";
            relations.history.add(message);
            trimHistory(relations);
            WorldForgeApi.postEvent("people", message,
                    polity.isBlank() ? List.of() : List.of(polity), Math.max(0L, (long) day * 1440L));
            reputation(a, polity, 1, day, "friendship", witnesses);
            reputation(b, polity, 1, day, "friendship", witnesses);
        } else if (oldAffinity > -30 && ab.affinity <= -30) {
            String polity = polityOf(a);
            String message = a.name + " и " + b.name + " стали соперниками";
            relations.history.add(message);
            trimHistory(relations);
            WorldForgeApi.postEvent("people", message,
                    polity.isBlank() ? List.of() : List.of(polity), Math.max(0L, (long) day * 1440L));
            reputation(a, polity, -2, day, "rivalry", witnesses);
            reputation(b, polity, -2, day, "rivalry", witnesses);
        }
    }

    private static void reputation(Character subject, String polity, int delta, int day,
                                    String reason, List<Character> group) {
        String[] witnesses = group.stream().filter(person -> person.alive && person != subject)
                .limit(8).map(person -> person.globalId).toArray(String[]::new);
        try {
            Class<?> bridge = Class.forName("dev.worldforge.reputation.mc.ReputationMod");
            Method method = bridge.getMethod("recordAutonomous", String.class, String.class,
                    int.class, long.class, String.class, String[].class);
            method.invoke(null, subject.globalId, polity, delta, Math.max(0L, (long) day * 1440L), reason, witnesses);
        } catch (ClassNotFoundException ignored) {
            // Reputation is an optional addon; relationships remain fully functional without it.
        } catch (ReflectiveOperationException ex) {
            RelationsMod.LOGGER.error("wf_relations: autonomous reputation bridge failed: {}", ex.toString());
        }
    }

    /** Trusted contacts used by wf-reputation's bounded rumor network. */
    public static List<Contact> contactsOf(String witnessGlobalId, String polityId) {
        Character witness = PeopleMod.authority().getGlobal(witnessGlobalId);
        if (witness == null) return List.of();
        CharacterRegistry registry = PeopleMod.authority().registryForGlobal(witnessGlobalId);
        if (registry == null) return List.of();
        String witnessPolity = polityOf(witness);
        if (!Objects.equals(witnessPolity, polityId)) return List.of();
        List<Contact> result = new ArrayList<>();
        for (Character candidate : registry.all()) {
            if (!candidate.alive || candidate == witness || !Objects.equals(polityOf(candidate), polityId)) continue;
            Relations.Bond bond = RelationsMc.REL.peek("npc:" + witness.globalId, "npc:" + candidate.globalId);
            if (bond == null) continue;
            result.add(new Contact(candidate.globalId, bond.affinity, polityId, true, true));
        }
        result.sort(Comparator.comparing(Contact::characterId));
        return result;
    }

    public static boolean isMember(String globalId, String polityId) {
        Character person = PeopleMod.authority().getGlobal(globalId);
        return person != null && person.alive && Objects.equals(polityOf(person), polityId);
    }

    private static String polityOf(Character person) {
        Object value = person.props.get("emergence.polity_id");
        return value == null ? "" : String.valueOf(value);
    }

    private static void trimHistory(Relations relations) {
        while (relations.history.size() > 500) relations.history.remove(0);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
