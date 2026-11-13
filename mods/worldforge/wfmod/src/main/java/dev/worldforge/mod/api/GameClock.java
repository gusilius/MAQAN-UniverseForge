package dev.worldforge.mod.api;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Общие часы мира: календарь, пауза и скорость времени, как в играх Paradox. Моды не считают дни сами, а берут их у часов
 * ({@link #pull}), поэтому пауза и ускорение действуют на все системы сразу. Чистая логика, без Minecraft.
 */
public final class GameClock {
    public static final int TICKS_PER_DAY = 1200, YEAR_DAYS = 365;
    private static final int[] MONTH_LENGTHS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    public static final int[] SPEEDS = {0, 1, 2, 5, 10};
    public static final String[] SPEED_NAMES = {"Пауза", "x1", "x2", "x5", "x10"};
    public static final String[] MONTHS = {"Января", "Февраля", "Марта", "Апреля", "Мая", "Июня", "Июля", "Августа", "Сентября", "Октября", "Ноября", "Декабря"};

    private double elapsed;                 // игровых дней с начала
    private int speed = 1;
    private int lastSpeed = 1;              // скорость до паузы
    // Test/campaign acceleration is applied at the shared clock, so every consumer observes the same elapsed days.
    private int simulationDaysPerTick = 1;
    public int startYear = 300;
    private final Map<String, Long> pulled = new HashMap<>();

    public double elapsedDays() { return elapsed; }

    /** Reset the singleton clock before a different server save is loaded in the same JVM. */
    public void reset() {
        elapsed = 0;
        speed = 1;
        lastSpeed = 1;
        simulationDaysPerTick = 1;
        startYear = 300;
        pulled.clear();
    }
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

    /**
     * Sets a bounded shared-calendar step for isolated long-running simulation campaigns.
     * It is deliberately not persisted: ordinary worlds always restart at one day per tick.
     */
    public void setSimulationDaysPerTick(int days) {
        if (days < 1 || days > 30) throw new IllegalArgumentException("Simulation step must be between 1 and 30 days");
        simulationDaysPerTick = days;
    }

    public int simulationDaysPerTick() { return simulationDaysPerTick; }

    /** Прошло столько серверных тиков (1 тик = 1/1200 дня при скорости x1). */
    public void advance(int ticks) {
        if (ticks < 0) throw new IllegalArgumentException("ticks cannot be negative");
        elapsed += (double) SPEEDS[speed] * ticks / TICKS_PER_DAY;
    }

    /** Advance one real server tick using the configured shared campaign step. */
    public void advanceServerTick() {
        advance(simulationDaysPerTick == 1 ? 1 : Math.multiplyExact(TICKS_PER_DAY, simulationDaysPerTick));
    }

    /** Сколько целых игровых дней прошло с прошлого вызова для этого потребителя; первый вызов возвращает 0. */
    public int pull(String consumer) {
        long now = (long) Math.floor(elapsed);
        Long last = pulled.put(consumer, now);
        return last == null ? 0 : (int) Math.max(0, now - last);
    }

    /** Множитель для систем реального времени (поселения): 0 на паузе, иначе скорость. */
    public double multiplier() { return (double) SPEEDS[speed] * simulationDaysPerTick; }

    // ------------------------------------------------------------------ календарь

    public long dayNumber() { return (long) Math.floor(elapsed); }
    /** Canonical elapsed world minute, including the fractional day. */
    public long worldMinute() { return Math.max(0L, (long) Math.floor(elapsed * 1440.0)); }
    public int seasonIndex() { return Math.min(3, dayOfYear() / 91); }
    public double seasonProgress() {
        int index = seasonIndex();
        int start = index * 91;
        int duration = index == 3 ? 92 : 91;
        double within = dayOfYear() - start + (elapsed - Math.floor(elapsed));
        return Math.max(0, Math.min(1, within / duration));
    }
    public int year() { return startYear + (int) (dayNumber() / YEAR_DAYS); }
    public int dayOfYear() { return (int) (dayNumber() % YEAR_DAYS); }
    public int month() {
        int remaining = dayOfYear();
        for (int i = 0; i < MONTH_LENGTHS.length; i++) {
            if (remaining < MONTH_LENGTHS[i]) return i;
            remaining -= MONTH_LENGTHS[i];
        }
        return 11;
    }
    public int dayOfMonth() {
        int remaining = dayOfYear();
        for (int i = 0; i < MONTH_LENGTHS.length; i++) {
            if (remaining < MONTH_LENGTHS[i]) return remaining + 1;
            remaining -= MONTH_LENGTHS[i];
        }
        return 31;
    }
    public String dateText() { return dayOfMonth() + " " + MONTHS[month()] + " " + year(); }

    // ------------------------------------------------------------------ сохранение

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "wf-clock/1"); m.put("elapsed", elapsed); m.put("speed", speed); m.put("last", lastSpeed); m.put("start", startYear);
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Clock save must be a JSON object");
        Map<String, Object> m = (Map<String, Object>) raw;
        if (!"wf-clock/1".equals(m.get("format"))) throw new IllegalArgumentException("Неверный формат часов");
        if (!(m.get("elapsed") instanceof Number elapsedValue)
                || !(m.get("speed") instanceof Number speedValue)
                || !(m.get("last") instanceof Number lastValue)
                || !(m.get("start") instanceof Number startValue))
            throw new IllegalArgumentException("Clock save is missing numeric state fields");

        double nextElapsed = elapsedValue.doubleValue();
        int nextSpeed = speedValue.intValue();
        int nextLast = lastValue.intValue();
        int nextStart = startValue.intValue();
        if (!Double.isFinite(nextElapsed) || nextElapsed < 0
                || nextElapsed > (Integer.MAX_VALUE - (double) Math.max(1, nextStart)) * YEAR_DAYS)
            throw new IllegalArgumentException("Clock elapsed time is outside the supported range");
        if (nextSpeed < 0 || nextSpeed >= SPEEDS.length)
            throw new IllegalArgumentException("Clock speed is outside the supported range");
        if (nextLast < 1 || nextLast >= SPEEDS.length)
            throw new IllegalArgumentException("Clock resume speed is outside the supported range");
        if (nextStart < 1) throw new IllegalArgumentException("Clock start year must be positive");

        // Commit only after every field has passed validation: malformed saves never partially mutate time.
        elapsed = nextElapsed;
        speed = nextSpeed;
        lastSpeed = nextLast;
        startYear = nextStart;
        pulled.clear();
    }
}
