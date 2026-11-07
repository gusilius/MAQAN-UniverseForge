package dev.worldforge.rts;

import dev.worldforge.settle.Settlement;

import java.util.*;

/**
 * Группы управления 1..9 как в Warcraft: Ctrl+цифра запоминает выбор, цифра вызывает, Shift+цифра добавляет к текущему выбору,
 * Shift+Ctrl+цифра добавляет выбор в группу, двойное нажатие подряд - запрос «центрировать камеру» ({@link Result#CENTER}).
 */
public final class ControlGroups {
    public static final int COUNT = 9;
    public static final long DOUBLE_MS = 450;

    public enum Result { EMPTY, SELECTED, CENTER }

    private final List<LinkedHashSet<Integer>> workers = new ArrayList<>();
    private final int[] building = new int[COUNT + 1];
    private int lastN;
    private long lastAt = Long.MIN_VALUE / 2;

    public ControlGroups() { for (int i = 0; i <= COUNT; i++) workers.add(new LinkedHashSet<>()); }

    private static void check(int n) { if (n < 1 || n > COUNT) throw new IllegalArgumentException("Группа 1.." + COUNT + ": " + n); }

    /** Ctrl+цифра: группа становится копией выбора (пустой выбор очищает группу). */
    public void assign(int n, Selection sel) {
        check(n);
        workers.get(n).clear();
        building[n] = 0;
        add(n, sel);
    }

    /** Shift+Ctrl+цифра: добавить выбор в группу. Здание в группе только одно и без рабочих. */
    public void add(int n, Selection sel) {
        check(n);
        if (sel.hasBuilding() && workers.get(n).isEmpty()) building[n] = sel.building();
        else if (sel.hasWorkers()) { building[n] = 0; workers.get(n).addAll(sel.workers()); }
    }

    public int size(int n) { check(n); return building[n] != 0 ? 1 : workers.get(n).size(); }

    public boolean has(int n) { return size(n) > 0; }

    public Set<Integer> members(int n) { check(n); return Collections.unmodifiableSet(workers.get(n)); }

    /**
     * Вызов группы. Исчезнувшие рабочие выбрасываются. Повторное нажатие той же цифры за {@link #DOUBLE_MS} мс - CENTER (выбор остаётся).
     * shift добавляет группу к текущему выбору рабочих вместо замены.
     */
    public Result recall(int n, Selection sel, Settlement s, long nowMs, boolean shift) {
        check(n);
        workers.get(n).removeIf(id -> s.worker(id) == null);
        if (building[n] != 0 && s.building(building[n]) == null) building[n] = 0;
        if (!has(n)) return Result.EMPTY;
        boolean twice = n == lastN && nowMs - lastAt <= DOUBLE_MS;
        if (building[n] != 0) sel.selectBuilding(building[n]);
        else if (shift && sel.hasWorkers()) for (int id : workers.get(n)) sel.add(id);
        else sel.set(workers.get(n));
        lastN = n;
        lastAt = twice ? Long.MIN_VALUE / 2 : nowMs;
        return twice ? Result.CENTER : Result.SELECTED;
    }
}
