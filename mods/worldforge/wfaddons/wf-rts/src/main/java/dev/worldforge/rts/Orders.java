package dev.worldforge.rts;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.*;

/**
 * Приказы RTS: превращают выбор и цель в строки команд {@code wfsettle ...} (те же, что разбирает mc/SettleCommands) и заранее
 * проверяют то, что проверит сервер, чтобы экран мог объяснить отказ, не отправляя заведомо плохой приказ. Ничего не меняют в Settlement.
 * Для нескольких рабочих выдаётся по команде на каждого (существующая команда принимает одного).
 */
public final class Orders {
    private Orders() {}

    /** Итог приказа: команды для сервера, сообщение для строки состояния, флаги. */
    public static final class Result {
        public final List<String> commands = new ArrayList<>();
        public String message = "";
        public boolean ok, cancelPending;

        static Result none() { return new Result(); }

        static Result fail(String m) { Result r = new Result(); r.message = m; return r; }

        public boolean isEmpty() { return commands.isEmpty(); }
    }

    /** Разбор «кого можно отправить в здание»: подходящие (в порядке выбора) и причина первого отказа. */
    public static final class AssignPlan {
        public final List<Integer> ok = new ArrayList<>();
        public int skipped;
        public String firstReason = "";
    }

    // ------------------------------------------------------------------ строки команд (единственное место, где они собираются)

    public static String assignCmd(int worker, int uid) { return "wfsettle assign " + worker + " " + uid; }

    public static String unassignCmd(int worker) { return "wfsettle unassign " + worker; }

    public static String professionCmd(int worker, String prof) { return "wfsettle profession " + worker + " " + prof; }

    public static String buildCmd(String def, int x, int z) { return "wfsettle build " + def + " " + x + " " + z; }

    public static String cancelCmd(int uid) { return "wfsettle cancel " + uid; }

    public static String priorityCmd(int uid, int n) { return "wfsettle priority " + uid + " " + n; }

    public static String autoCmd(boolean on) { return "wfsettle auto " + (on ? "on" : "off"); }

    /** ТРЕБУЕТ новой серверной команды у владельца wf-settlements: n 0..4, 0 - не делать, 1 - самое важное. */
    public static String priorityWorkCmd(int worker, String prof, int n) { return "wfsettle priority-work " + worker + " " + prof + " " + n; }

    // ------------------------------------------------------------------ проверки

    private static int assignedWith(Settlement s, int uid, String prof, int except) {
        int n = 0;
        for (Worker w : s.workers) if (w.building == uid && w.id != except && w.profession.equals(prof)) n++;
        return n;
    }

    /** Те же правила, что у {@code Settlement.assign}, но с учётом тех, кого этим же приказом уже отправили (места считаются сразу). */
    public static AssignPlan planAssign(Settlement s, Collection<Integer> workerIds, int uid) {
        AssignPlan p = new AssignPlan();
        Building b = s.building(uid);
        if (b == null) { p.firstReason = "Нет здания"; p.skipped = workerIds.size(); return p; }
        Map<String, Integer> added = new HashMap<>();
        for (int id : new LinkedHashSet<>(workerIds)) {
            Worker w = s.worker(id);
            if (w == null) continue;
            String why = null;
            if (w.building == uid) { p.skipped++; if (p.firstReason.isEmpty()) p.firstReason = w.name + " уже работает здесь"; continue; }
            if (b.state == Building.State.DONE) {
                int slots = b.def.jobs.getOrDefault(w.profession, 0);
                if (slots == 0) why = w.name + " (" + s.defs.professionName(w.profession) + ") не нужен в здании " + b.def.name;
                else if (assignedWith(s, uid, w.profession, id) + added.getOrDefault(w.profession, 0) >= slots) why = "В здании " + b.def.name + " нет свободных мест для профессии " + s.defs.professionName(w.profession);
            } else if (!w.profession.equals("builder")) why = "Строить могут только строители";
            else if (assignedWith(s, uid, "builder", id) + added.getOrDefault("builder", 0) >= b.def.builders) why = "На стройке уже максимум строителей (" + b.def.builders + ")";
            if (why != null) { p.skipped++; if (p.firstReason.isEmpty()) p.firstReason = why; continue; }
            added.merge(w.profession, 1, Integer::sum);
            p.ok.add(id);
        }
        return p;
    }

