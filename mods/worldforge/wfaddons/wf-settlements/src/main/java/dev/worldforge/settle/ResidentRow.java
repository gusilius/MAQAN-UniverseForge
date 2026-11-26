package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only client projection of one canonical wf-people Character in a settlement. */
public final class ResidentRow {
    public String id = "", name = "", stage = "", profession = "";
    public int age;
    public boolean working;

    public static ResidentRow of(dev.worldforge.people.Character person, int year, boolean working) {
        ResidentRow row = new ResidentRow();
        row.id = person.globalId;
        row.name = person.name == null ? "" : person.name;
        row.age = dev.worldforge.people.PopulationSimulation.age(person, year);
        row.stage = dev.worldforge.people.PopulationSimulation.lifeStage(person, year).name();
        row.profession = dev.worldforge.people.PopulationSimulation.profession(person);
        row.working = working;
        return row;
    }

    private Map<String, Object> map() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("name", name); value.put("age", age);
        value.put("stage", stage); value.put("profession", profession); value.put("working", working);
        return value;
    }

    public static String toJson(List<ResidentRow> rows) {
        List<Object> values = new ArrayList<>();
        for (ResidentRow row : rows) values.add(row.map());
        return Json.write(values);
    }

    @SuppressWarnings("unchecked")
    public static List<ResidentRow> fromJson(String json) {
        List<ResidentRow> rows = new ArrayList<>();
        if (json == null || json.isBlank()) return rows;
        for (Object value : (List<Object>) Json.parse(json)) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = (Map<String, Object>) raw;
            ResidentRow row = new ResidentRow();
            row.id = map.get("id") instanceof String v ? v : "";
            row.name = map.get("name") instanceof String v ? v : "";
            row.age = map.get("age") instanceof Number v ? Math.max(0, v.intValue()) : 0;
            row.stage = map.get("stage") instanceof String v ? v : "";
            row.profession = map.get("profession") instanceof String v ? v : "";
            row.working = Boolean.TRUE.equals(map.get("working"));
            if (!row.id.isBlank()) rows.add(row);
        }
        return rows;
    }
}
