package dev.worldforge.people.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;

import java.util.*;

/**
 * Мост к диалогам (мягкая зависимость). Строит контекст разговора с персонажем и регистрирует условия npc_role, npc_house, npc_trait.
 * Переменные текста: npc, npc_house, npc_role, npc_title, realm, player; для условий: npc_roles, npc_traits, player_id.
 */
public final class DialogueBridge {
    private DialogueBridge() {}

    /** Дополнение контекста разговора (аддоны поверх людей добавляют свои переменные: отношения и т.п.). */
    public interface ContextExtender { void extend(Ctx ctx, Character npc, String playerId, TitleData d); }

    public static final List<ContextExtender> EXTENDERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void register() {
        DialogueApi.registerCondition("npc_role", (c, a) -> split(c.vars.get("npc_roles")).contains(String.valueOf(a.get("role"))));
        DialogueApi.registerCondition("npc_house", (c, a) -> String.valueOf(a.get("name")).equals(c.vars.get("npc_house")));
        DialogueApi.registerCondition("npc_trait", (c, a) -> split(c.vars.get("npc_traits")).contains(String.valueOf(a.get("name"))));
        DialogueApi.registerCondition("has_var", (c, a) -> !c.vars.getOrDefault(String.valueOf(a.get("name")), "").isEmpty());
    }

    private static List<String> split(String s) { return s == null || s.isEmpty() ? List.of() : Arrays.asList(s.split(",")); }

    /** Id диалога: персональный (props.dialogue) или диалог первой роли, у которой он задан, иначе "default". */
    public static String dialogueId(Character c, RoleDefs defs) {
        Object own = c.props.get("dialogue");
        if (own != null) return String.valueOf(own);
        if (c.props.get("org") != null) return "wf_orders:master";      // представитель организации (аддон wf-orders)
        for (Character.Post p : c.posts) {
            Role r = defs.get(p.role);
            if (r != null && !r.dialogue.isEmpty()) return r.dialogue;
        }
        return "default";
    }

    public static Ctx context(Character npc, RoleDefs defs, TitleData d, String playerName, String playerId, Set<String> playerFlags) {
        Ctx ctx = new Ctx(playerFlags).var("npc", npc.name).var("npc_name", npc.name).var("npc_house", npc.house).var("player", playerName).var("player_id", playerId);
        List<String> roles = new ArrayList<>();
        String realm = "", title = "", realmId = "";
        for (Character.Post p : npc.posts) {
            roles.add(p.role);
            Role r = defs.get(p.role);
            if (title.isEmpty() && r != null) title = r.name;
            if (realm.isEmpty() && p.place.startsWith("realm:")) {
                TitleData.Realm re = d.realms.get(p.place.substring(6));
                if (re != null) { realm = re.name; realmId = re.id; }
            }
        }
        ctx.var("npc_roles", String.join(",", roles)).var("npc_traits", String.join(",", npc.traits)).var("npc_role", title)
                .var("npc_title", title.isEmpty() ? npc.name : title + " " + npc.name).var("realm", realm).var("npc_id", npc.id).var("npc_org", npc.props.get("org") == null ? "" : String.valueOf(npc.props.get("org"))).var("npc_realm_id", realmId);
        for (ContextExtender x : EXTENDERS) x.extend(ctx, npc, playerId, d);
        return ctx;
    }
}
