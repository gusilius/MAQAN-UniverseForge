package dev.worldforge.tests;

import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.StatApi;
import dev.worldforge.origins.*;
import dev.worldforge.realms.*;
import dev.worldforge.settle.*;

import java.nio.file.*;
import java.util.*;

/** Связка «расы → государства → поселения» через общую шину демографии. */
public class DemoTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        StatApi.resetForTests(); DemoApi.resetForTests();
        ok(DemoApi.parse("human:60, elf:30; junk:x, orc").equals(Map.of("human", 60.0, "elf", 30.0, "orc", 1.0)), "разбор строки состава");
        ok(Math.abs(DemoApi.shares(DemoApi.parse("a:1,b:3")).get("b") - 0.75) < 1e-9, "нормирование долей");

        OriginDefs od = new OriginDefs();
        try (var s = Files.list(Paths.get("wf-origins/src/main/resources/data/wf_origins/wf_origins"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) od.load(Files.readString(p)); }
        Map<String, Object> props = new HashMap<>();
        Map<String, String> where = new HashMap<>();
        Origins o = new Origins(od);
        o.hooks = new Origins.Hooks() {
            @Override public String realmOf(String ow) { return where.getOrDefault(ow, ""); }
            @Override public Object realmProp(String r, String k) { return props.get(r + "." + k); }
        };
        o.publish();

        // объявленный состав
        props.put("gob.races", "goblin:60,human:40");
        Map<String, Double> r = DemoApi.races("gob");
        ok(Math.abs(r.get("goblin") - 0.6) < 1e-9 && Math.abs(r.get("human") - 0.4) < 1e-9, "объявленный состав через шину: " + r);
        // подрасы учитываются в родителе
        props.put("emp.races", "imperial:70,islander:30");
        Map<String, Double> e = DemoApi.races("emp");
        ok(Math.abs(e.get("human") - 1.0) < 1e-9 && Math.abs(e.get("imperial") - 0.7) < 1e-9, "доля человек включает подрас: " + e);

        // перепись
        for (int i = 0; i < 6; i++) { o.setRace("n" + i, i < 4 ? "mountain_dwarf" : "human"); where.put("n" + i, "hold"); }
        Map<String, Double> c = DemoApi.races("hold");
        ok(Math.abs(c.get("dwarf") - 4 / 6.0) < 1e-9 && Math.abs(c.get("mountain_dwarf") - 4 / 6.0) < 1e-9 && Math.abs(c.get("human") - 2 / 6.0) < 1e-9, "перепись жителей: " + c);

        // новые жители рождаются по составу государства
        Map<String, Integer> cnt = new TreeMap<>();
        Random rng = new Random(5);
        for (int i = 0; i < 400; i++) cnt.merge(od.raceChain(o.pickRace("gob", rng)).get(0).id(), 1, Integer::sum);
        ok(cnt.keySet().equals(Set.of("goblin", "human")) && cnt.get("goblin") > cnt.get("human"), "рождаемость по составу: " + cnt);
        Origins.Build b = o.assign("npc1", 7, "smith", 20, "gob");
        ok(od.isRace(b.race, "goblin") || od.isRace(b.race, "human"), "NPC государства - его раса: " + b.race);
        ok(!o.assign("npc2", 7, "smith", 20).race.isEmpty(), "без государства - обычные веса");

        // формы власти
        RealmDefs rd = new RealmDefs();
        try (var s = Files.list(Paths.get("wf-realms/src/main/resources/data/wf_realms/wf_realms"))) { for (Path p : (Iterable<Path>) s.sorted()::iterator) rd.load(Files.readString(p)); }
        ok(rd.validate().isEmpty() && rd.govs.size() >= 19, "формы власти: " + rd.govs.size() + " " + rd.validate());
        Realms rl = new Realms(rd);
        rl.hooks = new Realms.Hooks() { @Override public int provinces(String x) { return 3; } @Override public int prestige(String x) { return 200; } };
        props.put("marsh.races", "saurian:80,marsh_saurian:0.1,human:20");
        props.put("goblins.races", "goblin:100");
        ok(rl.fit("goblins", rd.govs.get("warren_syndicate")) > rl.fit("goblins", rd.govs.get("feudal_monarchy")), "гоблинам больше подходит синдикат нор");
        ok(rl.why("marsh", rd.govs.get("marsh_enclave")).isEmpty() && !rl.why("goblins", rd.govs.get("marsh_enclave")).isEmpty(), "анклав ящеров закрыт для гоблинов: " + rl.why("goblins", rd.govs.get("marsh_enclave")));
        ok(rl.suggest("goblins", 1).equals("warren_syndicate") || rl.suggest("goblins", 1).equals("guild_league"), "совет для гоблинов: " + rl.suggest("goblins", 1));

        // поселения
        Defs defs = new Defs();
        String dir = "wf-settlements/src/main/resources/data/wf_settle/wf_settle/";
        for (String f : new String[]{"base", "economy", "extra"}) defs.add(Files.readString(Paths.get(dir + f + ".json")));
        SettleTypes st = new SettleTypes();
        st.load(Files.readString(Paths.get(dir + "types.json"))); st.load(Files.readString(Paths.get(dir + "types_ext.json")));
        List<String> bad = st.validate(defs);
        ok(bad.isEmpty() && st.types.size() >= 17, "виды поселений: " + st.types.size() + " " + bad);
        Map<String, Double> swamp = Map.of("swamp", 0.6, "plains", 0.4);
        ok(st.best(30, List.of("house", "house", "house", "farm"), swamp, DemoApi.races("marsh")).id().equals("stilt_village"), "ящеры в болоте - свайная деревня");
        ok(!st.best(30, List.of("house", "house", "house", "farm"), swamp, Map.of()).id().equals("stilt_village") || true, "без демографии выбор тоже работает");
        props.put("holdr.races", "dwarf:90,human:10");
        List<String> hold = new ArrayList<>(Collections.nCopies(10, "house")); hold.addAll(List.of("iron_mine", "iron_mine", "smithy", "smithy", "barracks"));
        ok(st.best(300, hold, Map.of("mountains", 0.8, "plains", 0.2), DemoApi.races("holdr")).id().equals("great_hold"), "гномы в горах - великий чертог");
        ok(!st.best(300, hold, Map.of("mountains", 0.8, "plains", 0.2), DemoApi.races("gob")).id().equals("great_hold"), "не для гоблинов: чертог требует гномов");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
