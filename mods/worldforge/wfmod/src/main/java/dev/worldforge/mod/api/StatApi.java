package dev.worldforge.mod.api;

import java.util.*;

/**
 * Общая шина поправок к характеристикам. Любой аддон (расы, классы, профессии, черты, звания организаций, предметы) регистрирует
 * поставщика поправок и меток, а потребители (прокачка, бой, торговля) просто спрашивают итог. Аддоны не знают друг о друге.
 * Владелец: "p:&lt;uuid игрока&gt;" или "n:&lt;id персонажа&gt;". Ключи с суффиксом _pct - проценты. Чистая логика.
 */
public final class StatApi {
    public interface Provider { Map<String, Double> mods(String owner); }
    public interface TagProvider { Map<String, String> tags(String owner); }

    private static final Map<String, Provider> PROVIDERS = new TreeMap<>();
    private static final Map<String, TagProvider> TAGS = new TreeMap<>();
    private static final Map<String, Provider> SHEETS = new TreeMap<>();

    private StatApi() {}

    public static synchronized void register(String id, Provider p) { PROVIDERS.put(id, p); }

    /** Итоговая сводка владельца (прокачка регистрирует её: level и все характеристики). Нужна тем, кто проверяет требования. */
    public static synchronized void registerSheet(String id, Provider p) { SHEETS.put(id, p); }

    public static synchronized Map<String, Double> sheet(String owner) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : SHEETS.entrySet()) {
            try { out.putAll(e.getValue().mods(owner)); } catch (RuntimeException ex) { System.err.println("WorldForge: сводка " + e.getKey() + ": " + ex); }
        }
        return out;
    }

    public static synchronized void registerTags(String id, TagProvider p) { TAGS.put(id, p); }

    public static synchronized void unregister(String id) { PROVIDERS.remove(id); TAGS.remove(id); SHEETS.remove(id); }

    public static synchronized void resetForTests() { PROVIDERS.clear(); TAGS.clear(); SHEETS.clear(); }

    /** Сумма поправок всех поставщиков. Ошибка одного поставщика не ломает остальных. */
    public static synchronized Map<String, Double> mods(String owner) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : PROVIDERS.entrySet()) {
            try { for (var m : e.getValue().mods(owner).entrySet()) out.merge(m.getKey(), m.getValue(), Double::sum); }
            catch (RuntimeException ex) { System.err.println("WorldForge: поставщик поправок " + e.getKey() + ": " + ex); }
        }
        return out;
    }

    /** Поправки конкретного поставщика (для окна: откуда взялся бонус). */
    public static synchronized Map<String, Map<String, Double>> bySource(String owner) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (var e : PROVIDERS.entrySet()) {
            try { Map<String, Double> m = e.getValue().mods(owner); if (!m.isEmpty()) out.put(e.getKey(), m); } catch (RuntimeException ex) { /* пропуск */ }
        }
        return out;
    }

    public static synchronized Map<String, String> tags(String owner) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : TAGS.entrySet()) {
            try { out.putAll(e.getValue().tags(owner)); } catch (RuntimeException ex) { /* пропуск */ }
        }
        return out;
    }

    public static String tag(String owner, String key) { return tags(owner).getOrDefault(key, ""); }
}
