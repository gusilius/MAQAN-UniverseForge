package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Отношения государств для дипломатического слоя карты. Основа: иерархия (сюзерен/подчинённые) и свойства государств
 * {@code allies} и {@code enemies} (id через запятую; достаточно указать с одной стороны). Аддон с войнами и NPC просто
 * записывает эти свойства, и слой «Дипломатия» оживает на карте, в панелях и на веб-карте без другого кода.
 */
public final class Diplomacy {
    public enum Rel {
        SELF("Выбранное", 0xF0C85A), OVERLORD("Сюзерены", 0xB06BE0), SUBJECT("Подчинённые", 0x4AA8FF), SIBLING("Вассалы того же сюзерена", 0x86C9E8),
        ALLY("Союзники", 0x4FCF5A), ENEMY("Враги", 0xE04A4A), NEIGHBOR("Соседи", 0xB8B8C4), NONE("Прочие", 0x55555E);

        public final String label;
        public final int rgb;

        Rel(String label, int rgb) { this.label = label; this.rgb = rgb; }
    }

    private Diplomacy() {}

    /** Значение свойства-списка id: «a, b;c» -> {a, b, c}. */
    public static Set<String> ids(TitleData.Realm r, String key) {
        Set<String> out = new HashSet<>();
        Object v = r.props.get(key);
        if (v == null) return out;
        for (String p : v.toString().split("[,;\\s]+")) if (!p.isBlank()) out.add(p.trim());
        return out;
    }

    /** Государства, у которых есть общая граница (между любыми их баронствами). */
    public static Map<String, Set<String>> adjacency(TitleData d) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (TitleData.Title t : d.allTitles()) {
            if (t.tier != 0) continue;
            TitleData.Realm a = d.realmOf(t);
            if (a == null) continue;
            for (int n : t.neighbors) {
                TitleData.Realm b = d.realmOf(d.get(n));
                if (b == null || b == a) continue;
                adj.computeIfAbsent(a.id, k -> new HashSet<>()).add(b.id);
                adj.computeIfAbsent(b.id, k -> new HashSet<>()).add(a.id);
            }
        }
        return adj;
    }

    private static boolean above(TitleData.Realm top, TitleData.Realm low) {
        int g = 0;
        for (TitleData.Realm c = low.overlord; c != null && g++ < 64; c = c.overlord) if (c == top) return true;
        return false;
    }

    public static Rel relation(TitleData d, TitleData.Realm focus, TitleData.Realm other, Map<String, Set<String>> adj) {
        if (focus == null || other == null) return Rel.NONE;
        if (focus == other) return Rel.SELF;
        if (ids(focus, "enemies").contains(other.id) || ids(other, "enemies").contains(focus.id)) return Rel.ENEMY;
        if (ids(focus, "allies").contains(other.id) || ids(other, "allies").contains(focus.id)) return Rel.ALLY;
        if (above(other, focus)) return Rel.OVERLORD;
        if (above(focus, other)) return Rel.SUBJECT;
        if (focus.overlord != null && focus.overlord == other.overlord) return Rel.SIBLING;
        Set<String> n = adj.get(focus.id);
        if (n != null && n.contains(other.id)) return Rel.NEIGHBOR;
        return Rel.NONE;
    }
}
