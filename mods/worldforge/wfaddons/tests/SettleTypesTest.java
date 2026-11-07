package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.nio.file.*;
import java.util.*;

public class SettleTypesTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        Defs defs = new Defs();
        String dir = "wf-settlements/src/main/resources/data/wf_settle/wf_settle/";
        for (String f : new String[]{"base", "economy", "extra"}) defs.add(Files.readString(Paths.get(dir + f + ".json")));
        SettleTypes st = new SettleTypes();
        st.load(Files.readString(Paths.get(dir + "types.json")));
        List<String> bad = st.validate(defs);
        ok(bad.isEmpty() && st.types.size() >= 8, "виды: " + st.types.size() + " " + bad);

        Map<String, Double> plains = Map.of("plains", 1.0), coast = Map.of("coast", 0.6, "plains", 0.4), mount = Map.of("mountains", 0.7, "plains", 0.3);
        Map<String, Double> none = Map.of();
        ok(st.best(3, List.of(), plains, none).id().equals("camp"), "малый лагерь - стоянка");
        ok(st.best(10, List.of("house", "house"), plains, none).id().equals("hamlet"), "два дома - хутор");
        ok(st.best(30, List.of("house", "house", "house", "house", "farm"), plains, none).id().equals("village"), "деревня");
        List<String> town = new ArrayList<>(Collections.nCopies(8, "house")); town.addAll(List.of("market", "tavern", "farm"));
        ok(st.best(100, town, plains, none).id().equals("town"), "город");
        List<String> port = new ArrayList<>(town); port.add("warehouse");
        ok(st.best(100, port, coast, none).id().equals("port"), "на побережье склад + рынок дают порт: " + st.best(100, port, coast, none).id());
        ok(!st.best(100, port, plains, none).id().equals("port"), "порт не на равнине");
        List<String> mine = new ArrayList<>(Collections.nCopies(4, "house")); mine.addAll(List.of("farm", "iron_mine", "smithy"));
        ok(st.best(40, mine, mount, Map.of("dwarf", 1.0)).id().equals("mining_town"), "горный посёлок гномов");
        ok(!st.why(st.types.get("mining_town"), 40, mine, plains, none).isEmpty(), "горный посёлок не на равнине: " + st.why(st.types.get("mining_town"), 40, mine, plains, none));

        SettleTypes.Type camp = st.types.get("camp"), fort = st.types.get("fortress");
        ok(!st.canBuild(camp, defs.buildings.get("market"), 0).isEmpty(), "рынок на стоянке нельзя");
        ok(st.canBuild(camp, defs.buildings.get("house"), 2).isEmpty() && !st.canBuild(camp, defs.buildings.get("house"), 3).isEmpty(), "лимит домов на стоянке: 3");
        ok(st.canBuild(fort, defs.buildings.get("barracks"), 0).isEmpty(), "казармы в крепости");
        ok(st.fit(st.types.get("mining_town"), mount, Map.of("dwarf", 1.0)) > st.fit(st.types.get("mining_town"), plains, Map.of("orc", 1.0)), "подгонка выше у гномов в горах");
        ok(st.mods(fort, mount, Map.of("dwarf", 1.0)).get("safety") > 10, "бонус безопасности усилен подгонкой: " + st.mods(fort, mount, Map.of("dwarf", 1.0)));

        for (String sc : st.schemes) {
            SettleTypes.Type t = new SettleTypes.Type("x", "x", "", 0, 0, 99, Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), sc, 2, Map.of(), Map.of(), Map.of(), "");
            List<int[]> l = st.layout(t, 20, 5), l2 = st.layout(t, 20, 5);
            boolean same = l.size() == 20, dup = false;
            for (int i = 0; i < l.size(); i++) { same &= Arrays.equals(l.get(i), l2.get(i)); for (int j = 0; j < i; j++) if (Arrays.equals(l.get(i), l.get(j))) dup = true; }
            ok(same && !dup, "схема " + sc + ": 20 участков, детерминирована, без совпадений");
        }
        Settlement se = new Settlement("s1", "Тест", defs);
        se.types = st;
        se.typeId = "camp";
        String err = se.placeError("market", 0, 0);
        ok(err != null && err.contains("не подходит") || err != null && err.contains("не строят"), "Settlement запрещает рынок на стоянке: " + err);
        se.typeId = "town";
        ok(se.placeError("market", 0, 0) == null, "в городе рынок можно");
        // свой вид одним JSON
        st.load("{\"settlement_types\":[{\"id\":\"elven_grove\",\"name\":\"Роща\",\"tier\":2,\"min_pop\":10,\"scheme\":\"ring\",\"req_races\":[\"elf\"]}]}");
        ok(st.types.containsKey("elven_grove") && st.best(20, List.of(), plains, Map.of("elf", 1.0)).id().equals("elven_grove"), "свой вид добавляется одним JSON");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
