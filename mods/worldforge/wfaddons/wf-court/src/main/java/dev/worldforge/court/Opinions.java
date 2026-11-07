package dev.worldforge.court;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;

import java.util.*;

/**
 * Мнение одного персонажа о другом, как в CK3: основа из родства и черт плюс временные поправки с причиной и сроком.
 * Всё складывается в число и в список причин для подсказки в окне.
 */
public final class Opinions {
    public static final class Modifier {
        public final String from, to, label;
        public final int value;
        public final int until;                                   // год окончания, 0 - навсегда

        public Modifier(String from, String to, String label, int value, int until) { this.from = from; this.to = to; this.label = label; this.value = value; this.until = until; }
    }

    public static final class Result {
        public int total;
        public final List<String> reasons = new ArrayList<>();
        public final List<Integer> values = new ArrayList<>();

        void add(String r, int v) { if (v != 0) { reasons.add(r); values.add(v); total += v; } }

        public String label() { return total >= 40 ? "Предан" : total >= 15 ? "Дружелюбен" : total > -15 ? "Равнодушен" : total > -40 ? "Враждебен" : "Ненавидит"; }
    }

    private static final String[][] OPPOSED = {{"brave", "craven"}, {"just", "cruel"}, {"greedy", "generous"}, {"diligent", "lazy"}, {"gregarious", "shy"}, {"content", "ambitious"}, {"patient", "wroth"}};

    public final List<Modifier> mods = new ArrayList<>();

    public void add(String from, String to, String label, int value, int until) {
        mods.removeIf(m -> m.from.equals(from) && m.to.equals(to) && m.label.equals(label));       // повторная поправка обновляет срок
        mods.add(new Modifier(from, to, label, value, until));
    }

    public void expire(int year) { mods.removeIf(m -> m.until != 0 && m.until <= year); }

    public Result of(CharacterRegistry reg, String fromId, String toId, int year) {
        Result r = new Result();
        Character a = reg.get(fromId), b = reg.get(toId);
        if (a == null || b == null) return r;
        if (a.spouse.equals(b.id)) r.add("Супруг(а)", 20);
        if (a.father.equals(b.id) || a.mother.equals(b.id) || b.father.equals(a.id) || b.mother.equals(a.id)) r.add("Родство", 25);
        else if (!a.house.isEmpty() && a.house.equals(b.house)) r.add("Один дом", 10);
        List<String> ta = CharacterStats.traits(a), tb = CharacterStats.traits(b);
        int same = 0, opp = 0;
        for (String t : ta) if (tb.contains(t)) same++;
        for (String[] p : OPPOSED) if ((ta.contains(p[0]) && tb.contains(p[1])) || (ta.contains(p[1]) && tb.contains(p[0]))) opp++;
        r.add("Общие черты", same * 6);
        r.add("Противоположные черты", -opp * 8);
        if (tb.contains("usurper")) r.add("Узурпатор", -15);
        if (tb.contains("just")) r.add("Справедливый правитель", 5);
        if (tb.contains("cruel")) r.add("Жестокий правитель", -5);
        for (Modifier m : mods) if (m.from.equals(fromId) && m.to.equals(toId) && (m.until == 0 || m.until > year)) r.add(m.label, m.value);
        return r;
    }

    public int opinion(CharacterRegistry reg, String from, String to, int year) { return of(reg, from, to, year).total; }
}
