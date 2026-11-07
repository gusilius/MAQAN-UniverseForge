package dev.worldforge.dialogue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Реестры условий и эффектов. Другие аддоны добавляют свои (например, репутация регистрирует rep_at_least и rep_add),
 * диалоги в JSON ссылаются на них по id. Неизвестный id находит {@link Dialogue#validate()}.
 */
public final class DialogueApi {
    public static final int API_VERSION = 1;
    private static final Map<String, Condition> CONDITIONS = new TreeMap<>();
    private static final Map<String, Effect> EFFECTS = new TreeMap<>();
    private static boolean builtins;

    private DialogueApi() {}

    public static synchronized void registerCondition(String id, Condition c) {
        if (CONDITIONS.containsKey(id)) throw new IllegalStateException("Условие уже зарегистрировано: " + id);
        CONDITIONS.put(id, c);
    }

    public static synchronized void registerEffect(String id, Effect e) {
        if (EFFECTS.containsKey(id)) throw new IllegalStateException("Эффект уже зарегистрирован: " + id);
        EFFECTS.put(id, e);
    }

    public static synchronized boolean hasCondition(String id) { ensure(); return CONDITIONS.containsKey(id); }

    public static synchronized boolean hasEffect(String id) { ensure(); return EFFECTS.containsKey(id); }

    public static synchronized java.util.Set<String> conditionIds() { ensure(); return new java.util.TreeSet<>(CONDITIONS.keySet()); }

    public static synchronized java.util.Set<String> effectIds() { ensure(); return new java.util.TreeSet<>(EFFECTS.keySet()); }

    public static synchronized void resetForTests() { CONDITIONS.clear(); EFFECTS.clear(); builtins = false; }

    static synchronized boolean test(Call c, Ctx ctx) {
        ensure();
        Condition cond = CONDITIONS.get(c.id);
        if (cond == null) return false;                     // неизвестное условие не выполняется: безопаснее, чем показать лишнее
        return cond.test(ctx, c.args);
    }

    static synchronized void run(Call c, Ctx ctx) {
        ensure();
        Effect e = EFFECTS.get(c.id);
        if (e != null) e.run(ctx, c.args);
    }

    @SuppressWarnings("unchecked")
    static boolean all(Object calls, Ctx ctx) {
        for (Call c : Dialogue.calls(calls, "if")) if (!test(c, ctx)) return false;
        return true;
    }

    private static void ensure() {
        if (builtins) return;
        builtins = true;
        CONDITIONS.put("flag", (ctx, a) -> ctx.flags.contains(String.valueOf(a.get("name"))));
        CONDITIONS.put("not", (ctx, a) -> !all(a.get("of"), ctx));
        CONDITIONS.put("all", (ctx, a) -> all(a.get("of"), ctx));
        CONDITIONS.put("any", (ctx, a) -> {
            List<Call> l = Dialogue.calls(a.get("of"), "if");
            for (Call c : l) if (test(c, ctx)) return true;
            return false;
        });
        CONDITIONS.put("var_equals", (ctx, a) -> String.valueOf(a.get("value")).equals(ctx.vars.get(String.valueOf(a.get("var")))));
        EFFECTS.put("set_flag", (ctx, a) -> ctx.flags.add(String.valueOf(a.get("name"))));
        EFFECTS.put("clear_flag", (ctx, a) -> ctx.flags.remove(String.valueOf(a.get("name"))));
    }
}
