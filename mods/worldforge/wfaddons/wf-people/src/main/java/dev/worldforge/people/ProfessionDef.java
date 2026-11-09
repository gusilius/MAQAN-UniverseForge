package dev.worldforge.people;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data-driven profession definition. Political posts remain separate from professions. */
public final class ProfessionDef {
    public final String id, name, category;
    public final Map<String, Integer> requiredSkills;
    public final PopulationSimulation.Duty duty;

    ProfessionDef(String id, String name, String category, Map<String, Integer> requiredSkills,
                  PopulationSimulation.Duty duty) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.requiredSkills = Collections.unmodifiableMap(new LinkedHashMap<>(requiredSkills));
        this.duty = duty;
    }
}
