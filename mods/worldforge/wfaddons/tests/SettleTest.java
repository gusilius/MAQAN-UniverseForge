package dev.worldforge.tests;

import dev.worldforge.settle.*;
import dev.worldforge.mod.Json;

import java.nio.file.*;
import java.util.*;

public class SettleTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }
    static String firstDiff(Object a, Object b, String path) {
        if (Objects.equals(a, b)) return "";
        if (a instanceof Map<?, ?> am && b instanceof Map<?, ?> bm) {
            Set<String> keys = new TreeSet<>();
            am.keySet().forEach(k -> keys.add(String.valueOf(k)));
            bm.keySet().forEach(k -> keys.add(String.valueOf(k)));
            for (String key : keys) {
                String diff = firstDiff(am.get(key), bm.get(key), path + "." + key);
                if (!diff.isEmpty()) return diff;
            }
        }
        if (a instanceof List<?> al && b instanceof List<?> bl) {
            if (al.size() != bl.size()) return path + ".size: " + al.size() + " != " + bl.size();
            for (int i = 0; i < al.size(); i++) {
                String diff = firstDiff(al.get(i), bl.get(i), path + "[" + i + "]");
                if (!diff.isEmpty()) return diff;
            }
        }
        return path + ": " + a + " != " + b;
    }

    static Defs defs() throws Exception {
        Defs d = new Defs();
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/economy.json")));
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/extra.json")));
        return d;
    }

    static int nextSettlementId = 1;
    static Settlement fresh(Defs d, int workers) {
        Settlement s = new Settlement("settle_test_" + (nextSettlementId++), "Тест", d);
        s.add("wood", 100); s.add("stone", 20); s.add("food", 50);
        for (int i = 0; i < workers; i++) s.addWorker(null);
        return s;
    }

    static boolean throwsOrder(Runnable r) {
        try { r.run(); return false; } catch (Settlement.OrderException e) { return true; }
    }

    public static void main(String[] a) throws Exception {
        Defs d = defs();
        ok(d.validate().stream().noneMatch(m -> !m.startsWith("Предупреждение")), "набор данных без ошибок: " + d.validate());
        ok(d.buildings.size() >= 90 && d.professions.size() >= 46,
                "единый каталог содержит сельские и городские постройки и полный набор профессий");
        ok(d.buildings.values().stream().noneMatch(b -> b.limit > 0),
                "каталог не навязывает региональные и унаследованные лимиты построек");
        ok(d.buildings.values().stream().allMatch(b -> b.description != null && !b.description.isBlank()),
                "у каждой постройки определено назначение");
        ok(d.buildings.containsKey("windmill") && d.buildings.get("windmill").in.containsKey("grain")
                        && d.buildings.get("bakery").in.containsKey("flour"),
                "зерно проходит цепочку мельница -> мука -> пекарня");
        ok(d.buildings.containsKey("gold_mine") && d.buildings.containsKey("silver_mine")
                        && d.buildings.containsKey("copper_mine")
                        && d.buildings.get("mint").in.containsKey("gold"),
                "добыча драгоценных и цветных металлов связана с ювелирным делом и чеканкой");
        ok(d.buildings.get("village_granary").storage.getOrDefault("food", 0) > 0
                        && d.buildings.get("city_storehouse").storage.getOrDefault("steel", 0) > 0,
                "деревенский и городской склады реально увеличивают вместимость общего хранилища");
        ok(d.professions.containsKey("trainer") && d.buildings.get("barracks").jobs.getOrDefault("trainer", 0) == 1,
                "казармы требуют рабочего-инструктора");
        Worker roleProbe = fresh(d, 1).workers.get(0);
        ok(roleProbe.generalist() && roleProbe.canPerform("lumberjack") && roleProbe.canPerform("builder")
                        && roleProbe.productivity("lumberjack") == 1.0,
                "общий рабочий универсален, но работает без профессионального бонуса");
        roleProbe.profession = "lumberjack";
        ok(!roleProbe.canPerform("builder") && roleProbe.productivity("lumberjack") > 1.0,
                "дровосек не подменяет профессию строителем и добывает быстрее");
        ok(d.buildings.get("archery_range") != null && d.buildings.get("stable") != null
                && d.buildings.get("siege_workshop") != null,
                "стрельбище, конюшня и осадная мастерская загружаются из датапака");
        ok(d.buildings.get("archery_range").requires.contains("barracks")
                && d.buildings.get("stable").requires.contains("barracks")
                && d.buildings.get("siege_workshop").requires.contains("smithy"),
                "специализированные военные здания имеют технологические зависимости");
        Settlement supply = fresh(d, 0);
        double supplyWood = supply.amount("wood"), supplyStone = supply.amount("stone");
        ok(!supply.spend(Map.of("wood", 10, "stone", 999))
                && supply.amount("wood") == supplyWood && supply.amount("stone") == supplyStone,
                "нехватка одного ресурса не списывает частично остальные");
        ok(supply.spend(Map.of("wood", 10, "stone", 5))
                && supply.amount("wood") == supplyWood - 10 && supply.amount("stone") == supplyStone - 5,
                "снабжение военного найма списывается одной атомарной операцией");

        Flow flow = new Flow();
        flow.record(1, "wood", 5, true);
        double validFlow = flow.perMinute(2, "wood", true);
        flow.record(Double.NaN, "wood", 100, true);
        flow.record(Double.POSITIVE_INFINITY, "wood", 100, true);
        flow.record(2, "wood", Double.NaN, true);
        flow.record(2, "wood", Double.POSITIVE_INFINITY, true);
        flow.record(2, "wood", -1, true);
        flow.record(2, "", 100, true);
        ok(flow.perMinute(2, "wood", true) == validFlow, "невалидные события не портят статистику производства");
        ok(flow.perMinute(Double.NaN, "wood", true) == 0 && flow.perMinute(Double.POSITIVE_INFINITY, "wood", true) == 0, "некорректное время запроса статистики безопасно");
        List<Object> corruptedFlow = new ArrayList<>();
        corruptedFlow.add(Map.of("t", 1L, "r", "wood", "p", Double.NaN, "c", 2.0));
        corruptedFlow.add(Map.of("t", 2L, "r", "stone", "p", 3.0, "c", -1.0));
        corruptedFlow.add("broken-entry");
        corruptedFlow.add(Map.of("t", 3L, "r", "food", "p", 4.0, "c", 1.0));
        Flow restoredFlow = new Flow();
        restoredFlow.load(corruptedFlow);
        ok(restoredFlow.resources().equals(Set.of("food")) && restoredFlow.perMinute(4, "food", true) > 0, "загрузка статистики пропускает повреждённые записи");

        Settlement s = fresh(d, 4);
        String unchanged = s.toJson();
        s.tick(Double.NaN);
        s.tick(Double.POSITIVE_INFINITY);
        s.tick(Double.NEGATIVE_INFINITY);
        s.tick(-1);
        s.tick(0);
        ok(s.toJson().equals(unchanged), "невалидный шаг времени не меняет поселение");
        boolean oversizedRejected = false;
        try { s.tick(Settlement.MAX_TICK + 1); } catch (IllegalArgumentException ex) { oversizedRejected = true; }
        ok(oversizedRejected && s.toJson().equals(unchanged), "чрезмерный шаг времени отклоняется до симуляции");
        Building h = s.place("house", 0, 0);
        s.tick(1);
        ok(h.state == Building.State.BUILDING && s.amount("wood") == 80, "стройка началась, материалы списаны");
        s.tick(60);
        ok(h.state == Building.State.DONE, "дом построен (работа 30, строителей до 3)");
        ok(s.housing() == 8, "жильё выросло до 8");

        Building lc = s.place("lumber_camp", 10, 0);
        s.tick(60);
        ok(lc.state == Building.State.DONE, "лагерь построен");
        ok(s.assigned(lc.uid).stream().filter(w -> w.workRole().equals("lumberjack")).count() == 3
                        && s.assigned(lc.uid).stream().filter(w -> w.workRole().equals("lumberjack"))
                        .allMatch(w -> w.profession.equals("laborer")),
                "общие рабочие добывают, не теряя постоянную профессию");
        ok(s.assigned(lc.uid).stream().anyMatch(w -> w.workRole().equals("hauler"))
                        || !lc.localStock.isEmpty(),
                "логистическая система назначает носильщика или удерживает груз в локальном буфере");
        double w0 = s.amount("wood");
        s.tick(120);
        ok(s.amount("wood") > w0 && s.taskGraph.count(ResidentTaskGraph.Stage.STORED) > 0,
                "дерево проходит добычу, доставку носильщиком и поступает на общий склад");

        Settlement generalistYield = fresh(d, 1);
        generalistYield.auto = false;
        Building generalistCamp = generalistYield.place("lumber_camp", 10, 0);
        generalistYield.tick(2);
        generalistYield.assign(generalistYield.workers.get(0).id, generalistCamp.uid);
        generalistYield.tick(30);
        generalistYield.assign(generalistYield.workers.get(0).id, generalistCamp.uid);
        generalistYield.tick(120);
        double generalistWood = generalistCamp.localStock.getOrDefault("wood", 0.0);
        Settlement specialistYield = fresh(d, 1);
        specialistYield.auto = false;
        Building specialistCamp = specialistYield.place("lumber_camp", 10, 0);
        specialistYield.tick(2);
        specialistYield.assign(specialistYield.workers.get(0).id, specialistCamp.uid);
        specialistYield.tick(30);
        Worker lumberjack = specialistYield.workers.get(0);
        specialistYield.setProfession(lumberjack.id, "lumberjack");
        specialistYield.assign(lumberjack.id, specialistCamp.uid);
        specialistYield.tick(120);
        double specialistWood = specialistCamp.localStock.getOrDefault("wood", 0.0);
        ok(specialistWood > generalistWood,
                "профильный дровосек добывает больше общего рабочего за одинаковое время");
        Settlement generalBuilder = fresh(d, 1);
        generalBuilder.auto = false;
        Building generalSite = generalBuilder.place("house", 0, 0);
        generalBuilder.tick(1);
        generalBuilder.assign(generalBuilder.workers.get(0).id, generalSite.uid);
        generalBuilder.tick(10);
        double generalBuildProgress = generalSite.progress;
        Settlement specialistBuilder = fresh(d, 1);
        specialistBuilder.auto = false;
        Building specialistSite = specialistBuilder.place("house", 0, 0);
        specialistBuilder.tick(1);
        specialistBuilder.setProfession(specialistBuilder.workers.get(0).id, "builder");
        specialistBuilder.assign(specialistBuilder.workers.get(0).id, specialistSite.uid);
        specialistBuilder.tick(10);
        ok(specialistSite.progress > generalBuildProgress,
                "профильный строитель реально строит быстрее общего рабочего");

        Settlement exactRole = fresh(d, 2);
        exactRole.auto = false;
        Building exactCamp = exactRole.place("lumber_camp", 12, 0);
        exactRole.tick(2);
        Worker exactHauler = exactRole.workers.get(0);
        exactRole.setProfession(exactHauler.id, "lumberjack");
        exactRole.assign(exactHauler.id, exactCamp.uid, "lumberjack");
        ok("lumberjack".equals(exactHauler.workRole()),
                "прямое поручение назначает именно выбранную роль, а не первую свободную вакансию");
        Worker wrongRole = exactRole.workers.get(1);
        exactRole.setProfession(wrongRole.id, "lumberjack");
        ok(throwsOrder(() -> exactRole.assign(wrongRole.id, exactCamp.uid, "builder")),
                "прямое поручение отклоняет роль, которой нет среди вакансий здания");

        Settlement bufferTest = fresh(d, 3);
        bufferTest.auto = false;
        Building bufferCamp = bufferTest.place("lumber_camp", 10, 0);
        bufferTest.tick(2);
        bufferTest.assign(bufferTest.workers.get(0).id, bufferCamp.uid);
        bufferTest.tick(30);
        for (Worker worker : bufferTest.workers) bufferTest.assign(worker.id, bufferCamp.uid);
        double storedBeforeHaul = bufferTest.amount("wood");
        bufferTest.tick(120);
        double localWood = bufferCamp.localStock.getOrDefault("wood", 0.0);
        ok(localWood > 0 && bufferTest.amount("wood") == storedBeforeHaul,
                "добытое дерево остаётся в буфере лесопилки, пока нет носильщика");
        Settlement bufferRestored = Settlement.fromJson(bufferTest.toJson(), d);
        ok(bufferRestored.building(bufferCamp.uid).localStock.getOrDefault("wood", 0.0) == localWood
                        && bufferRestored.taskGraph.count(ResidentTaskGraph.Stage.EXTRACTED) > 0,
                "локальный склад и граф добычи сохраняются после перезапуска");
        Worker newHauler = bufferTest.addWorker("Носильщик");
        bufferTest.assignHauler(newHauler.id, bufferCamp.uid);
        bufferTest.tick(5);
        ok(bufferTest.amount("wood") > storedBeforeHaul
                        && bufferTest.building(bufferCamp.uid).localStock.getOrDefault("wood", 0.0) < localWood
                        && bufferTest.taskGraph.count(ResidentTaskGraph.Stage.STORED) > 0,
                "назначенный общий рабочий доставляет древесину из локального буфера в общий склад");
        Settlement manualHaulerBack = Settlement.fromJson(bufferTest.toJson(), d);
        ok(manualHaulerBack.worker(newHauler.id).manualHauler
                        && manualHaulerBack.worker(newHauler.id).workRole().equals("hauler"),
                "ручное назначение носильщика сохраняется после перезапуска поселения");

        // зависимости и лимиты
        ok(throwsOrder(() -> s.place("granary", 30, 30)), "амбар требует лесопилку");
        ok(throwsOrder(() -> s.place("house", 1, 1)), "нельзя строить поверх другого здания");
        ok(throwsOrder(() -> s.place("nope", 50, 50)), "неизвестное здание отклоняется");
        Building mill = s.place("sawmill", 20, 20);
        s.tick(60);
        ok(mill.state == Building.State.DONE, "лесопилка действительно построена перед проверкой зависимости");
        ok(throwsOrder(() -> s.cancel(lc.uid)), "нельзя снести лесной лагерь, пока от него зависит построенная лесопилка");

        // отмена с возвратом
        Settlement c = fresh(d, 0);
        Building p = c.place("house", 0, 0);
        c.cancel(p.uid);
        ok(c.amount("wood") == 100 && c.buildings.isEmpty(), "отмена не начатой стройки: полный возврат");
        Building q = c.place("house", 0, 0);
        c.tick(1);
        c.cancel(q.uid);
        ok(c.amount("wood") == 90, "отмена начатой: возврат 50% (получено " + c.amount("wood") + ")");

        // ручное управление
        Settlement m = fresh(d, 2);
        m.auto = false;
        Building f = m.place("farm", 0, 0);
        m.tick(5);
        Worker w1 = m.workers.get(0);
        m.assign(w1.id, f.uid);
        ok(w1.profession.equals("laborer") && w1.workRole().equals("builder"),
                "универсальный рабочий может строить без смены постоянной профессии");
        m.tick(40);
        ok(f.state == Building.State.DONE, "один универсальный строитель достроил ферму");
        Worker w2 = m.workers.get(1);
        m.assign(w2.id, f.uid);
        ok(w2.profession.equals("laborer") && w2.workRole().equals("farmer"),
                "универсальный рабочий может выполнять фермерскую работу без смены профессии");
        double f0 = m.amount("food");
        m.tick(140);
        ok(f.localStock.getOrDefault("food", 0.0) > 0,
                "ферма работает с одним универсальным фермером, а урожай ждёт носильщика в локальном буфере");

        // рост и голод
        Settlement g = fresh(d, 2);
        g.place("house", 0, 0);
        g.tick(300);
        ok(g.workers.size() > 2, "при еде и жилье приходят новые жители: " + g.workers.size());
        Settlement hungry = fresh(d, 3);
        hungry.stock.put("food", 0.0);
        hungry.tick(200);
        ok(hungry.workers.size() < 3, "без еды жители умирают: " + hungry.workers.size());

        // приоритет
        Settlement pr = fresh(d, 1);
        Building a1 = pr.place("quarry", 0, 0), a2 = pr.place("farm", 20, 0);
        pr.setPriority(a2.uid, 9);
        pr.tick(1);
        ok(pr.assigned(a2.uid).size() == 1 && pr.assigned(a1.uid).isEmpty(), "единственный строитель идёт на высокий приоритет");

        // склад
        Settlement cap = fresh(d, 0);
        ok(cap.add("wood", 1000) == 0 || cap.amount("wood") <= 100, "склад ограничен");

        Settlement manual = fresh(d, 1);
        Worker commanded = manual.workers.get(0);
        manual.moveTo(commanded.id, 12.5, -7.25);
        ok(commanded.manualOrder && commanded.building == 0 && manual.targetOf(commanded)[0] == 12.5,
                "приказ RTS задаёт индивидуальную точку и снимает назначение здания");
        manual.auto = true; manual.tick(1);
        ok(commanded.manualOrder && commanded.building == 0, "автораспределение не перехватывает рабочего с ручным приказом");
        Settlement manualBack = Settlement.fromJson(manual.toJson(), d);
        Worker restoredCommand = manualBack.worker(commanded.id);
        ok(restoredCommand.manualOrder && restoredCommand.orderX == 12.5 && restoredCommand.orderZ == -7.25,
                "ручной приказ рабочего сохраняется и восстанавливается");
        Settlement following = fresh(d, 1);
        Worker follower = following.workers.get(0);
        follower.manualFollow = true;
        follower.manualFollowOwner = "player-uuid";
        follower.building = 0;
        following.auto = true;
        following.tick(1);
        ok(follower.manualFollow && follower.building == 0,
                "automatic staffing does not steal a resident following the player");
        Settlement followingBack = Settlement.fromJson(following.toJson(), d);
        Worker restoredFollower = followingBack.worker(follower.id);
        ok(restoredFollower.manualFollow && restoredFollower.manualFollowOwner.equals("player-uuid"),
                "режим следования за игроком и его UUID сохраняются после загрузки");
        follower.lifeAction = "sleeping";
        follower.lifeTargetX = 4.5;
        follower.lifeTargetZ = -3.25;
        follower.lifeActionUntil = 9876;
        Settlement dailyLifeBack = Settlement.fromJson(following.toJson(), d);
        Worker restoredDailyLife = dailyLifeBack.worker(follower.id);
        ok(restoredDailyLife.lifeAction.equals("sleeping") && restoredDailyLife.lifeTargetX == 4.5
                        && restoredDailyLife.lifeTargetZ == -3.25 && restoredDailyLife.lifeActionUntil == 9876,
                "сон жителя, цель дома и время пробуждения переживают сохранение");
        follower.lifeAction = "defending";
        follower.lifeTargetX = 0;
        follower.lifeTargetZ = 0;
        follower.lifeActionUntil = 1.0e12;
        Settlement defenceBack = Settlement.fromJson(following.toJson(), d);
        ok(defenceBack.worker(follower.id).lifeAction.equals("defending")
                        && defenceBack.worker(follower.id).lifeActionUntil == 1.0e12,
                "приказ жителя защищать поселение сохраняется и восстанавливается");
        Settlement queued= fresh(d,1);
        Worker queuedWorker=queued.workers.get(0);
        queued.moveTo(queuedWorker.id,10,20);
        queued.moveTo(queuedWorker.id,30,40,true);
        queued.moveTo(queuedWorker.id,-5,8,true);
        ok(queuedWorker.manualOrder&&queuedWorker.orderQueue.size()==2
                &&queued.targetOf(queuedWorker)[0]==10,
                "Shift+ПКМ добавляет две точки в очередь рабочего, не заменяя текущую");
        Settlement queuedBack=Settlement.fromJson(queued.toJson(),d);
        Worker queuedRestored=queuedBack.worker(queuedWorker.id);
        ok(queuedRestored.orderQueue.size()==2,"очередь маршрута рабочего сохраняется в мире");
        queuedBack.advanceManualOrder(queuedRestored.id);
        ok(queuedRestored.orderX==30&&queuedRestored.orderZ==40&&queuedRestored.manualOrder,
                "после достижения первой точки активируется следующая");
        queuedBack.advanceManualOrder(queuedRestored.id);
        ok(queuedRestored.orderX==-5&&queuedRestored.orderZ==8&&queuedRestored.orderQueue.isEmpty(),
                "очередь последовательно активирует конечную точку");
        queuedBack.advanceManualOrder(queuedRestored.id);
        ok(!queuedRestored.manualOrder&&queuedRestored.status.equals("свободен"),
                "после последней точки ручной приказ завершается");
        queuedBack.moveTo(queuedRestored.id,1,1);
        queuedBack.moveTo(queuedRestored.id,2,2,true);
        queuedBack.holdAt(queuedRestored.id,4,4);
        ok(queuedRestored.orderQueue.isEmpty()&&queuedRestored.orderX==4,
                "приказ удержания отменяет оставшуюся очередь");
        boolean queueLimit=false;
        try{
            queuedBack.moveTo(queuedRestored.id,0,0);
            for(int i=0;i<Worker.MAX_MANUAL_ORDERS+1;i++)queuedBack.moveTo(queuedRestored.id,i+1,i+1,true);
        }catch(Settlement.OrderException expectedQueue){queueLimit=true;}
        ok(queueLimit&&queuedRestored.orderQueue.size()==Worker.MAX_MANUAL_ORDERS,
                "очередь рабочего ограничена и не переполняется");

        manual.holdAt(commanded.id, 3.0, 4.0);
        ok(commanded.manualOrder && commanded.orderX == 3.0 && commanded.orderZ == 4.0
                && commanded.status.contains("удерживать"), "приказ удержания фиксирует позицию рабочего");
        manual.moveTo(commanded.id, 900.0, -1250.0);
        ok(commanded.manualOrder && commanded.orderX == 900.0 && commanded.orderZ == -1250.0,
                "приказ RTS допускает перемещение в удалённую точку карты");
        boolean badMoveRejected = false;
        try { manual.moveTo(commanded.id, Double.NaN, 0); } catch (Settlement.OrderException expectedMove) { badMoveRejected = true; }
        ok(badMoveRejected && commanded.manualOrder, "некорректные координаты не сбрасывают действующий приказ");

        // детерминизм и сохранение
        Settlement x = fresh(d, 5), y = fresh(d, 5);
        for (Settlement t : new Settlement[]{x, y}) { t.place("house", 0, 0); t.place("lumber_camp", 10, 0); t.place("farm", 20, 0); t.tick(500); }
        ok(x.toJson().replace(x.id, "<settlement-id>").equals(y.toJson().replace(y.id, "<settlement-id>")), "две одинаковые симуляции совпадают независимо от идентификатора поселения");
        if (!x.workers.isEmpty()) x.workers.get(0).person = "c-person-roundtrip";
        String beforeReload = x.toJson();
        Settlement r = Settlement.fromJson(beforeReload, d);
        String afterReload = r.toJson();
        boolean roundTrip = afterReload.equals(beforeReload);
        if (!roundTrip) System.out.println("ROUNDTRIP DIFF " + firstDiff(Json.parse(beforeReload), Json.parse(afterReload), "$"));
        ok(roundTrip, "сохранение и загрузка без потерь, включая ссылку на личность");
        ok(!r.workers.isEmpty() && r.workers.get(0).person.equals("c-person-roundtrip"),
                "ссылка рабочего на CharacterRegistry сохраняется как стабильный foreign key");
        x.tick(100); r.tick(100);
        ok(x.toJson().equals(r.toJson()), "после загрузки симуляция идёт так же");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
