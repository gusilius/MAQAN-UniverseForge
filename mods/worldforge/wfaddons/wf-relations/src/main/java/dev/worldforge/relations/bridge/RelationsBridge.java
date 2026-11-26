package dev.worldforge.relations.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.bridge.DialogueBridge;
import dev.worldforge.relations.Relations;

import java.util.*;

/**
 * Мост к диалогам (мягкая зависимость): условия и эффекты отношений (npc_aff_at_least, npc_gift, npc_marry, delegate и др.)
 * и переменные контекста hearts, mood, request, neighbor1..3. Грузится, только если есть мод wf_dialogue.
 */
public final class RelationsBridge {
    private RelationsBridge() {}

    private static void extend(Ctx ctx, Character npc, String playerId, TitleData d) {
        Relations.Bond bond = relations.bond(playerId, npc.id);
        ctx.var("hearts", bond.heartsText()).var("mood", bond.mood()).var("affinity", String.valueOf(bond.affinity)).var("love", String.valueOf(bond.love)).var("reply", "");
        Relations.Request rq = relations.openRequest(npc.id);
        ctx.var("request", rq == null ? "" : rq.text);
        String pr = playerId == null ? "" : relations.hooks.realmOf(playerId);
        List<String> nb = pr.isEmpty() ? List.of() : relations.hooks.neighbors(pr);
        for (int i = 0; i < 3; i++) {
            TitleData.Realm nr = i < nb.size() ? d.realms.get(nb.get(i)) : null;
            ctx.var("neighbor" + (i + 1), nr == null ? "" : nr.id).var("neighbor" + (i + 1) + "_name", nr == null ? "" : nr.name);
        }
    }

    /** Хозяин состояния (мод людей) задаёт всё это до открытия диалога. */
    public static Relations relations = new Relations();
    public static java.util.function.Function<String, Character> npcs = id -> null;
    public static java.util.function.IntSupplier today = () -> 0;
    public static Random rng = new Random();

    private static Character npc(Ctx c) { return npcs.apply(c.vars.getOrDefault("npc_id", "")); }

