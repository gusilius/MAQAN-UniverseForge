package dev.worldforge.relations;

import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
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
    private static final int TRAVEL_SAMPLES_PER_GROUP_PER_DAY = 4;
    private static final int MAX_COMPATIBLE_PARTNER_PROBES = 64;
    /** Last processed band per canonical registry; identity keys avoid synthetic string allocations. */
    private static final Map<CharacterRegistry, String> GROUP_CURSORS = new IdentityHashMap<>();
    private static String groupCursorWorld = "";
    private static long groupCursorRegistryGeneration = Long.MIN_VALUE;
    private static int partitionCursor;

    private static volatile long contactIndexDay = Long.MIN_VALUE;
    private static volatile String contactIndexWorld = "";
    private static volatile Map<CharacterRegistry, Map<String, List<Character>>> contactsByRegistry = Map.of();

    private static final int CONTACT_INDEX_CHARACTERS_PER_TICK = 2048;
    // A count-only budget is unsafe when Character extension data varies widely.
    // Cap wall-clock work as well, checking periodically to keep timer overhead low.
    private static final long CONTACT_INDEX_NANOS_PER_TICK = 2_000_000L;
    private static final int CONTACT_INDEX_DEADLINE_CHECK_MASK = 31;

    private static final class RegistryScan {
        final CharacterRegistry registry;
        final List<Character> people;
        final long membershipGeneration;
        int limit;
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
        long membershipRevision;
        final List<RegistryScan> scans;
        // These maps remain private and are published through read-only views. Appends
        // extend their lists in place, so an index remains reusable during population growth.
        final Map<CharacterRegistry, NavigableMap<String, List<Character>>> groups = new IdentityHashMap<>();
        final Map<CharacterRegistry, NavigableMap<String, List<Character>>> publishedGroups = new IdentityHashMap<>();
        int scanIndex;
        boolean published;

        ContactIndexBuild(long day, int year, String world, long registryGeneration,
                          long membershipRevision, List<RegistryScan> scans) {
            this.day = day;
            this.year = year;
            this.world = world;
            this.registryGeneration = registryGeneration;
            this.membershipRevision = membershipRevision;
            this.scans = scans;
            for (RegistryScan scan : scans) groups.put(scan.registry, new TreeMap<>());
        }
    }

    private static ContactIndexBuild contactIndexBuild;
    private static volatile int contactIndexYear = Integer.MIN_VALUE;
    private static volatile long contactIndexRegistryGeneration = Long.MIN_VALUE;
    private static volatile long contactIndexMembershipRevision = Long.MIN_VALUE;

    /**
     * Incrementally prepares the daily social index. CharacterRegistry.all() is an append-only
     * view, so each partition's captured size can be scanned safely over multiple server ticks.
     * This prevents a million-character world from rebuilding its entire social index in one tick.
     */
    public static synchronized boolean prepareContactIndex(long day, int year, int characterBudget) {
        String world = String.valueOf(WorldForgeMod.worldId());
        long registryGeneration = PeopleMod.populationRegistryGeneration();
        long membershipRevision = CharacterRegistry.globalMembershipRevision();
        if (contactIndexDay == day && contactIndexYear == year && contactIndexWorld.equals(world)
                && contactIndexRegistryGeneration == registryGeneration
                && contactIndexMembershipRevision == membershipRevision) return true;
        if (!PeopleMod.identityPartitionsReady()) return false;

        // Registry membership and Character membership are different dimensions. A new
        // registry requires a new scan plan; appending residents to an existing append-only
        // registry must extend the current cursor rather than discard all work already done.
        if (contactIndexBuild == null || contactIndexBuild.day != day
                || contactIndexBuild.year != year || !contactIndexBuild.world.equals(world)
                || contactIndexBuild.registryGeneration != registryGeneration) {
            List<RegistryScan> scans = new ArrayList<>();
            for (CharacterRegistry registry : PeopleMod.populationRegistriesSnapshot()) {
                if (registry != null && registry != PeopleMod.playerRegistry())
                    scans.add(new RegistryScan(registry));
            }
            contactIndexBuild = new ContactIndexBuild(day, year, world, registryGeneration,
                    membershipRevision, scans);
            // The previous day/world snapshot is no longer queryable. Release its band
            // lists immediately instead of retaining two population-sized indexes while
            // the next one is built incrementally.
            contactsByRegistry = Map.of();
            contactIndexDay = Long.MIN_VALUE;
            contactIndexYear = Integer.MIN_VALUE;
            contactIndexWorld = "";
            contactIndexRegistryGeneration = Long.MIN_VALUE;
            contactIndexMembershipRevision = Long.MIN_VALUE;
        }

        ContactIndexBuild build = contactIndexBuild;
        // Detect append-only growth in every partition, including partitions whose first
        // scan already completed. Rewind only to the first newly extended partition.
        for (int i = 0; i < build.scans.size(); i++) {
            RegistryScan scan = build.scans.get(i);
            if (scan.registry.membershipGeneration() != scan.membershipGeneration) {
                contactIndexBuild = null;
                return false; // A wholesale restore gets a fresh, consistent scan next tick.
            }
            int currentSize = scan.people.size();
            if (currentSize > scan.limit) scan.limit = currentSize;
            if (scan.cursor < scan.limit && i < build.scanIndex) build.scanIndex = i;
        }

        int remaining = Math.max(1, Math.min(CONTACT_INDEX_CHARACTERS_PER_TICK, characterBudget));
        long deadline = System.nanoTime() + CONTACT_INDEX_NANOS_PER_TICK;
        int processed = 0;
        while (remaining > 0 && build.scanIndex < build.scans.size()) {
            RegistryScan scan = build.scans.get(build.scanIndex);
            while (remaining > 0 && scan.cursor < scan.limit) {
                if (processed > 0 && (processed & CONTACT_INDEX_DEADLINE_CHECK_MASK) == 0
                        && System.nanoTime() >= deadline) return false;
                Character person = scan.people.get(scan.cursor++);
                processed++;
                remaining--;
                if (person == null || !person.alive || year - person.birthYear < 18
                        || !person.props.containsKey("emergence.polity_id")) continue;
                String polity = polityOf(person);
                if (polity.isBlank()) continue;
                String band = String.valueOf(person.props.getOrDefault("emergence.band_id", polity));
                if (band.isBlank()) band = polity;
                NavigableMap<String, List<Character>> byBand = build.groups.get(scan.registry);
                List<Character> members = byBand.get(band);
                if (members == null) {
                    members = new ArrayList<>();
                    byBand.put(band, members);
                    if (build.published) {
                        build.publishedGroups.get(scan.registry)
                                .put(band, Collections.unmodifiableList(members));
                    }
                }
                members.add(person);
            }
            if (scan.cursor >= scan.limit) build.scanIndex++;
        }

        if (build.scanIndex < build.scans.size()) return false;
        if (build.registryGeneration != PeopleMod.populationRegistryGeneration()
                || build.scans.stream().anyMatch(scan ->
                        scan.registry.membershipGeneration() != scan.membershipGeneration)) {
            contactIndexBuild = null;
            return false;
        }

        // Recheck live sizes before publishing. If a partition grew while the scan was
        // running, continue from its old cursor on the next tick instead of restarting.
        for (int i = 0; i < build.scans.size(); i++) {
            RegistryScan scan = build.scans.get(i);
            int currentSize = scan.people.size();
            if (currentSize > scan.limit) scan.limit = currentSize;
            if (scan.cursor < scan.limit) {
                build.scanIndex = i;
                return false;
            }
        }

        // Publish only against the revision observed at method entry. If another
        // mutation raced the scan, leave the previous revision in place so the next
        // tick necessarily resumes the newly appended tail instead of blessing a gap.
        if (CharacterRegistry.globalMembershipRevision() != membershipRevision) return false;
        build.membershipRevision = membershipRevision;
        if (!build.published) {
            Map<CharacterRegistry, Map<String, List<Character>>> frozen = new IdentityHashMap<>();
            build.groups.forEach((registry, byBand) -> {
                NavigableMap<String, List<Character>> readOnlyBands = new TreeMap<>();
                byBand.forEach((band, members) ->
                        readOnlyBands.put(band, Collections.unmodifiableList(members)));
                build.publishedGroups.put(registry, readOnlyBands);
                frozen.put(registry, Collections.unmodifiableNavigableMap(readOnlyBands));
            });
            contactsByRegistry = Collections.unmodifiableMap(frozen);
            build.published = true;
        }
        contactIndexMembershipRevision = membershipRevision;
        contactIndexRegistryGeneration = build.registryGeneration;
        contactIndexWorld = build.world;
        contactIndexYear = build.year;
        contactIndexDay = build.day;
        return true;
    }

    private static Map<CharacterRegistry, Map<String, List<Character>>> contactIndex(long day, int year) {
        String world = String.valueOf(WorldForgeMod.worldId());
        return contactIndexDay == day && contactIndexYear == year && contactIndexWorld.equals(world)
                && contactIndexRegistryGeneration == PeopleMod.populationRegistryGeneration()
                && contactIndexMembershipRevision == CharacterRegistry.globalMembershipRevision()
                ? contactsByRegistry : null;
    }

    public static int advance(Relations relations, int day, int year) {
        if (relations == null || !PeopleMod.identityPartitionsReady()) return 0;
        Map<CharacterRegistry, Map<String, List<Character>>> indexedGroups = contactIndex(day, year);
        if (indexedGroups == null) return 0; // Index construction is advanced incrementally by RelationsMod.

        List<CharacterRegistry> registries = PeopleMod.populationRegistriesSnapshot();
        int registryCount = registries.size();
        if (registryCount > 0 && registries.get(registryCount - 1) == PeopleMod.playerRegistry())
            registryCount--;
        if (registryCount <= 0) return 0;

        long seed = WorldForgeMod.worldId() == null ? 0L : WorldForgeMod.worldId().toString().hashCode();
        String cursorWorld = String.valueOf(WorldForgeMod.worldId());
        long registryGeneration = PeopleMod.populationRegistryGeneration();
        synchronized (GROUP_CURSORS) {
            if (!cursorWorld.equals(groupCursorWorld)
                    || groupCursorRegistryGeneration != registryGeneration) {
                GROUP_CURSORS.clear();
                groupCursorWorld = cursorWorld;
                groupCursorRegistryGeneration = registryGeneration;
                partitionCursor = 0;
            }
        }

        int visitCount = Math.min(MAX_GROUPS_PER_DAY, registryCount);
        int start = Math.floorMod(partitionCursor, registryCount);
        int baseShare = MAX_GROUPS_PER_DAY / visitCount;
        int extraShare = MAX_GROUPS_PER_DAY % visitCount;
        int interactions = 0;
        Map<String, Map<String, Integer>> ancestryCache = new HashMap<>();
        // Linked insertion order follows the stable canonical registry snapshot, keeping
        // travel interactions deterministic across server restarts.
        Map<CharacterRegistry, List<Character>> travelCandidates = new LinkedHashMap<>();

        for (int offset = 0; offset < visitCount; offset++) {
            CharacterRegistry registry = registries.get((start + offset) % registryCount);
            Map<String, List<Character>> rawGroups = indexedGroups.getOrDefault(registry, Map.of());
            if (!(rawGroups instanceof NavigableMap<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            NavigableMap<String, List<Character>> groups =
                    (NavigableMap<String, List<Character>>) rawGroups;
            if (groups.isEmpty()) continue;

            int share = baseShare + (offset < extraShare ? 1 : 0);
            int groupLimit = Math.min(share, groups.size());
            String lastGroup;
            synchronized (GROUP_CURSORS) { lastGroup = GROUP_CURSORS.get(registry); }
            for (int groupOffset = 0; groupOffset < groupLimit; groupOffset++) {
                Map.Entry<String, List<Character>> group =
                        lastGroup == null ? groups.firstEntry() : groups.higherEntry(lastGroup);
                if (group == null) group = groups.firstEntry();
                if (group == null) break;
                lastGroup = group.getKey();
                List<Character> members = group.getValue();

                int travelSamples = Math.min(TRAVEL_SAMPLES_PER_GROUP_PER_DAY, members.size());
                if (travelSamples > 0) {
                    long sampleHash = mix(seed ^ group.getKey().hashCode()
                            ^ ((long) day * 0x9E3779B97F4A7C15L));
                    int sampleStart = Math.floorMod((int) sampleHash, members.size());
                    List<Character> registryTravelCandidates =
                            travelCandidates.computeIfAbsent(registry, ignored -> new ArrayList<>());
                    for (int sample = 0; sample < travelSamples; sample++)
                        registryTravelCandidates.add(members.get((sampleStart + sample) % members.size()));
                }
                interactions += processSocialGroup(relations, registry, group.getKey(), members,
                        day, year, seed, ancestryCache);
            }
            synchronized (GROUP_CURSORS) { GROUP_CURSORS.put(registry, lastGroup); }
        }

        // The partition window advances by a coprime stride: no registry is permanently
        // excluded when partition count and the 256-group budget share a divisor.
        partitionCursor = registryCount <= MAX_GROUPS_PER_DAY
                ? 0 : (start + rotationStride(visitCount, registryCount)) % registryCount;

        // Cross-band travel is evaluated independently inside each canonical registry:
        // kinship checks and marriage writes must never cross registry ownership boundaries.
        for (Map.Entry<CharacterRegistry, List<Character>> travelEntry : travelCandidates.entrySet()) {
            CharacterRegistry registry = travelEntry.getKey();
            List<Character> candidatesForRegistry = travelEntry.getValue();
            Map<String, List<Character>> adultsByBand = new TreeMap<>();
            for (Character adult : candidatesForRegistry) {
                String band = String.valueOf(adult.props.getOrDefault("emergence.band_id", ""));
                adultsByBand.computeIfAbsent(band, ignored -> new ArrayList<>()).add(adult);
            }
            List<String> travelBands = new ArrayList<>(adultsByBand.keySet());
            if (travelBands.size() <= 1) continue;
            long travelEpoch = Math.floorDiv(day, 30);
            for (Character traveler : candidatesForRegistry) {
                if (!traveler.alive || !hasTravelRole(traveler)) continue;
                String homeBand = String.valueOf(traveler.props.getOrDefault("emergence.band_id", ""));
                long travelHash = mix(seed ^ traveler.globalId.hashCode() ^ travelEpoch);
                int startBand = Math.floorMod((int) travelHash, travelBands.size());
                String targetBand = travelBands.get(startBand);
                if (targetBand.equals(homeBand))
                    targetBand = travelBands.get((startBand + 1) % travelBands.size());
                List<Character> candidates = adultsByBand.get(targetBand);
                if (candidates == null || candidates.isEmpty()) continue;
                Character candidate = candidates.get(Math.floorMod((int) (travelHash >>> 32), candidates.size()));
                if (candidate == traveler || !candidate.alive) continue;
                interact(relations, registry, traveler, candidate,
                        "travel:" + homeBand + ":" + targetBand, candidatesForRegistry,
                        day, year, ancestryCache);
                interactions++;
            }
        }
        return interactions;
    }

    private static int processSocialGroup(Relations relations, CharacterRegistry registry, String groupId,
                                          List<Character> members, int day, int year, long seed,
                                          Map<String, Map<String, Integer>> ancestryCache) {
        if (members.size() < 2) return 0;
        int interactions = 0;
        long interactionEpoch = Math.floorDiv(day, 90);
        for (int slot = 0; slot < PAIRS_PER_GROUP_PER_DAY; slot++) {
            long hash = mix(seed ^ (interactionEpoch * 0xD6E8FEB86659FD93L)
                    ^ groupId.hashCode() ^ slot * 0x9E3779B97F4A7C15L);
            int first = Math.floorMod((int) hash, members.size());
            int second = Math.floorMod((int) (hash >>> 32), members.size() - 1);
            if (second >= first) second++;
            Character a = members.get(first), b = members.get(second);
            if (a.gender.equals(b.gender)) {
                for (int candidateOffset = 1;
                     candidateOffset < Math.min(members.size(), MAX_COMPATIBLE_PARTNER_PROBES);
                     candidateOffset++) {
                    Character candidate = members.get((second + candidateOffset) % members.size());
                    if (!candidate.gender.equals(a.gender) && candidate.alive
                            && year - candidate.birthYear >= 18) {
                        b = candidate;
                        break;
                    }
                }
            }
            if (a == b || !a.alive || !b.alive || !canMeetLocally(a, b)) continue;
            interact(relations, registry, a, b, groupId, members, day, year, ancestryCache);
            interactions++;
        }
        return interactions;
    }

    private static int rotationStride(int minimum, int size) {
        if (size <= 1) return 1;
        int stride = Math.max(1, Math.min(minimum, size - 1));
        while (stride < size && gcd(stride, size) != 1) stride++;
        return stride < size ? stride : 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int next = a % b; a = b; b = next; }
        return Math.abs(a);
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

        int delta = 1;
        if (CharacterStats.hasTrait(a, "gregarious") || CharacterStats.hasTrait(b, "gregarious")) delta++;
        if (CharacterStats.hasTrait(a, "generous") && CharacterStats.hasTrait(b, "generous")) delta++;
        if (CharacterStats.hasTrait(a, "loyal") && CharacterStats.hasTrait(b, "loyal")) delta++;
        if ((CharacterStats.hasTrait(a, "cruel") && CharacterStats.hasTrait(b, "just")) || (CharacterStats.hasTrait(b, "cruel") && CharacterStats.hasTrait(a, "just"))) delta -= 3;
        if ((CharacterStats.hasTrait(a, "deceitful") && CharacterStats.hasTrait(b, "honorable")) || (CharacterStats.hasTrait(b, "deceitful") && CharacterStats.hasTrait(a, "honorable"))) delta -= 2;
        if (CharacterStats.hasTrait(a, "wroth") && CharacterStats.hasTrait(b, "wroth")) delta--;
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
            boolean tolerant = CharacterStats.hasTrait(a, "tolerant") || CharacterStats.hasTrait(b, "tolerant");
            boolean zealous = CharacterStats.hasTrait(a, "zealous") || CharacterStats.hasTrait(b, "zealous");
            delta += tolerant ? 0 : zealous ? -2 : -1;
        } else if (!faithA.isBlank() && faithA.equalsIgnoreCase(faithB)) delta++;
        String professionA = PopulationSimulation.profession(a);
        String professionB = PopulationSimulation.profession(b);
        if (!professionA.isBlank() && professionA.equals(professionB)) delta++;
        if (!a.house.isBlank() && a.house.equalsIgnoreCase(b.house)) delta++;
        String classA = identityValue(a, "social.class", "population.social_class", "identity.class");
        String classB = identityValue(b, "social.class", "population.social_class", "identity.class");
        if (!classA.isBlank() && !classB.isBlank() && !classA.equalsIgnoreCase(classB)) {
            if (CharacterStats.hasTrait(a, "egalitarian") || CharacterStats.hasTrait(b, "egalitarian")) delta++;
            else if (CharacterStats.hasTrait(a, "proud") || CharacterStats.hasTrait(b, "proud")) delta--;
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
            int loveDelta = CharacterStats.hasTrait(a, "shy") || CharacterStats.hasTrait(b, "shy") ? 1 : 2;
            if (kinship >= 3 && kinship <= 5) loveDelta = Math.max(1, loveDelta - 1);
            if (CharacterStats.hasTrait(a, "gregarious") || CharacterStats.hasTrait(b, "gregarious")) loveDelta++;
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
        int day = RelationsMc.dayNumber();
        int year = WorldForgeApi.clock().startYear + Math.floorDiv(day, 365);
        Map<CharacterRegistry, Map<String, List<Character>>> index = contactIndex(day, year);
        if (index == null) return List.of();
        List<Character> members = index.getOrDefault(registry, Map.of()).getOrDefault(bandId, List.of());
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
