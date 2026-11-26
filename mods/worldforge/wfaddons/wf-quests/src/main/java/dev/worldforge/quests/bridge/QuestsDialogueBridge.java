package dev.worldforge.quests.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.quests.Quests;

import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;

/**
 * Мост к диалогам (мягкая зависимость). Дающий строится из переменных контекста людей: npc_id, npc_name, npc_roles (через запятую),
 * npc_org, npc_realm_id; игрок - player_id. Условия: quest_offers_exist, quest_interactable, quest_active_here. Эффекты: quest_offers (заполняет quest_list),
 * quest_accept (n), quest_interact (сдать/доложить).
 */
public final class QuestsDialogueBridge {
    private QuestsDialogueBridge() {}

    public static Quests quests;
    public static IntSupplier today = () -> 0;
    /** Вид организации по id (гильдия, орден...) - подставляет склейка. */
    public static UnaryOperator<String> orgKind = o -> "";

    private static String pl(Ctx c) { return c.vars.getOrDefault("player_id", ""); }

    public static Quests.Giver giver(Ctx c) {
        Set<String> roles = new HashSet<>();
        for (String r : c.vars.getOrDefault("npc_roles", "").split("[,; ]+")) if (!r.isBlank()) roles.add(r.trim());
        String org = c.vars.getOrDefault("npc_org", "");
        return new Quests.Giver(c.vars.getOrDefault("npc_id", ""), c.vars.getOrDefault("npc_name", "Собеседник"), roles, org, org.isEmpty() ? "" : orgKind.apply(org), c.vars.getOrDefault("npc_realm_id", ""));
    }

    private static void list(Ctx c, List<Quests.Offer> offers) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Quests.Offer o : offers) sb.append(i++).append(". ").append(o.name()).append(" - ").append(o.text()).append('\n');
        c.var("quest_list", sb.length() == 0 ? "Сейчас поручений нет." : sb.toString().trim());
    }

    public static void register() {
        DialogueApi.registerCondition("quest_offers_exist", (c, a) -> !quests.offers(pl(c), giver(c), today.getAsInt()).isEmpty());
        DialogueApi.registerCondition("quest_interactable", (c, a) -> quests.canInteract(pl(c), giver(c)));
        DialogueApi.registerCondition("quest_active_here", (c, a) -> quests.canInteract(pl(c), giver(c)));

        DialogueApi.registerEffect("quest_offers", (c, a) -> { list(c, quests.offers(pl(c), giver(c), today.getAsInt())); c.var("reply", ""); });
        DialogueApi.registerEffect("quest_accept", (c, a) -> {
            Quests.Giver g = giver(c);
            List<Quests.Offer> l = quests.offers(pl(c), g, today.getAsInt());
            int i = (a.get("n") instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(a.getOrDefault("n", "1")).trim())) - 1;
            if (i < 0 || i >= l.size()) { c.var("reply", "Такого поручения нет."); return; }
            Quests.Active act = quests.accept(pl(c), g, l.get(i).key(), today.getAsInt());
            c.var("reply", act != null ? "Договорились: " + l.get(i).name() + ". Подробности - в журнале (клавиша J)." : quests.lastError);
            list(c, quests.offers(pl(c), g, today.getAsInt()));
        });
        DialogueApi.registerEffect("quest_interact", (c, a) -> {
            List<String> msg = quests.interact(pl(c), giver(c), today.getAsInt());
            c.var("reply", msg.isEmpty() ? "Мне нечего вам сказать по этому делу." : String.join("\n", msg));
        });
    }
}
