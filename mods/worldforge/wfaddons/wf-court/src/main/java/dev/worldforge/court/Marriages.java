package dev.worldforge.court;

import dev.worldforge.people.Character;

import java.util.*;

/**
 * Династические браки между государствами. Предложение оценивается как «мнение» (мнение правителя невесты/жениха о просителе
 * плюс склонность его совета к договорам и мелкие слагаемые), поэтому игроку понятно, почему отказали.
 * Родственные узлы между государствами живут, пока жив хотя бы один такой брак; они читаются как свойство `people.ties`.
 */
public final class Marriages {
    private Marriages() {}

    public static final int MIN_AGE = 16, TIE_OPINION = 30;

    public static final class Offer {
        public boolean ok;
        public int score;
        public final List<String> reasons = new ArrayList<>(), problems = new ArrayList<>();
        void add(String r, int v) { reasons.add((v >= 0 ? "+" : "") + v + " " + r); score += v; }
    }

    /** Государство, к чьему дому относится персонаж: свой пост, иначе государство отца или матери. */
    public static String family(CourtSim s, Character c) {
        // Follow the complete known lineage. A fixed four-generation limit made older
        // dynasties silently lose their realm affiliation. The visited set also protects
        // imported or corrupted family graphs from cycles.
        Set<String> visited = new HashSet<>();
        ArrayDeque<Character> pending = new ArrayDeque<>();
        if (c != null) pending.add(c);
        while (!pending.isEmpty()) {
            Character current = pending.removeFirst();
            if (current == null || !visited.add(current.globalId)) continue;
            String realm = s.realmOf(current);
            if (!realm.isEmpty()) return realm;
            // Preserve the established paternal-first preference, but do not discard
            // the maternal branch when the father link is absent or unresolved.
            Character father = current.father.isEmpty() ? null : s.reg.get(current.father);
            Character mother = current.mother.isEmpty() ? null : s.reg.get(current.mother);
            if (father != null) pending.addLast(father);
            if (mother != null) pending.addLast(mother);
        }
        return "";
    }

    public static boolean single(CourtSim s, Character c) {
        return c != null && c.alive && c.spouse.isEmpty() && s.year() - c.birthYear >= MIN_AGE;
    }

