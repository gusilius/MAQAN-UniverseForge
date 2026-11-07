package dev.worldforge.mod.api;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Общие часы мира: календарь, пауза и скорость времени, как в играх Paradox. Моды не считают дни сами, а берут их у часов
 * ({@link #pull}), поэтому пауза и ускорение действуют на все системы сразу. Чистая логика, без Minecraft.
 */
public final class GameClock {
    public static final int TICKS_PER_DAY = 1200, YEAR_DAYS = 365, MONTH_DAYS = 30;
    public static final int[] SPEEDS = {0, 1, 2, 5, 10};
    public static final String[] SPEED_NAMES = {"Пауза", "x1", "x2", "x5", "x10"};
    public static final String[] MONTHS = {"Января", "Февраля", "Марта", "Апреля", "Мая", "Июня", "Июля", "Августа", "Сентября", "Октября", "Ноября", "Декабря"};

    private double elapsed;                 // игровых дней с начала
    private int speed = 1;
    private int lastSpeed = 1;              // скорость до паузы
    public int startYear = 300;
    private final Map<String, Long> pulled = new HashMap<>();

    public double elapsedDays() { return elapsed; }
    public int speedIndex() { return speed; }
    public boolean paused() { return speed == 0; }
    public String speedName() { return SPEED_NAMES[speed]; }

    public void setSpeed(int i) {
        if (i < 0 || i >= SPEEDS.length) throw new IllegalArgumentException("Скорость 0.." + (SPEEDS.length - 1));
        if (i != 0) lastSpeed = i;
        speed = i;
    }

    public void pause() { if (speed != 0) lastSpeed = speed; speed = 0; }
    public void resume() { speed = lastSpeed == 0 ? 1 : lastSpeed; }
    public void toggle() { if (paused()) resume(); else pause(); }
    public void faster() { if (speed < SPEEDS.length - 1) setSpeed(speed + 1); }
    public void slower() { if (speed > 1) setSpeed(speed - 1); }

    /** Прошло столько серверных тиков (1 тик = 1/1200 дня при скорости x1). */
    public void advance(int ticks) { elapsed += (double) SPEEDS[speed] * ticks / TICKS_PER_DAY; }

    /** Сколько целых игровых дней прошло с прошлого вызова для этого потребителя; первый вызов возвращает 0. */
    public int pull(String consumer) {
        long now = (long) Math.floor(elapsed);
        Long last = pulled.put(consumer, now);
        return last == null ? 0 : (int) Math.max(0, now - last);
    }

    /** Множитель для систем реального времени (поселения): 0 на паузе, иначе скорость. */
    public double multiplier() { return SPEEDS[speed]; }

    // ------------------------------------------------------------------ календарь

    public long dayNumber() { return (long) Math.floor(elapsed); }
    public int year() { return startYear + (int) (dayNumber() / YEAR_DAYS); }
    public int dayOfYear() { return (int) (dayNumber() % YEAR_DAYS); }
    public int month() { return Math.min(11, dayOfYear() / MONTH_DAYS); }
    public int dayOfMonth() { return dayOfYear() - month() * MONTH_DAYS + 1; }
    public String dateText() { return dayOfMonth() + " " + MONTHS[month()] + " " + year(); }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "wf-clock/1"); m.put("elapsed", elapsed); m.put("speed", speed); m.put("last", lastSpeed); m.put("start", startYear);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        if (!"wf-clock/1".equals(m.get("format"))) throw new IllegalArgumentException("Неверный формат часов");
        elapsed = ((Number) m.get("elapsed")).doubleValue();
        speed = Math.max(0, Math.min(SPEEDS.length - 1, ((Number) m.get("speed")).intValue()));
        lastSpeed = Math.max(1, ((Number) m.get("last")).intValue());
        startYear = ((Number) m.get("start")).intValue();
        pulled.clear();
    }
}
