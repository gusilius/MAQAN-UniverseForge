package dev.worldforge.dialogue;

import dev.worldforge.mod.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Дерево диалога из JSON:
 * <pre>
 * {"id": "ruler_greeting", "start": "hello", "nodes": {
 *   "hello": {"speaker": "npc", "text": "Приветствую, {player}.",
 *             "choices": [{"text": "Служить вам", "next": "oath", "if": [{"if": "rep_at_least", "target": "realm:stark", "value": 10}], "do": [{"do": "set_flag", "name": "met"}]},
 *                         {"text": "Прощайте"}]},
 *   "oath": {"text": "Добро.", "do": [{"do": "rep_add", "target": "realm:stark", "value": 5}]}}}
 * </pre>
 * Выбор без next завершает разговор. Реплика без choices и с next показывает «Далее», без next - завершает.
 */
public final class Dialogue {
    public static final class Choice {
        public final String text, next;
        final List<Call> conditions, effects;

        Choice(String text, String next, List<Call> conditions, List<Call> effects) {
            this.text = text; this.next = next; this.conditions = conditions; this.effects = effects;
        }
    }

    public static final class Node {
        public final String id, speaker, text, next;
        public final List<Choice> choices;
        final List<Call> effects;

        Node(String id, String speaker, String text, String next, List<Choice> choices, List<Call> effects) {
            this.id = id; this.speaker = speaker; this.text = text; this.next = next; this.choices = choices; this.effects = effects;
        }
    }

    public final String id, start;
    public final Map<String, Node> nodes = new LinkedHashMap<>();

    private Dialogue(String id, String start) { this.id = id; this.start = start; }

    @SuppressWarnings("unchecked")
    static List<Call> calls(Object raw, String key) {
        List<Call> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            Object id = m.remove(key);
            if (id == null) throw new IllegalArgumentException("В вызове нет поля '" + key + "': " + o);
            out.add(new Call(String.valueOf(id), m));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Dialogue parse(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Диалог должен быть объектом JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (!(m.get("id") instanceof String) || !(m.get("start") instanceof String) || !(m.get("nodes") instanceof Map)) {
            throw new IllegalArgumentException("Нужны поля id, start и nodes");
        }
        Dialogue d = new Dialogue((String) m.get("id"), (String) m.get("start"));
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("nodes")).entrySet()) {
            Map<String, Object> n = (Map<String, Object>) e.getValue();
            List<Choice> cs = new ArrayList<>();
            if (n.get("choices") instanceof List) {
                for (Object co : (List<Object>) n.get("choices")) {
                    Map<String, Object> c = (Map<String, Object>) co;
                    cs.add(new Choice(String.valueOf(c.get("text")), (String) c.get("next"), calls(c.get("if"), "if"), calls(c.get("do"), "do")));
                }
            }
            d.nodes.put(e.getKey(), new Node(e.getKey(), n.get("speaker") == null ? "npc" : String.valueOf(n.get("speaker")),
                    String.valueOf(n.get("text") == null ? "" : n.get("text")), (String) n.get("next"), cs, calls(n.get("do"), "do")));
        }
        return d;
    }

    /** Ошибки структуры и ссылок на неизвестные условия/эффекты; недостижимые реплики (предупреждение) тоже. Пустой список = всё хорошо. */
    public List<String> validate() {
        List<String> p = new ArrayList<>();
        if (!nodes.containsKey(start)) p.add("Нет стартовой реплики '" + start + "'");
        for (Node n : nodes.values()) {
            if (n.next != null && !nodes.containsKey(n.next)) p.add(n.id + ": переход на несуществующую реплику '" + n.next + "'");
            for (Call c : n.effects) if (!DialogueApi.hasEffect(c.id)) p.add(n.id + ": неизвестный эффект '" + c.id + "'");
            for (Choice ch : n.choices) {
                if (ch.next != null && !nodes.containsKey(ch.next)) p.add(n.id + " -> '" + ch.text + "': нет реплики '" + ch.next + "'");
                for (Call c : ch.effects) if (!DialogueApi.hasEffect(c.id)) p.add(n.id + " -> '" + ch.text + "': неизвестный эффект '" + c.id + "'");
                for (Call c : ch.conditions) checkCondition(p, n.id + " -> '" + ch.text + "'", c);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        walk(start, seen);
        for (String id : nodes.keySet()) if (!seen.contains(id)) p.add("Предупреждение: реплика '" + id + "' недостижима");
        return p;
    }

    private void checkCondition(List<String> p, String where, Call c) {
        if (!DialogueApi.hasCondition(c.id)) p.add(where + ": неизвестное условие '" + c.id + "'");
        Object of = c.args.get("of");
        if (of instanceof List) for (Call inner : calls(of, "if")) checkCondition(p, where, inner);
    }

    private void walk(String id, Set<String> seen) {
        Node n = nodes.get(id);
        if (n == null || !seen.add(id)) return;
        if (n.next != null) walk(n.next, seen);
        for (Choice c : n.choices) if (c.next != null) walk(c.next, seen);
    }
}
