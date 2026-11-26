package dev.worldforge.tests;

import dev.worldforge.progress.*;

import java.nio.file.*;
import java.util.*;

public class ThreatTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    public static void main(String[] a) throws Exception {
        Threat t = new Threat();
        t.load(Files.readString(Paths.get("wf-progress/src/main/resources/data/wf_progress/wf_progress/threats.json")));
        ok(t.defs.size() >= 6, "угроз загружено " + t.defs.size());
        ok(t.zoneLevel(0, 1, 0) == 1, "у точки отсчёта 1 уровень");
        ok(t.zoneLevel(6000, 5, 0) == 10, "далеко от центра не выше игрок+5: " + t.zoneLevel(6000, 5, 0));
        ok(t.zoneLevel(0, 30, 0) == 27, "игрок высокого уровня не найдёт слабых: " + t.zoneLevel(0, 30, 0));
        ok(t.hpMult(1) == 1.0 && t.hpMult(30) > t.hpMult(10) && t.dmgMult(30) > 2.9, "множители растут: " + t.hpMult(30) + "/" + t.dmgMult(30));
        Random rng = new Random(2);
        Map<String, Integer> seen = new TreeMap<>();
        for (int i = 0; i < 300; i++) { var e = t.roll("bandits", 6, rng); seen.merge(e.def.id(), 1, Integer::sum); }
        ok(seen.keySet().equals(Set.of("poachers", "road_bandits")), "на 6 уровне только слабые банды: " + seen);
        seen.clear();
        for (int i = 0; i < 300; i++) { var e = t.roll("bandits", 35, rng); seen.merge(e.def.id(), 1, Integer::sum); }
        ok(seen.keySet().equals(Set.of("outlaw_band", "warband")), "на 35 уровне серьёзные банды: " + seen);
        var low = t.roll("bandits", 2, new Random(1)); var high = t.build(t.defs.get("warband"), 40, new Random(1));
        ok(high.fighters.size() > low.fighters.size() && high.gold > low.gold && high.xp > low.xp, "сильная встреча больше, богаче и опытнее: " + low.fighters.size() + "→" + high.fighters.size());
        boolean boss = false; double maxHp = 0;
        for (var f : high.fighters) { if (f.role.equals("boss")) boss = true; maxHp = Math.max(maxHp, f.hpMult); }
        ok(boss && maxHp > 10, "у военной банды есть босс с большим запасом здоровья: " + maxHp);
        ok(Threat.label(high.fighters.get(0)).startsWith("[") && t.roll("undead", 1, rng) == null, "подпись с уровнем; нежити на 1 уровне нет");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAIL");
        if (fails > 0) System.exit(1);
    }
}
