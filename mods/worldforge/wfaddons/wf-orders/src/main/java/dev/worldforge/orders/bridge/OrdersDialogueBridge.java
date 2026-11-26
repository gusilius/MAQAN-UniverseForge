package dev.worldforge.orders.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.orders.OrgDef;
import dev.worldforge.orders.Orders;

import java.util.*;
import java.util.function.IntSupplier;

/**
 * Мост к диалогам (мягкая зависимость). Организация собеседника берётся из переменной npc_org (её задаёт мод людей по props.org),
 * игрок - из player_id. Условия: org_member, org_rank_at_least, org_can_join, org_offer_exists, org_offer_accepted, org_petition_ok.
 * Эффекты: org_join, org_leave, org_donate, org_status, org_offers, org_accept, org_complete, org_petition.
 */
public final class OrdersDialogueBridge {
    private OrdersDialogueBridge() {}

    public static Orders orders = new Orders();
    public static IntSupplier today = () -> 0;
    public static Random rng = new Random();

    private static String pl(Ctx c) { return c.vars.getOrDefault("player_id", ""); }

    private static String org(Ctx c, Map<String, Object> a) { return a.get("org") != null ? String.valueOf(a.get("org")) : c.vars.getOrDefault("npc_org", ""); }

    private static int num(Object o, int d) { return o == null ? d : o instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(o).trim()); }

    private static Orders.Contract nth(Ctx c, Map<String, Object> a) {
        List<Orders.Contract> l = orders.contractsOf(pl(c), org(c, a));
        int i = num(a.get("n"), 1) - 1;
        return i >= 0 && i < l.size() ? l.get(i) : null;
    }

    private static void status(Ctx c, String org) {
        OrgDef d = orders.defs.get(org);
        if (d == null) return;
        Orders.State s = orders.state(org);
        c.var("org_name", d.name).var("org_dues", String.valueOf(d.dues)).var("org_join_gold", String.valueOf(d.joinGold)).var("org_power", String.valueOf((int) s.power));
        Orders.Member m = orders.member(pl(c), org);
        if (m == null) { c.var("org_rank", "не состоите").var("org_influence", "0").var("org_next", ""); return; }
        c.var("org_rank", d.ranks.get(m.rank).name()).var("org_influence", String.valueOf(m.influence));
        c.var("org_next", m.rank + 1 < d.ranks.size() ? d.ranks.get(m.rank + 1).name() + " (" + d.ranks.get(m.rank + 1).influence() + ")" : "высшее звание");
    }

    private static void list(Ctx c, String org) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Orders.Contract k : orders.contractsOf(pl(c), org)) {
            sb.append(i++).append(". ").append(k.text).append(" Награда: ").append(k.reward).append(" зол., влияние +").append(k.influence);
            if (k.type.equals("deed")) sb.append(" [").append(k.progress).append('/').append(k.count).append(']');
            sb.append(k.accepted ? " (взято)" : "").append('\n');
        }
        c.var("org_contracts", sb.length() == 0 ? "Сейчас заданий нет." : sb.toString().trim());
    }

    public static void register() {
        DialogueApi.registerCondition("org_member", (c, a) -> orders.member(pl(c), org(c, a)) != null);
        DialogueApi.registerCondition("org_rank_at_least", (c, a) -> orders.rankOf(pl(c), org(c, a)) >= num(a.get("rank"), 0));
        DialogueApi.registerCondition("org_can_join", (c, a) -> orders.canJoin(pl(c), org(c, a), today.getAsInt()) == null);
        DialogueApi.registerCondition("org_offer_exists", (c, a) -> nth(c, a) != null);
        DialogueApi.registerCondition("org_offer_accepted", (c, a) -> { Orders.Contract k = nth(c, a); return k != null && k.accepted; });
        DialogueApi.registerCondition("org_offer_open", (c, a) -> { Orders.Contract k = nth(c, a); return k != null && !k.accepted; });
        DialogueApi.registerCondition("org_petition_ok", (c, a) -> orders.canPetition(pl(c), org(c, a), String.valueOf(a.get("id")), today.getAsInt()) == null);

        DialogueApi.registerEffect("org_status", (c, a) -> {
            status(c, org(c, a)); list(c, org(c, a));
            if (Boolean.TRUE.equals(a.get("clear"))) c.var("reply", ""); else c.vars.putIfAbsent("reply", "");
        });
        DialogueApi.registerEffect("org_join", (c, a) -> {
            String o = org(c, a);
            boolean ok = orders.join(pl(c), o, today.getAsInt());
            c.var("reply", ok ? "Добро пожаловать в «" + orders.defs.get(o).name + "»." : orders.lastError);
            status(c, o);
        });
        DialogueApi.registerEffect("org_leave", (c, a) -> { orders.leave(pl(c), org(c, a), today.getAsInt()); c.var("reply", "Как знаете. Двери открыты, но не сразу."); status(c, org(c, a)); });
        DialogueApi.registerEffect("org_donate", (c, a) -> {
            boolean ok = orders.donate(pl(c), org(c, a), num(a.get("value"), 10));
            c.var("reply", ok ? "Ваш вклад не забудется." : orders.lastError);
            status(c, org(c, a));
        });
        DialogueApi.registerEffect("org_offers", (c, a) -> { orders.offers(pl(c), org(c, a), today.getAsInt(), rng); list(c, org(c, a)); c.var("reply", ""); });
        DialogueApi.registerEffect("org_accept", (c, a) -> {
            Orders.Contract k = nth(c, a);
            c.var("reply", k != null && orders.accept(pl(c), k.id) ? "Договорились: " + k.text : "Такого задания нет.");
            list(c, org(c, a));
        });
        DialogueApi.registerEffect("org_complete", (c, a) -> {
            Orders.Contract k = nth(c, a);
            String o = org(c, a);
            String before = orders.rankName(pl(c), o);
            boolean ok = k != null && orders.complete(pl(c), k.id, today.getAsInt());
            String after = orders.rankName(pl(c), o);
            c.var("reply", ok ? "Работа сделана." + (after.equals(before) ? "" : " Поздравляю с новым званием: " + after + "!") : orders.lastError);
            status(c, o); list(c, o);
        });
        DialogueApi.registerEffect("org_petition", (c, a) -> {
            String msg = orders.petition(pl(c), org(c, a), String.valueOf(a.get("id")), today.getAsInt());
            c.var("reply", msg != null ? msg : orders.lastError);
            status(c, org(c, a));
        });
    }
}
