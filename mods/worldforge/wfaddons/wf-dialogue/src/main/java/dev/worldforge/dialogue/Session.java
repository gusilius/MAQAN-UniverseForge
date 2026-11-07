package dev.worldforge.dialogue;

import java.util.ArrayList;
import java.util.List;

/** Один разговор: показывает текущую реплику, отдаёт доступные ответы, применяет эффекты. Не зависит от Minecraft. */
public final class Session {
    /** Что показать игроку. */
    public static final class View {
        public final String speaker, text;
        public final List<String> choices;
        public final boolean cont, end;      // cont: единственная кнопка «Далее»; end: реплика последняя (кнопка «Закрыть»)

        View(String speaker, String text, List<String> choices, boolean cont, boolean end) {
            this.speaker = speaker; this.text = text; this.choices = choices; this.cont = cont; this.end = end;
        }
    }

    private final Dialogue dialogue;
    private final Ctx ctx;
    private Dialogue.Node node;
    private List<Dialogue.Choice> visible = List.of();
    private boolean ended;
    private int steps;

    public Session(Dialogue dialogue, Ctx ctx) {
        this.dialogue = dialogue;
        this.ctx = ctx;
        enter(dialogue.start);
    }

    private void enter(String id) {
        Dialogue.Node n = dialogue.nodes.get(id);
        if (n == null || ++steps > 200) { ended = true; node = null; return; }       // защита от пустых ссылок и бесконечных циклов
        node = n;
        for (Call c : n.effects) DialogueApi.run(c, ctx);
        visible = new ArrayList<>();
        for (Dialogue.Choice ch : n.choices) if (DialogueApi.all(rawOf(ch), ctx)) visible.add(ch);
    }

    private static Object rawOf(Dialogue.Choice ch) {
        List<Object> l = new ArrayList<>();
        for (Call c : ch.conditions) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(c.args);
            m.put("if", c.id);
            l.add(m);
        }
        return l;
    }

    public boolean ended() { return ended; }

    public View view() {
        if (ended || node == null) return null;
        List<String> texts = new ArrayList<>();
        for (Dialogue.Choice c : visible) texts.add(template(c.text));
        boolean noChoices = node.choices.isEmpty();
        return new View(template(node.speaker), template(node.text), texts, noChoices && node.next != null, noChoices && node.next == null);
    }

    /** Выбрать ответ по номеру в списке view().choices; для «Далее» и «Закрыть» номер 0. */
    public void choose(int i) {
        if (ended || node == null) return;
        if (node.choices.isEmpty()) {
            if (node.next == null) { ended = true; return; }
            enter(node.next);
            return;
        }
        if (i < 0 || i >= visible.size()) throw new IllegalArgumentException("Нет ответа с номером " + i);
        Dialogue.Choice c = visible.get(i);
        for (Call e : c.effects) DialogueApi.run(e, ctx);
        if (c.next == null) { ended = true; return; }
        enter(c.next);
    }

    private String template(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int close = ch == '{' ? s.indexOf('}', i) : -1;
            if (close > 0) {
                String key = s.substring(i + 1, close);
                String v = ctx.vars.get(key);
                out.append(v == null ? "{" + key + "}" : v);
                i = close;
            } else out.append(ch);
        }
        return out.toString();
    }
}
