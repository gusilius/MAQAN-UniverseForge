package dev.worldforge.trade;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Товары из JSON (секция "goods"). need - потребление на 100 жителей в день; terrain - во сколько раз местность производит товар (доля местности x множитель);
 * races - спрос по расам (по умолчанию 1); season - выпуск по временам года [весна, лето, осень, зима]; contraband - запрещённый товар; item - предмет игры для личной торговли.
 */
public final class TradeDefs {
    public record Good(String id, String name, String category, double base, double need, double weight, String item,
                       Map<String, Double> terrain, Map<String, Double> races, double[] season, boolean contraband) {}

    public static final Set<String> CATEGORIES = Set.of("food", "material", "craft", "luxury", "military", "magic", "contraband");
    public final Map<String, Good> goods = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }
    private static Map<String, Double> map(Object o) { Map<String, Double> m = new LinkedHashMap<>(); if (o instanceof Map<?, ?> mm) for (var e : mm.entrySet()) m.put(String.valueOf(e.getKey()), dbl(e.getValue(), 0)); return m; }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        if (((Map<String, Object>) root).get("goods") instanceof List<?> l) for (Object o : l) add((Map<String, Object>) o);
    }

    public void add(Map<String, Object> g) {
        String id = str(g.get("id"), "");
        if (id.isEmpty()) throw new IllegalArgumentException("Товар без id");
        double[] s = {1, 1, 1, 1};
        if (g.get("season") instanceof List<?> l && l.size() == 4) for (int i = 0; i < 4; i++) s[i] = dbl(l.get(i), 1);
        boolean contra = Boolean.TRUE.equals(g.get("contraband")) || "contraband".equals(g.get("category"));
        goods.put(id, new Good(id, str(g.get("name"), id), str(g.get("category"), "material"), dbl(g.get("base"), 10), dbl(g.get("need"), 1), dbl(g.get("weight"), 1), str(g.get("item"), ""),
                map(g.get("terrain")), map(g.get("races")), s, contra));
    }

    public List<String> validate() {
        List<String> e = new ArrayList<>();
        for (Good g : goods.values()) {
            if (!CATEGORIES.contains(g.category())) e.add(g.id() + ": неизвестная категория " + g.category());
            if (g.base() <= 0) e.add(g.id() + ": цена должна быть больше нуля");
            if (g.need() < 0) e.add(g.id() + ": потребление не может быть отрицательным");
            if (g.weight() <= 0) e.add(g.id() + ": вес должен быть больше нуля");
            for (double s : g.season()) if (s < 0) e.add(g.id() + ": сезонный множитель меньше нуля");
        }
        if (goods.isEmpty()) e.add("нет товаров");
        return e;
    }
}
