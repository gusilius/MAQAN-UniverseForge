package dev.worldforge.tests;

import dev.worldforge.troops.*;
import java.nio.file.*;
import java.util.*;

public class TroopsTest {
    static int fails = 0;
    static void check(boolean ok, String msg) { if (!ok) { fails++; System.out.println("FAIL " + msg); } }

    public static void main(String[] a) throws Exception {
        TroopDefs d = new TroopDefs();
        d.load(Files.readString(Path.of("wf-troops/src/main/resources/data/wf_troops/wf_troops/units.json")));
        check(d.units.size() == 14, "14 юнитов " + d.units.size());
        check(d.validate().isEmpty(), "валидация " + d.validate());
        TroopDefs bad = new TroopDefs();
        bad.add(new HashMap<>(Map.of("id", "x", "role", "wizard", "hp", 0)));
        check(bad.validate().size() >= 2, "ошибки данных");

        // строи
        for (Formations.Type t : Formations.Type.values()) {
            for (int n : new int[]{1, 5, 17, 40}) {
                List<Formations.Slot> s = Formations.slots(t, n, 1.5);
                check(s.size() == n, t + " мест " + n);
                Set<String> uniq = new HashSet<>();
                for (Formations.Slot x : s) uniq.add(Math.round(x.x() * 100) + ":" + Math.round(x.z() * 100));
                if (t != Formations.Type.LOOSE) check(uniq.size() == n, t + " без наложений " + n);
            }
        }
        List<Formations.Slot> line = Formations.slots(Formations.Type.LINE, 5, 2);
        check(line.stream().allMatch(s -> s.z() == 0), "шеренга в одну линию");
        List<double[]> p0 = Formations.place(Formations.slots(Formations.Type.COLUMN, 4, 2), 100, 100, 0);
        check(Math.abs(p0.get(0)[0] - p0.get(2)[0]) < 1e-9 && Math.abs(p0.get(0)[1] - p0.get(2)[1]) > 1, "колонна вытянута по курсу");
        double yaw = Formations.yawTo(0, 0, 10, 0);
        check(Math.abs(yaw - (-90)) < 1e-6, "yaw на восток " + yaw);
        List<String> roles = List.of("ranged", "melee", "cavalry", "melee", "ranged");
        List<Formations.Slot> sl = Formations.slots(Formations.Type.BLOCK, 5, 1.5);
        int[] as = Formations.assign(roles, sl, null);
        check(new HashSet<>(Arrays.asList(Arrays.stream(as).boxed().toArray())).size() == 5, "места уникальны");
        check(sl.get(as[1]).z() >= sl.get(as[0]).z(), "ближний бой впереди стрелков");

        // отряды
        Squads sq = new Squads();
        Squads.Squad s1 = sq.create("p1", "Гвардия", List.of("a", "b", "c", "d", "e", "f"));
        check(sq.squadOf("c") == s1, "членство");
        Squads.Squad part = sq.commandUnits("p1", List.of("a", "b"), Squads.Order.move(10, 20), false);
        check(part != s1 && part.members.size() == 2 && s1.members.size() == 4, "часть выделена в новый отряд");
        check(part.current().kind() == Squads.Kind.MOVE && s1.current() == null, "приказ только части");
        Squads.Squad all = sq.commandUnits("p1", s1.members, Squads.Order.of(Squads.Kind.HOLD), false);
        check(all == s1 && s1.current().kind() == Squads.Kind.HOLD, "весь отряд без дробления");
        check(sq.commandUnits("p2", List.of("a"), Squads.Order.move(0, 0), false) == null, "чужие солдаты не слушаются");
        Squads.Squad mix = sq.commandUnits("p1", List.of("a", "c"), Squads.Order.move(1, 1), false);
        check(mix.members.size() == 2 && sq.squadOf("a") == mix && sq.squadOf("c") == mix, "сводный отряд");
        check(sq.all().stream().allMatch(s -> !s.members.isEmpty()), "пустые отряды удалены");
        sq.command(mix.id, Squads.Order.move(5, 5), true);
        check(mix.queue.size() == 2, "очередь приказов (Shift)");
        sq.completeCurrent(mix.id);
        check(mix.queue.size() == 1, "приказ выполнен");
        sq.command(mix.id, Squads.Order.of(Squads.Kind.STOP), false);
        check(mix.queue.isEmpty(), "стоп");
        Squads.Squad big = sq.create("p1", "Толпа", new ArrayList<>(java.util.stream.IntStream.range(0, 60).mapToObj(i -> "u" + i).toList()));
        check(big.members.size() == Squads.MAX_SIZE, "лимит отряда");
        sq.remove("u0");
        check(!big.members.contains("u0"), "смерть удаляет из отряда");
        check(sq.merge(s1.id, mix.id), "слияние");
        Squads duplicateInput = new Squads();
        Squads.Squad uniqueSquad = duplicateInput.create("p1", "Unique", Arrays.asList("x", "x", "", null, "y"));
        check(uniqueSquad.members.equals(List.of("x", "y")), "создание отряда удаляет дубликаты и пустые UUID");
        Squads partialMerge = new Squads();
        Squads.Squad receiver = partialMerge.create("p1", "Receiver", java.util.stream.IntStream.range(0, 39).mapToObj(i -> "r" + i).toList());
        Squads.Squad donor = partialMerge.create("p1", "Donor", List.of("d1", "d2", "d3"));
        donor.initial = 6;
        check(partialMerge.merge(receiver.id, donor.id) && receiver.members.size() == 40
                && donor.members.equals(List.of("d2", "d3")) && receiver.initial == 41 && donor.initial == 4,
                "частичное слияние переносит только доступных бойцов и пропорциональные потери");
        check(!partialMerge.merge(receiver.id, donor.id), "слияние полного отряда отклоняется без потери бойцов");
        String json = sq.toJson();
        Squads sq2 = new Squads();
        sq2.fromJson(json);
        check(sq2.all().size() == sq.all().size() && sq2.get(s1.id).members.equals(s1.members), "сохранение/загрузка");
        check(sq2.create("p1", "", List.of("zz")).id.compareTo("s") > 0 && sq2.get("s" + 99) == null, "счётчик продолжается");
        check(sq.viewJson("p1").startsWith("[{"), "view");

        Squads damaged = new Squads();
        damaged.fromJson("{\\"counter\\":2,\\"squads\\":[null,7,{\\"id\\":\\"s1\\",\\"name\\":\\"bad\\",\\"owner\\":\\"p1\\",\\"members\\":[\\"u1\\",\\"u1\\",null,\\"u2\\",7],\\"cx\\":1e999,\\"morale\\":-4},{\\"id\\":\\"s2\\",\\"name\\":\\"\\"},{\\"id\\":\\"s3\\",\\"owner\\":\\"p1\\",\\"members\\":[\\"u1\\",\\"u3\\"]}]}");
        check(damaged.all().size() == 1 && damaged.get("s1").members.equals(List.of("u1", "u2"))
                && damaged.get("s1").cx == 0 && damaged.get("s1").morale == 0,
                "загрузка повреждённых отрядов фильтрует записи, UUID и нечисловые значения");
        String escaped = damaged.create("p1", "Guard \\"North\\\\Wing\\n", List.of("u4")).name;
        check(dev.worldforge.mod.Json.parse(damaged.toJson()) instanceof Map, "сериализация отрядов экранирует строки и остаётся валидным JSON");

        // выделение
        UnitSelection sel = new UnitSelection();
        List<UnitSelection.Screen> sc = List.of(
                new UnitSelection.Screen("a", "footman", 100, 100, 10, true), new UnitSelection.Screen("b", "footman", 130, 100, 10, true),
                new UnitSelection.Screen("c", "archer", 300, 200, 10, true), new UnitSelection.Screen("d", "footman", 500, 500, 10, false));
        check(sel.click(sc, 101, 99, false, 0) && sel.selected().equals(Set.of("a")), "клик");
        sel.click(sc, 131, 100, true, 1000);
        check(sel.selected().equals(Set.of("a", "b")), "shift добавляет");
        sel.click(sc, 131, 100, true, 2000);
        check(sel.selected().equals(Set.of("a")), "shift убирает");
        sel.click(sc, 300, 200, false, 5000); sel.click(sc, 300, 200, false, 5200);
        check(sel.selected().equals(Set.of("c")), "двойной клик по единственному типу");
        sel.click(sc, 100, 100, false, 9000); sel.click(sc, 100, 100, false, 9200);
        check(sel.selected().equals(Set.of("a", "b")), "двойной клик - все того же типа на экране (невидимых нет) " + sel.selected());
        check(!sel.click(sc, 700, 700, false, 20000) && sel.selected().isEmpty(), "клик в пустоту снимает выбор");
        UnitSelection missReset = new UnitSelection();
        missReset.click(sc, 100, 100, false, 0);
        missReset.click(sc, 700, 700, false, 100);
        missReset.click(sc, 100, 100, false, 200);
        check(missReset.selected().equals(Set.of("a")), "клик в пустоту сбрасывает последовательность двойного клика");
        check(sel.box(sc, 50, 50, 350, 250, false) == 3, "рамка");
        check(!UnitSelection.isBox(10, 10, 12, 13) && UnitSelection.isBox(10, 10, 40, 40), "малая рамка - клик");
        sel.saveGroup(0);
        check(!sel.recallGroup(0, Set.of("a", "c")), "нулевая группа не используется");
        sel.saveGroup(1); sel.clear();
        check(sel.recallGroup(1, Set.of("a", "c")) && sel.selected().equals(Set.of("a", "c")), "группа 1, павшие убраны");
        UnitSelection clockSelection = new UnitSelection();
        clockSelection.click(sc, 100, 100, false, 1000);
        clockSelection.click(sc, 100, 100, false, 900);
        check(clockSelection.selected().equals(Set.of("a")), "откат системного времени не считается двойным кликом");
        List<UnitSelection.Screen> mixedScreens = Arrays.asList(
                new UnitSelection.Screen("valid", "worker", 10, 10, 5, true),
                new UnitSelection.Screen("bad-x", "worker", Double.NaN, 10, 5, true),
                new UnitSelection.Screen("bad-radius", "worker", 10, 10, Double.POSITIVE_INFINITY, true),
                new UnitSelection.Screen("", "worker", 10, 10, 5, true),
                null);
        UnitSelection guardedSelection = new UnitSelection();
        check(guardedSelection.click(mixedScreens, 10, 10, false, 10)
                && guardedSelection.selected().equals(Set.of("valid")), "выбор игнорирует повреждённые и пустые экранные сущности");
        check(!guardedSelection.click(mixedScreens, Double.NaN, 10, false, 11)
                && guardedSelection.selected().equals(Set.of("valid")), "некорректная позиция курсора не меняет выбор");
        guardedSelection.set(Arrays.asList("valid", null, "", "valid"));
        check(guardedSelection.selected().equals(Set.of("valid")), "установка выбора фильтрует пустые идентификаторы");
        guardedSelection.saveGroup(1);
        guardedSelection.retain(Set.of());
        check(guardedSelection.recallGroup(1, Set.of("valid")) == false, "очистка живых сущностей очищает и состав групп");

        // проекция
        double[] vp = Projection.mul(Projection.perspective(70, 16 / 9.0, 0.05, 512), Projection.view(0, 30));
        double[] pt = Projection.project(vp, 0, -5, 10, 1600, 900);
        check(pt[2] > 0, "точка перед камерой");
        double[] inv = Projection.invert(vp);
        check(inv != null, "обратимость");
        double[] dir = Projection.ray(inv, pt[0], pt[1], 1600, 900);
        double[] hit = Projection.hitPlane(dir, -5);
        check(hit != null && Math.abs(hit[0]) < 1e-3 && Math.abs(hit[2] - 10) < 1e-2, "луч попадает в точку на земле " + Arrays.toString(hit));
        check(Projection.project(vp, 0, 0, -10, 1600, 900)[2] < 0, "позади камеры");

        // камера
        CameraRig cam = new CameraRig();
        cam.start(100, 100, 0, CameraRig.View.SKY);
        double[] pos = cam.position(64);
        check(pos[1] > 64 + 30 && Math.abs(pos[0] - 100) < 1e-9 && pos[2] < 100, "камера сверху и позади фокуса " + Arrays.toString(pos));
        double bz = cam.fz; cam.pan(1, 0, 0.05, false);
        check(cam.fz > bz && Math.abs(cam.fx - 100) < 1e-6, "W двигает вперёд по курсу (yaw 0 -> +Z)");
        double bx = cam.fx; cam.pan(0, 1, 0.05, false);
        check(cam.fx < bx, "D двигает вправо (yaw 0 -> -X)");
        cam.yaw = 90; bx = cam.fx; cam.pan(1, 0, 0.05, false);
        check(cam.fx < bx, "yaw 90: вперёд = -X");
        double d1 = cam.zoom; cam.zoomBy(3);
        check(cam.zoom < d1 && cam.zoom >= CameraRig.MIN_ZOOM, "приближение");
        cam.zoomBy(-100); check(cam.zoom == CameraRig.MAX_ZOOM, "потолок высоты");
        cam.zoomBy(500); check(cam.zoom == CameraRig.MIN_ZOOM, "пол высоты");
        cam.focusOn(1000, 1000); cam.clamp(0, 0);
        check(Math.hypot(cam.fx, cam.fz) <= cam.leash + 1e-6, "привязь к игроку");
        cam.rotate(400, 500); check(cam.pitch == CameraRig.MAX_PITCH && Math.abs(cam.yaw) < 360, "поворот и наклон ограничены");
        cam.cycle(); check(cam.view == CameraRig.View.TACTICS && cam.pitch == CameraRig.View.TACTICS.pitch, "смена вида камеры");
        double[] es = CameraRig.edgeScroll(2, 300, 1600, 900);
        check(es[0] == -1 && es[1] == 0, "скролл у левого края");
        check(CameraRig.edgeScroll(800, 450, 1600, 900)[0] == 0, "в центре не скроллит");
        CameraRig c2 = new CameraRig(); c2.start(0, 0, 0, CameraRig.View.SKY); double[] cp = c2.position(10);
        double[] vp2 = Projection.mul(Projection.perspective(70, 16 / 9.0, 0.05, 512), Projection.view(0, CameraRig.View.SKY.pitch));
        check(vp2 != null && cp[1] > 10, "положение камеры над землёй");

        // призрак здания
        BuildGhost.Def farm = new BuildGhost.Def("farm", "Ферма", "food", 5, 5, Map.of("wood", 15, "stone", 4), "");
        List<BuildGhost.Rect> taken = List.of(new BuildGhost.Rect(100, 100, 5, 5));
        check(BuildGhost.check(farm, 100, 100, 100, 100, taken, Map.of()).equals("Место занято"), "занято");
        check(BuildGhost.check(farm, 104, 100, 100, 100, taken, Map.of()).equals("Место занято"), "почти вплотную пересекает");
        check(BuildGhost.check(farm, 105, 100, 100, 100, taken, Map.of()).isEmpty(), "вплотную можно");
        check(!BuildGhost.check(farm, 400, 100, 100, 100, taken, Map.of()).isEmpty(), "слишком далеко");
        check(BuildGhost.missing(farm, Map.of("wood", 20)).equals("stone 4"), "нехватка материалов " + BuildGhost.missing(farm, Map.of("wood", 20)));
        check(BuildGhost.missing(farm, Map.of("wood", 20, "stone", 9)).isEmpty(), "материалов хватает");
        check(Arrays.equals(BuildGhost.offset(130, 90, 100, 100), new int[]{30, -10}), "смещение от центра поселения");
        check(BuildGhost.corners(10, 10, 4, 6).length == 4, "4 угла");
        List<BuildGhost.Def> parsed = BuildGhost.parseDefs(dev.worldforge.mod.Json.parse("[{\"id\":\"hut\",\"name\":\"Хижина\",\"category\":\"housing\",\"size\":[3,4],\"cost\":{\"wood\":8}}]"));
        check(parsed.size() == 1 && parsed.get(0).sz() == 4 && parsed.get(0).cost().get("wood") == 8, "разбор списка зданий");

        // призыв из армии и жалованье
        check(d.validate().isEmpty() && d.armyMap.size() == 6, "army_map в данных " + d.armyMap);
        Map<String, Integer> army = new LinkedHashMap<>(); army.put("levy", 400); army.put("spear", 95); army.put("archer", 30); army.put("siege", 5);
        Muster.Plan pl = Muster.plan(army, 50, d.armyTypes());
        check(pl.total() == 50 && pl.soldiers().get("levy_spear") == 40 && pl.soldiers().get("pikeman") == 9 && pl.soldiers().get("archer") == 1, "план призыва " + pl.soldiers());
        check(pl.menTaken().get("levy") == 400 && pl.menTaken().get("spear") == 90 && !pl.menTaken().containsKey("siege"), "снято людей " + pl.menTaken());
        check(Muster.plan(Map.of("siege", 100), 10, d.armyTypes()).soldiers().get("ram") == 10, "осадные расчёты призываются как тараны");
        check(Muster.plan(Map.of("spear", 9), 10, d.armyTypes()).total() == 0, "меньше SCALE людей - никого");
        Map<String, Integer> back = Muster.back(Map.of("pikeman", 5, "levy_spear", 3), d.armyTypes());
        check(back.get("spear") == 50 && back.get("levy") == 30, "возврат выживших " + back);
        check(Muster.upkeep(d, Map.of("footman", 10, "knight", 2)) == 5.0, "жалованье " + Muster.upkeep(d, Map.of("footman", 10, "knight", 2)));
        int desertTotal = 0; Random rr = new Random(1);
        for (int i = 0; i < 400; i++) desertTotal += Muster.deserters(10, rr).size();
        check(Math.abs(desertTotal / 4000.0 - Muster.DESERT_CHANCE) < 0.05, "дезертирство около четверти " + desertTotal);

        // мораль
        double m = 1.0;
        for (int i = 0; i < 40; i++) m = Morale.step(m, 1.0, 0, 0, false, false, 0.5);
        check(m > 0.99, "спокойный полный отряд держит мораль " + m);
        m = Morale.step(1.0, 0.6, 0.4, 2, false, true, 0.5);
        check(m < 0.55, "потеря 40% бьёт по духу сразу " + m);
        double mm = 1.0; boolean rt = false; int rounds = 0;
        for (int i = 0; i < 80 && !rt; i++) { mm = Morale.step(mm, 0.4, i % 4 == 0 ? 0.08 : 0, 3, false, true, 0.5); rt = Morale.routed(rt, mm, true); rounds++; }
        check(rt, "под натиском с потерями отряд бежит (шаг " + rounds + ", мораль " + mm + ")");
        check(Morale.routed(true, 0.4, false), "ещё не оправился (0.4 < 0.55)");
        check(Morale.routed(true, 0.7, true), "враги рядом - не оправился");
        check(!Morale.routed(true, 0.7, false), "оправился");
        check(!Morale.routed(false, 0.3, true), "не бежал, мораль выше порога");
        double withLeader = 0, without = 0;
        for (int i = 0; i < 60; i++) { withLeader = Morale.step(i == 0 ? 0.5 : withLeader, 0.7, 0, 1.5, true, true, 0.5); without = Morale.step(i == 0 ? 0.5 : without, 0.7, 0, 1.5, false, true, 0.5); }
        check(withLeader > without, "командир удерживает мораль выше " + withLeader + " > " + without);
        check(Morale.damage(1, false) == 1.0 && Morale.damage(0.2, false) < 0.7 && Morale.damage(1, true) > 1.1, "множитель урона");
        double[] fl = Morale.flee(100, 100, 100, 130, 40);
        check(Math.abs(fl[0] - 100) < 1e-6 && fl[1] < 100 - 39, "бегут от врага " + Arrays.toString(fl));
        double[] hp = {10, 20, 5, 15}, mx = {20, 20, 20, 20};
        double[] hl = Morale.healPlan(hp, mx, 2, 3);
        check(hl[2] == 3 && hl[0] == 3 && hl[1] == 0 && hl[3] == 0, "лечит самых раненных, не больше двух " + Arrays.toString(hl));
        check(Arrays.stream(Morale.healPlan(new double[]{20, 20}, new double[]{20, 20}, 3, 3)).sum() == 0, "здоровым лечение не нужно");
        check(d.units.get("healer").heal() > 0 && d.units.get("sergeant").aura() >= 12, "лекарь лечит, сержант даёт ауру");
        Squads sm = new Squads();
        Squads.Squad ms = sm.create("p", "M", List.of("a", "b", "c", "d"));
        check(ms.initial == 4, "начальный состав");
        ms.morale = 0.3; ms.routed = true;
        Squads sm2 = new Squads(); sm2.fromJson(sm.toJson());
        Squads.Squad ms2 = sm2.get(ms.id);
        check(ms2.initial == 4 && Math.abs(ms2.morale - 0.3) < 1e-9 && ms2.routed, "мораль сохраняется");
        Squads.Squad part2 = sm.commandUnits("p", List.of("a"), Squads.Order.move(1, 1), false);
        check(part2.morale == 0.3 && part2.routed && ms.initial == 3, "часть отряда наследует мораль, состав пересчитан " + ms.initial);

        // укрепления
        Forts fs = new Forts();
        Forts.Fort fort = fs.create("Тестовая", "p1", "", "minecraft:overworld", 100, 100, 16, 2);
        check(fort.wallMax == 200 + 160 + 100 && fort.wall == fort.wallMax, "стена по размеру " + fort.wallMax);
        check(fort.hostileTo("p2", "red") && !fort.hostileTo("p1", "") && !fort.hostileTo("p2", ""), "враждебность: частные владельцы мирные, государства враждебны");
        Forts.Fort bandit = fs.create("Лагерь", "", "", "minecraft:overworld", 0, 0, 10, 1);
        check(bandit.hostileTo("p1", "") && bandit.hostileTo("p9", "red"), "бандитский лагерь враждебен всем");
        Forts.Fort realmFort = fs.create("Замок", "p1", "red", "minecraft:overworld", 0, 0, 12, 3);
        check(!realmFort.hostileTo("p3", "red") && realmFort.hostileTo("p3", "blue") && realmFort.hostileTo("p3", ""), "замок государства");
        Forts.Tick tk = fs.tick(fort, 0, 5, 4, true, 1);
        check(tk.towerDps() == 2 * Forts.TOWER_DPS && !tk.captured() && fort.garrison == 4, "башни стреляют по врагам рядом " + tk.towerDps());
        check(fs.tick(fort, 0, 0, 4, false, 1).towerDps() == 0, "врагов нет - башни молчат");
        check(fs.tick(fort, 0, 5, 0, true, 1).towerDps() == 2 * Forts.TOWER_DPS * Forts.TOWER_BLIND, "без гарнизона башни слепнут");
        double w0 = fort.wall; fs.tick(fort, 14 * 3, 5, 4, true, 1);
        check(Math.abs(fort.wall - (w0 - 42)) < 1e-6, "три тарана бьют стену " + fort.wall);
        int secs = 0; boolean fellOnce = false;
        while (!fort.breached() && secs < 600) { Forts.Tick x = fs.tick(fort, 42, 5, 4, true, 1); fellOnce |= x.wallFell(); secs++; }
        check(fort.breached() && fellOnce && secs > 5 && secs < 40, "стена рухнет за разумное время: " + secs + " с");
        for (int i = 0; i < 100; i++) fs.tick(fort, 0, 5, 3, true, 1);
        check(fort.capture == 0 && fort.wall > 0, "пока есть защитники - захвата нет, стену они починили");
        fs.damageWall(fort, fort.wallMax);
        check(fort.breached(), "стена снова разрушена");
        boolean cap = false; int cs = 0;
        for (; cs < 60 && !cap; cs++) cap = fs.tick(fort, 0, 5, 0, true, 1).captured();
        check(cap && cs >= Forts.CAPTURE_SECONDS - 1 && cs <= Forts.CAPTURE_SECONDS + 2, "захват за ~20 с без защитников: " + cs);
        fs.capture(fort, "p2", "blue");
        check(fort.owner.equals("p2") && fort.realm.equals("blue") && fort.wall >= fort.wallMax * 0.25 && !fort.hostileTo("p2", "blue"), "новый владелец");
        Forts.Fort rep = fs.create("Ремонт", "p1", "", "minecraft:overworld", 0, 0, 10, 0); fs.damageWall(rep, 100);
        for (int i = 0; i < 12; i++) fs.tick(rep, 0, 0, 5, false, 1);
        check(rep.wall > rep.wallMax - 100, "защитники чинят стену, когда не бьют " + rep.wall);
        Forts fs2 = new Forts(); fs2.fromJson(fs.toJson());
        check(fs2.all().size() == fs.all().size() && fs2.get(fort.id).owner.equals("p2") && Math.abs(fs2.get(rep.id).wall - rep.wall) < 1e-6, "укрепления сохраняются");
        check(fs2.create("Новая", "", "", "minecraft:overworld", 0, 0, 5, 0).id > rep.id, "счётчик id продолжается");
        check(d.units.get("ram").siege() > d.units.get("footman").siege() && d.units.get("footman").siege() > 0 && d.units.get("archer").siege() == 0, "осадный урон: таран > пехота > стрелки");

        // войны государств
        check(Wars.atWar("red", "blue,green", "blue", "") && Wars.atWar("blue", "", "red", "x, blue") && !Wars.atWar("red", "green", "blue", "") , "война по списку врагов");
        check(!Wars.atWar("red", "red", "red", "red") && !Wars.atWar("", "red", "red", "") && !Wars.atWar("red", null, "blue", null), "не воюют: одно государство, без государства, пустые списки");
        check(Wars.listed(" a , b ", "b") && !Wars.listed("ab", "b"), "разбор списка");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
