package dev.worldforge.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Проверка целостности политической карты: разорванные провинции, пустые титулы, неверная иерархия, странные государства. */
public final class TitleValidator {
    private TitleValidator() {}

    public static List<String> check(TitleData d) {
        List<String> out = new ArrayList<>();
        int w = d.w, h = d.h;
        short[] px = d.pixels();
        // разорванные провинции: связные компоненты по 4-соседству
        int[] comp = new int[w * h];
        Map<Integer, List<Integer>> sizes = new HashMap<>();
        int[] stack = new int[w * h];
        int next = 0;
        for (int i = 0; i < px.length; i++) {
            int id = px[i] & 0xFFFF;
            if (id == 0 || comp[i] != 0) continue;
            next++;
            int sp = 0, size = 0;
            stack[sp++] = i;
            comp[i] = next;
            while (sp > 0) {
                int c = stack[--sp];
                size++;
                int x = c % w, z = c / w;
                if (x > 0 && comp[c - 1] == 0 && (px[c - 1] & 0xFFFF) == id) { comp[c - 1] = next; stack[sp++] = c - 1; }
                if (x + 1 < w && comp[c + 1] == 0 && (px[c + 1] & 0xFFFF) == id) { comp[c + 1] = next; stack[sp++] = c + 1; }
                if (z > 0 && comp[c - w] == 0 && (px[c - w] & 0xFFFF) == id) { comp[c - w] = next; stack[sp++] = c - w; }
                if (z + 1 < h && comp[c + w] == 0 && (px[c + w] & 0xFFFF) == id) { comp[c + w] = next; stack[sp++] = c + w; }
            }
            sizes.computeIfAbsent(id, k -> new ArrayList<>()).add(size);
        }
        for (TitleData.Title t : d.allTitles()) {
            if (t.tier == 0) {
                List<Integer> s = sizes.get(t.id);
                if (s == null) {
                    out.add("Провинция '" + t.name + "' пуста (нет пикселей), закрасьте её или удалите");
                } else if (s.size() > 1) {
                    int total = 0, max = 0;
                    for (int v : s) { total += v; max = Math.max(max, v); }
                    out.add("Провинция '" + t.name + "' разорвана на " + s.size() + " частей (основная " + max + " из " + total + " px)");
                }
            } else if (t.children.length == 0) {
                out.add("Титул '" + t.name + "' (" + d.tiers.get(t.tier).name + ") пуст: в нём нет дочерних титулов");
            }
            if (t.parent != 0) {
                TitleData.Title p = d.get(t.parent);
                if (p == null) out.add("У '" + t.name + "' родитель не существует (номер " + t.parent + ")");
                else if (p.tier <= t.tier) out.add("'" + t.name + "' вложен в '" + p.name + "', но уровень родителя не выше");
            } else if (t.tier < d.tiers.size() - 1 && t.areaBlocks > 0) {
                out.add("'" + t.name + "' (" + d.tiers.get(t.tier).name + ") ни в чём не состоит, хотя выше есть уровни");
            }
        }
        for (TitleData.Realm r : d.realmList()) {
            if (r.titleKeys.isEmpty() && r.subjectIds.isEmpty()) out.add("Государство '" + r.name + "' ничем не владеет");
            if (r.overlordId != null && !d.realms.containsKey(r.overlordId)) out.add("У '" + r.name + "' сюзерен '" + r.overlordId + "' не существует");
            if (r.overlordId != null && !d.relationTypes.containsKey(r.relation)) out.add("У '" + r.name + "' неизвестный тип отношений '" + r.relation + "'");
            if (d.overlordChain(r).size() >= 16) out.add("Цикл подчинения вокруг '" + r.name + "'");
            for (String k : r.titleKeys) if (d.byKey(k) == null) out.add("Государство '" + r.name + "' владеет несуществующим титулом '" + k + "'");
        }
        return out;
    }
}
