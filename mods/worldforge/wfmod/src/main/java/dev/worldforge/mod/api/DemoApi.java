package dev.worldforge.mod.api;

import java.util.*;
import java.util.function.Function;

/**
 * Демография государств: доли рас среди жителей и доли местностей. Источники регистрируют аддоны (перепись жителей в wf-origins, свойства государства
 * races/terrain), потребители (формы власти, типы поселений) спрашивают общую шину и ничего не знают друг о друге. Поставщик отдаёт доли 0..1 (доля родительской расы включает подрас); при нескольких источниках берётся большее значение.
 * Свойства государства задаются строкой «id:доля,id:доля» ({@link #parse}).
 */
public final class DemoApi {
    private static final Map<String, Function<String, Map<String, Double>>> RACES = new LinkedHashMap<>(), TERRAIN = new LinkedHashMap<>();

    private DemoApi() {}

    public static synchronized void registerRaces(String id, Function<String, Map<String, Double>> p) { RACES.put(id, p); }
    public static synchronized void registerTerrain(String id, Function<String, Map<String, Double>> p) { TERRAIN.put(id, p); }
    public static synchronized void resetForTests() { RACES.clear(); TERRAIN.clear(); }

    /** «human:60, elf:30» -> {human: 60, elf: 30}. Мусор пропускается. */
    public static Map<String, Double> parse(Object o) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (o == null) return m;
        for (String part : String.valueOf(o).split("[,;]")) {
            String[] kv = part.trim().split("[:=]");
            if (kv.length == 2) { try { double v = Double.parseDouble(kv[1].trim()); if (v > 0) m.put(kv[0].trim(), v); } catch (NumberFormatException e) { /* пропуск */ } }
            else if (kv.length == 1 && !kv[0].isBlank()) m.merge(kv[0].trim(), 1.0, Double::sum);
        }
        return m;
    }

    private static Map<String, Double> collect(Map<String, Function<String, Map<String, Double>>> src, String realm) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : src.entrySet()) {
            try { Map<String, Double> m = e.getValue().apply(realm); if (m != null) m.forEach((k, v) -> { if (v > 0) out.merge(k, v, Math::max); }); }
            catch (RuntimeException ex) { System.err.println("WorldForge: демография " + e.getKey() + ": " + ex); }
        }
        return out;
    }

    /** Нормирует счётчики на сумму 1 (для строк свойств вида «human:60,elf:30»). */
    public static Map<String, Double> shares(Map<String, Double> m) {
        double sum = 0;
        for (double v : m.values()) sum += v;
        Map<String, Double> out = new LinkedHashMap<>();
        if (sum <= 0) return out;
        for (var e : m.entrySet()) out.put(e.getKey(), e.getValue() / sum);
        return out;
    }

    public static synchronized Map<String, Double> races(String realm) { return collect(RACES, realm); }
    public static synchronized Map<String, Double> terrain(String realm) { return collect(TERRAIN, realm); }
}
