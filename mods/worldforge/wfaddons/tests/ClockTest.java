package dev.worldforge.tests;

import dev.worldforge.mod.api.GameClock;

public class ClockTest {
    static int fails;
    static void ok(boolean c, String what) { System.out.println((c ? "ok   " : "FAIL ") + what); if (!c) fails++; }

    public static void main(String[] a) {
        GameClock c = new GameClock();
        ok(c.dateText().equals("1 Января 300") && c.speedName().equals("x1"), "старт: " + c.dateText());
        ok(c.pull("war") == 0, "первый вызов потребителя ничего не отдаёт");
        c.advance(GameClock.TICKS_PER_DAY * 3);
        ok(c.pull("war") == 3 && c.pull("war") == 0, "три дня отданы один раз");
        ok(c.pull("court") == 0 && c.pull("court") == 0, "другой потребитель начинает со своей отметки");
        c.advance(GameClock.TICKS_PER_DAY * 2);
        ok(c.pull("court") == 2 && c.pull("war") == 2, "каждый получает свои дни");
        // пауза и скорость
        c.pause();
        double e0 = c.elapsedDays();
        c.advance(100000);
        ok(c.paused() && c.elapsedDays() == e0 && c.multiplier() == 0, "на паузе время стоит");
        c.resume();
        ok(!c.paused() && c.speedName().equals("x1"), "возобновление на прежней скорости");
        c.setSpeed(3);
        c.pause(); c.resume();
        ok(c.speedName().equals("x5"), "скорость помнится после паузы");
        c.pull("t");
        c.advance(GameClock.TICKS_PER_DAY);
        ok(c.pull("t") == 5, "x5: день реального хода даёт 5 дней");
        c.faster(); ok(c.speedName().equals("x10"), "быстрее");
        c.faster(); ok(c.speedName().equals("x10"), "предел скорости");
        c.setSpeed(2); c.slower(); c.slower(); ok(c.speedName().equals("x1"), "медленнее не ниже x1 (паузу задают отдельно)");
        boolean bad = false; try { c.setSpeed(9); } catch (IllegalArgumentException e) { bad = true; }
        ok(bad, "неверная скорость отклонена");
        // календарь
        GameClock d = new GameClock();
        d.setSpeed(1);
        d.advance(GameClock.TICKS_PER_DAY * 45);
        ok(d.month() == 1 && d.dayOfMonth() == 16 && d.dateText().equals("16 Февраля 300"), "45-й день: " + d.dateText());
        d.advance(GameClock.TICKS_PER_DAY * 365);
        ok(d.year() == 301, "год сменился: " + d.dateText());
        d.advance(GameClock.TICKS_PER_DAY * 315);
        ok(d.month() == 11, "последние дни года остаются в декабре: " + d.dateText());
        // сохранение
        GameClock r = new GameClock();
        r.load(d.toJson());
        ok(r.dateText().equals(d.dateText()) && r.speedIndex() == d.speedIndex() && r.pull("x") == 0, "часы сохраняются, потребители начинают заново");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
