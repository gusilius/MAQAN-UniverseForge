package dev.worldforge.tests;

import dev.worldforge.settle.*;
import dev.worldforge.rts.*;

import java.nio.file.*;
import java.util.*;

public class RtsTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static Defs defs() throws Exception {
        Defs d = new Defs();
        for (String f : new String[]{"base.json", "extra.json"}) d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f)));
        return d;
    }

    static Settlement town(Defs d) {
        Settlement s = new Settlement("s1", "Тест", d);
        s.add("wood", 100); s.add("stone", 30); s.add("food", 50);
        for (int i = 0; i < 6; i++) s.addWorker(null);
        s.place("house", 0, 0); s.place("lumber_camp", 12, 0);
        s.tick(120);
        return s;
    }

    static void apply(Settlement s, WorkPriorities p, Orders.Result r) { for (String c : r.commands) Dispatch.apply(s, p, c); }

    public static void main(String[] a) throws Exception {
        Defs d = defs();
        Settlement s = town(d);
        Selection sel = new Selection();

        // ---- выбор
        ok(sel.isEmpty(), "выбор пуст");
        sel.add(1); sel.add(2);
        ok(sel.size() == 2 && sel.hasWorkers() && !sel.hasBuilding(), "добавили двоих");
        ok(!sel.toggle(1) && sel.size() == 1 && sel.toggle(3) && sel.size() == 2, "toggle убирает и добавляет");
        sel.selectBuilding(2);
        ok(sel.hasBuilding() && !sel.hasWorkers(), "здание вытесняет рабочих");
        sel.add(4);
        ok(!sel.hasBuilding() && sel.contains(4), "рабочий вытесняет здание");
        sel.clear();
        int idle = sel.selectIdle(s, false);
        ok(idle == s.idleWorkers().size() && idle > 0, "'.' выбирает свободных: " + idle);
        int nb = sel.selectBuilders(s, false);
        ok(nb == s.workers.stream().filter(w -> w.workRole().equals("builder")).count(), "все строители по текущей работе: " + nb);
        ok(sel.selectAll(s) == s.workers.size(), "выбрать всех");
        long lumberjackJobs = s.workers.stream().filter(w -> w.workRole().equals("lumberjack")).count();
        sel.selectProfession(s, "lumberjack", false);
        ok(lumberjackJobs > 0 && sel.size() == lumberjackJobs
                        && sel.workers(s).stream().allMatch(w -> w.workRole().equals("lumberjack")),
                "выбрать всех работников по текущей работе");
        sel.clickWorker(s, 1, false, false, false);
        ok(sel.size() == 1, "обычный щелчок: один");
        sel.clickWorker(s, 1, false, true, false);
        ok(sel.workers(s).stream().allMatch(w -> w.workRole().equals(s.worker(1).workRole())), "ctrl-щелчок: все с той же текущей работой");
        // рамка
        List<Selection.Rect> rs = List.of(new Selection.Rect(Selection.Rect.WORKER, 1, 10, 10, 5, 5), new Selection.Rect(Selection.Rect.WORKER, 2, 30, 10, 5, 5),
                new Selection.Rect(Selection.Rect.BUILDING, 7, 0, 0, 100, 100));
        sel.clear();
        ok(sel.boxSelect(rs, 5, 5, 20, 20, false) == 1 && sel.contains(1), "рамка берёт рабочего");
        ok(sel.boxSelect(rs, 40, 40, 20, 20, false) == 0 && sel.building() == 7, "рамка без рабочих берёт здание");
        sel.clear();
        ok(sel.boxSelect(rs, 100, 100, 5, 5, false) == 2 && sel.size() == 2, "углы рамки в любом порядке");
        ok(sel.boxSelect(rs, 500, 500, 600, 600, false) == 0 && sel.isEmpty(), "пустая рамка снимает выбор");
        sel.set(List.of(1, 99));
        sel.prune(s);
        ok(sel.size() == 1, "prune убирает исчезнувших");

        // ---- группы
        ControlGroups cg = new ControlGroups();
        sel.set(List.of(1, 2, 3));
        cg.assign(1, sel);
        sel.set(List.of(4));
        cg.capture(1, sel, true);
        ok(cg.size(1) == 4, "Shift+Ctrl capture adds to existing group");
        cg.capture(1, sel, false);
        ok(cg.size(1) == 1 && cg.members(1).contains(4), "Ctrl capture replaces group");
        sel.set(List.of(1, 2, 3));
        cg.assign(1, sel);
        sel.clear();
        ok(cg.recall(1, sel, s, 1000, false) == ControlGroups.Result.SELECTED && sel.size() == 3, "группа 1 вызвана");
        ok(cg.recall(1, sel, s, 1200, false) == ControlGroups.Result.CENTER, "двойное нажатие: камера");
        ok(cg.recall(1, sel, s, 1300, false) == ControlGroups.Result.SELECTED, "после CENTER счёт двойного нажатия сброшен");
        ok(cg.recall(1, sel, s, 5000, false) == ControlGroups.Result.SELECTED, "медленное нажатие - не двойное");
        ok(cg.recall(2, sel, s, 6000, false) == ControlGroups.Result.EMPTY, "пустая группа");
        sel.set(List.of(4));
        cg.recall(1, sel, s, 9000, true);
        ok(sel.size() == 4, "shift добавляет группу к выбору");
        sel.set(List.of(5));
        cg.add(1, sel);
        ok(cg.size(1) == 4, "Shift+Ctrl добавляет в группу");
        sel.selectBuilding(1);
        cg.assign(3, sel);
        sel.clear();
        ok(cg.recall(3, sel, s, 20000, false) == ControlGroups.Result.SELECTED && sel.building() == 1, "группа из здания");
        boolean threw = false;
        try { cg.assign(0, sel); } catch (IllegalArgumentException e) { threw = true; }
        ok(threw, "группа 0 недопустима");

        // ---- карточка по контекстам
        sel.clear();
        CommandCard.Card c = CommandCard.menu(CommandCard.ROOT, sel, s);
        ok(c.title.equals("Поселение") && c.byHotkey('q').id.equals("build") && c.byId("selectIdle") != null && c.byId("selectAll") != null, "ничего не выбрано: Построить, Свободные, Все");
        ok(c.byId("assign") == null && c.byId("demolish") == null, "нет приказов рабочим и зданию");
        sel.set(List.of(4, 5));
        c = CommandCard.menu(CommandCard.ROOT, sel, s);
        ok(c.byHotkey('Q').id.equals("assign") && c.byHotkey('Q').kind == Command.Kind.TARGET, "рабочие: Q назначить - режим цели");
        ok(c.byId("unassign") != null && c.byId("prof").kind == Command.Kind.SUBMENU && c.byId("build").kind == Command.Kind.SUBMENU, "рабочие: снять, профессия, построить");
        ok(c.byId("priorities") != null && c.byId("unassign").slot == 1 && c.byId("unassign").hotkey.equals("W"), "горячие клавиши по сетке");
        for (Command k : c.all()) ok(k.slot >= 0 && k.slot < 12 && c.cells[k.slot] == k && k.tooltip.size() >= 1, "клетка " + k + " на месте с подсказкой");
        Worker idleW = s.idleWorkers().get(0);
        sel.set(List.of(idleW.id));
        c = CommandCard.menu(CommandCard.ROOT, sel, s);
        ok(!c.byId("unassign").enabled && c.byId("unassign").tooltip.stream().anyMatch(x -> x.startsWith("!")), "свободного нельзя снять: красная причина");
        c = CommandCard.menu(CommandCard.PROF, sel, s);
        ok(c.all().size() == d.professions.size() + 1 && c.byId("back").slot == 11, "подменю профессий + назад");
        ok(!c.byId("prof:" + idleW.profession).enabled && c.byId("prof:smith").enabled, "своя профессия недоступна, чужая доступна");
        c = CommandCard.menu(CommandCard.BUILD, sel, s);
        List<String> cats = new ArrayList<>();
        for (Command k : c.all()) cats.add(k.id);
        ok(cats.containsAll(List.of("cat:housing", "cat:extract", "cat:process", "cat:storage", "cat:defense")), "категории стройки: " + cats);
        ok(CommandCard.category(d.buildings.get("house")).equals("housing") && CommandCard.category(d.buildings.get("farm")).equals("extract")
                && CommandCard.category(d.buildings.get("sawmill")).equals("process") && CommandCard.category(d.buildings.get("granary")).equals("storage")
                && CommandCard.category(d.buildings.get("barracks")).equals("defense"), "категории выводятся из содержимого");
        c = CommandCard.menu("build:process", sel, s);
        Command saw = c.byId("place:sawmill"), smith = c.byId("place:smithy");
        ok(saw != null && saw.enabled && saw.kind == Command.Kind.PLACE && saw.arg.equals("sawmill"), "лесопилка доступна (лагерь стоит)");
        ok(smith != null && !smith.enabled && smith.tooltip.stream().anyMatch(x -> x.startsWith("!") && x.contains("Рудник")), "кузница недоступна: нужен рудник");
        ok(saw.tooltip.stream().anyMatch(x -> x.startsWith("Цена")) && saw.tooltip.stream().anyMatch(x -> x.startsWith("Берёт")), "в подсказке цена и сырьё");
        Settlement poor = new Settlement("p", "Бедные", d);
        ok(CommandCard.buildingTip(poor, d.buildings.get("house")).stream().anyMatch(x -> x.startsWith("!Не хватает")), "нехватка денег - красная строка");
        ok(CommandCard.parent("build:process").equals("build") && CommandCard.parent("build").equals("root"), "родители меню");

        // здание
        Building lc = s.buildings.get(1);
        sel.selectBuilding(lc.uid);
        c = CommandCard.menu(CommandCard.ROOT, sel, s);
        ok(c.title.equals("Здание") && c.byId("prio+") != null && c.byId("prio-") != null && c.byId("demolish") != null && c.byId("fill") != null, "здание: приоритет, снос и управление рабочими");
        ok(c.byId("upgrade") == null, "не показываем неработающее улучшение как доступный приказ");
        int p0 = lc.priority;
        Orders.Result r = CommandCard.execute(c.byId("prio+"), sel, s);
        ok(r.commands.equals(List.of("wfsettle priority " + lc.uid + " " + (p0 + 1))), "приоритет+ -> команда: " + r.commands);
        apply(s, null, r);
        ok(lc.priority == p0 + 1, "команда меняет приоритет в Settlement");
        r = CommandCard.execute(c.byId("demolish"), sel, s);
        ok(r.commands.equals(List.of("wfsettle cancel " + lc.uid)), "снести -> cancel");
        // заполнение из свободных
        Settlement t2 = new Settlement("t", "T", d);
        t2.add("wood", 100); t2.add("food", 50);
        for (int i = 0; i < 4; i++) t2.addWorker(null);
        t2.auto = false;
        Building f = t2.place("farm", 0, 0);
        t2.tick(1);
        for (Worker w : t2.workers) t2.setProfession(w.id, "builder");
        for (int k = 0; k < Math.min(f.def.builders, t2.workers.size()); k++) t2.assign(t2.workers.get(k).id, f.uid);
        t2.tick(80);
        ok(f.state == Building.State.DONE, "ферма построена");
        for (Worker w : t2.workers) { t2.unassign(w.id); t2.setProfession(w.id, "laborer"); }
        Selection s2 = new Selection();
        s2.selectBuilding(f.uid);
        CommandCard.Card c2 = CommandCard.menu(CommandCard.ROOT, s2, t2);
        r = CommandCard.execute(c2.byId("fill"), s2, t2);
        ok(r.ok && r.commands.stream().filter(x -> x.contains(" assign ")).count() == 4, "назначить рабочих: 4 места - 4 приказа: " + r.commands);
        apply(t2, null, r);
        ok(t2.assigned(f.uid).size() == 4
                        && t2.assigned(f.uid).stream().allMatch(w -> w.profession.equals("laborer") && w.workRole().equals("farmer")),
                "RTS назначает универсальных рабочих на ферму, сохраняя их постоянную профессию");
        c2 = CommandCard.menu(CommandCard.ROOT, s2, t2);
        ok(!c2.byId("fill").enabled, "мест нет: назначить недоступно");

        // выполнение «снять» и «профессия»
        Selection s3 = new Selection();
        s3.selectProfession(t2, "farmer", false);
        r = CommandCard.execute(CommandCard.menu(CommandCard.ROOT, s3, t2).byId("unassign"), s3, t2);
        ok(r.commands.size() == 4, "снять: по приказу на каждого");
        apply(t2, null, r);
        ok(t2.idleWorkers().size() == 4, "все свободны");
        r = CommandCard.execute(CommandCard.menu(CommandCard.PROF, s3, t2).byId("prof:smith"), s3, t2);
        apply(t2, null, r);
        ok(t2.workers.stream().allMatch(w -> w.profession.equals("smith")), "профессия сменена командами");
        r = CommandCard.execute(CommandCard.menu(CommandCard.ROOT, s3, t2).byId("auto"), s3, t2);
        apply(t2, null, r);
        ok(t2.auto, "команда auto переключает");

        // ---- умный ПКМ
        Settlement g = new Settlement("g", "G", d);
        g.add("wood", 200); g.add("stone", 50); g.add("food", 50);
        for (int i = 0; i < 5; i++) g.addWorker(null);
        g.auto = false;
        Building farm = g.place("farm", 20, 0);
        g.tick(1);
        Selection q = new Selection();
        q.set(List.of(1, 2));
        r = Orders.rightClick(q, g, Target.building(farm.uid, 20, 0), null);
        ok(r.ok && r.commands.equals(List.of("wfsettle assign 1 " + farm.uid, "wfsettle assign 2 " + farm.uid)),
                "ПКМ: универсальные рабочие получают приказ строить без переучивания: " + r.commands);
        apply(g, null, r);
        ok(g.worker(1).profession.equals("laborer") && g.worker(1).workRole().equals("builder"),
                "приказ строить сохраняет постоянную профессию");
        g.tick(100);
        ok(farm.state == Building.State.DONE, "ферма достроена");
        g.setProfession(2, "builder");
        g.setProfession(3, "farmer");
        q.set(List.of(3));
        r = Orders.rightClick(q, g, Target.building(farm.uid, 20, 0), null);
        ok(r.ok && r.commands.size() == 1, "ПКМ: земледелец на ферму");
        q.set(List.of(2));
        r = Orders.rightClick(q, g, Target.building(farm.uid, 20, 0), null);
        ok(r.isEmpty() && r.message.contains("не нужен"), "ПКМ: строитель на ферму - отказ с причиной: " + r.message);
        q.selectBuilding(farm.uid);
        r = Orders.rightClick(q, g, Target.worker(3, 0, 0), null);
        ok(r.ok && r.commands.equals(List.of("wfsettle assign 3 " + farm.uid)), "здание выбрано, ПКМ по рабочему -> назначить");
        q.set(List.of(3));
        r = Orders.rightClick(q, g, Target.ground(0, 0), null);
        ok(r.ok && r.commands.equals(List.of("wfsettle move 3 0.0 0.0")),
                "ПКМ по земле выдаёт выбранным рабочим приказы движения");
        r = Orders.rightClick(q, g, Target.ground(-20, 20), "house");
        ok(r.ok && r.commands.equals(List.of("wfsettle build house -20 20")), "ПКМ по земле при размещении -> build");
        r = Orders.rightClick(q, g, Target.ground(20, 0), "house");
        ok(r.isEmpty() && r.message.contains("занято"), "место занято: причина от сервера: " + r.message);
        r = Orders.rightClick(q, g, Target.building(farm.uid, 20, 0), "house");
        ok(r.cancelPending, "ПКМ по зданию при размещении отменяет его");
        apply(g, null, Orders.place(g, "house", -20, 20));
        ok(g.count("house", false) == 1, "build применяется в Settlement");

        // ---- приоритеты работ
        WorkPriorities wp = new WorkPriorities();
        Worker w1 = g.worker(1);
        ok(wp.get(w1, "builder") == 1 && wp.get(w1, "farmer") == 1,
                "умолчание: универсальный рабочий имеет базовый приоритет для каждой доступной роли");
        String cmd = wp.cycle(w1, "farmer", false);
        ok(cmd.equals("wfsettle priority-work 1 farmer 2") && wp.get(w1, "farmer") == 2,
                "клик повышает базовый приоритет универсального рабочего: 1 -> 2");
        for (int i = 0; i < 3; i++) wp.cycle(w1, "farmer", false);
        ok(wp.get(w1, "farmer") == 0, "цикл приоритетов замыкается после пяти значений");
        wp.cycle(w1, "farmer", true);
        ok(wp.get(w1, "farmer") == 4, "ПКМ - назад");
        Dispatch.apply(g, wp, "wfsettle priority-work 1 smith 3");
        ok(wp.get(w1, "smith") == 3 && wp.best(g, w1).equals("builder"), "Dispatch и best");

        // ---- карта
        MapView collapsed = MapView.fit(g, 0, 0, 0, 0);
        ok(Double.isFinite(collapsed.scale) && collapsed.scale > 0, "свёрнутая миникарта сохраняет конечный положительный масштаб");
        MapView narrow = MapView.fit(g, 0, 0, -20, 100);
        ok(Double.isFinite(narrow.scale) && narrow.scale > 0, "некорректная ширина миникарты не создаёт бесконечный масштаб");
        MapView mv = MapView.fit(g, 0, 0, 120, 100);
        Worker badMapWorker = g.workers.get(0);
        double oldMapX = badMapWorker.x, oldMapZ = badMapWorker.z;
        badMapWorker.x = Double.NaN; badMapWorker.z = Double.POSITIVE_INFINITY;
        MapView sanitized = MapView.fit(g, 0, 0, 120, 100);
        ok(Double.isFinite(sanitized.minX) && Double.isFinite(sanitized.minZ) && Double.isFinite(sanitized.scale), "повреждённые координаты рабочего не ломают карту поселения");
        ok(sanitized.rects(g).stream().noneMatch(rect -> rect.kind == Selection.Rect.WORKER && rect.id == badMapWorker.id), "рабочий с некорректными координатами не попадает в выбор карты");
        badMapWorker.x = oldMapX; badMapWorker.z = oldMapZ;
        Building b0 = g.buildings.get(0);
        // Invalid entries are skipped by rects() and must not shift hit-test indices.
        g.buildings.add(0, null);
        ok(mv.pick(g, mv.sx(b0.x), mv.sy(b0.z)).kind == Target.Kind.BUILDING, "миникарта: пропуск повреждённой записи не ломает hit-test");
        g.buildings.remove(0);
        ok(mv.pick(g, mv.sx(b0.x), mv.sy(b0.z)).kind == Target.Kind.BUILDING, "миникарта: здание под точкой");
        Worker mw = g.workers.get(0);
        Target wt = mv.pick(g, mv.sx(mw.x), mv.sy(mw.z));
        ok(wt.kind == Target.Kind.WORKER && wt.id == mw.id, "миникарта: рабочий важнее");
        ok(Math.abs(mv.wx(mv.sx(7.0)) - 7.0) < 1.0 / mv.scale, "преобразование туда-обратно");
        ok(mv.pick(g, 0, 0).kind == Target.Kind.GROUND, "угол - земля");
        sel.clear();
        sel.boxSelect(mv.rects(g), mv.px, mv.py, mv.px + mv.pw, mv.py + mv.ph, false);
        ok(sel.size() == g.workers.size(), "рамка на всю миникарту берёт всех рабочих");

        // ---- вид
        Worker lj = s.workers.stream().filter(w -> w.profession.equals("lumberjack")).findFirst().get();
        ok(Look.state(s, idleW) == Look.WorkerState.IDLE, "свободный - IDLE");
        ok(Look.glyph(d, "lumberjack").equals("Д") && Look.glyph(d, "unknown_prof").equals("U"), "знаки профессий");
        ok(lj != null && lj.profession.equals("lumberjack"), "проверка профессии не маскирует ошибку безусловным true");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
