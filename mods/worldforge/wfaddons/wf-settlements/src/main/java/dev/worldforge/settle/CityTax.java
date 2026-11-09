package dev.worldforge.settle;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.mod.economy.TreasuryService;

import java.util.*;
import java.util.function.Function;

/**
 * Налоги городов в казну государства. Город копит собранные налоги (серебро); раз в период государство забирает свою долю (доля короны, по умолчанию 25%),
 * переводя серебро в золото казны по курсу {@link #SILVER_PER_GOLD}. Правитель задаёт долю и налоговую политику для всех своих городов; высокая подать
 * портит настроение горожан. Казна платит городам гранты. Всё записывается в книгу казны (TreasuryApi) по источникам.
 */
public final class CityTax {
    public static final double SILVER_PER_GOLD = 5, RESERVE_MINUTES = 5;
    public static final int DEFAULT_SHARE = 25, MAX_SHARE = 60;
    public static final String LABEL_TAX = "Налоги городов", LABEL_GRANT = "Гранты городам";

    public static final class Stat { public double remittedGold, grantedGold; public int lastRemitDay = -1; }

    public final Map<String, Integer> share = new LinkedHashMap<>();
    public final Map<String, Integer> policy = new LinkedHashMap<>();   // налоговый уровень для всех городов государства (-1 - решает сам город)
    public final Map<String, Stat> stats = new LinkedHashMap<>();
    public String lastError = "";

    public int share(String realm) { return share.getOrDefault(realm, DEFAULT_SHARE); }
    public int policy(String realm) { return policy.getOrDefault(realm, -1); }
    public Stat stat(String realm) { return stats.computeIfAbsent(realm, r -> new Stat()); }

    public void setShare(String realm, int pct) {
        if (pct < 0 || pct > MAX_SHARE) throw new Settlement.OrderException("Доля короны от 0 до " + MAX_SHARE + "%.");
        share.put(realm, pct);
    }

    public void setPolicy(String realm, int level) {
        if (level < -1 || level > 3) throw new Settlement.OrderException("Налог: -1 (по усмотрению города), 0..3.");
        policy.put(realm, level);
    }

    /** Резерв города: содержание зданий на несколько минут, чтобы налог не оставлял стройки без денег. */
    public static double reserve(Settlement s) { return s.upkeepPerMin() * RESERVE_MINUTES; }

