package dev.worldforge.tests;

import dev.worldforge.mod.api.StatApi;
import dev.worldforge.origins.*;
import dev.worldforge.progress.*;

import java.nio.file.*;
import java.util.*;

public class OriginsTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        OriginDefs d = new OriginDefs();
        try (var s = Files.list(Paths.get("wf-origins/src/main/resources/data/wf_origins/wf_origins"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        List<String> bad = d.validate();
        ok(bad.isEmpty(), "данные корректны " + bad);
        ok(d.races.size() >= 13 && d.classes.size() >= 20 && d.professions.size() >= 14, "объём: рас " + d.races.size() + ", классов " + d.classes.size() + ", профессий " + d.professions.size());

        StatApi.resetForTests();
        ProgressDefs pd = ProgressTest.defs();
        Progress pr = new Progress(pd);
        pr.publish();
        Origins o = new Origins(d);
        o.hooks = new Origins.Hooks() {
            @Override public void archetype(String owner, String id) { pr.chooseArchetype(owner, id); }
            @Override public boolean flag(String owner, String f) { return f.equals("quest:trial_of_arms") && owner.equals("p:1"); }
        };
        o.publish();
        dev.worldforge.mod.api.WorldForgeApi.revisions().resetForTests();
        String P = "p:1";
        double hp0 = pr.get(P, "max_health");
        long originRevisionBeforeRace = dev.worldforge.mod.api.WorldForgeApi.revisions().snapshot().orElseThrow()
                .revision(dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS);
        ok(o.setRace(P, "orc") && pr.get(P, "max_health") > hp0 + 5, "раса меняет здоровье через общую шину: " + hp0 + " → " + pr.get(P, "max_health"));
        long originRevisionAfterRace = dev.worldforge.mod.api.WorldForgeApi.revisions().snapshot().orElseThrow()
                .revision(dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS);
        ok(originRevisionAfterRace == originRevisionBeforeRace + 1, "выбор расы продвигает ORIGINS revision ровно один раз");
        ok(!o.setRace(P, "elf"), "раса выбирается один раз");
        ok(dev.worldforge.mod.api.WorldForgeApi.revisions().snapshot().orElseThrow()
                .revision(dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS) == originRevisionAfterRace,
                "отклонённый выбор расы не меняет revision");
        Origins.Build detached = o.build(P);
        detached.race = "elf";
        detached.history.add("untracked");
        ok("orc".equals(o.peek(P).race) && o.peek(P).history.isEmpty(),
                "публичные Build getters возвращают detached snapshot, обход writer API невозможен");
        Origins.Build missing = o.build("p:missing");
        missing.race = "human";
        ok(o.peek("p:missing") == null, "чтение отсутствующего владельца не материализует запись");
        ok(StatApi.tag(P, "race").equals("orc"), "метка расы в шине");

        // классы
        var opts = o.options(P);
        ok(opts.size() == 6 && opts.values().stream().allMatch(Objects::isNull), "шесть базовых классов доступны");
        ok(o.chooseClass(P, "warrior") && pr.profile(P).archetype.equals("warrior"), "класс выдал архетип прокачки");
        ok(!o.chooseClass(P, "knight") && o.lastError.contains("уровень"), "рыцарь закрыт: " + o.lastError);
        pr.award(P, 1e9);                                       // до 60 уровня
        ok(o.options(P).keySet().stream().allMatch(c -> c.from().equals("warrior")), "после выбора видны только улучшения воина");
        pr.addAttr(P, "might", pr.profile(P).attrFree);
        ok(o.chooseClass(P, "knight") && StatApi.tag(P, "class").equals("knight") && StatApi.tag(P, "class_tier").equals("2"), "рыцарь выбран: " + o.lastError);
        ok(pr.get(P, "armor") > 3, "бонусы класса в броне: " + pr.get(P, "armor"));
        pr.profile(P).attrFree += 40; pr.addAttr(P, "might", 6); pr.addAttr(P, "martial", 10);
        ok(o.chooseClass(P, "champion") && o.build(P).history.equals(List.of("warrior", "knight", "champion")), "чемпион по флагу квеста: " + o.lastError);
        ok(!o.chooseClass(P, "berserker"), "ветка воина уже закрыта");
        // расовый запрет
        ProgressDefs.class.getName();
        String Q = "p:2";
        o.setRace(Q, "human");
        ok(!d.classes.get("champion").reqRaces().contains("human") && d.classes.get("champion").reqRaces().isEmpty(), "у чемпиона нет расового запрета");

        // профессии
        ok(o.learn(P, "smith") && o.learn(P, "miner") && !o.learn(P, "farmer") && o.lastError.contains("не более"), "две профессии максимум");
        double q0 = pr.get(P, "craft_quality_pct");
        ok(o.work(P, "smith", 200) == 1 && pr.get(P, "craft_quality_pct") > q0, "работа поднимает ранг: " + o.rank(P, "smith"));
        ok(o.work(P, "smith", 10) == -1, "ранг не вырос - -1");
        o.drop(P, "miner");
        ok(o.learn(P, "farmer"), "после ухода освободился слот");
        ok(!o.learn("p:3", "merchant") && o.lastError.contains("diplomacy"), "торговцу нужна дипломатия: " + o.lastError);

        // NPC
        Origins.Build n1 = o.assign("n:a", 7, "smith", 30), n2 = o.assign("n:b", 7, "smith", 30), n3 = o.assign("n:c", 8, "craft", 3);
        ok(n1.race.equals(n2.race) && n1.history.equals(n2.history), "NPC детерминированы по сиду");
        ok(n1.prof.containsKey("smith") && !n1.history.isEmpty(), "NPC 30 уровня: профессия и класс " + n1.history);
        Origins.Build n1Again = o.assign("n:a", 999, "trade", 55);
        ok(n1Again != n1 && n1Again.history.equals(n1.history) && n1Again.prof.equals(n1.prof),
                "повторное назначение NPC сохраняет развитие и возвращает detached snapshot");
        n1Again.history.clear();
        n1Again.prof.clear();
        ok(o.build("n:a").history.equals(n1.history) && o.build("n:a").prof.equals(n1.prof),
                "изменение результата assign не обходит канонический Origins writer");
        ok(n3.history.isEmpty(), "новичок без класса");
        // сохранение
        Origins o2 = new Origins(d);
        o2.fromJson(o.toJson());
        ok(o2.build(P).history.equals(o.build(P).history) && o2.build(P).prof.equals(o.build(P).prof) && o2.mods(P).equals(o.mods(P)), "save/load сохраняет историю, профессии и рассчитанные бонусы");
        String validOriginsSave = o.toJson();
        String corruptOriginsSave = validOriginsSave.replace("\"race\":\"orc\"", "\"race\":\"unknown_race\"");
        boolean invalidOriginsRejected = false;
        try { o2.fromJson(corruptOriginsSave); } catch (IllegalArgumentException invalidSave) { invalidOriginsRejected = true; }
        ok(invalidOriginsRejected && o2.toJson().equals(validOriginsSave),
                "неизвестная раса отклоняется без очистки действующего прогресса");

        ok(o.viewJson(P).contains("champion") || o.viewJson(P).contains("professions"), "viewJson");

        // подрасы и специализации профессий
        Origins o3 = new Origins(d); o3.hooks = new Origins.Hooks() {};
        String E = "p:sub";
        ok(d.isRace("wood_elf", "elf") && !d.isRace("elf", "wood_elf") && d.raceChain("wood_elf").size() == 2, "подраса считается своей расой-родителем");
        o3.setRace(E, "wood_elf");
        double hp = o3.mods(E).getOrDefault("max_health", 0.0);
        ok(hp == -3.0 && o3.mods(E).getOrDefault("stealth_pct", 0.0) == 14.0, "подраса наследует свойства родителя и добавляет свои: " + o3.mods(E));
        ok(o3.canLearn(E, "weaponsmith") != null && o3.canLearn(E, "weaponsmith").contains("Сначала"), "специализация закрыта без основной профессии");
        String F = "p:sm"; o3.setRace(F, "mountain_dwarf");
        StatApi.resetForTests(); StatApi.registerSheet("t", ow -> Map.of("level", 20.0, "might", 10.0));
        ok(o3.learn(F, "smith") && o3.learn(F, "miner"), "две основные профессии");
        ok(o3.canLearn(F, "weaponsmith") != null && o3.canLearn(F, "weaponsmith").contains("звание"), "нужно звание в основной: " + o3.canLearn(F, "weaponsmith"));
        o3.work(F, "smith", 700);
        ok(o3.learn(F, "weaponsmith") && o3.build(F).prof.size() == 3, "специализация не занимает слот основных профессий");
        ok(o3.canLearn(F, "armorer") == null && o3.learn(F, "armorer"), "можно несколько специализаций");
        o3.drop(F, "smith");
        ok(!o3.build(F).prof.containsKey("weaponsmith") && !o3.build(F).prof.containsKey("armorer") && o3.build(F).prof.containsKey("miner"), "бросив основную, теряешь её специализации");
        Origins o4 = new Origins(d); o4.hooks = new Origins.Hooks() {};
        o4.setRace("p:r", "high_elf");
        boolean elfOk = d.classes.values().stream().anyMatch(c -> c.reqRaces().contains("elf"));
        ok(o4.options("p:r").entrySet().stream().allMatch(e -> d.raceOk("high_elf", e.getKey().reqRaces()) || e.getValue() != null), "несовместимые классы для подрасы явно заблокированы");

        // глубина и широта: расширенные данные
        ok(d.races.size() >= 33 && d.classes.size() >= 60 && d.professions.size() >= 50, "широта: рас " + d.races.size() + ", классов " + d.classes.size() + ", профессий " + d.professions.size());
        ok(d.classes.values().stream().anyMatch(c -> c.tier() == 4) && d.classes.values().stream().filter(c -> !c.alsoFrom().isEmpty()).count() >= 8, "глубина: 4-я ступень и сходящиеся ветви");
        StatApi.resetForTests();
        StatApi.registerSheet("t2", ow -> { Map<String, Double> m = new LinkedHashMap<>(); m.put("level", 99.0); for (String k : List.of("might", "agility", "vitality", "martial", "intellect", "learning", "spirit", "diplomacy", "stewardship", "intrigue")) m.put(k, 40.0); return m; });
        Origins o5 = new Origins(d);
        o5.hooks = new Origins.Hooks() { @Override public boolean flag(String ow, String f) { return true; } };
        String H = "p:h";
        o5.setRace(H, "saurian");
        ok(o5.chooseClass(H, "scholar") && o5.options(H).keySet().stream().anyMatch(c -> c.id().equals("apothecary")), "у учёного есть аптекарь");
        ok(o5.options(H).get(d.classes.get("apothecary")) != null && o5.options(H).get(d.classes.get("apothecary")).contains("профессия"), "аптекарь требует профессию алхимика: " + o5.options(H).get(d.classes.get("apothecary")));
        ok(o5.learn(H, "alchemist") && o5.work(H, "alchemist", 200) >= 0 && o5.chooseClass(H, "apothecary"), "профессия открыла класс: " + o5.lastError);
        ok(o5.chooseClass(H, "grand_healer") && o5.build(H).history.equals(List.of("scholar", "apothecary", "grand_healer")), "ступень 3: великий целитель");
        ok(o5.learn(H, "healer") && o5.learn(H, "battle_medic") == false, "полевой лекарь закрыт без звания: " + o5.lastError);
        // сходящиеся ветви: полевой хирург - и из рыцаря, и из аптекаря
        ok(d.isParent(d.classes.get("battle_surgeon"), "knight") && d.isParent(d.classes.get("battle_surgeon"), "physician") && d.isParent(d.classes.get("battle_surgeon"), "bulwark"), "хирург растёт из трёх ветвей");
        // переобучение
        ok(o5.retrain(H, 100).equals("grand_healer") && o5.build(H).cls.equals("apothecary"), "переобучение снимает последний класс");
        ok(o5.retrain(H, 110) == null && o5.lastError.contains("раз в"), "переобучение раз в 30 дней: " + o5.lastError);
        ok("apothecary".equals(o5.retrain(H, 131)), "после срока можно снова");
        ok("scholar".equals(o5.retrain(H, 170)) && o5.build(H).cls.isEmpty(), "можно вернуться к чистому листу");
        o5.fromJson(o5.toJson());
        ok(o5.build(H).retrainDay == 170, "день переобучения сохраняется");
        // взаимоисключающие пути (на своих данных)
        OriginDefs x = new OriginDefs();
        x.load("{\"classes\":[{\"id\":\"a\",\"name\":\"A\",\"tier\":1},{\"id\":\"b\",\"name\":\"B\",\"tier\":2,\"from\":\"a\",\"exclusive\":\"x\"},{\"id\":\"c\",\"name\":\"C\",\"tier\":3,\"from\":\"b\",\"exclusive\":\"x\"},{\"id\":\"e\",\"name\":\"E\",\"tier\":3,\"from\":\"b\"}],\"races\":[{\"id\":\"r\"}]}");
        Origins ox = new Origins(x);
        ox.setRace("q", "r"); ox.chooseClass("q", "a"); ox.chooseClass("q", "b");
        ok(!ox.chooseClass("q", "c") && ox.lastError.contains("Несовместимо") && ox.chooseClass("q", "e"), "исключающие группы: " + ox.lastError);
        OriginDefs badDefs = new OriginDefs();
        badDefs.load("{\"classes\":[{\"id\":\"a\",\"tier\":1},{\"id\":\"b\",\"tier\":2,\"from\":\"a\",\"also_from\":[\"zz\"],\"req_prof\":{\"nope\":1}}],\"professions\":[{\"id\":\"p\",\"ranks\":[{\"xp\":0}],\"req_classes\":[\"qq\"]}]}");
        ok(badDefs.validate().size() == 3, "проверка данных ловит неизвестные связи: " + badDefs.validate());
        OriginDefs duplicateDefs = new OriginDefs();
        duplicateDefs.load("{\"races\":[{\"id\":\"human\",\"name\":\"Human\"}]}");
        boolean duplicateRaceRejected = false;
        try { duplicateDefs.load("{\"races\":[{\"id\":\"human\",\"name\":\"Overwritten\"}]}"); }
        catch (IllegalArgumentException duplicate) { duplicateRaceRejected = true; }
        ok(duplicateRaceRejected && duplicateDefs.races.size() == 1
                && duplicateDefs.races.get("human").name().equals("Human"),
                "повторное определение расы отклоняется без частичной перезаписи датапака");
        OriginDefs replacementSource = new OriginDefs();
        replacementSource.load("{\"races\":[{\"id\":\"elf\",\"name\":\"Elf\"}]}");
        duplicateDefs.replaceWith(replacementSource);
        ok(duplicateDefs.races.size() == 1 && duplicateDefs.races.containsKey("elf")
                && !duplicateDefs.races.containsKey("human"),
                "перезагрузка определений заменяет удалённые записи, не накапливая устаревшие расы");
        OriginDefs invalidReplacement = new OriginDefs();
        invalidReplacement.load("{\"races\":[{\"id\":\"child\",\"parent\":\"missing\"}]}");
        boolean invalidReplacementRejected = false;
        try { duplicateDefs.replaceWith(invalidReplacement); } catch (IllegalArgumentException expected) { invalidReplacementRejected = true; }
        ok(invalidReplacementRejected && duplicateDefs.races.containsKey("elf")
                && !duplicateDefs.races.containsKey("child"),
                "некорректный набор не заменяет последнюю корректную версию");
        // специализации: взаимоисключающие, по классам
        Origins o6 = new Origins(d); o6.hooks = new Origins.Hooks() {};
        String M = "p:m";
        o6.setRace(M, "human"); o6.learn(M, "merchant"); o6.work(M, "merchant", 700);
        ok(o6.learn(M, "banker") && o6.canLearn(M, "smuggler_trader") != null && o6.canLearn(M, "smuggler_trader").contains("Несовместимо"), "банкир и скупщик исключают друг друга");
        ok(o6.canLearn(M, "caravaneer") == null, "а караванщик - без ограничений");
        o6.learn(M, "guard"); o6.work(M, "guard", 700);
        ok(o6.canLearn(M, "captain_guard").contains("класс"), "начальник стражи требует воинский класс: " + o6.canLearn(M, "captain_guard"));
        o6.chooseClass(M, "warrior");
        ok(o6.canLearn(M, "captain_guard") == null, "с классом воина - можно");
        // подрасы наследуют: гоблин
        o6.setRace("p:g", "city_goblin");
        ok(o6.mods("p:g").getOrDefault("trade_profit_pct", 0.0) >= 4.0 && o6.mods("p:g").getOrDefault("max_health", 0.0) == -3.0 && d.raceChain("city_goblin").size() == 2, "городской гоблин: наследует гоблина, прибавляет своё: " + o6.mods("p:g"));

        // расовые особенности и переплетения
        ok(d.races.size() >= 46 && d.synergies.size() >= 30, "расы: " + d.races.size() + ", переплетений: " + d.synergies.size());
        ok(d.races.keySet().stream().allMatch(r -> !d.perksOf(r).isEmpty()), "у каждой расы есть особенности");
        StatApi.resetForTests();
        final double[] lvl = {1};
        StatApi.registerSheet("t3", ow -> { Map<String, Double> m = new LinkedHashMap<>(); m.put("level", lvl[0]); for (String k : List.of("might", "agility", "vitality", "martial", "intellect", "learning", "spirit", "diplomacy", "stewardship", "intrigue")) m.put(k, 40.0); return m; });
        Origins o7 = new Origins(d); o7.hooks = new Origins.Hooks() {};
        o7.setRace("p:d", "dwarf");
        double m1 = o7.mods("p:d").getOrDefault("mining_speed_pct", 0.0);
        lvl[0] = 20;
        double m2 = o7.mods("p:d").getOrDefault("mining_speed_pct", 0.0);
        ok(m2 > m1 && d.perkName("dwarf", 15).equals("Чутьё на руду"), "особенность гнома открылась на 15 уровне: " + m1 + " → " + m2);
        o7.learn("p:d", "miner"); o7.work("p:d", "miner", 200);
        ok(o7.activeSynergies("p:d").stream().anyMatch(y -> y.id().equals("dwarf_miner")) && o7.mods("p:d").getOrDefault("mining_speed_pct", 0.0) >= m2 + 8, "переплетение «гном + горняк» включилось");
        o7.drop("p:d", "miner");
        ok(o7.activeSynergies("p:d").isEmpty() && o7.viewJson("p:d").contains("synergies"), "бросил ремесло - переплетение выключилось");
        // формы как флаги: рыцарь крови требует вампира, охотник на нечисть запрещает
        final Set<String> flags = new HashSet<>();
        Origins o8 = new Origins(d); o8.hooks = new Origins.Hooks() { @Override public boolean flag(String ow, String f) { return flags.contains(f); } };
        o8.setRace("p:v", "human"); o8.chooseClass("p:v", "warrior"); lvl[0] = 40; o8.chooseClass("p:v", "knight");
        ok(!o8.chooseClass("p:v", "blood_knight") && o8.lastError.contains("form:vampire"), "рыцарь крови закрыт без вампиризма: " + o8.lastError);
        flags.add("form:vampire");
        ok(o8.chooseClass("p:v", "blood_knight"), "с вампиризмом открыт: " + o8.lastError);
        flags.clear(); o8.setRace("p:h", "human"); o8.chooseClass("p:h", "ranger"); o8.chooseClass("p:h", "night_hunter"); flags.add("form:vampire");
        ok(!o8.chooseClass("p:h", "witch_hunter") && o8.lastError.contains("Несовместимо"), "охотник на ведьм не может быть вампиром: " + o8.lastError);

        // Concurrent commands must not both win the one-time race selection.
        dev.worldforge.mod.api.WorldForgeApi.revisions().resetForTests();
        Origins concurrent = new Origins(d);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();
        Thread raceA = new Thread(() -> { try { start.await(); if (concurrent.setRace("p:race-race", "human")) winners.incrementAndGet(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        Thread raceB = new Thread(() -> { try { start.await(); if (concurrent.setRace("p:race-race", "orc")) winners.incrementAndGet(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
        raceA.start(); raceB.start(); start.countDown(); raceA.join(); raceB.join();
        long raceRevision = dev.worldforge.mod.api.WorldForgeApi.revisions().snapshot().orElseThrow()
                .revision(dev.worldforge.mod.api.DomainMutationRevisions.Domain.ORIGINS);
        ok(winners.get() == 1 && raceRevision == 1
                        && Set.of("human", "orc").contains(concurrent.build("p:race-race").race),
                "параллельный выбор расы сериализован: один победитель и одна revision");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
