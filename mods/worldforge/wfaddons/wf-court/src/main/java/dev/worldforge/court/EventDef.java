package dev.worldforge.court;


import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Событие из JSON (data/<ns>/wf_events/*.json): условия, текст и варианты ответа с последствиями. Новое событие - файл, без кода.
 * <pre>{"id": "hunt", "weight": 3, "who": "ruler", "cond": {"trait": "brave"}, "cooldown_years": 5, "title": "Охота", "text": "{name} ...",
 *  "options": [{"text": "...", "ai": 2, "effects": [{"op": "stat", "attr": "martial", "delta": 1}]}]}</pre>
 * Условия: trait, no_trait, min_age, max_age, married, children, liege (есть сюзерен), vassals (есть вассалы), at_war, role.
 * Последствия (op): trait{add|remove}, stat{attr,delta}, opinion{who: liege|vassals|spouse|council, value, years, label}, kill, gold{value}, child.
 */
public final class EventDef {
    public static final class Option {
        public final String text;
        public final double ai;
        public final List<Map<String, Object>> effects;

        Option(String text, double ai, List<Map<String, Object>> effects) { this.text = text; this.ai = ai; this.effects = effects; }
    }

    public final String id, title, text, who;
    public final double weight;
    public final int cooldownYears;
    public final Map<String, Object> cond;
    public final List<Option> options = new ArrayList<>();

    EventDef(String id, String title, String text, String who, double weight, int cooldownYears, Map<String, Object> cond) {
        this.id = id; this.title = title; this.text = text; this.who = who; this.weight = weight; this.cooldownYears = cooldownYears; this.cond = cond;
    }

    @SuppressWarnings("unchecked")
    public static EventDef parse(String json) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(json);
        String id = String.valueOf(m.get("id"));
        if (!id.matches("[a-z0-9_.:-]{1,48}")) throw new IllegalArgumentException("Недопустимый id события: " + id);
        if (!(m.get("options") instanceof List<?> ol) || ol.isEmpty()) throw new IllegalArgumentException("У события " + id + " нет вариантов ответа");
        EventDef e = new EventDef(id, String.valueOf(m.getOrDefault("title", id)), String.valueOf(m.getOrDefault("text", "")), String.valueOf(m.getOrDefault("who", "ruler")),
                m.get("weight") instanceof Number n ? n.doubleValue() : 1, m.get("cooldown_years") instanceof Number c ? c.intValue() : 10,
                m.get("cond") instanceof Map ? (Map<String, Object>) m.get("cond") : Map.of());
        for (Object o : ol) {
            Map<String, Object> om = (Map<String, Object>) o;
            List<Map<String, Object>> fx = new ArrayList<>();
            if (om.get("effects") instanceof List<?> fl) for (Object f : fl) fx.add((Map<String, Object>) f);
            e.options.add(new Option(String.valueOf(om.getOrDefault("text", "...")), om.get("ai") instanceof Number n ? n.doubleValue() : 1, fx));
        }
        return e;
    }
}
