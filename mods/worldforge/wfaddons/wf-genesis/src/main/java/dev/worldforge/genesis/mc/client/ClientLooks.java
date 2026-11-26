package dev.worldforge.genesis.mc.client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Облик игроков на клиенте: раса, внешность и особенности по UUID. Приходит строкой "race|key=val;key=val|f1,f2". */
public final class ClientLooks {
    public record Look(String race, Map<String, String> look, Set<String> features) {
        public double scale(String key) {
            try { return Math.max(0.5, Math.min(1.5, Double.parseDouble(look.getOrDefault(key, "100")) / 100.0)); } catch (NumberFormatException e) { return 1.0; }
        }
        public int color(String key, int def) {
            String s = look.get(key);
            try { return s != null && s.matches("#[0-9a-fA-F]{6}") ? Integer.parseInt(s.substring(1), 16) : def; } catch (NumberFormatException e) { return def; }
        }
    }

    private static final Map<UUID, Look> LOOKS = new ConcurrentHashMap<>();

    private ClientLooks() {}

    public static Look get(UUID id) { return LOOKS.get(id); }

    public static void put(UUID id, Look l) { if (l == null) LOOKS.remove(id); else LOOKS.put(id, l); }

    public static Look parse(String s) {
        String[] p = s.split("\\|", -1);
        if (p.length < 3) return null;
        Map<String, String> look = new LinkedHashMap<>();
        for (String kv : p[1].split(";")) { int i = kv.indexOf('='); if (i > 0) look.put(kv.substring(0, i), kv.substring(i + 1)); }
        Set<String> f = new LinkedHashSet<>();
        for (String x : p[2].split(",")) if (!x.isEmpty()) f.add(x);
        return new Look(p[0], look, f);
    }

    /** Из сообщения "uuid\nстрока". */
    public static void accept(String payload) {
        int i = payload.indexOf('\n');
        if (i < 0) return;
        try { put(UUID.fromString(payload.substring(0, i).trim()), parse(payload.substring(i + 1).trim())); } catch (IllegalArgumentException ignored) {}
    }
}
