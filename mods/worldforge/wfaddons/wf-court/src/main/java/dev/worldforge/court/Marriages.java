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
        for (int i = 0; i < 4 && c != null; i++) {
            String r = s.realmOf(c);
            if (!r.isEmpty()) return r;
            Character p = !c.father.isEmpty() ? s.reg.get(c.father) : !c.mother.isEmpty() ? s.reg.get(c.mother) : null;
            c = p;
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
        for (var r : s.d.realmList()) if (!s.humans.contains(r.id)) realms.add(r.id);
        for (String ra : realms) {
            if (s.rnd("marry", ra.hashCode() + s.day).nextDouble() > 0.35) continue;
            Character best = null, bestB = null; int bestScore = Integer.MIN_VALUE;
            for (Character a : candidates(s, ra)) {
                for (String rb : realms) {
                    if (rb.equals(ra)) continue;
                    for (Character b : candidates(s, rb)) {
                        if (a.gender.equals(b.gender)) continue;
                        Offer o = evaluate(s, a.id, b.id);
                        if (o.ok && o.score > bestScore) { bestScore = o.score; best = a; bestB = b; }
                    }
                }
                if (best != null) break;
            }
            if (best != null) propose(s, best.id, bestB.id);
        }
    }
}
