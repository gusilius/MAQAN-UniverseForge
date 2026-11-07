package dev.worldforge.tests;

import dev.worldforge.war.*;
import dev.worldforge.army.*;

public class RealmEconomyTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }
    static boolean fails(Runnable r) { try { r.run(); return false; } catch (dev.worldforge.mod.TitleEditor.EditException e) { return true; } }

    public static void main(String[] a) throws Exception {
        String[] ids = new String[2];
        ArmyEngine e = ArmyTest.fresh(a[0], ids);
        e.ai = false;
        String A = ids[0], B = ids[1];
        Economy eco = e.eco;
        int n = e.baroniesOf(A).size();
        Economy.Report r0 = eco.report(A);
        ok(Math.abs(r0.net() - ArmyEngine.INCOME_PER_BARONY * n) < 1e-6, "базовый доход = баронства x 0.4: " + r0.net());
        ok(r0.income.size() == 1 && r0.expense.isEmpty(), "одна статья дохода");

        // налоги
        eco.setTax(A, 2);
        double hi = eco.net(A);
        eco.setTax(A, 0);
        double lo = eco.net(A);
        ok(hi > r0.net() && lo < r0.net(), "высокие налоги дают больше, низкие меньше: " + lo + " < " + r0.net() + " < " + hi);
        ok(fails(() -> eco.setTax(A, 3)), "политика только 0-2");
        eco.setTax(A, 1);

        // развитие
        int b = e.baroniesOf(A).get(0);
        double c1 = eco.developCost(b);
        e.gold.put(A, c1 - 1);
        ok(fails(() -> eco.develop(A, b)), "без золота развитие недоступно");
        e.gold.put(A, 1000.0);
        double before = eco.net(A);
        eco.develop(A, b);
        ok(eco.level(b) == 1 && e.gold(A) == 1000 - c1, "уровень 1, золото списано");
        ok(eco.net(A) > before, "развитие повышает доход: " + before + " -> " + eco.net(A));
        ok(eco.developCost(b) > c1, "следующий уровень дороже");
        for (int i = 0; i < 10 && eco.level(b) < Economy.MAX_DEV; i++) { e.gold.put(A, 1000.0); eco.develop(A, b); }
        e.gold.put(A, 1000.0);
        ok(eco.level(b) == Economy.MAX_DEV && fails(() -> eco.develop(A, b)), "предел развития " + Economy.MAX_DEV);
        int foreign = e.baroniesOf(B).get(0);
        ok(fails(() -> eco.develop(A, foreign)), "чужое баронство развивать нельзя");

        // доход реально капает в казну
        e.gold.put(A, 100.0); e.pool.clear();
        double inc = eco.net(A);
        e.tick(10);
        ok(Math.abs(e.gold(A) - (100 + inc * 10)) < 1e-6, "казна растёт на чистый доход: " + e.gold(A));

        // дань вассала
        e.war.editor.setOverlord(B, A, "vassal");
        e.invalidate();
        double aBefore = eco.net(A), bBefore = eco.net(B);
        eco.setTribute(A, 20);
        ok(eco.net(A) > aBefore && eco.net(B) < bBefore, "дань переходит от вассала к сюзерену");
        ok(Math.abs((eco.net(A) - aBefore) - (bBefore - eco.net(B))) < 1e-6, "переходит ровно столько, сколько ушло");
        ok(eco.report(B).expense.stream().anyMatch(l -> l.label.contains("сюзерену")), "у вассала есть статья расхода");
        ok(fails(() -> eco.setTribute(A, 31)), "дань не выше 30%");

        // торговый договор в статьях
        e.war.editor.setOverlord(B, null, "");
        e.invalidate();
        eco.setTribute(A, 0);
        e.proposePact(A, B);
        ok(eco.report(A).income.stream().anyMatch(l -> l.label.contains("Торговые")), "договор виден как статья дохода");

        // сохранение
        ArmyEngine back = new ArmyEngine(e.war);
        back.fromJson(e.toJson());
        ok(back.eco.level(b) == Economy.MAX_DEV && back.eco.taxPolicy(A) == 1, "экономика сохраняется");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
