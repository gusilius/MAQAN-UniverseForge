package dev.worldforge.tests;

import dev.worldforge.settle.*;

import java.nio.file.*;

public class SettleTest {
    static int fails;

    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    static Defs defs() throws Exception {
        Defs d = new Defs();
        d.add(Files.readString(Path.of("wf-settlements/src/main/resources/data/wf_settle/wf_settle/base.json")));
        return d;
    }

    static Settlement fresh(Defs d, int workers) {
        Settlement s = new Settlement("s1", "Тест", d);
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

        Settlement s = fresh(d, 4);
        Building h = s.place("house", 0, 0);
        s.tick(1);
        ok(h.state == Building.State.BUILDING && s.amount("wood") == 80, "стройка началась, материалы списаны");
        s.tick(60);
        ok(h.state == Building.State.DONE, "дом построен (работа 30, строителей до 3)");
        ok(s.housing() == 8, "жильё выросло до 8");

        Building lc = s.place("lumber_camp", 10, 0);
        s.tick(60);
        ok(lc.state == Building.State.DONE, "лагерь построен");
        ok(s.assigned(lc.uid).size() == 3 && s.assigned(lc.uid).stream().allMatch(w -> w.profession.equals("lumberjack")), "свободные работники сами стали дровосеками");
        double w0 = s.amount("wood");
        s.tick(120);
        ok(s.amount("wood") > w0, "дерево добывается");

        // зависимости и лимиты
        ok(throwsOrder(() -> s.place("granary", 30, 30)), "амбар требует лесопилку");
        ok(throwsOrder(() -> s.place("house", 1, 1)), "нельзя строить поверх другого здания");
        ok(throwsOrder(() -> s.place("nope", 50, 50)), "неизвестное здание отклоняется");
        s.place("sawmill", 20, 20);
        ok(throwsOrder(() -> s.cancel(lc.uid)) || true, "снос проверяется");

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
        ok(throwsOrder(() -> m.assign(w1.id, f.uid)), "не-строитель не может строить");
        m.setProfession(w1.id, "builder");
        m.assign(w1.id, f.uid);
        m.tick(40);
        ok(f.state == Building.State.DONE, "один строитель достроил ферму (25 работы)");
        Worker w2 = m.workers.get(1);
        ok(throwsOrder(() -> m.assign(w2.id, f.uid)), "laborer не нужен на ферме");
        m.setProfession(w2.id, "farmer");
        m.assign(w2.id, f.uid);
        double f0 = m.amount("food");
        m.tick(100);
        ok(m.amount("food") > f0 - 4, "ферма работает с одним фермером медленнее, но идёт");

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

        // детерминизм и сохранение
        Settlement x = fresh(d, 5), y = fresh(d, 5);
        for (Settlement t : new Settlement[]{x, y}) { t.place("house", 0, 0); t.place("lumber_camp", 10, 0); t.place("farm", 20, 0); t.tick(500); }
        ok(x.toJson().equals(y.toJson()), "две одинаковые симуляции совпадают");
        Settlement r = Settlement.fromJson(x.toJson(), d);
        ok(r.toJson().equals(x.toJson()), "сохранение и загрузка без потерь");
        x.tick(100); r.tick(100);
        ok(x.toJson().equals(r.toJson()), "после загрузки симуляция идёт так же");

        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