    private static String pl(Ctx c) { return c.vars.getOrDefault("player_id", ""); }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : o == null ? 0 : Integer.parseInt(String.valueOf(o).trim()); }

    private static void refresh(Ctx c, Character n) {
        Relations.Bond b = relations.bond(pl(c), n.id);
        c.var("hearts", b.heartsText()).var("mood", b.mood()).var("affinity", String.valueOf(b.affinity)).var("love", String.valueOf(b.love));
        Relations.Request r = relations.openRequest(n.id);
        c.var("request", r == null ? "" : r.text);
    }

    public static void register() {
        DialogueBridge.EXTENDERS.add(RelationsBridge::extend);
        DialogueApi.registerCondition("npc_aff_at_least", (c, a) -> { Character n = npc(c); return n != null && relations.bond(pl(c), n.id).affinity >= num(a.get("value")); });
        DialogueApi.registerCondition("npc_love_at_least", (c, a) -> { Character n = npc(c); return n != null && relations.bond(pl(c), n.id).love >= num(a.get("value")); });
        DialogueApi.registerCondition("npc_ready", (c, a) -> { Character n = npc(c); return n != null && relations.ready(pl(c), n, String.valueOf(a.get("kind")), today.getAsInt()); });
        DialogueApi.registerCondition("npc_dating", (c, a) -> { Character n = npc(c); return n != null && relations.bond(pl(c), n.id).dating; });
        DialogueApi.registerCondition("npc_married_to_you", (c, a) -> { Character n = npc(c); return n != null && relations.bond(pl(c), n.id).married; });
        DialogueApi.registerCondition("npc_can_date", (c, a) -> { Character n = npc(c); return n != null && relations.canDate(pl(c), n) == null; });
        DialogueApi.registerCondition("npc_can_marry", (c, a) -> { Character n = npc(c); return n != null && relations.canMarry(pl(c), n) == null; });
        DialogueApi.registerCondition("npc_can_hire", (c, a) -> { Character n = npc(c); return n != null && relations.canHire(pl(c), n) == null; });
        DialogueApi.registerCondition("npc_employed_by_you", (c, a) -> { Character n = npc(c); return n != null && relations.bond(pl(c), n.id).employed; });
        DialogueApi.registerCondition("npc_has_request", (c, a) -> { Character n = npc(c); return n != null && relations.openRequest(n.id) != null; });
        DialogueApi.registerCondition("npc_request_ready", (c, a) -> { Character n = npc(c); return n != null && relations.requestReady(pl(c), n.id, today.getAsInt()); });
        DialogueApi.registerCondition("player_has_realm", (c, a) -> !relations.hooks.realmOf(pl(c)).isEmpty());
        DialogueApi.registerCondition("npc_realm_is_yours", (c, a) -> {
            String r = relations.hooks.realmOf(pl(c));
            return !r.isEmpty() && r.equals(c.vars.get("npc_realm_id"));
        });

        DialogueApi.registerEffect("npc_refresh", (c, a) -> { Character n = npc(c); if (n != null) refresh(c, n); });
        DialogueApi.registerEffect("npc_interact", (c, a) -> {
            Character n = npc(c);
            if (n == null) return;
            Relations.Result r = relations.interact(pl(c), n, String.valueOf(a.get("kind")), today.getAsInt(), rng);
            c.var("reply", r.line).var("delta", (r.delta > 0 ? "+" : "") + r.delta);
            refresh(c, n);
        });
        DialogueApi.registerEffect("npc_gift", (c, a) -> {
            Character n = npc(c);
            if (n == null) return;
            int v = a.get("value") != null ? num(a.get("value")) : relations.hooks.takeGift(pl(c));
            Relations.Result r = relations.gift(pl(c), n, v, today.getAsInt());
            c.var("reply", r.line).var("delta", (r.delta > 0 ? "+" : "") + r.delta);
            refresh(c, n);
        });
        DialogueApi.registerEffect("npc_date", (c, a) -> { Character n = npc(c); if (n != null) { String why = relations.canDate(pl(c), n); if (why == null) relations.startDating(pl(c), n); c.var("reply", why == null ? "Я согласен(на). Давайте узнаем друг друга лучше." : why); refresh(c, n); } });
        DialogueApi.registerEffect("npc_marry", (c, a) -> {
            Character n = npc(c);
            if (n == null) return;
            String why = relations.canMarry(pl(c), n);
            boolean ok = why == null && relations.marry(pl(c), n, today.getAsInt());
            if (ok) c.flags.add("wf_married");
            c.var("reply", ok ? "Да! Тысячу раз да. Пусть весь край знает." : why);
            refresh(c, n);
        });
        DialogueApi.registerEffect("npc_divorce", (c, a) -> { Character n = npc(c); if (n != null) { relations.divorce(pl(c), n); c.flags.remove("wf_married"); refresh(c, n); } });
        DialogueApi.registerEffect("npc_hire", (c, a) -> {
            Character n = npc(c);
            if (n == null) return;
            String why = relations.canHire(pl(c), n);
            boolean ok = why == null && relations.hire(pl(c), n, today.getAsInt());
            c.var("reply", ok ? "Я с вами. Жалованье - " + relations.bond(pl(c), n.id).wage + " монет в неделю." : why);
            refresh(c, n);
        });
        DialogueApi.registerEffect("npc_dismiss", (c, a) -> { Character n = npc(c); if (n != null) { relations.dismiss(pl(c), n); refresh(c, n); } });
        DialogueApi.registerEffect("npc_request_offer", (c, a) -> { Character n = npc(c); if (n != null) { relations.offerRequest(n, today.getAsInt(), rng); refresh(c, n); } });
        DialogueApi.registerEffect("npc_request_complete", (c, a) -> {
            Character n = npc(c);
            if (n == null) return;
            Relations.Result r = relations.completeRequest(pl(c), n, today.getAsInt());
            c.var("reply", r.line);
            refresh(c, n);
        });
        DialogueApi.registerEffect("npc_rumor", (c, a) -> { Character n = npc(c); if (n != null) c.var("reply", relations.rumor(n, rng)); });
        // Поручение советнику: действие карты от лица игрока в его государстве. Аргументы через |, как в /wfaction.
        DialogueApi.registerEffect("delegate", (c, a) -> {
            String realm = relations.hooks.realmOf(pl(c));
            if (realm.isEmpty()) { c.var("reply", "Вы не правите государством, и мне некому служить."); return; }
            List<String> args = new ArrayList<>();
            if (a.get("args") != null) for (String s : String.valueOf(a.get("args")).split("\\|")) args.add(s.replace("{realm_id}", realm).trim());
            String msg = relations.hooks.run(pl(c), String.valueOf(a.get("action")), realm, args);
            String ok = a.get("ok") == null ? "Будет исполнено." : String.valueOf(a.get("ok"));
            c.var("reply", msg != null ? ok + " " + msg : "Не получилось: " + relations.lastError);
        });
    }

}
