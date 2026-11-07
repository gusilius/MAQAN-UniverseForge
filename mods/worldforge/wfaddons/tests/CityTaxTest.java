package dev.worldforge.tests;

import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.settle.*;

import java.nio.file.*;
import java.util.*;

public class CityTaxTest {
    static int fails;
    static void ok(boolean c, String w) { System.out.println((c ? "ok   " : "FAIL ") + w); if (!c) fails++; }

    static Settlement city(Defs d, String id, int workers) {
        Settlement s = new Settlement(id, "Город " + id, d);
        s.add("wood", 100); s.add("food", 400);
        for (int i = 0; i < workers; i++) s.addWorker(null);
        s.place("house", 0, 0); s.place("house", 6, 0);
        s.tick(80);
        return s;
    }

    public static void main(String[] a) throws Exception {
        TreasuryApi.resetForTests();
        Defs d = new Defs();
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        Settlement a1 = city(d, "a1", 6), a2 = city(d, "a2", 4), b1 = city(d, "b1", 5);
        Map<String, String> realm = Map.of("a1", "north", "a2", "north", "b1", "south");
        java.util.function.Function<Settlement, String> of = s -> realm.getOrDefault(s.id, "");
        for (Settlement s : List.of(a1, a2, b1)) { s.tick(600); }
        ok(a1.taxCollected > 0, "налоги копятся: " + Math.round(a1.taxCollected));

        CityTax t = new CityTax();
        ok(t.share("north") == 25, "доля по умолчанию 25%");
        double c1 = a1.taxCollected, silver1 = a1.amount(Defs.SILVER);
        Map<String, Double> got = t.remit(List.of(a1, a2, b1), of, 10);
        ok(got.containsKey("north") && got.containsKey("south"), "собрано с двух государств: " + got);
        ok(Math.abs(TreasuryApi.balance("north") - got.get("north")) < 1e-9 && TreasuryApi.balance("north") > 0, "золото в казне: " + Math.round(TreasuryApi.balance("north") * 10) / 10.0);
        ok(a1.taxCollected == 0 && a1.amount(Defs.SILVER) < silver1, "город отдал серебро, счётчик сброшен");
        double expect = Math.min(c1 * 0.25, Math.max(0, silver1 - CityTax.reserve(a1))) / CityTax.SILVER_PER_GOLD + Math.min(a2.remittedSilver, 1e9) / CityTax.SILVER_PER_GOLD;
        ok(Math.abs(a1.remittedSilver - Math.min(c1 * 0.25, Math.max(0, silver1 - CityTax.reserve(a1)))) < 1e-6, "доля короны 25% от собранного");
        ok(TreasuryApi.ledger("north").containsKey(CityTax.LABEL_TAX), "в книге казны: " + TreasuryApi.ledger("north"));
        ok(t.stat("north").remittedGold > 0 && t.stat("north").lastRemitDay == 10, "статистика");

        // доля и настроение
        a1.tick(10); int ap25 = a1.approval();
        t.setShare("north", 50); t.remit(List.of(a1), of, 11);
        int ap50 = a1.approval();
        ok(ap50 < ap25, "высокая подать злит: " + ap25 + " -> " + ap50);
        t.setShare("north", 5); t.remit(List.of(a1), of, 12);
        ok(a1.approval() > ap50, "низкая подать радует: " + a1.approval());
        try { t.setShare("north", 90); ok(false, "доля > 60% отвергнута"); } catch (Settlement.OrderException e) { ok(true, "доля > 60% отвергнута"); }
        try { t.setPolicy("north", 7); ok(false, "политика вне 0..3 отвергнута"); } catch (Settlement.OrderException e) { ok(true, "политика вне диапазона отвергнута"); }

        // резерв: бедный город не отдаёт последнее
        Settlement poor = city(d, "p", 3);
        poor.stock.put(Defs.SILVER, 0.0); poor.taxCollected = 1000;
        double before = TreasuryApi.balance("south");
        t.setShare("south", 60);
        t.remit(List.of(poor), s -> "south", 20);
        ok(poor.amount(Defs.SILVER) == 0.0 && TreasuryApi.balance("south") == before, "ниже резерва не берут");

        // политика налогов
        t.setPolicy("north", 3);
        t.remit(List.of(a1, a2), of, 21);
        ok(a1.taxLevel == 3 && a2.taxLevel == 3, "политика применена ко всем городам");
        t.setPolicy("north", -1); a1.taxLevel = 1; t.remit(List.of(a1), of, 22);
        ok(a1.taxLevel == 1, "без политики город сам решает");

        // гранты
        TreasuryApi.add("north", 100, "Тест");
        double bal = TreasuryApi.balance("north"), s0 = a2.amount(Defs.SILVER);
        ok(t.grant("north", a2, 40) && Math.abs(a2.amount(Defs.SILVER) - s0 - 200) < 1e-6, "грант 40 золота = 200 серебра");
        ok(Math.abs(TreasuryApi.balance("north") - (bal - 40)) < 1e-6, "казна уменьшилась");
        ok(!t.grant("north", a2, 1e6) && t.lastError.contains("нет столько"), "гранта больше казны нет: " + t.lastError);
        ok(!t.grant("north", a2, -5), "отрицательная сумма отвергнута");
        ok(TreasuryApi.ledger("north").containsKey(CityTax.LABEL_GRANT), "расход в книге");

        // вид и сохранение
        String v = t.viewJson("north", List.of(a1, a2, b1), of);
        ok(v.contains("\"cities\"") && v.contains("a1") && !v.contains("\"b1\""), "вид содержит только города государства");
        CityTax t2 = new CityTax(); t2.fromJson(t.toJson());
        ok(t2.share("north") == 5 && t2.share("south") == 60 && Math.abs(t2.stat("north").grantedGold - 40) < 1e-9, "сохранение налогов");
        String js = a2.toJson(); Settlement r = Settlement.fromJson(js, d);
        ok(r.grantedSilver == a2.grantedSilver && r.crownShare == a2.crownShare, "город помнит гранты и долю");

        // книга переживает перезапуск
        Map<String, Double> led = TreasuryApi.ledger("north"); TreasuryApi.resetForTests(); TreasuryApi.setLedger("north", led);
        ok(TreasuryApi.ledger("north").size() == led.size(), "книга восстановлена");
        // автономная казна
        TreasuryApi.resetForTests();
        TreasuryApi.add("x", 10, "a"); ok(TreasuryApi.spend("x", 4, "b") && !TreasuryApi.spend("x", 100, "b") && TreasuryApi.balance("x") == 6, "казна без поставщика");
        System.out.println(fails == 0 ? "ALL OK" : "FAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