    /** Свободные места в здании по профессиям (для стройки - строители). Пустая карта: мест нет. */
    public static Map<String, Integer> freeSlots(Settlement s, Building b) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (b.state != Building.State.DONE) {
            int f = b.def.builders - assignedWith(s, b.uid, "builder", -1);
            if (f > 0) m.put("builder", f);
            return m;
        }
        for (Map.Entry<String, Integer> j : b.def.jobs.entrySet()) {
            int f = j.getValue() - assignedWith(s, b.uid, j.getKey(), -1);
            if (f > 0) m.put(j.getKey(), f);
        }
        return m;
    }

    /** Почему здание нельзя снести или отменить (то же, что в {@code Settlement.cancel}); null - можно. */
    public static String demolishError(Settlement s, Building b) {
        if (b.state == Building.State.DONE) {
            for (Building o : s.buildings) {
                if (o != b && o.state != Building.State.PLANNED && o.def.requires.contains(b.def.id) && s.count(b.def.id, true) <= 1) return "Нельзя снести: нужно для " + o.def.name;
            }
        }
        return null;
    }

    /** Возврат материалов при сносе/отмене: доля 0, 0.5 или 0.25 как в Settlement. */
    public static double refundShare(Building b) { return b.state == Building.State.PLANNED ? 1.0 : b.state == Building.State.BUILDING ? 0.5 : 0.25; }

    /** Что мешает строить здание вообще (требования и предел), без учёта места и денег; null - можно. */
    public static String buildBlocker(Settlement s, String defId) {
        Defs.Building d = s.defs.buildings.get(defId);
        if (d == null) return "Нет такого здания";
        for (String r : d.requires) if (s.count(r, true) == 0) return "Сначала нужно построить: " + s.defs.buildings.get(r).name;
        if (d.limit > 0 && s.count(defId, false) >= d.limit) return "Достигнут предел: " + d.limit;
        return null;
    }

    // ------------------------------------------------------------------ сборка приказов

    public static Result assign(Settlement s, Collection<Integer> workers, int uid) {
        AssignPlan p = planAssign(s, workers, uid);
        Result r = new Result();
        if (p.ok.isEmpty()) { r.message = p.firstReason.isEmpty() ? "Некого назначать" : p.firstReason; return r; }
        for (int id : p.ok) r.commands.add(assignCmd(id, uid));
        r.ok = true;
        Building b = s.building(uid);
        r.message = "В " + b.def.name + (p.ok.size() == 1 ? ": 1 рабочий" : ": рабочих " + p.ok.size()) + (p.skipped > 0 ? " (не подошло " + p.skipped + ": " + p.firstReason + ")" : "");
        return r;
    }

    public static Result unassign(Settlement s, Collection<Integer> workers) {
        Result r = new Result();
        for (int id : workers) { Worker w = s.worker(id); if (w != null && w.building != 0) r.commands.add(unassignCmd(id)); }
        r.ok = !r.commands.isEmpty();
        r.message = r.ok ? "Сняты с работы: " + r.commands.size() : "Все выбранные уже свободны";
        return r;
    }

    public static Result setProfession(Settlement s, Collection<Integer> workers, String prof) {
        Result r = new Result();
        if (!s.defs.professions.containsKey(prof)) { r.message = "Нет профессии " + prof; return r; }
        for (int id : workers) { Worker w = s.worker(id); if (w != null && !(w.profession.equals(prof))) r.commands.add(professionCmd(id, prof)); }
        r.ok = !r.commands.isEmpty();
        r.message = r.ok ? s.defs.professionName(prof) + ": " + r.commands.size() : "Уже " + s.defs.professionName(prof).toLowerCase();
        return r;
    }

    public static Result place(Settlement s, String defId, int x, int z) {
        String err = s.placeError(defId, x, z);
        if (err != null) return Result.fail(err);
        Result r = new Result();
        r.commands.add(buildCmd(defId, x, z));
        r.ok = true;
        r.message = "Заложено: " + s.defs.buildings.get(defId).name;
        return r;
    }

    public static Result demolish(Settlement s, int uid) {
        Building b = s.building(uid);
        if (b == null) return Result.fail("Нет здания");
        String err = demolishError(s, b);
        if (err != null) return Result.fail(err);
        Result r = new Result();
        r.commands.add(cancelCmd(uid));
        r.ok = true;
        r.message = (b.state == Building.State.DONE ? "Снос: " : "Отмена: ") + b.def.name;
        return r;
    }

    public static Result setBuildingPriority(Settlement s, int uid, int delta) {
        Building b = s.building(uid);
        if (b == null) return Result.fail("Нет здания");
        int n = Math.max(1, Math.min(9, b.priority + delta));
        if (n == b.priority) return Result.fail("Приоритет уже " + (delta > 0 ? "наивысший" : "наименьший"));
        Result r = new Result();
        r.commands.add(priorityCmd(uid, n));
        r.ok = true;
        r.message = b.def.name + ": приоритет " + n;
        return r;
    }

    /**
     * Заполнить свободные места здания свободными рабочими: сперва подходящей профессии, потом «работники» (laborer) переучиваются
     * командой profession и сразу назначаются. Возвращает приказы или объяснение.
     */
    public static Result fillFromIdle(Settlement s, int uid) {
        Building b = s.building(uid);
        if (b == null) return Result.fail("Нет здания");
        Map<String, Integer> free = freeSlots(s, b);
        if (free.isEmpty()) return Result.fail("В здании нет свободных мест");
        List<Worker> idle = new ArrayList<>(s.idleWorkers());
        Result r = new Result();
        int n = 0;
        for (Map.Entry<String, Integer> f : free.entrySet()) {
            int need = f.getValue();
            for (Iterator<Worker> it = idle.iterator(); it.hasNext() && need > 0; ) {
                Worker w = it.next();
                if (!w.profession.equals(f.getKey())) continue;
                r.commands.add(assignCmd(w.id, uid)); it.remove(); need--; n++;
            }
            for (Iterator<Worker> it = idle.iterator(); it.hasNext() && need > 0; ) {
                Worker w = it.next();
                if (!w.profession.equals("laborer")) continue;
                r.commands.add(professionCmd(w.id, f.getKey())); r.commands.add(assignCmd(w.id, uid)); it.remove(); need--; n++;
            }
        }
        r.ok = n > 0;
        r.message = r.ok ? "Отправлено рабочих: " + n : "Нет свободных рабочих нужной профессии";
        return r;
    }

    public static Result unassignAll(Settlement s, int uid) {
        Result r = new Result();
        for (Worker w : s.assigned(uid)) r.commands.add(unassignCmd(w.id));
        r.ok = !r.commands.isEmpty();
        r.message = r.ok ? "Сняты с работы: " + r.commands.size() : "В здании никого нет";
        return r;
    }

    // ------------------------------------------------------------------ умный приказ ПКМ

    /**
     * Правый щелчок как в Warcraft. Рабочие на здание: назначить (строители - на стройку). Здание выбрано, цель - рабочий: назначить его.
     * Ожидается размещение (pendingBuild не null): земля - заложить здание, любая другая цель отменяет размещение.
     * Рабочие на землю или на рабочего, здание на землю: ничего (сообщения нет).
     */
    public static Result rightClick(Selection sel, Settlement s, Target t, String pendingBuild) {
        if (pendingBuild != null) {
            if (t.kind != Target.Kind.GROUND) { Result r = Result.none(); r.cancelPending = true; r.message = "Размещение отменено"; return r; }
            return place(s, pendingBuild, t.x, t.z);
        }
        if (sel.hasWorkers() && t.kind == Target.Kind.BUILDING) return assign(s, sel.workers(), t.id);
        if (sel.hasBuilding() && t.kind == Target.Kind.WORKER) return assign(s, List.of(t.id), sel.building());
        return Result.none();
    }
}
