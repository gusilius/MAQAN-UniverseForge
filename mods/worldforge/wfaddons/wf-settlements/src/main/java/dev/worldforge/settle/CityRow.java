package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/** Строка сводной таблицы городов (как «реестр» в Paradox): ключевые показатели поселения в одной строке. */
public final class CityRow {
    public String id, name, season;
    public int pop, workforce, housing, approval, buildings, tax, repelled, lost;
    public double silver, silverNet, food, foodNet, defense, wealth;
    public boolean raid;
    public final Map<String, Double> prices = new LinkedHashMap<>(), stock = new LinkedHashMap<>();

    public static CityRow of(Settlement s) {
        CityRow r = new CityRow();
        r.id = s.id; r.name = s.name; r.season = s.seasonName();
        r.pop = s.population(); r.workforce = s.workers.size(); r.housing = s.housing(); r.approval = s.approval();
        for (Building b : s.buildings) if (b.state == Building.State.DONE) r.buildings++;
        r.tax = s.taxLevel; r.repelled = s.raidsRepelled; r.lost = s.raidsLost;
        r.silver = s.amount(Defs.SILVER); r.silverNet = s.silverPerMin();
        r.food = s.amount("food"); r.foodNet = s.producedPerMin("food") - s.consumedPerMin("food");
        for (String res : s.defs.prices.keySet()) if (!res.equals(Defs.SILVER)) { r.prices.put(res, Math.round(s.price(res) * 100) / 100.0); r.stock.put(res, Math.floor(s.amount(res))); }
        r.defense = s.defense(); r.wealth = s.wealth(); r.raid = s.raidIncoming;
        return r;
    }

    private Map<String, Object> map() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("season", season); m.put("pop", pop); m.put("workforce", workforce); m.put("housing", housing); m.put("approval", approval);
        m.put("buildings", buildings); m.put("tax", tax); m.put("ok", repelled); m.put("bad", lost);
        m.put("silver", silver); m.put("silverNet", silverNet); m.put("food", food); m.put("foodNet", foodNet); m.put("defense", defense); m.put("wealth", wealth); m.put("raid", raid); m.put("prices", new LinkedHashMap<String, Object>(prices)); m.put("stock", new LinkedHashMap<String, Object>(stock));
        return m;
    }

    public static String toJson(List<CityRow> rows) {
        List<Object> l = new ArrayList<>();
        for (CityRow r : rows) l.add(r.map());
        return Json.write(l);
    }

    @SuppressWarnings("unchecked")
    public static List<CityRow> fromJson(String json) {
        List<CityRow> out = new ArrayList<>();
        for (Object o : (List<Object>) Json.parse(json)) {
            Map<String, Object> m = (Map<String, Object>) o;
            CityRow r = new CityRow();
            r.id = (String) m.get("id"); r.name = (String) m.get("name"); r.season = (String) m.get("season");
            r.pop = ((Number) m.get("pop")).intValue();
            r.workforce = m.get("workforce") instanceof Number workforce ? workforce.intValue() : r.pop;
            r.housing = ((Number) m.get("housing")).intValue(); r.approval = ((Number) m.get("approval")).intValue();
            r.buildings = ((Number) m.get("buildings")).intValue(); r.tax = ((Number) m.get("tax")).intValue();
            r.repelled = ((Number) m.get("ok")).intValue(); r.lost = ((Number) m.get("bad")).intValue();
            r.silver = ((Number) m.get("silver")).doubleValue(); r.silverNet = ((Number) m.get("silverNet")).doubleValue();
            r.food = ((Number) m.get("food")).doubleValue(); r.foodNet = ((Number) m.get("foodNet")).doubleValue();
            r.defense = ((Number) m.get("defense")).doubleValue(); r.wealth = ((Number) m.get("wealth")).doubleValue();
            r.raid = Boolean.TRUE.equals(m.get("raid"));
            if (m.get("prices") instanceof Map<?, ?> pm) for (var e : pm.entrySet()) r.prices.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
            if (m.get("stock") instanceof Map<?, ?> sm) for (var e : sm.entrySet()) r.stock.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
            out.add(r);
        }
        return out;
    }
}
