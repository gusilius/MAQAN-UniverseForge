package dev.worldforge.reputation.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.reputation.ReputationStore;
import dev.worldforge.reputation.Standing;

import java.util.Map;

/**
 * Мост к моду диалогов (мягкая зависимость: класс загружается, только если wf_dialogue есть). Регистрирует в диалогах:
 * условия rep_at_least, rep_at_most, standing_is и эффект rep_add. Хранилище берётся из Ctx.service(ReputationStore.class),
 * игрок из переменной player_id.
 */
public final class DialogueBridge {
    private DialogueBridge() {}

    public static void register() {
        DialogueApi.registerCondition("rep_at_least", (c, a) -> value(c, a) >= num(a, "value"));
        DialogueApi.registerCondition("rep_at_most", (c, a) -> value(c, a) <= num(a, "value"));
        DialogueApi.registerCondition("standing_is", (c, a) -> {
            ReputationStore s = store(c);
            return s.standing(player(c), target(a)) == Standing.byName(String.valueOf(a.get("name")));
        });
        // NPC reactions use only reputation personally known to that speaker.
        DialogueApi.registerCondition("rep_known", (c, a) ->
                store(c).known(resident(c), player(c), scope(a), scopeId(a)) != null);
        DialogueApi.registerCondition("known_rep_at_least", (c, a) -> {
            ReputationStore.KnownReputation known = store(c).known(resident(c), player(c), scope(a), scopeId(a));
            return known != null && known.value >= num(a, "value");
        });
        DialogueApi.registerCondition("known_rep_at_most", (c, a) -> {
            ReputationStore.KnownReputation known = store(c).known(resident(c), player(c), scope(a), scopeId(a));
            return known != null && known.value <= num(a, "value");
        });
        DialogueApi.registerCondition("known_standing_is", (c, a) -> {
            ReputationStore.KnownReputation known = store(c).known(resident(c), player(c), scope(a), scopeId(a));
            return known != null && Standing.of(known.value) == Standing.byName(String.valueOf(a.get("name")));
        });
        DialogueApi.registerEffect("rep_add", (c, a) -> store(c).add(player(c), target(a), num(a, "value"), a.get("reason") == null ? "диалог" : String.valueOf(a.get("reason"))));
    }

    private static ReputationStore store(Ctx c) {
        ReputationStore s = c.service(ReputationStore.class);
        if (s == null) throw new IllegalStateException("В разговоре нет ReputationStore");
        return s;
    }

    private static String resident(Ctx c) {
        String id = c.vars.get("npc_id");
        if (id == null || id.isBlank()) throw new IllegalStateException("В разговоре нет npc_id");
        return id;
    }

    private static ReputationStore.Scope scope(Map<String, Object> a) {
        return ReputationStore.Scope.valueOf(String.valueOf(a.get("scope")).toUpperCase(java.util.Locale.ROOT));
    }

    private static String scopeId(Map<String, Object> a) {
        Object id = a.get("scope_id");
        return id == null ? "" : String.valueOf(id);
    }

    private static String player(Ctx c) {
        String p = c.vars.get("player_id");
        if (p == null) throw new IllegalStateException("В разговоре нет переменной player_id");
        return p;
    }

    private static String target(Map<String, Object> a) { return String.valueOf(a.get("target")); }

    private static int num(Map<String, Object> a, String k) { return ((Number) a.get(k)).intValue(); }

    private static int value(Ctx c, Map<String, Object> a) { return store(c).get(player(c), target(a)); }
}