    public static List<Character> candidates(CourtSim s, String realm) {
        List<Character> out = new ArrayList<>();
        Character ruler = s.ruler(realm);
        for (Character c : s.reg.all()) if (single(s, c) && family(s, c).equals(realm) && c != ruler) out.add(c);
        out.sort(Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.id));
        return out;
    }

    /** realmA предлагает брак своего charA с charB из другого государства (оценивает правитель государства B). */
    public static Offer evaluate(CourtSim s, String charA, String charB) {
        Offer o = new Offer();
        Character a = s.reg.get(charA), b = s.reg.get(charB);
        if (!single(s, a) || !single(s, b)) { o.problems.add("Оба должны быть живы, совершеннолетни и не состоять в браке"); return o; }
        if (a.gender.equals(b.gender)) { o.problems.add("Брак возможен только между мужчиной и женщиной"); return o; }
        String ra = family(s, a), rb = family(s, b);
        if (ra.isEmpty() || rb.isEmpty() || ra.equals(rb)) { o.problems.add("Нужны персонажи из разных государств"); return o; }
        if (s.hooks.atWar(ra) && s.hooks.atWar(rb)) { o.problems.add("Государства воюют"); }
        Character kinA = s.ruler(ra), kinB = s.ruler(rb);
        if (kinA == null || kinB == null) { o.problems.add("У государства нет правителя"); return o; }
        if (!o.problems.isEmpty()) return o;
        o.add("Мнение правителя о просителе", Math.max(-40, Math.min(40, s.opinion(kinB.id, kinA.id))));
        int cn = (int) Math.round(s.council(ra).getOrDefault("ai_accept", 0.0) / 2);
        if (cn != 0) o.add("Канцлер просителя", Math.min(15, cn));
        int gap = Math.abs(a.birthYear - b.birthYear);
        if (gap > 12) o.add("Разница в возрасте", -Math.min(20, gap - 12));
        if (a.traits.contains("craven") || b.traits.contains("craven")) o.add("Трусость жениха или невесты", -3);
        o.add("Выгода союза", 5);
        o.ok = o.score >= 0;
        return o;
    }

    /** Выполняет брак, если предложение принято. Возвращает оценку. */
    public static Offer propose(CourtSim s, String charA, String charB) {
        Offer o = evaluate(s, charA, charB);
        if (!o.ok) return o;
        Character a = s.reg.get(charA), b = s.reg.get(charB);
        String ra = family(s, a), rb = family(s, b);
        s.reg.marry(a.id, b.id);
        Character ka = s.ruler(ra), kb = s.ruler(rb);
        if (ka != null && kb != null) {
            s.opinions.add(ka.id, kb.id, "Династический брак", TIE_OPINION, 0);
            s.opinions.add(kb.id, ka.id, "Династический брак", TIE_OPINION, 0);
        }
        s.announce("Брак: " + a.name + " (" + s.realmName(ra) + ") и " + b.name + " (" + s.realmName(rb) + ")");
        s.hooks.marriage(ra, rb);
        s.syncProps();
        return o;
    }

    /** Государства, связанные с realm живым браком. */
    public static Set<String> ties(CourtSim s, String realm) {
        Set<String> out = new TreeSet<>();
        for (Character c : s.reg.all()) {
            if (!c.alive || c.spouse.isEmpty()) continue;
            Character sp = s.reg.get(c.spouse);
            if (sp == null || !sp.alive) continue;
            String r1 = family(s, c), r2 = family(s, sp);
            if (r1.equals(realm) && !r2.isEmpty() && !r2.equals(realm)) out.add(r2);
        }
        return out;
    }

    /** Раз в год ИИ женит наследников: ищет подходящую пару в государстве с лучшим мнением правителей. */
    public static void aiYear(CourtSim s) {
        List<String> realms = new ArrayList<>();
        for (var realm : s.d.realmList()) if (!s.humans.contains(realm.id)) realms.add(realm.id);

        // One registry pass builds all eligible marriage pools. Previously candidates()
        // rescanned the full population for every realm from inside a nested realm loop.
        Map<String, List<Character>> candidatesByRealm = new HashMap<>();
        Map<String, Map<String, List<Character>>> candidatesByRealmGender = new HashMap<>();
        Map<String, Character> rulers = new HashMap<>();
        for (Character character : s.reg.all()) {
            if (!single(s, character)) continue;
            String realm = family(s, character);
            if (realm.isEmpty()) continue;
            Character ruler = rulers.computeIfAbsent(realm, s::ruler);
            if (character == ruler) continue;
            candidatesByRealm.computeIfAbsent(realm, ignored -> new ArrayList<>()).add(character);
            candidatesByRealmGender.computeIfAbsent(realm, ignored -> new TreeMap<>())
                    .computeIfAbsent(character.gender, ignored -> new ArrayList<>()).add(character);
        }
        Comparator<Character> order = Comparator
                .comparingInt((Character character) -> character.birthYear)
                .thenComparing(character -> character.id);
        candidatesByRealm.values().forEach(pool -> pool.sort(order));
        candidatesByRealmGender.values().forEach(byGender ->
                byGender.values().forEach(pool -> pool.sort(order)));

        final int maxSourceCandidates = 4;
        final int maxPartnerRealms = 16;
        final int maxCandidatesPerPartnerRealm = 4;
        for (String ra : realms) {
            if (s.rnd("marry", ra.hashCode() + s.day).nextDouble() > 0.35) continue;
            List<Character> sourcePool = candidatesByRealm.getOrDefault(ra, List.of());
            if (sourcePool.isEmpty() || realms.size() < 2) continue;
            Character best = null, bestB = null;
            int bestScore = Integer.MIN_VALUE;
            // Keep a bounded amount of work per realm, but rotate every window. The old
            // implementation always inspected the first four source and partner candidates,
            // permanently excluding later members of large dynasties.
            int sourceStart = s.rnd("marry:sources:" + ra, s.day).nextInt(sourcePool.size());
            int sourceCount = Math.min(maxSourceCandidates, sourcePool.size());
            for (int sourceOffset = 0; sourceOffset < sourceCount; sourceOffset++) {
                Character a = sourcePool.get((sourceStart + sourceOffset) % sourcePool.size());
                Random partnerOrder = s.rnd("marry:partners:" + a.id, ra.hashCode() + s.day);
                int start = partnerOrder.nextInt(realms.size());
                int attempts = Math.min(realms.size(), maxPartnerRealms + 1);
                for (int attempt = 0; attempt < attempts; attempt++) {
                    String rb = realms.get((start + attempt) % realms.size());
                    if (rb.equals(ra)) continue;
                    Map<String, List<Character>> partnerPools =
                            candidatesByRealmGender.getOrDefault(rb, Map.of());
                    for (Map.Entry<String, List<Character>> genderPool : partnerPools.entrySet()) {
                        if (a.gender.equals(genderPool.getKey())) continue;
                        List<Character> partnerPool = genderPool.getValue();
                        if (partnerPool.isEmpty()) continue;
                        int candidateStart = s.rnd("marry:candidates:" + a.globalId + ":" + rb
                                + ":" + genderPool.getKey(), s.day).nextInt(partnerPool.size());
                        int candidateCount = Math.min(maxCandidatesPerPartnerRealm, partnerPool.size());
                        for (int candidateOffset = 0; candidateOffset < candidateCount; candidateOffset++) {
                            Character b = partnerPool.get((candidateStart + candidateOffset) % partnerPool.size());
                            Offer offer = evaluate(s, a.id, b.id);
                            if (offer.ok && offer.score > bestScore) {
                                bestScore = offer.score;
                                best = a;
                                bestB = b;
                            }
                        }
                    }
                }
                if (best != null) break;
            }
            if (best != null) propose(s, best.id, bestB.id);
        }
    }
}
