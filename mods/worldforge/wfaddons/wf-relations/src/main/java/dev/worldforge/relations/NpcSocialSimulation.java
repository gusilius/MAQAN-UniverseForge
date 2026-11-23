package dev.worldforge.relations;

import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterIdentity;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.relations.mc.RelationsMc;
import dev.worldforge.relations.mc.RelationsMod;

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
    private static final Map<String, Integer> GROUP_CURSORS = new HashMap<>();
    private static String groupCursorWorld = "";

    private static volatile long contactIndexDay = Long.MIN_VALUE;
    private static volatile String contactIndexWorld = "";
    private static volatile Map<CharacterRegistry, Map<String, List<Character>>> contactsByRegistry = Map.of();

    private static final int CONTACT_INDEX_CHARACTERS_PER_TICK = 2048;

    private static final class RegistryScan {
        final CharacterRegistry registry;
        final List<Character> people;
        final int limit;
        final long membershipGeneration;
        int cursor;

        RegistryScan(CharacterRegistry registry) {
            this.registry = registry;
            this.people = registry.all();
            this.limit = people.size();
            this.membershipGeneration = registry.membershipGeneration();
        }
    }

    private static final class ContactIndexBuild {
        final long day;
        final int year;
        final String world;
        final long registryGeneration;
        final List<RegistryScan> scans;
        final Map<CharacterRegistry, Map<String, List<Character>>> groups = new IdentityHashMap<>();
        int scanIndex;

        ContactIndexBuild(long day, int year, String world, long registryGeneration, List<RegistryScan> scans) {
            this.day = day;
            this.year = year;
            this.world = world;
            this.registryGeneration = registryGeneration;
            this.scans = scans;
        }
    }

    private static ContactIndexBuild contactIndexBuild;
    private static volatile int contactIndexYear = Integer.MIN_VALUE;
    private static volatile long contactIndexRegistryGeneration = Long.MIN_VALUE;
    private static volatile Map<CharacterRegistry, Long> contactIndexMembership = Map.of();

    /**
     * Incrementally prepares the daily social index. CharacterRegistry.all() is an append-only
     * view, so each partition's captured size can be scanned safely over multiple server ticks.
     * This prevents a million-character world from rebuilding its entire social index in one tick.
     */
    public static synchronized boolean prepareContactIndex(long day, int year, int characterBudget) {
        String world = String.valueOf(WorldForgeMod.worldId());
        if (contactIndexDay == day && contactIndexYear == year && contactIndexWorld.equals(world)
                && contactIndexRegistryGeneration == PeopleMod.populationRegistryGeneration()
                && contactIndexMembershipCurrent()) return true;
        if (!PeopleMod.identityPartitionsReady()) return false;

        long registryGeneration = PeopleMod.populationRegistryGeneration();
        if (contactIndexBuild == null || contactIndexBuild.day != day
                || contactIndexBuild.year != year || !contactIndexBuild.world.equals(world)
                || contactIndexBuild.registryGeneration != registryGeneration) {
            Set<String> ids = new TreeSet<>();
            for (CharacterIdentity identity : PeopleMod.authority().identities())
                if (!"__players__".equals(identity.mapId())) ids.add(identity.mapId());
            List<RegistryScan> scans = new ArrayList<>(ids.size());
            for (String id : ids) {
                CharacterRegistry registry = PeopleMod.authority().registry(id);
                if (registry != null) scans.add(new RegistryScan(registry));
            }
            contactIndexBuild = new ContactIndexBuild(day, year, world, registryGeneration, scans);
        }

        int remaining = Math.max(1, Math.min(8192, characterBudget));
        ContactIndexBuild build = contactIndexBuild;
        while (remaining > 0 && build.scanIndex < build.scans.size()) {
            RegistryScan scan = build.scans.get(build.scanIndex);
            while (remaining > 0 && scan.cursor < scan.limit) {
                Character person = scan.people.get(scan.cursor++);
                remaining--;
                if (person == null || !person.alive || year - person.birthYear < 18
                        || !person.props.containsKey("emergence.polity_id")) continue;
                String polity = polityOf(person);
                if (polity.isBlank()) continue;
                String band = String.valueOf(person.props.getOrDefault("emergence.band_id", polity));
                if (band.isBlank()) band = polity;
                build.groups.computeIfAbsent(scan.registry, ignored -> new TreeMap<>())
                        .computeIfAbsent(band, ignored -> new ArrayList<>()).add(person);
            }
            if (scan.cursor >= scan.limit) build.scanIndex++;
        }

        if (build.scanIndex < build.scans.size()) return false;
        // Membership may change while a large index is being built. Never publish a partial
        // view that silently omits newly appended Characters or refers to replaced partitions.
        if (build.registryGeneration != PeopleMod.populationRegistryGeneration()
                || build.scans.stream().anyMatch(scan ->
                        scan.registry.membershipGeneration() != scan.membershipGeneration)) {
            contactIndexBuild = null;
            return false;
        }

        Map<CharacterRegistry, Map<String, List<Character>>> frozen = new IdentityHashMap<>();
        build.groups.forEach((registry, byBand) -> {
            Map<String, List<Character>> groups = new TreeMap<>();
            // CharacterRegistry.all() preserves deterministic insertion order; retain it
            // directly instead of sorting potentially million-member bands in one tick.
            byBand.forEach((band, members) -> groups.put(band, List.copyOf(members)));
            frozen.put(registry, Collections.unmodifiableMap(groups));
        });
        contactsByRegistry = Collections.unmodifiableMap(frozen);
        Map<CharacterRegistry, Long> membership = new IdentityHashMap<>();
        for (RegistryScan scan : build.scans) membership.put(scan.registry, scan.membershipGeneration);
        contactIndexMembership = Collections.unmodifiableMap(membership);
        contactIndexRegistryGeneration = build.registryGeneration;
        contactIndexWorld = world;
        contactIndexDay = day;
        contactIndexYear = year;
        contactIndexBuild = null;
        return true;
    }

    private static boolean contactIndexMembershipCurrent() {
        for (Map.Entry<CharacterRegistry, Long> entry : contactIndexMembership.entrySet())
            if (entry.getKey().membershipGeneration() != entry.getValue()) return false;
        return true;
    }

    private static Map<CharacterRegistry, Map<String, List<Character>>> contactIndex(long day, int year) {
        String world = String.valueOf(WorldForgeMod.worldId());
        return contactIndexDay == day && contactIndexYear == year && contactIndexWorld.equals(world)
                && contactIndexRegistryGeneration == PeopleMod.populationRegistryGeneration()
                && contactIndexMembershipCurrent() ? contactsByRegistry : null;
    }

    public static int advance(Relations relations, int day, int year) {
        if (relations == null || !PeopleMod.identityPartitionsReady()) return 0;
        Map<String, CharacterRegistry> registries = new TreeMap<>();
        Set<String> mapIds = new TreeSet<>();
        for (CharacterIdentity identity : PeopleMod.authority().identities()) {
            if (!"__players__".equals(identity.mapId())) mapIds.add(identity.mapId());
        }
        for (String mapId : mapIds) {
            CharacterRegistry registry = PeopleMod.authority().registry(mapId);
            if (registry != null) registries.put(mapId, registry);
        }
        Map<CharacterRegistry, Map<String, List<Character>>> indexedGroups = contactIndex(day, year);
        if (indexedGroups == null) return 0; // Index construction is advanced incrementally by RelationsMod.
        long seed = WorldForgeMod.worldId() == null ? 0L : WorldForgeMod.worldId().toString().hashCode();
        int interactions = 0;
        // Family trees are stable during this daily pass; cache ancestry to avoid repeated traversal.
        Map<String, Map<String, Integer>> ancestryCache = new HashMap<>();
        String cursorWorld = String.valueOf(WorldForgeMod.worldId());
        synchronized (GROUP_CURSORS) {
            if (!cursorWorld.equals(groupCursorWorld)) {
                GROUP_CURSORS.clear();
                groupCursorWorld = cursorWorld;
            }
        }
        for (Map.Entry<String, CharacterRegistry> partition : registries.entrySet()) {
            CharacterRegistry registry = partition.getValue();
            Map<String, List<Character>> groups = indexedGroups.getOrDefault(registry, Map.of());
            List<Character> adults = new ArrayList<>();
            // Rotate a bounded window per partition. A fixed first-N cutoff starved
            // every later group indefinitely in large worlds.
            List<Map.Entry<String, List<Character>>> orderedGroups = new ArrayList<>(groups.entrySet());
            if (!orderedGroups.isEmpty()) {
                String cursorKey = cursorWorld + "|" + partition.getKey();
                int visitCount = Math.min(MAX_GROUPS_PER_DAY, orderedGroups.size());
                int start;
                synchronized (GROUP_CURSORS) {
                    start = Math.floorMod(GROUP_CURSORS.getOrDefault(cursorKey, 0), orderedGroups.size());
                    // Slide by one group: advancing by the full window can cycle over
                    // only a subset whenever group count and window size share a divisor.
                    GROUP_CURSORS.put(cursorKey, (start + 1) % orderedGroups.size());
                }
                for (int groupOffset = 0; groupOffset < visitCount; groupOffset++) {
                    Map.Entry<String, List<Character>> group =
                            orderedGroups.get((start + groupOffset) % orderedGroups.size());
                    List<Character> members = group.getValue();
                    adults.addAll(members);
                    if (members.size() < 2) continue;
                for (int slot = 0; slot < PAIRS_PER_GROUP_PER_DAY; slot++) {
                    // Keep a small set of neighbours interacting regularly for a quarter-year,
                    // then rotate the pairs so the whole settlement gradually builds social ties.
                    long interactionEpoch = Math.floorDiv(day, 90);
                    long hash = mix(seed ^ (interactionEpoch * 0xD6E8FEB86659FD93L)
                            ^ group.getKey().hashCode() ^ slot * 0x9E3779B97F4A7C15L);
                    int first = Math.floorMod((int) hash, members.size());
                    int second = Math.floorMod((int) (hash >>> 32), members.size() - 1);
                    if (second >= first) second++;
                    Character a = members.get(first), b = members.get(second);
                    // Prefer a biologically compatible adult pair when one exists in the same
                    // social band. The starting candidate remains deterministic; this avoids
                    // starving marriage simulation merely because random slots keep pairing
                    // same-gender neighbours in a small settlement.
                    if (a.gender.equals(b.gender)) {
                        for (int candidateOffset = 1; candidateOffset < members.size(); candidateOffset++) {
                            Character candidate = members.get((second + candidateOffset) % members.size());
                            if (!candidate.gender.equals(a.gender) && candidate.alive
                                    && year - candidate.birthYear >= 18) {
                                b = candidate;
                                break;
                            }
                        }
                    }
                    if (a == b || !a.alive || !b.alive || !canMeetLocally(a, b)) continue;
                    interact(relations, registry, a, b, group.getKey(), members, day, year, ancestryCache);
                    interactions++;
                    }
                }
            }

            // Traders and diplomatic envoys can establish ties beyond their home settlement.
            // Keep this bounded and deterministic; ordinary residents never receive this bypass.
            adults.sort(Comparator.comparing(person -> person.globalId));
            Map<String, List<Character>> adultsByBand = new TreeMap<>();
            for (Character adult : adults) {
                String band = String.valueOf(adult.props.getOrDefault("emergence.band_id", ""));
                adultsByBand.computeIfAbsent(band, ignored -> new ArrayList<>()).add(adult);
            }
            List<String> travelBands = new ArrayList<>(adultsByBand.keySet());
            if (travelBands.size() > 1) {
                long travelEpoch = Math.floorDiv(day, 30);
                for (Character traveler : adults) {
                    if (!hasTravelRole(traveler)) continue;
                    String homeBand = String.valueOf(traveler.props.getOrDefault("emergence.band_id", ""));
                    long travelHash = mix(seed ^ traveler.globalId.hashCode() ^ travelEpoch);
                    int startBand = Math.floorMod((int) travelHash, travelBands.size());
                    String targetBand = travelBands.get(startBand);
                    if (targetBand.equals(homeBand)) targetBand = travelBands.get((startBand + 1) % travelBands.size());
                    List<Character> candidates = adultsByBand.get(targetBand);
                    if (candidates == null || candidates.isEmpty()) continue;
                    Character candidate = candidates.get(Math.floorMod((int) (travelHash >>> 32), candidates.size()));
                    if (candidate == traveler) continue;
                    interact(relations, registry, traveler, candidate,
                            "travel:" + homeBand + ":" + targetBand, adults, day, year, ancestryCache);
                    interactions++;
                }
            }
        }
        return interactions;
    }

    private static boolean hasDiplomaticRole(Character person) {
        for (String role : List.of("diplomat", "envoy", "ambassador"))
            if (person.hasRole(role)) return true;
        Object role = person.props.get("emergence.polity_role");
        return role instanceof String value && List.of("diplomat", "envoy", "ambassador")
                .contains(value.toLowerCase(Locale.ROOT));
    }

    private static boolean canMeetLocally(Character a, Character b) {
        if (hasTravelRole(a) || hasTravelRole(b)) return true;
        Double ax = coordinate(a, "x"), az = coordinate(a, "z");
        Double bx = coordinate(b, "x"), bz = coordinate(b, "z");
        if (ax != null && az != null && bx != null && bz != null) {
            double dx = ax - bx, dz = az - bz;
            return dx * dx + dz * dz <= 256.0 * 256.0;
        }
        Object aBand = a.props.get("emergence.band_id"), bBand = b.props.get("emergence.band_id");
        return aBand instanceof String band && !band.isBlank() && Objects.equals(aBand, bBand);
    }

    private static Double coordinate(Character person, String axis) {
        for (String key : List.of("world." + axis, "position." + axis, "emergence." + axis,
                "population." + axis, "settlement." + axis)) {
            Object value = person.props.get(key);
            if (value instanceof Number number) return number.doubleValue();
        }
        return null;
    }

    private static boolean hasTravelRole(Character person) {
        for (String role : List.of("trader", "merchant", "diplomat", "envoy", "ambassador"))
            if (person.hasRole(role)) return true;
        Object role = person.props.get("emergence.polity_role");
        if (role instanceof String value && List.of("trader", "merchant", "diplomat", "envoy", "ambassador")
                .contains(value.toLowerCase(Locale.ROOT))) return true;
        List<String> traits = CharacterStats.traits(person);
        return traits.contains("trader") || traits.contains("diplomat") || traits.contains("merchant");
    }

    private static int kinshipDistance(Character a, Character b, CharacterRegistry registry,
                                      Map<String, Map<String, Integer>> ancestryCache) {
        if (a.globalId.equals(b.globalId)) return 0;
        Map<String, Integer> aa = ancestryCache.computeIfAbsent(a.globalId, ignored -> ancestors(a, registry));
        Map<String, Integer> bb = ancestryCache.computeIfAbsent(b.globalId, ignored -> ancestors(b, registry));
        int best = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : aa.entrySet()) {
            Integer other = bb.get(entry.getKey());
            if (other != null) best = Math.min(best, entry.getValue() + other);
        }
        return best;
    }

    private static Map<String, Integer> ancestors(Character person, CharacterRegistry registry) {
        Map<String, Integer> result = new HashMap<>();
        result.put(person.globalId, 0);
        ArrayDeque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        addParents(queue, person, registry);
        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> entry = queue.removeFirst();
            if (entry.getValue() > 6 || result.putIfAbsent(entry.getKey(), entry.getValue()) != null) continue;
            Character parent = PeopleMod.authority().getGlobal(entry.getKey());
            if (parent != null) addParents(queue, parent, registry, entry.getValue() + 1);
        }
        return result;
    }

    private static void addParents(Deque<Map.Entry<String, Integer>> queue, Character person,
                                   CharacterRegistry registry) {
        addParents(queue, person, registry, 1);
    }

    private static void addParents(Deque<Map.Entry<String, Integer>> queue, Character person,
                                   CharacterRegistry registry, int distance) {
        for (String key : List.of("family.father_global_id", "family.mother_global_id")) {
            Object value = person.props.get(key);
            if (value instanceof String id && !id.isBlank()) queue.add(Map.entry(id, distance));
        }
        for (String id : List.of(person.father, person.mother)) {
            if (id == null || id.isBlank()) continue;
            Character parent = registry.get(id);
            if (parent == null) parent = PeopleMod.authority().getGlobal(id);
            if (parent != null) queue.add(Map.entry(parent.globalId, distance));
        }
    }

    private static String identityValue(Character person, String... keys) {
        for (String key : keys) {
            Object value = person.props.get(key);
            if (value instanceof String text && !text.isBlank()) return text.trim();
        }
        return "";
    }

    private static void interact(Relations relations, CharacterRegistry registry, Character a, Character b,
                                 String groupId, List<Character> witnesses, int day, int year,
                                 Map<String, Map<String, Integer>> ancestryCache) {
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
        // Social compatibility is multi-dimensional: culture, language, faith, occupation,
        // house and political context influence affinity, but none of them is an absolute
        // barrier to friendship, rivalry, courtship or diplomacy.
        String cultureA = identityValue(a, "culture", "identity.culture", "emergence.culture_id");
        String cultureB = identityValue(b, "culture", "identity.culture", "emergence.culture_id");
        if (!cultureA.isBlank() && !cultureB.isBlank())
            delta += cultureA.equalsIgnoreCase(cultureB) ? 1 : -1;
        String languageA = identityValue(a, "language", "identity.language", "emergence.language_id");
        String languageB = identityValue(b, "language", "identity.language", "emergence.language_id");
        if (!languageA.isBlank() && !languageB.isBlank())
            delta += languageA.equalsIgnoreCase(languageB) ? 1 : -1;
        String faithA = identityValue(a, "religion", "faith", "identity.religion", "identity.faith");
        String faithB = identityValue(b, "religion", "faith", "identity.religion", "identity.faith");
        if (!faithA.isBlank() && !faithB.isBlank() && !faithA.equalsIgnoreCase(faithB)) {
            boolean tolerant = ta.contains("tolerant") || tb.contains("tolerant");
            boolean zealous = ta.contains("zealous") || tb.contains("zealous");
            delta += tolerant ? 0 : zealous ? -2 : -1;
        } else if (!faithA.isBlank() && faithA.equalsIgnoreCase(faithB)) delta++;
        String professionA = PopulationSimulation.profession(a);
        String professionB = PopulationSimulation.profession(b);
        if (!professionA.isBlank() && professionA.equals(professionB)) delta++;
        if (!a.house.isBlank() && a.house.equalsIgnoreCase(b.house)) delta++;
        String classA = identityValue(a, "social.class", "population.social_class", "identity.class");
        String classB = identityValue(b, "social.class", "population.social_class", "identity.class");
        if (!classA.isBlank() && !classB.isBlank() && !classA.equalsIgnoreCase(classB)) {
            if (ta.contains("egalitarian") || tb.contains("egalitarian")) delta++;
            else if (ta.contains("proud") || tb.contains("proud")) delta--;
        }
        String polityA = polityOf(a), polityB = polityOf(b);
        if (!polityA.isBlank() && !polityB.isBlank() && !polityA.equals(polityB)) delta--;
        delta = Math.max(-5, Math.min(5, delta));
        ab.affinity = clamp(ab.affinity + delta, -100, 100);
        ba.affinity = clamp(ba.affinity + delta, -100, 100);
        ab.lastContact = ba.lastContact = day;
        ab.met++; ba.met++;
        ab.talks++; ba.talks++;
        if (a.spouse.equals(b.id) && b.spouse.equals(a.id)) {
            ab.married = ba.married = true;
            ab.dating = ba.dating = false;
        }

        int kinship = kinshipDistance(a, b, registry, ancestryCache);
        // Parents, children and siblings are excluded from romance; distant kin are not
        // categorically blocked, but courtship develops more cautiously.
        boolean eligibleRomance = kinship > 2 && !a.gender.equals(b.gender)
                && a.spouse.isEmpty() && b.spouse.isEmpty()
                && year - a.birthYear >= 18 && year - b.birthYear >= 18
                && year - a.birthYear <= 70 && year - b.birthYear <= 70;
        if (eligibleRomance && ab.affinity >= 25) {
            int loveDelta = ta.contains("shy") || tb.contains("shy") ? 1 : 2;
            if (kinship >= 3 && kinship <= 5) loveDelta = Math.max(1, loveDelta - 1);
            if (ta.contains("gregarious") || tb.contains("gregarious")) loveDelta++;
            ab.love = clamp(ab.love + loveDelta, 0, 100);
            ba.love = clamp(ba.love + loveDelta, 0, 100);
            if (ab.affinity >= 40 && ab.love >= 35) { ab.dating = true; ba.dating = true; }
            long marriageRoll = mix(a.globalId.hashCode() * 31L + b.globalId.hashCode() + day + groupId.hashCode());
            if (ab.affinity >= 35 && ab.love >= 20 && Math.floorMod(marriageRoll, 8) == 0) {
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
                    RelationsMod.LOGGER.info("wf_relations: autonomous social event kind=marriage a={} b={} day={}",
                            a.globalId, b.globalId, day);
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
            RelationsMod.LOGGER.info("wf_relations: autonomous social event kind=friendship a={} b={} day={}",
                    a.globalId, b.globalId, day);
        } else if (oldAffinity > -30 && ab.affinity <= -30) {
            String polity = polityOf(a);
            String message = a.name + " и " + b.name + " стали соперниками";
            relations.history.add(message);
            trimHistory(relations);
            WorldForgeApi.postEvent("people", message,
                    polity.isBlank() ? List.of() : List.of(polity), Math.max(0L, (long) day * 1440L));
            reputation(a, polity, -2, day, "rivalry", witnesses);
            reputation(b, polity, -2, day, "rivalry", witnesses);
            RelationsMod.LOGGER.debug("wf_relations: autonomous social event kind=rivalry a={} b={} day={}",
                    a.globalId, b.globalId, day);
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
        String bandId = String.valueOf(witness.props.getOrDefault("emergence.band_id", polityId));
        if (bandId.isBlank()) bandId = polityId;
        List<Character> members = contactIndex(RelationsMc.dayNumber())
                .getOrDefault(registry, Map.of()).getOrDefault(bandId, List.of());
        if (members.isEmpty()) return List.of();
        List<Contact> result = new ArrayList<>();
        for (Character candidate : members) {
            if (candidate == witness || !candidate.alive || !Objects.equals(polityOf(candidate), polityId)) continue;
            Relations.Bond bond = RelationsMc.REL.peek("npc:" + witness.globalId, "npc:" + candidate.globalId);
            if (bond == null) continue;
            result.add(new Contact(candidate.globalId, bond.affinity, polityId, true, true));
        }
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
