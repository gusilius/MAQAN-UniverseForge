package dev.worldforge.war;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.Diplomacy;

import java.util.*;

/**
 * Мнение государства A о государстве B как список именованных слагаемых (как «мнение» в CK3).
 * Итог зажат в -100..100; {@link #label()} даёт словесную оценку. Чистая логика поверх {@link WarEngine}.
 */
public final class Attitude {
    public static final int ALLY = 60, ACTIVE_WAR = -80, PAST_WAR = -15, PAST_WAR_CAP = -45, TRUCE = 5, SHARED_ENEMY = 20, SHARED_ALLY = 10, NEIGHBOUR = -5, RELATION = 10, SIBLING = 5, MARRIAGE = 20, TRADE = 10, ENEMY_PROP = -40;

    public final String from, to;
    public final List<String> reasons = new ArrayList<>();
    public final List<Integer> values = new ArrayList<>();
    public final int total;

    private Attitude(String from, String to, List<String> r, List<Integer> v) {
        this.from = from; this.to = to;
        reasons.addAll(r); values.addAll(v);
        int s = 0;
        for (int x : v) s += x;
        total = Math.max(-100, Math.min(100, s));
    }

    public String[] reasonArray() { return reasons.toArray(new String[0]); }

    public int[] valueArray() { int[] a = new int[values.size()]; for (int i = 0; i < a.length; i++) a[i] = values.get(i); return a; }

    public String label() { return labelFor(total); }

    public static String labelFor(int v) {
        if (v <= -50) return "Враждебное";
        if (v < -15) return "Недоверие";
        if (v <= 15) return "Нейтральное";
        if (v < 60) return "Дружелюбное";
        return "Союзное";
    }

    /** Враги государства: участники противоположных сторон в идущих войнах + свойство enemies. */
    private static Set<String> enemiesOf(WarEngine e, String id) {
        Set<String> out = new LinkedHashSet<>();
        for (War w : e.wars.values()) {
            if (!w.active) continue;
            War.Side s = w.sides.get(id);
            if (s == null) continue;
            for (Map.Entry<String, War.Side> x : w.sides.entrySet()) if (x.getValue() != s) out.add(x.getKey());
        }
        TitleData.Realm r = e.editor.data().realms.get(id);
        if (r != null) out.addAll(Diplomacy.ids(r, "enemies"));
        out.remove(id);
        return out;
    }

    public static Attitude of(WarEngine e, String from, String to) {
        List<String> r = new ArrayList<>();
        List<Integer> v = new ArrayList<>();
        TitleData d = e.editor.data();
        if (from.equals(to) || !d.realms.containsKey(from) || !d.realms.containsKey(to)) return new Attitude(from, to, r, v);
        Set<String> allies = e.alliesOf(from);
        if (allies.contains(to)) { r.add("Союзник"); v.add(ALLY); }
        if (e.isSubjectOf(from, to)) { r.add("Наш сюзерен"); v.add(RELATION); }
        else if (e.isSubjectOf(to, from)) { r.add("Наш подданный"); v.add(RELATION); }
        else {
            TitleData.Realm a = d.realms.get(from), b = d.realms.get(to);
            if (a.overlordId != null && a.overlordId.equals(b.overlordId)) { r.add("Вассал того же сюзерена"); v.add(SIBLING); }
        }
        if (Diplomacy.ids(d.realms.get(from), "people.ties").contains(to) || Diplomacy.ids(d.realms.get(to), "people.ties").contains(from)) { r.add("Династический брак"); v.add(MARRIAGE); }
        if (e.warBetween(from, to) != null) { r.add("Идёт война"); v.add(ACTIVE_WAR); }
        else if (e.diplomacyStatus(from, to).kind == WarEngine.Status.ENEMY) { r.add("Объявлен врагом"); v.add(ENEMY_PROP); }
        int past = 0;
        for (War w : e.wars.values()) {
            if (w.active) continue;
            War.Side sa = w.sides.get(from), sb = w.sides.get(to);
            if (sa != null && sb != null && sa != sb) past++;
        }
        if (past > 0) { r.add("Прошлые войны против нас: " + past); v.add(Math.max(PAST_WAR_CAP, past * PAST_WAR)); }
        if (e.truceActive(from, to)) { r.add("Действует перемирие"); v.add(TRUCE); }
        Set<String> ea = enemiesOf(e, from), eb = enemiesOf(e, to);
        ea.retainAll(eb);
        ea.remove(from); ea.remove(to);
        if (!ea.isEmpty()) { r.add("Общие враги: " + ea.size()); v.add(SHARED_ENEMY); }
        Set<String> sa = new LinkedHashSet<>(allies);
        sa.retainAll(e.alliesOf(to));
        sa.remove(from); sa.remove(to);
        if (!sa.isEmpty()) { r.add("Общие союзники: " + sa.size()); v.add(SHARED_ALLY); }
        if (e.neighbours(from, to)) { r.add("Соседи: спорные границы"); v.add(NEIGHBOUR); }
        return new Attitude(from, to, r, v);
    }
}
