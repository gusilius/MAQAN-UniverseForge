package dev.worldforge.people;

import dev.worldforge.mod.Json;
import java.util.*;

/** Data-driven profession catalog. Accepts one definition or an array of definitions. */
public final class ProfessionDefs {
    private final Map<String, ProfessionDef> definitions = new LinkedHashMap<>();

    public void add(String json) {
        Object raw = Json.parse(json);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("Profession catalog entries must be objects");
                addDefinition(map);
            }
            return;
        }
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Profession must be a JSON object or array");
        addDefinition(map);
    }

    private void addDefinition(Map<?, ?> map) {
        String id = required(map, "id").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_.:-]{1,64}")) throw new IllegalArgumentException("Invalid profession id: " + id);
        String name = required(map, "name");
        String category = map.get("category") == null ? "worker" : String.valueOf(map.get("category")).toLowerCase(Locale.ROOT);
        if (!category.equals("worker") && !category.equals("specialist")) throw new IllegalArgumentException("Invalid profession category: " + category);
        PopulationSimulation.Duty duty = category.equals("specialist")
                ? PopulationSimulation.Duty.SPECIALIST : PopulationSimulation.Duty.WORKER;
        Map<String, Integer> skills = new LinkedHashMap<>();
        if (map.get("required_skills") instanceof Map<?, ?> requirements) {
            for (Map.Entry<?, ?> entry : requirements.entrySet()) {
                String skill = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (!skill.matches("[a-z0-9_.:-]{1,64}") || !(entry.getValue() instanceof Number n) || n.intValue() < 0 || n.intValue() > PopulationSimulation.MAX_SKILL_RANK)
                    throw new IllegalArgumentException("Invalid skill requirement for profession " + id + ": " + skill);
                skills.put(skill, n.intValue());
            }
        }
        if (definitions.putIfAbsent(id, new ProfessionDef(id, name, category, skills, duty)) != null)
            throw new IllegalArgumentException("Duplicate profession id: " + id);
    }

    public ProfessionDef get(String id) { return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<ProfessionDef> all() { return Collections.unmodifiableCollection(definitions.values()); }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Missing profession field: " + key);
        return s.trim();
    }
}
