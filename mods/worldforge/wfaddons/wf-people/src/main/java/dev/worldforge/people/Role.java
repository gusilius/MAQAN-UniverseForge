package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Роль из JSON (датапак data/<ns>/wf_roles/*.json). Новая роль - это файл, без кода:
 * <pre>
 * {"id": "chancellor", "name": "Канцлер", "per": "realm", "count": 1, "dialogue": "wf_people:chancellor",
 *  "unlocks": ["diplomacy"], "family": false, "noble": true}
 * </pre>
 * per: "realm" - у каждого государства; иначе ключ уровня титула ("barony", "county"...) - у каждого титула этого уровня.
 * unlocks: что роль разрешает другим аддонам (они проверяют через {@link CharacterRegistry#unlocked}).
 */
public final class Role {
    public final String id, name, per, dialogue;
    public final int count;
    public final boolean noble, family, ruler;
    public final List<String> unlocks;
    /** Совет: какую характеристику носителя роль применяет и что от неё получает государство (ключ эффекта -> прирост за единицу). Пусто - роль не советническая. */
    public String councilAttr = "";
    public final Map<String, Double> councilEffects = new LinkedHashMap<>();

    Role(String id, String name, String per, int count, String dialogue, boolean noble, boolean family, boolean ruler, List<String> unlocks) {
        this.id = id; this.name = name; this.per = per; this.count = count; this.dialogue = dialogue;
        this.noble = noble; this.family = family; this.ruler = ruler; this.unlocks = unlocks;
    }

    @SuppressWarnings("unchecked")
    public static Role parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Роль должна быть объектом JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        String id = String.valueOf(m.get("id"));
        if (!id.matches("[a-z0-9_.:-]{1,48}")) throw new IllegalArgumentException("Недопустимый id роли: " + id);
        if (m.get("name") == null) throw new IllegalArgumentException("У роли " + id + " нет name");
        int count = m.get("count") instanceof Number n ? n.intValue() : 1;
        if (count < 0 || count > 50) throw new IllegalArgumentException("У роли " + id + " странное число: " + count);
        List<String> un = new ArrayList<>();
        if (m.get("unlocks") instanceof List) for (Object o : (List<Object>) m.get("unlocks")) un.add(String.valueOf(o));
        Role role = new Role(id, String.valueOf(m.get("name")), m.get("per") == null ? "realm" : String.valueOf(m.get("per")), count,
                m.get("dialogue") == null ? "" : String.valueOf(m.get("dialogue")), Boolean.TRUE.equals(m.get("noble")),
                Boolean.TRUE.equals(m.get("family")), Boolean.TRUE.equals(m.get("ruler")), Collections.unmodifiableList(un));
        if (m.get("council") instanceof Map<?, ?> cm) {
            role.councilAttr = String.valueOf(cm.get("attr"));
            if (cm.get("effects") instanceof Map<?, ?> em) for (Map.Entry<?, ?> e : em.entrySet()) role.councilEffects.put(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
        }
        return role;
    }
}
