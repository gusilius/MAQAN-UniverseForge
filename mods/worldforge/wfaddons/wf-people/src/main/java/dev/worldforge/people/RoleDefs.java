package dev.worldforge.people;

import java.util.*;

/** Набор ролей. Хозяин мода загружает файлы датапаков через {@link #add}. Порядок добавления сохраняется. */
public final class RoleDefs {
    private final Map<String, Role> roles = new LinkedHashMap<>();

    public void add(String json) {
        Role r = Role.parse(json);
        roles.put(r.id, r);                      // поздний файл заменяет ранний: так датапак переопределяет роль
    }

    /**
     * Every map can define its own arbitrary title tiers (province/region/state,
     * barony/county/duchy/kingdom/empire, or a custom hierarchy). Ensure a real
     * ruler office exists for each tier instead of assuming a fixed five-rank pack.
     * The generated role is ordinary wf-people data and is consumed by Courts.ensure.
     */
    public int ensureTerritorialRulers(dev.worldforge.mod.TitleData data) {
        if (data == null) return 0;
        int added = 0;
        for (dev.worldforge.mod.TitleData.Tier tier : data.tiers) {
            boolean supplied = roles.values().stream()
                    .anyMatch(role -> role.ruler && tier.key.equals(role.per));
            if (supplied) continue;
            String suffix = tier.key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]", "_");
            if (suffix.isBlank()) suffix = Integer.toString(tier.index);
            String id = "wf_tier_ruler_" + suffix;
            if (id.length() > 48) id = id.substring(0, 48);
            // Avoid collisions after sanitization while remaining deterministic.
            if (roles.containsKey(id) && !tier.key.equals(roles.get(id).per))
                id = "wf_tier_" + tier.index + "_ruler";
            String json = "{\\"id\\":\\"" + id + "\\",\\"name\\":\\"Правитель: "
                    + escape(tier.name) + "\\",\\"per\\":\\"" + escape(tier.key)
                    + "\\",\\"count\\":1,\\"noble\\":true,\\"ruler\\":true}";
            add(json);
            added++;
        }
        return added;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\\\", "\\\\\\\\").replace("\\\"", "\\\\\"");
    }

    public Role get(String id) { return roles.get(id); }

    public Collection<Role> all() { return roles.values(); }

    public Role ruler() {
        for (Role r : roles.values()) if (r.ruler) return r;
        return null;
    }
}
