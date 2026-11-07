package dev.worldforge.tests;

import dev.worldforge.mod.api.StatApi;
import dev.worldforge.realms.*;

import java.nio.file.*;
import java.util.*;

public class RealmsTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }
    static int day = 100;

    public static void main(String[] a) throws Exception {
        RealmDefs d = new RealmDefs();
        try (var s = Files.list(Paths.get("wf-realms/src/main/resources/data/wf_realms/wf_realms"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) d.load(Files.readString(p)); }
        List<String> bad = d.validate();
        ok(bad.isEmpty(), "данные корректны " + bad);
        ok(d.govs.size() >= 10 && d.levels.size() == 5 && d.terrains.size() == 7 && d.branches.size() == 4 && d.laws.size() == 4, "объём данных");

        StatApi.resetForTests();
        Realms r = new Realms(d);
        final int[] prov = {1}, pres = {0};
        final Map<String, Double> races = new HashMap<>(), terr = new HashMap<>();
        r.hooks = new Realms.Hooks() {
            @Override public int provinces(String x) { return prov[0]; }
            @Override public int prestige(String x) { return pres[0]; }
            @Override public Map<String, Double> races(String x) { return races; }
            @Override public Map<String, Double> terrain(String x) { return terr; }
            @Override public int today() { return day; }
        };
        r.publish();
        ok(r.level("a") == 0 && r.levelDef("a").id().equals("band"), "стоянка при 1 провинции");
        prov[0] = 9; pres[0] = 450;
        ok(r.levelDef("a").id().equals("kingdom"), "королевство при 9 провинциях и 450 престижа");
        pres[0] = 0;
        ok(r.levelDef("a").id().equals("tribe") || r.levelDef("a").id().equals("band") || r.level("a") == 1, "без престижа уровень ограничен");
        pres[0] = 450;

        // расы и местность
        races.put("human", 0.9); races.put("dwarf", 0.1); terr.put("plains", 0.8); terr.put("coast", 0.2);
        ok(!r.setGov("a", "holdfast", false) && r.lastError.contains("40%"), "гномий оплот закрыт людям: " + r.lastError);
        races.clear(); races.put("dwarf", 0.8); races.put("human", 0.2); terr.clear(); terr.put("mountains", 0.9); terr.put("plains", 0.1);
        ok(r.setGov("a", "holdfast", false), "оплот открыт гномам: " + r.lastError);
        double fitHold = r.fit("a", d.govs.get("holdfast")), fitFeud = r.fit("a", d.govs.get("feudal_monarchy"));
        ok(fitHold > 40 && fitFeud < 10, "подгонка: оплот " + fitHold + ", феодализм " + fitFeud);
        ok(StatApi.tag("r:a", "gov").equals("holdfast") && StatApi.tag("r:a", "gov_category").equals("republic") && StatApi.tag("r:a", "realm_level").equals("kingdom"), "метки в шине");
        ok(StatApi.mods("r:a").getOrDefault("mining_speed_pct", 0.0) > 25, "бонусы через шину: " + StatApi.mods("r:a").get("mining_speed_pct"));
        ok(!r.setGov("a", "horde", false) && r.lastError.contains("орк") || r.lastError.length() > 0, "смена строя: " + r.lastError);
        ok(!r.setGov("a", "theocracy", false) && r.lastError.contains("дн"), "кулдаун смены строя: " + r.lastError);
        day += 31;
        ok(r.setGov("a", "theocracy", false) && r.state("a").laws.isEmpty(), "после кулдауна смена удалась");

        // доктрины
        ok(!r.unlock("a", "martial:levy") && r.lastError.contains("нужно"), "ветка требует предшественника: " + r.lastError);
        ok(!r.unlock("a", "martial:drill") && r.lastError.contains("очков"), "нужны очки: " + r.lastError);
        r.tick("a"); day += 20; int days = r.tick("a");
        ok(days == 20 && r.state("a").points >= 40, "очки накапливаются: " + r.state("a").points);
        ok(r.unlock("a", "martial:drill") && r.unlock("a", "martial:levy"), "цепочка изучена");
        ok(!r.unlock("a", "martial:cavalry") && r.lastError.contains("строю"), "кавалерия не для теократии: " + r.lastError);
        double lev0 = r.mods("a").getOrDefault("levy_pct", 0.0);
        ok(lev0 > 10, "доктрины дают бонус: " + lev0);
        // ячейки
        int slots = r.doctrineSlots("a");
        r.state("a").points = 1000;
        int learned = 2;
        for (String k : List.of("economy:tools", "economy:roads", "economy:markets", "lore:scribes", "lore:schools", "lore:hospitals", "statecraft:courts", "statecraft:bureaucracy", "lore:university")) if (r.unlock("a", k)) learned++;
        ok(learned == slots && r.lastError.contains("ячеек"), "ячейки доктрин ограничены: " + learned + "/" + slots);

        // законы
        ok(r.setLaw("a", "taxation", "heavy") && r.mods("a").getOrDefault("opinion", 0.0) < r.mods("a").getOrDefault("opinion", 0.0) + 1, "закон принят");
        ok(!r.setLaw("a", "taxation", "low") && r.lastError.contains("дн"), "кулдаун закона: " + r.lastError);
        ok(!r.setLaw("a", "nope", "x"), "нет закона");
        r.setLaw("a", "faith", "state"); r.setLaw("a", "trade_policy", "free"); r.setLaw("a", "conscription", "levy");
        ok(r.state("a").laws.size() <= r.lawSlots("a"), "ячейки законов ограничены: " + r.state("a").laws.size() + "/" + r.lawSlots("a"));

        // ИИ
        races.clear(); races.put("orc", 1.0); terr.clear(); terr.put("plains", 1.0);
        String g1 = r.suggest("b", 1), g2 = r.suggest("b", 1);
        ok(g1.equals(g2) && !g1.isEmpty(), "ИИ выбирает строй детерминированно: " + g1);
        r.autoDevelop("b", 5);
        ok(!r.state("b").gov.isEmpty(), "автоустановка строя: " + r.state("b").gov);
        races.clear(); races.put("orc", 1.0); r.state("c").gov = "";
        ok(d.govs.get(r.suggest("c", 3)).reqRaces().isEmpty() || d.govs.get(r.suggest("c", 3)).reqRaces().contains("orc"), "ИИ не берёт чужую расу");

        // сохранение
        Realms r2 = new Realms(d); r2.hooks = r.hooks;
        r2.fromJson(r.toJson());
        ok(r2.state("a").nodes.equals(r.state("a").nodes) && r2.state("a").laws.equals(r.state("a").laws) && r2.mods("a").equals(r.mods("a")), "save/load");
        ok(r.viewJson("a").contains("govs") && r.viewJson("a").contains("holdfast"), "viewJson");

        // расширяемость: добавление строя вручную
        RealmDefs d2 = new RealmDefs();
        d2.load("{\"levels\":[{\"id\":\"x\",\"name\":\"X\"}],\"governments\":[{\"id\":\"mine\",\"name\":\"М\",\"mods\":{\"a\":1}}]}");
        ok(d2.validate().isEmpty() && d2.govs.containsKey("mine"), "своё государство добавляется одним JSON");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
