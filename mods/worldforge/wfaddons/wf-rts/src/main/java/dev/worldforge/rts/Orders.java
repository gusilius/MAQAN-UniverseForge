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

    public static String moveCmd(int worker, double x, double z) { return "wfsettle move " + worker + " " + x + " " + z; }

    public static String professionCmd(int worker, String prof) { return "wfsettle profession " + worker + " " + prof; }

    public static String buildCmd(String def, int x, int z) { return "wfsettle build " + def + " " + x + " " + z; }

    public static String cancelCmd(int uid) { return "wfsettle cancel " + uid; }

    public static String priorityCmd(int uid, int n) { return "wfsettle priority " + uid + " " + n; }

    public static String autoCmd(boolean on) { return "wfsettle auto " + (on ? "on" : "off"); }

    /** ТРЕБУЕТ новой серверной команды у владельца wf-settlements: n 0..4, 0 - не делать, 1 - самое важное. */
    public static String priorityWorkCmd(int worker, String prof, int n) { return "wfsettle priority-work " + worker + " " + prof + " " + n; }

    // ------------------------------------------------------------------ проверки

    private static int assignedWith(Settlement s, int uid, String role, int except) {
        int n = 0;
        for (Worker w : s.workers) if (w.building == uid && w.id != except && w.workRole().equals(role)) n++;
        return n;
    }

    /** Mirrors Settlement.assign: specialists first by compatibility, general workers may fill any compatible vacancy. */
    public static AssignPlan planAssign(Settlement s, Collection<Integer> workerIds, int uid) {
        AssignPlan plan = new AssignPlan();
        Building building = s.building(uid);
        if (building == null) { plan.firstReason = "Нет здания"; plan.skipped = workerIds.size(); return plan; }
        Map<String, Integer> added = new HashMap<>();
        for (int id : new LinkedHashSet<>(workerIds)) {
            Worker worker = s.worker(id);
            if (worker == null) continue;
            if (worker.building == uid) {
                plan.skipped++;
                if (plan.firstReason.isEmpty()) plan.firstReason = worker.name + " уже работает здесь";
                continue;
            }
            String role = "";
            String why = null;
            if (building.state == Building.State.BUILDING) {
                if (!worker.canPerform("builder")) why = worker.name + " не может строить";
                else role = "builder";
            } else {
                for (Map.Entry<String, Integer> job : building.def.jobs.entrySet()) {
                    String candidate = job.getKey();
                    if (!worker.canPerform(candidate)) continue;
                    int occupied = assignedWith(s, uid, candidate, id) + added.getOrDefault(candidate, 0);
                    if (occupied < job.getValue()) { role = candidate; break; }
                }
                if (role.isEmpty()) {
                    boolean compatible = building.def.jobs.keySet().stream().anyMatch(worker::canPerform);
                    why = compatible
                            ? "В здании " + building.def.name + " нет свободных мест для " + worker.name
                            : worker.name + " (" + s.defs.professionName(worker.profession) + ") не нужен для работ в " + building.def.name;
                }
            }
            if (role.isEmpty()) {
                plan.skipped++;
                if (plan.firstReason.isEmpty()) plan.firstReason = why == null ? "Нет совместимой работы" : why;
                continue;
            }
            if (building.state == Building.State.BUILDING
                    && assignedWith(s, uid, role, id) + added.getOrDefault(role, 0) >= building.def.builders) {
                plan.skipped++;
                if (plan.firstReason.isEmpty()) plan.firstReason = "На стройке уже максимум строителей (" + building.def.builders + ")";
                continue;
            }
            added.merge(role, 1, Integer::sum);
            plan.ok.add(id);
        }
        return plan;
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
     * Fill vacancies without changing permanent professions: compatible specialists first,
     * then versatile general workers at baseline productivity.
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
            for (int pass = 0; pass < 2 && need > 0; pass++) {
                for (Iterator<Worker> it = idle.iterator(); it.hasNext() && need > 0; ) {
                    Worker worker = it.next();
                    if (!worker.canPerform(f.getKey())) continue;
                    if ((pass == 0) == worker.generalist()) continue;
                    r.commands.add(assignCmd(worker.id, uid));
                    it.remove();
                    need--;
                    n++;
                }
            }
        }
        r.ok = n > 0;
        r.message = r.ok ? "Отправлено рабочих: " + n : "Нет свободных совместимых рабочих";
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
        if (sel.hasWorkers() && t.kind == Target.Kind.GROUND) {
            Result r = new Result();
            for (int id : sel.workers()) if (s.worker(id) != null) r.commands.add(moveCmd(id, t.x, t.z));
            r.ok = !r.commands.isEmpty();
            r.message = r.ok ? "Приказ перемещения: " + r.commands.size() + " рабочих" : "Нет выбранных рабочих";
            return r;
        }
        if (sel.hasBuilding() && t.kind == Target.Kind.WORKER) return assign(s, List.of(t.id), sel.building());
        return Result.none();
    }
}
