package dev.worldforge.mod.api;

import java.util.*;

/**
 * Казна государства: общая шина денег. Владельца баланса (wf-army хранит золото государства) регистрирует поставщик; остальные аддоны (торговые пошлины, налоги городов,
 * события заданий) вносят и берут деньги, не зная друг о друге. Каждая операция записывается в книгу по источнику (метка) - окно казны показывает, откуда идут деньги.
 * Если поставщика нет, баланс ведётся здесь (для автономной работы и тестов). Единица - золото.
 */
public final class TreasuryApi {
    public interface Provider {
        boolean has(String realm);
        double balance(String realm);
        void add(String realm, double delta);
    }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Map<String, Double> LOCAL = new HashMap<>();
    private static final Map<String, Map<String, Double>> LEDGER = new HashMap<>();

    private TreasuryApi() {}

    public static synchronized void register(String id, Provider p) { PROVIDERS.put(id, p); }
    public static synchronized void resetForTests() { PROVIDERS.clear(); LOCAL.clear(); LEDGER.clear(); }

    private static Provider owner(String realm) {
        for (Provider p : PROVIDERS.values()) if (p.has(realm)) return p;
        return null;
    }

    public static synchronized double balance(String realm) {
        Provider p = owner(realm);
        return p != null ? p.balance(realm) : LOCAL.getOrDefault(realm, 0.0);
    }

    /** Вносит (delta &gt; 0) или списывает (delta &lt; 0) деньги, label - источник для книги. Баланс может уйти в минус только при списании без проверки: используйте {@link #spend}. */
    public static synchronized void add(String realm, double delta, String label) {
        if (delta == 0 || realm == null || realm.isEmpty()) return;
        Provider p = owner(realm);
        if (p != null) p.add(realm, delta); else LOCAL.merge(realm, delta, Double::sum);
        LEDGER.computeIfAbsent(realm, r -> new LinkedHashMap<>()).merge(label == null || label.isEmpty() ? "Прочее" : label, delta, Double::sum);
    }

    /** Списывает, только если денег хватает. */
    public static synchronized boolean spend(String realm, double amount, String label) {
        if (amount < 0 || balance(realm) < amount) return false;
        add(realm, -amount, label);
        return true;
    }

    /** Книга государства: источник -> итог (плюс приход, минус расход) с начала игры. */
    public static synchronized Map<String, Double> ledger(String realm) { return new LinkedHashMap<>(LEDGER.getOrDefault(realm, Map.of())); }
    public static synchronized void setLedger(String realm, Map<String, Double> l) { LEDGER.put(realm, new LinkedHashMap<>(l)); }
    public static synchronized Map<String, Map<String, Double>> allLedgers() {
        Map<String, Map<String, Double>> o = new LinkedHashMap<>();
        for (var e : LEDGER.entrySet()) o.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        return o;
    }
}
