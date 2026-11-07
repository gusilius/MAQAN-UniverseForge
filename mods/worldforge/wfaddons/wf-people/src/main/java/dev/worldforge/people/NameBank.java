package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Имена и дома по культурам (JSON, расширяется датапаками):
 * {"default": "northern", "cultures": {"northern": {"male": [...], "female": [...], "houses": [...]}}}
 */
public final class NameBank {
    public static final class Pool {
        public final List<String> male = new ArrayList<>(), female = new ArrayList<>(), houses = new ArrayList<>();
    }

    private final Map<String, Pool> cultures = new LinkedHashMap<>();
    private String def = "";

    @SuppressWarnings("unchecked")
    public void add(String json) {
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (root.get("default") != null) def = String.valueOf(root.get("default"));
        Map<String, Object> cs = (Map<String, Object>) root.get("cultures");
        if (cs == null) throw new IllegalArgumentException("Нужен раздел cultures");
        for (Map.Entry<String, Object> e : cs.entrySet()) {
            Pool p = cultures.computeIfAbsent(e.getKey(), k -> new Pool());
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            fill(p.male, m.get("male")); fill(p.female, m.get("female")); fill(p.houses, m.get("houses"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void fill(List<String> to, Object from) {
        if (from instanceof List) for (Object o : (List<Object>) from) if (!to.contains(String.valueOf(o))) to.add(String.valueOf(o));
    }

    public Pool pool(String culture) {
        Pool p = cultures.get(culture);
        if (p == null) p = cultures.get(def);
        if (p == null || p.male.isEmpty() || p.female.isEmpty() || p.houses.isEmpty()) throw new IllegalStateException("Нет имён для культуры '" + culture + "'");
        return p;
    }

    public String culture(String wanted) { return cultures.containsKey(wanted) ? wanted : def; }
}
