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
        ok(d.month() == 1 && d.dayOfMonth() == 15 && d.dateText().equals("15 Февраля 300"), "45-й день: " + d.dateText());
        d.advance(GameClock.TICKS_PER_DAY * 365);
        ok(d.year() == 301, "год сменился: " + d.dateText());
        d.advance(GameClock.TICKS_PER_DAY * 315);
        ok(d.month() == 11, "последние дни года остаются в декабре: " + d.dateText());
        ok(d.worldMinute() == d.dayNumber() * 1440L, "canonical world minute is derived from the 365-day clock");
        GameClock seasons = new GameClock();
        seasons.advance(GameClock.TICKS_PER_DAY * 91);
        ok(seasons.seasonIndex() == 1 && seasons.seasonProgress() == 0.0, "season boundary follows the shared world year");
        seasons.advance(GameClock.TICKS_PER_DAY * 182);
        ok(seasons.seasonIndex() == 3, "winter begins at day 273 and the final season spans 92 days");
        // сохранение
        GameClock r = new GameClock();
        r.load(d.toJson());
        ok(r.dateText().equals(d.dateText()) && r.speedIndex() == d.speedIndex() && r.pull("x") == 0, "часы сохраняются, потребители начинают заново");
        GameClock strict = new GameClock();
        strict.advance(GameClock.TICKS_PER_DAY * 12);
        String validBeforeBadLoad = strict.toJson();
        boolean invalidClockRejected = false;
        try { strict.load("{\"format\":\"wf-clock/1\",\"elapsed\":-1,\"speed\":1,\"last\":1,\"start\":300}"); }
        catch (IllegalArgumentException expected) { invalidClockRejected = true; }
        ok(invalidClockRejected && strict.toJson().equals(validBeforeBadLoad),
                "invalid clock save is rejected without partially mutating the running calendar");
        boolean nonFiniteRejected = false;
        try { strict.load("{\"format\":\"wf-clock/1\",\"elapsed\":1e999,\"speed\":1,\"last\":1,\"start\":300}"); }
        catch (IllegalArgumentException expected) { nonFiniteRejected = true; }
        ok(nonFiniteRejected && strict.toJson().equals(validBeforeBadLoad),
                "non-finite clock elapsed time is rejected without resetting world time");
        strict.reset();
        ok(strict.dayNumber() == 0 && strict.year() == 300 && strict.speedIndex() == 1,
                "clock singleton can be safely reset before loading a different save");

        // A campaign advances the one shared clock; every addon consumer receives the same elapsed days.
        GameClock campaign = new GameClock();
        campaign.setSimulationTickMultiplier(8400);
        ok(campaign.simulationTickMultiplier() == 8400 && campaign.multiplier() == 8400.0,
                "campaign multiplier is shared with real-time domain consumers");
        campaign.advanceServerTick();
        ok(campaign.dayNumber() == 7 && campaign.pull("people") == 0 && campaign.pull("people") == 0,
                "one campaign server tick advances exactly seven shared calendar days");
        ok(campaign.pull("court") == 0, "each campaign consumer has an independent initial cursor");
        campaign.advanceServerTick();
        ok(campaign.pull("people") == 7 && campaign.pull("court") == 7,
                "all addon consumers observe the same seven-day elapsed interval");
        campaign.pause();
        double campaignDay = campaign.elapsedDays();
        campaign.advance(10);
        ok(campaign.elapsedDays() == campaignDay && campaign.multiplier() == 0.0,
                "campaign acceleration respects the shared pause state");
        boolean excessiveStepRejected = false;
        try { campaign.setSimulationTickMultiplier(10001); } catch (IllegalArgumentException expected) { excessiveStepRejected = true; }
        ok(excessiveStepRejected, "campaign multiplier is bounded to prevent unsafe calendar jumps");
        campaign.reset();
        ok(campaign.simulationTickMultiplier() == 1 && campaign.multiplier() == 1.0,
                "campaign acceleration is cleared when a server world is reset");
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
