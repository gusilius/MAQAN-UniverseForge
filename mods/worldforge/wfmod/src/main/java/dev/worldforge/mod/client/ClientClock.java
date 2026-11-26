package dev.worldforge.mod.client;

import dev.worldforge.mod.Json;

import java.util.Map;

/** Клиентская копия общих часов: дата и скорость приходят с сервера раз в пару секунд и рисуются в заголовках окон. */
public final class ClientClock {
    private static String date = "";
    private static String speed = "";
    private static boolean paused;

    private ClientClock() {}

    @SuppressWarnings("unchecked")
    public static void update(String payload) {
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            date = String.valueOf(m.get("date"));
            speed = String.valueOf(m.get("speed"));
            paused = Boolean.TRUE.equals(m.get("paused"));
        } catch (RuntimeException ignored) { }
    }

    public static boolean known() { return !date.isEmpty(); }
    public static String date() { return date; }
    public static String speed() { return speed; }
    public static boolean paused() { return paused; }
}
