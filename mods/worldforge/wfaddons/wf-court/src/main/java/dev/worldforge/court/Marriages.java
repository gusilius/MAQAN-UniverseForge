package dev.worldforge.court;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import java.util.*;

/** Династические браки. Долгоживущие пулы содержат только UUID и компактные метаданные. */
public final class Marriages {
    private Marriages() {}
    public static final int MIN_AGE = 16, TIE_OPINION = 30;
    private static final int SOURCE_RESERVOIR_SIZE = 32, PARTNER_RESERVOIR_SIZE = 16;

    public static final class Offer {
        public boolean ok;
        public int score;
        public final List<String> reasons = new ArrayList<>(), problems = new ArrayList<>();
        void add(String reason, int value) {
            reasons.add((value >= 0 ? "+" : "") + value + " " + reason);
            score += value;
        }
    }

    private record CandidateRef(String id, String globalId, String gender, int birthYear) {}
    private record Person(String id, String globalId, String name, String gender, int birthYear,
                          boolean alive, String spouse, boolean craven) {}

    public static String family(CourtSim sim, Character character) {
        return family(sim, character == null ? "" : character.id);
    }

    private static String family(CourtSim sim, String localId) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        if (localId != null && !localId.isBlank()) pending.add(localId);
        while (!pending.isEmpty()) {
            String currentId = pending.removeFirst();
            if (!visited.add(currentId)) continue;
            final String[] realm = {""}, father = {""}, mother = {""};
            withCharacterLease(sim.reg, currentId, current -> {
                if (current == null) return;
                realm[0] = sim.realmOf(current);
                father[0] = current.father;
                mother[0] = current.mother;
            });
            if (!realm[0].isBlank()) return realm[0];
            if (!father[0].isBlank()) pending.addLast(father[0]);
            if (!mother[0].isBlank()) pending.addLast(mother[0]);
        }
        return "";
    }

    public static boolean single(CourtSim sim, Character character) {
        return character != null && character.alive && character.spouse.isEmpty()
                && sim.year() - character.birthYear >= MIN_AGE;
    }

    private static boolean single(CourtSim sim, Person person) {
        return person != null && person.alive && person.spouse.isEmpty()
                && sim.year() - person.birthYear >= MIN_AGE;
    }

    private static Person snapshot(Character character) {
        return new Person(character.id, character.globalId, character.name, character.gender,
                character.birthYear, character.alive, character.spouse, character.traits.contains("craven"));
    }

    private static CandidateRef candidate(Character character) {
        return new CandidateRef(character.id, character.globalId, character.gender, character.birthYear);
    }

    private static Person readPerson(CourtSim sim, String localId) {
        final Person[] result = {null};
        withCharacterLease(sim.reg, localId, character -> {
            if (character != null) result[0] = snapshot(character);
        });
        return result[0];
    }

    /** Candidate UI projection returns local IDs, never Character references. */
    public static List<String> candidates(CourtSim sim, String realm) {
        List<CandidateRef> pool = new ArrayList<>();
        String sovereignId = rulerId(sim, realm);
        forEachCharacter(sim.reg, character -> {
            if (!single(sim, character) || character.id.equals(sovereignId)
                    || !family(sim, character.id).equals(realm)) return;
            pool.add(candidate(character));
        });
        pool.sort(Comparator.comparingInt(CandidateRef::birthYear).thenComparing(CandidateRef::id));
        return pool.stream().map(CandidateRef::id).toList();
    }

    private static String rulerId(CourtSim sim, String realm) {
        Character ruler = sim.ruler(realm);
        return ruler == null ? "" : ruler.id;
    }

    public static Offer evaluate(CourtSim sim, String charA, String charB) {
        return evaluate(sim, readPerson(sim, charA), readPerson(sim, charB));
    }

    private static Offer evaluate(CourtSim sim, Person a, Person b) {
        Offer offer = new Offer();
        if (!single(sim, a) || !single(sim, b)) {
            offer.problems.add("Оба должны быть живы, совершеннолетни и не состоять в браке");
            return offer;
        }
        if (a.gender.equals(b.gender)) {
            offer.problems.add("Брак возможен только между мужчиной и женщиной");
            return offer;
        }
        String realmA = family(sim, a.id), realmB = family(sim, b.id);
        if (realmA.isEmpty() || realmB.isEmpty() || realmA.equals(realmB)) {
            offer.problems.add("Нужны персонажи из разных государств");
            return offer;
        }
        if (sim.hooks.atWar(realmA) && sim.hooks.atWar(realmB)) offer.problems.add("Государства воюют");
        String rulerA = rulerId(sim, realmA), rulerB = rulerId(sim, realmB);
        if (rulerA.isBlank() || rulerB.isBlank()) {
            offer.problems.add("У государства нет правителя");
            return offer;
        }
        if (!offer.problems.isEmpty()) return offer;
        offer.add("Мнение правителя о просителе", Math.max(-40, Math.min(40, sim.opinion(rulerB, rulerA))));
        int council = (int) Math.round(sim.council(realmA).getOrDefault("ai_accept", 0.0) / 2);
        if (council != 0) offer.add("Канцлер просителя", Math.min(15, council));
        int ageGap = Math.abs(a.birthYear - b.birthYear);
        if (ageGap > 12) offer.add("Разница в возрасте", -Math.min(20, ageGap - 12));
        if (a.craven || b.craven) offer.add("Трусость жениха или невесты", -3);
        offer.add("Выгода союза", 5);
        offer.ok = offer.score >= 0;
        return offer;
    }

    public static Offer propose(CourtSim sim, String charA, String charB) {
        Person a = readPerson(sim, charA), b = readPerson(sim, charB);
        Offer offer = evaluate(sim, a, b);
        if (!offer.ok || a == null || b == null) return offer;
        String realmA = family(sim, a.id), realmB = family(sim, b.id);
        final boolean[] married = {false};
        withCharacterLease(sim.reg, a.id, first ->
                withCharacterLease(sim.reg, b.id, second -> {
                    if (first == null || second == null) return;
                    sim.reg.marry(first.id, second.id);
                    married[0] = true;
                }));
        if (!married[0]) {
            offer.ok = false;
            offer.problems.add("Один из участников брака больше недоступен");
            return offer;
        }
        String rulerA = rulerId(sim, realmA), rulerB = rulerId(sim, realmB);
        if (!rulerA.isBlank() && !rulerB.isBlank()) {
            sim.opinions.add(rulerA, rulerB, "Династический брак", TIE_OPINION, 0);
            sim.opinions.add(rulerB, rulerA, "Династический брак", TIE_OPINION, 0);
        }
        sim.announce("Брак: " + a.name + " (" + sim.realmName(realmA) + ") и "
                + b.name + " (" + sim.realmName(realmB) + ")");
        sim.hooks.marriage(realmA, realmB);
        sim.syncProps();
        return offer;
    }

    public static Set<String> ties(CourtSim sim, String realm) {
        Set<String> result = new TreeSet<>();
        forEachCharacter(sim.reg, character -> {
            if (!character.alive || character.spouse.isEmpty()) return;
            String firstRealm = family(sim, character.id);
            Person spouse = readPerson(sim, character.spouse);
            if (spouse == null || !spouse.alive) return;
            String secondRealm = family(sim, spouse.id);
            if (firstRealm.equals(realm) && !secondRealm.isEmpty() && !secondRealm.equals(realm))
                result.add(secondRealm);
        });
        return result;
    }

    /** Annual AI uses bounded deterministic reservoirs, not population-sized Character lists. */
    public static void aiYear(CourtSim sim) {
        List<String> realms = new ArrayList<>();
        for (var realm : sim.d.realmList()) if (!sim.humans.contains(realm.id)) realms.add(realm.id);
        Map<String, List<CandidateRef>> sourcePools = new HashMap<>();
        Map<String, Map<String, List<CandidateRef>>> partnerPools = new HashMap<>();
        Map<String, Integer> sourceSeen = new HashMap<>(), partnerSeen = new HashMap<>();
        Map<String, String> rulers = new HashMap<>();

        forEachCharacter(sim.reg, character -> {
            if (!single(sim, character)) return;
            String realm = family(sim, character.id);
            if (realm.isEmpty()) return;
            String sovereignId = rulers.computeIfAbsent(realm, key -> rulerId(sim, key));
            if (character.id.equals(sovereignId)) return;
            CandidateRef ref = candidate(character);
            int seenInRealm = sourceSeen.merge(realm, 1, Integer::sum);
            reservoirAdd(sourcePools.computeIfAbsent(realm, ignored -> new ArrayList<>()), ref,
                    seenInRealm, SOURCE_RESERVOIR_SIZE,
                    sim.rnd("marry:source-reservoir:" + realm, ref.globalId.hashCode() ^ (long) seenInRealm));
            String genderKey = realm + "|" + ref.gender;
            int seenInGender = partnerSeen.merge(genderKey, 1, Integer::sum);
            List<CandidateRef> genderPool = partnerPools.computeIfAbsent(realm, ignored -> new TreeMap<>())
                    .computeIfAbsent(ref.gender, ignored -> new ArrayList<>());
            reservoirAdd(genderPool, ref, seenInGender, PARTNER_RESERVOIR_SIZE,
                    sim.rnd("marry:partner-reservoir:" + genderKey, ref.globalId.hashCode() ^ (long) seenInGender));
        });

        Comparator<CandidateRef> order = Comparator.comparingInt(CandidateRef::birthYear).thenComparing(CandidateRef::id);
        sourcePools.values().forEach(pool -> pool.sort(order));
        partnerPools.values().forEach(byGender -> byGender.values().forEach(pool -> pool.sort(order)));
        final int maxSourceCandidates = 4, maxPartnerRealms = 16, maxCandidatesPerPartnerRealm = 4;
        for (String realmA : realms) {
            if (sim.rnd("marry", realmA.hashCode() + sim.day).nextDouble() > 0.35) continue;
            List<CandidateRef> sourcePool = sourcePools.getOrDefault(realmA, List.of());
            if (sourcePool.isEmpty() || realms.size() < 2) continue;
            CandidateRef bestA = null, bestB = null;
            int bestScore = Integer.MIN_VALUE;
            int sourceStart = sim.rnd("marry:sources:" + realmA, sim.day).nextInt(sourcePool.size());
            int sourceCount = Math.min(maxSourceCandidates, sourcePool.size());
            for (int sourceOffset = 0; sourceOffset < sourceCount; sourceOffset++) {
                CandidateRef a = sourcePool.get((sourceStart + sourceOffset) % sourcePool.size());
                Random partnerOrder = sim.rnd("marry:partners:" + a.id, realmA.hashCode() + sim.day);
                int start = partnerOrder.nextInt(realms.size());
                int attempts = Math.min(realms.size(), maxPartnerRealms + 1);
                for (int attempt = 0; attempt < attempts; attempt++) {
                    String realmB = realms.get((start + attempt) % realms.size());
                    if (realmB.equals(realmA)) continue;
                    Map<String, List<CandidateRef>> byGender = partnerPools.getOrDefault(realmB, Map.of());
                    for (Map.Entry<String, List<CandidateRef>> genderPool : byGender.entrySet()) {
                        if (a.gender.equals(genderPool.getKey())) continue;
                        List<CandidateRef> pool = genderPool.getValue();
                        if (pool.isEmpty()) continue;
                        int candidateStart = sim.rnd("marry:candidates:" + a.globalId + ":" + realmB
                                + ":" + genderPool.getKey(), sim.day).nextInt(pool.size());
                        int candidateCount = Math.min(maxCandidatesPerPartnerRealm, pool.size());
                        for (int candidateOffset = 0; candidateOffset < candidateCount; candidateOffset++) {
                            CandidateRef b = pool.get((candidateStart + candidateOffset) % pool.size());
                            Offer offer = evaluate(sim, a.id, b.id);
                            if (offer.ok && offer.score > bestScore) {
                                bestScore = offer.score;
                                bestA = a;
                                bestB = b;
                            }
                        }
                    }
                }
            }
            if (bestA != null) propose(sim, bestA.id, bestB.id);
        }
    }

    private static void reservoirAdd(List<CandidateRef> pool, CandidateRef value, int seen,
                                     int capacity, Random random) {
        if (pool.size() < capacity) { pool.add(value); return; }
        int slot = random.nextInt(seen);
        if (slot < capacity) pool.set(slot, value);
    }

    private static void withCharacterLease(CharacterRegistry registry, String localId,
                                           java.util.function.Consumer<Character> visitor) {
        if (localId == null || localId.isBlank()) { visitor.accept(null); return; }
        Object lease = null;
        try {
            Object access = registry.getClass().getMethod("access").invoke(registry);
            lease = access.getClass().getMethod("acquire", String.class).invoke(access, localId);
            if (lease == null) { visitor.accept(null); return; }
            visitor.accept((Character) lease.getClass().getMethod("get").invoke(lease));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("wf-court could not acquire Character lease " + localId, error);
        } finally {
            if (lease instanceof AutoCloseable closeable) {
                try { closeable.close(); }
                catch (Exception error) { throw new IllegalStateException("wf-court could not release Character lease " + localId, error); }
            }
        }
    }

    private static void forEachCharacter(CharacterRegistry registry,
                                         java.util.function.Consumer<Character> visitor) {
        try {
            java.lang.reflect.Method idPage = registry.getClass().getMethod("idPage", int.class, int.class);
            Object access = registry.getClass().getMethod("access").invoke(registry);
            java.lang.reflect.Method acquire = access.getClass().getMethod("acquire", String.class);
            int count = registry.size();
            for (int offset = 0; offset < count; offset += 256) {
                Object raw = idPage.invoke(registry, offset, 256);
                if (!(raw instanceof List<?> ids)) throw new IllegalStateException("Character ID page is not a list");
                for (Object rawId : ids) {
                    Object lease = acquire.invoke(access, String.valueOf(rawId));
                    if (lease == null) throw new IllegalStateException("Could not lease Character " + rawId);
                    try { visitor.accept((Character) lease.getClass().getMethod("get").invoke(lease)); }
                    finally { ((AutoCloseable) lease).close(); }
                }
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("wf-court requires bounded Character ID pages", error);
        } catch (Exception error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not release wf-court Character lease", error);
        }
    }
}