    /** Забирает долю короны у всех городов. Возвращает собранное золото по государствам. */
    public Map<String, Double> remit(Collection<Settlement> cities, Function<Settlement, String> realmOf, int day) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Settlement s : cities) {
            String realm = realmOf.apply(s);
            if (realm == null || realm.isEmpty()) continue;
            int pct = share(realm);
            s.crownShare = pct;
            int pol = policy(realm);
            if (pol >= 0 && s.taxLevel != pol) s.taxLevel = pol;
            double due = s.taxCollected * pct / 100.0;
            double take = Math.min(due, Math.max(0, s.amount(Defs.SILVER) - reserve(s)));
            if (take <= 0) { s.taxCollected = 0; continue; }
            double gold = take / SILVER_PER_GOLD;
            if (!TreasuryApi.remitSettlement(s.ledgerAccountId(Defs.SILVER), realm, take, LABEL_TAX)) continue;
            s.stock.put(Defs.SILVER, TreasuryService.current().balance(s.ledgerAccountId(Defs.SILVER)));
            s.taxCollected = 0;
            s.remittedSilver += take;
            Stat st = stat(realm);
            st.remittedGold += gold; st.lastRemitDay = day;
            out.merge(realm, gold, Double::sum);
        }
        return out;
    }

    /** Грант городу из казны: золото казны -> серебро города. */
    public boolean grant(String realm, Settlement s, double gold) {
        if (gold <= 0 || !Double.isFinite(gold)) { lastError = "Нужна положительная сумма."; return false; }
        double silver = gold * SILVER_PER_GOLD;
        s.amount(Defs.SILVER); // ensure the destination account exists before the atomic transfer
        if (!TreasuryApi.grantSettlement(realm, s.ledgerAccountId(Defs.SILVER), silver, LABEL_GRANT)) {
            lastError = "В казне нет столько золота (" + Math.round(TreasuryApi.balance(realm)) + ").";
            return false;
        }
        s.stock.put(Defs.SILVER, TreasuryService.current().balance("settlement:" + s.id));
        s.grantedSilver += silver;
        stat(realm).grantedGold += gold;
        return true;
    }

    /** Состояние для окна: казна, книга по источникам, города государства. */
    public String viewJson(String realm, Collection<Settlement> cities, Function<Settlement, String> realmOf) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("realm", realm); root.put("balance", Math.round(TreasuryApi.balance(realm) * 10) / 10.0);
        root.put("share", share(realm)); root.put("policy", policy(realm));
        List<Object> led = new ArrayList<>();
        for (var e : TreasuryApi.ledger(realm).entrySet()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("label", e.getKey()); m.put("value", Math.round(e.getValue() * 10) / 10.0); led.add(m); }
        led.sort((x, y) -> Double.compare(Math.abs(((Number) ((Map<?, ?>) y).get("value")).doubleValue()), Math.abs(((Number) ((Map<?, ?>) x).get("value")).doubleValue())));
        root.put("ledger", led);
        List<Object> cl = new ArrayList<>();
        double perMin = 0;
        for (Settlement s : cities) {
            if (!realm.equals(realmOf.apply(s))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id); m.put("name", s.name); m.put("people", s.workers.size()); m.put("approval", s.approval()); m.put("tax", s.taxLevel);
            m.put("taxMin", Math.round(s.taxPerMin() * 10) / 10.0); m.put("silver", Math.round(s.amount(Defs.SILVER))); m.put("remitted", Math.round(s.remittedSilver / SILVER_PER_GOLD * 10) / 10.0);
            m.put("granted", Math.round(s.grantedSilver / SILVER_PER_GOLD * 10) / 10.0);
            perMin += s.taxPerMin() * s.crownShare / 100.0 / SILVER_PER_GOLD;
            cl.add(m);
        }
        root.put("cities", cl);
        root.put("perMin", Math.round(perMin * 100) / 100.0);
        Stat st = stat(realm);
        root.put("remittedGold", Math.round(st.remittedGold * 10) / 10.0); root.put("grantedGold", Math.round(st.grantedGold * 10) / 10.0);
        return Json.write(root);
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-citytax/1");
        root.put("share", new LinkedHashMap<String, Object>(share)); root.put("policy", new LinkedHashMap<String, Object>(policy));
        Map<String, Object> st = new LinkedHashMap<>();
        for (var e : stats.entrySet()) { Map<String, Object> m = new LinkedHashMap<>(); m.put("r", e.getValue().remittedGold); m.put("g", e.getValue().grantedGold); m.put("d", e.getValue().lastRemitDay); st.put(e.getKey(), m); }
        root.put("stats", st);
        return Json.write(root);
    }

    public void fromJson(String json) {
        Object o = Json.parse(json);
        if (!(o instanceof Map<?, ?> root) || !"wf-citytax/1".equals(root.get("format"))) throw new IllegalArgumentException("Неверный формат налогов городов");
        share.clear(); policy.clear(); stats.clear();
        if (root.get("share") instanceof Map<?, ?> m) for (var e : m.entrySet()) share.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
        if (root.get("policy") instanceof Map<?, ?> m) for (var e : m.entrySet()) policy.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
        if (root.get("stats") instanceof Map<?, ?> m) for (var e : m.entrySet()) {
            Map<?, ?> v = (Map<?, ?>) e.getValue(); Stat s = new Stat();
            s.remittedGold = ((Number) v.get("r")).doubleValue(); s.grantedGold = ((Number) v.get("g")).doubleValue(); s.lastRemitDay = ((Number) v.get("d")).intValue();
            stats.put(String.valueOf(e.getKey()), s);
        }
    }
}
