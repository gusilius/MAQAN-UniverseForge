package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.nio.file.*;

public class SeasonTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static int nextSettlementId = 1;
    static Settlement make(boolean guards) throws Exception {
        Defs d = new Defs();
        for (String f : new String[]{"base", "extra", "economy"}) d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/" + f + ".json")));
        Settlement s = new Settlement("season_test_" + (nextSettlementId++), "Тест", d);
        s.add("wood", 300); s.add("planks", 100); s.add("stone", 100); s.add("food", 400); s.add("silver", 500);
        for (int i = 0; i < 10; i++) s.addWorker(null);
        s.place("house", 0, 0); s.place("house", 10, 0);
        if (guards) { s.place("watchtower", 30, 0); }
        return s;
    }

    public static void main(String[] a) throws Exception {
        Settlement s = make(false);
        Settlement globallyTimed = make(false);
        globallyTimed.setWorldCalendar(91L * 1440L, 301);
        ok(globallyTimed.seasonName().equals("Лето") && globallyTimed.year() == 301
                        && globallyTimed.seasonProgress() == 0.0,
                "settlement season and year follow the shared world calendar, not local creation time");
        globallyTimed.setWorldCalendar(273L * 1440L, 301);
        ok(globallyTimed.seasonName().equals("Зима"),
                "all settlements enter winter on the same global calendar day");
        ok(s.seasonName().equals("Весна") && s.year() == 1, "старт: весна, год 1");
        s.tick(Settlement.SEASON_LEN * 2 + 1);
        ok(s.seasonName().equals("Осень"), "через две смены - осень: " + s.seasonName());
        s.tick(Settlement.SEASON_LEN);
        ok(s.seasonName().equals("Зима"), "зима");
        s.tick(Settlement.SEASON_LEN);
        ok(s.seasonName().equals("Весна") && s.year() == 2, "год сменился");

        // фермы: зимой выход ниже, чем летом
        double[] pm = new double[2];
        int k = 0;
        for (int season : new int[]{1, 3}) {
            Settlement f = make(false); f.place("farm", 40, 0); f.tick(200);
            f.time = Settlement.SEASON_LEN * season + 1; f.tick(100);
            pm[k++] = f.producedPerMin("food");
        }
        ok(pm[0] > pm[1] * 2, "лето дарит еды больше зимы: " + pm[0] + " > " + pm[1]);

        // набег без обороны бьёт по запасам
        Settlement r = make(false);
        r.raidTimer = 10;
        double silver0 = r.amount("silver");
        r.tick(10 + Settlement.RAID_WARN + 30);
        ok(r.raidsLost == 1 && r.raidsRepelled == 0, "без обороны набег удался");
        ok(r.amount("silver") < silver0, "унесено серебро: " + silver0 + " -> " + r.amount("silver"));
        ok(r.log.stream().anyMatch(x -> x.contains("Налёт")), "запись в журнале");

        // предупреждение
        Settlement p = make(false);
        p.raidTimer = 5; p.tick(6);
        ok(p.raidIncoming && p.alerts().stream().anyMatch(x -> x.kind.equals("raid")), "заранее есть тревога о набеге");

        // оборона отбивает
        Settlement g = make(true);
        g.tick(250);
        for (int i = 0; i < 6; i++) g.addWorker(null);
        g.stock.put("silver", 50.0);
        for (var wk : g.workers) if (g.workers.indexOf(wk) < 10) wk.profession = "guard";
        g.raidTimer = 1;
        g.tick(1 + Settlement.RAID_WARN + 5);
        ok(g.raidsRepelled == 1 && g.raidsLost == 0, "стража и башня отбили набег: оборона " + g.defense() + ", сила " + g.raidStrength);

        // сохранение
        Settlement l = Settlement.fromJson(g.toJson(), g.defs);
        ok(l.raidsRepelled == g.raidsRepelled && l.raidTimer == g.raidTimer, "набеги сохраняются");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
