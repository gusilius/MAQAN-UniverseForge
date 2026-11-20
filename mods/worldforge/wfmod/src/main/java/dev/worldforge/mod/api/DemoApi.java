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
    private static final Map<String, Function<Collection<String>, Map<String, Map<String, Double>>>> RACE_BATCH = new LinkedHashMap<>();

    private DemoApi() {}

    public static synchronized void registerRaces(String id, Function<String, Map<String, Double>> p) { RACES.put(id, p); }
    public static synchronized void registerTerrain(String id, Function<String, Map<String, Double>> p) { TERRAIN.put(id, p); }
    /** Optional source-level census API: compute every requested realm in one population pass. */
    public static synchronized void registerRaceBatch(String id,
            Function<Collection<String>, Map<String, Map<String, Double>>> provider) {
        RACE_BATCH.put(id, provider);
    }
    public static synchronized void resetForTests() { RACES.clear(); TERRAIN.clear(); RACE_BATCH.clear(); }

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

    /**
     * Batch demographic lookup. A census-backed source can group all requested realms
     * in one pass instead of rescanning the complete Character registry for every realm.
     * Legacy single-realm providers remain fully supported.
     */
    public static synchronized Map<String, Map<String, Double>> races(Collection<String> realms) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (realms != null) for (String realm : realms) if (realm != null && !realm.isBlank()) requested.add(realm);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (var provider : RACE_BATCH.entrySet()) {
            try {
                Map<String, Map<String, Double>> batch = provider.getValue().apply(Collections.unmodifiableSet(requested));
                if (batch == null) continue;
                for (String realm : requested) {
                    Map<String, Double> values = batch.get(realm);
                    if (values == null || values.isEmpty()) continue;
                    Map<String, Double> target = out.computeIfAbsent(realm, ignored -> new LinkedHashMap<>());
                    values.forEach((key, value) -> {
                        if (key != null && value != null && value > 0) target.merge(key, value, Math::max);
                    });
                }
            } catch (RuntimeException ex) {
                System.err.println("WorldForge: пакетная демография " + provider.getKey() + ": " + ex);
            }
        }
        for (String realm : requested) {
            Map<String, Double> legacy = collect(RACES, realm);
            if (!legacy.isEmpty()) {
                Map<String, Double> target = out.computeIfAbsent(realm, ignored -> new LinkedHashMap<>());
                legacy.forEach((key, value) -> target.merge(key, value, Math::max));
            }
        }
        Map<String, Map<String, Double>> immutable = new LinkedHashMap<>();
        out.forEach((realm, values) -> immutable.put(realm, Map.copyOf(values)));
        return Collections.unmodifiableMap(immutable);
    }

    public static synchronized Map<String, Double> terrain(String realm) { return collect(TERRAIN, realm); }
}
