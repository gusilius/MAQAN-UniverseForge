package dev.worldforge.rts;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.*;
import java.util.function.Predicate;

/**
 * Выбор как в RTS: набор рабочих ИЛИ одно здание (вперемешку нельзя). Чистая логика: ничего не знает об экране.
 * Рамка выбора принимает прямоугольники на экране (из миникарты или из проекции мира) и сама решает, что попало.
 */
public final class Selection {
    /** Прямоугольник объекта на экране для выбора рамкой. */
    public static final class Rect {
        public static final int WORKER = 1, BUILDING = 2;
        public final int kind, id, x, y, w, h;

        public Rect(int kind, int id, int x, int y, int w, int h) { this.kind = kind; this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; }

        boolean hits(int l, int t, int r, int b) { return x <= r && x + w >= l && y <= b && y + h >= t; }
    }

    private final LinkedHashSet<Integer> workers = new LinkedHashSet<>();
    private int building;

    public Set<Integer> workers() { return Collections.unmodifiableSet(workers); }

    public int building() { return building; }

    public boolean hasWorkers() { return !workers.isEmpty(); }

    public boolean hasBuilding() { return building != 0; }

    public boolean isEmpty() { return workers.isEmpty() && building == 0; }

    public int size() { return building != 0 ? 1 : workers.size(); }

    public boolean contains(int workerId) { return workers.contains(workerId); }

    public void clear() { workers.clear(); building = 0; }

    public void set(Collection<Integer> ids) { clear(); workers.addAll(ids); }

    public void selectBuilding(int uid) { clear(); building = uid; }

    public boolean add(int id) { building = 0; return workers.add(id); }

    public boolean remove(int id) { return workers.remove(id); }

    /** Шифт+щелчок: добавить или убрать. Возвращает true, если теперь выбран. */
    public boolean toggle(int id) {
        building = 0;
        if (!workers.remove(id)) { workers.add(id); return true; }
        return false;
    }

    // ------------------------------------------------------------------ рамка и предикаты

    /**
     * Рамка (x1,y1)-(x2,y2) в любом порядке углов. Рабочие в рамке имеют приоритет; если рабочих нет - выбирается первое здание.
     * additive (шифт) добавляет к выбору, иначе заменяет; пустая рамка без шифта снимает выбор. Возвращает число выбранных рабочих.
     */
    public int boxSelect(List<Rect> rects, int x1, int y1, int x2, int y2, boolean additive) {
        int l = Math.min(x1, x2), r = Math.max(x1, x2), t = Math.min(y1, y2), b = Math.max(y1, y2);
        List<Integer> ws = new ArrayList<>();
        int bu = 0;
        for (Rect q : rects) {
            if (!q.hits(l, t, r, b)) continue;
            if (q.kind == Rect.WORKER) ws.add(q.id); else if (bu == 0) bu = q.id;
        }
        if (!ws.isEmpty()) {
            if (!additive) clear();
            building = 0;
            workers.addAll(ws);
            return ws.size();
        }
        if (bu != 0) {
            if (!additive || workers.isEmpty()) selectBuilding(bu);
            return 0;
        }
        if (!additive) clear();
        return 0;
    }

    /** Выбрать всех рабочих, подходящих под условие. */
    public int selectWhere(Settlement s, Predicate<Worker> p, boolean additive) {
        if (!additive) clear();
        building = 0;
        int n = 0;
        for (Worker w : s.workers) if (p.test(w)) { workers.add(w.id); n++; }
        return n;
    }

    /** Все рабочие этой профессии (Ctrl+щелчок и двойной щелчок). */
    public int selectProfession(Settlement s, String profession, boolean additive) { return selectWhere(s, w -> w.workRole().equals(profession), additive); }

    /** Клавиша «.»: свободные рабочие. */
    public int selectIdle(Settlement s, boolean additive) { return selectWhere(s, w -> w.building == 0, additive); }

    public int selectBuilders(Settlement s, boolean additive) { return selectProfession(s, "builder", additive); }

    public int selectAll(Settlement s) { return selectWhere(s, w -> true, false); }

    /** Работники здания. */
    public int selectStaff(Settlement s, int uid) { return selectWhere(s, w -> w.building == uid, false); }

    /**
     * Щелчок по рабочему: обычный - только он; Shift - переключить; Ctrl или двойной - все его профессии (с Shift - добавить к выбору).
     */
    public void clickWorker(Settlement s, int id, boolean shift, boolean ctrl, boolean doubleClick) {
        Worker w = s.worker(id);
        if (w == null) return;
        if (ctrl || doubleClick) selectProfession(s, w.workRole(), shift);
        else if (shift) toggle(id);
        else { clear(); workers.add(id); }
    }

    // ------------------------------------------------------------------ сведения

    /** Убрать из выбора тех, кто исчез (умер, ушёл, снесено). */
    public void prune(Settlement s) {
        workers.removeIf(id -> s.worker(id) == null);
        if (building != 0 && s.building(building) == null) building = 0;
    }

    public List<Worker> workers(Settlement s) {
        List<Worker> l = new ArrayList<>();
        for (int id : workers) { Worker w = s.worker(id); if (w != null) l.add(w); }
        return l;
    }

    public Building building(Settlement s) { return building == 0 ? null : s.building(building); }

    /** Сводка «профессия -> сколько», больше - раньше (для надписи «Дровосек x3»). */
    public LinkedHashMap<String, Integer> byProfession(Settlement s) {
        Map<String, Integer> m = new HashMap<>();
        for (Worker w : workers(s)) m.merge(w.profession, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> a.getValue().equals(b.getValue()) ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue());
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** Центр выбора в координатах поселения (запрос камере: двойное нажатие 1..9 и т.п.); null, если выбор пуст. */
    public double[] center(Settlement s) {
        Building b = building(s);
        if (b != null) return new double[]{b.x, b.z};
        List<Worker> l = workers(s);
        if (l.isEmpty()) return null;
        double x = 0, z = 0;
        for (Worker w : l) { x += w.x; z += w.z; }
        return new double[]{x / l.size(), z / l.size()};
    }
}
