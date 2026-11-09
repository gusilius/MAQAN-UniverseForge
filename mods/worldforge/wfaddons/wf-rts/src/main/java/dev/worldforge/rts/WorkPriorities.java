package dev.worldforge.rts;

import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.*;

/**
 * Матрица приоритетов работ как в RimWorld: строка - рабочий, столбец - профессия, значение 0 (не делать), 1 (самое важное) .. 4 (в последнюю очередь).
 * Пока это ТОЛЬКО клиентская модель: сервер её не знает. Чтобы приоритеты что-то меняли, владельцу wf-settlements нужно добавить команду
 * {@code /wfsettle priority-work <worker> <prof> <n>} (n 0..4) и хранить значения у {@code Worker}; автоназначение должно брать для
 * свободного рабочего профессию с наименьшим ненулевым значением. Умолчание: своя профессия - 1, остальные - 0.
 */
public final class WorkPriorities {
    public static final int MAX = 4;

    private final Map<Integer, Map<String, Integer>> values = new HashMap<>();

    public int get(Worker w, String prof) {
        Map<String, Integer> m = values.get(w.id);
        if (m != null && m.containsKey(prof)) return m.get(prof);
        return w.canPerform(prof) ? 1 : 0;
    }

    /** Установить значение (0..4); возвращает команду для сервера. */
    public String set(Worker w, String prof, int n) {
        n = Math.max(0, Math.min(MAX, n));
        values.computeIfAbsent(w.id, k -> new HashMap<>()).put(prof, n);
        return Orders.priorityWorkCmd(w.id, prof, n);
    }

    /** Щелчок по ячейке: 0->1->2->3->4->0, назад при back (ПКМ). Возвращает команду. */
    public String cycle(Worker w, String prof, boolean back) {
        int v = get(w, prof);
        return set(w, prof, back ? (v + MAX) % (MAX + 1) : (v + 1) % (MAX + 1));
    }

    /** Щелчок по заголовку столбца: всем рабочим ставится следующее значение по первому в списке. */
    public List<String> cycleColumn(Settlement s, String prof) {
        List<String> out = new ArrayList<>();
        if (s.workers.isEmpty()) return out;
        int next = (get(s.workers.get(0), prof) + 1) % (MAX + 1);
        for (Worker w : s.workers) out.add(set(w, prof, next));
        return out;
    }

    /** Профессия с наименьшим ненулевым значением (при равенстве - текущая, затем по порядку в наборе); null, если все нули. */
    public String best(Settlement s, Worker w) {
        String best = null;
        int bv = 99;
        for (String p : s.defs.professions.keySet()) {
            int v = get(w, p);
            if (v > 0 && (v < bv || (v == bv && p.equals(w.workRole())))) { best = p; bv = v; }
        }
        return best;
    }

    public static List<String> professions(Defs d) { return new ArrayList<>(d.professions.keySet()); }
}
