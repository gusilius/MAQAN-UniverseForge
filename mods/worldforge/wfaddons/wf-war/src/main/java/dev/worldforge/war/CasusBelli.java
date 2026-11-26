package dev.worldforge.war;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Повод для войны из JSON (data/<ns>/wf_war/*.json):
 * <pre>{"casus_belli": {"conquest": {"name": "Завоевание", "goal": "conquer", "target": "independent", "max_goals": 2}}}</pre>
 * goal: conquer (забрать титулы), subjugate (подчинить цель), independence (освободиться от сюзерена), humiliate (только мир и перемирие).
 * target: any, independent (цель никому не подчинена), overlord (цель - мой сюзерен), ally_ok (можно нападать даже на союзника - для предательства).
 */
public final class CasusBelli {
    public enum Goal { CONQUER, SUBJUGATE, INDEPENDENCE, HUMILIATE }

    public final String id, name, target, relation;
    public final Goal goal;
    public final int maxGoals, truceDays;

    CasusBelli(String id, String name, Goal goal, String target, int maxGoals, int truceDays, String relation) {
        this.id = id; this.name = name; this.goal = goal; this.target = target; this.maxGoals = maxGoals; this.truceDays = truceDays; this.relation = relation;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, CasusBelli> parse(String json) {
        Map<String, CasusBelli> out = new LinkedHashMap<>();
        Map<String, Object> root = (Map<String, Object>) Json.parse(json);
        if (!(root.get("casus_belli") instanceof Map)) return out;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) root.get("casus_belli")).entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            Goal g = Goal.valueOf(String.valueOf(m.getOrDefault("goal", "conquer")).toUpperCase(Locale.ROOT));
            out.put(e.getKey(), new CasusBelli(e.getKey(), String.valueOf(m.getOrDefault("name", e.getKey())), g, String.valueOf(m.getOrDefault("target", "independent")),
                    m.get("max_goals") instanceof Number n ? n.intValue() : 1, m.get("truce_days") instanceof Number n ? n.intValue() : 1825,
                    String.valueOf(m.getOrDefault("relation", "vassal"))));
        }
        return out;
    }
}
