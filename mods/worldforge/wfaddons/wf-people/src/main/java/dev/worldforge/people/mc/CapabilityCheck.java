package dev.worldforge.people.mc;

import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Role;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Статическая проверка полномочий для других модов (wf-war зовёт её рефлексией: у него нет зависимости от wf-people).
 * Сигнатуры намеренно из примитивов и String:
 * <pre>
 *   Class.forName("dev.worldforge.people.mc.CapabilityCheck").getMethod("mayAct", String.class, String.class, String.class)
 *        .invoke(null, actor, realmId, "war")   // -> Boolean
 * </pre>
 * Актёр - имя или UUID игрока. Он имеет полномочие, если за ним закреплён персонаж (props.player == UUID игрока или имя),
 * живой, с должностью в этом государстве, роль которой содержит capability в unlocks. Ссылка игрок-персонаж ставится кем-то
 * другим (команда, сценарий); этот класс её только читает. Исключений не бросает: при любой неопределённости возвращает false.
 */
public final class CapabilityCheck {
    private CapabilityCheck() {}

    /** true, если актёр (игрок) держит в государстве роль с таким unlock. */
    public static boolean mayAct(String actor, String realmId, String capability) {
        try {
            if (actor == null || realmId == null || capability == null) return false;
            String uuid = uuidOf(actor);
            for (MapData m : WorldForgeMod.loadedMaps().values()) {
                if (m.titles == null || !m.titles.realms.containsKey(realmId)) continue;
                CharacterRegistry reg = PeopleMod.registry(m);
                if (reg == null) continue;
                String place = dev.worldforge.people.Courts.realmPlace(realmId);
                for (Character c : reg.all()) {
                    if (!c.alive || !isPlayer(c, actor, uuid)) continue;
                    for (Character.Post p : c.posts) {
                        Role r = PeopleMod.ROLES.get(p.role);
                        if (r != null && p.place.equals(place) && r.unlocks.contains(capability)) return true;
                    }
                }
            }
        } catch (Throwable t) {
            PeopleMod.LOGGER.error("wf_people: CapabilityCheck.mayAct: {}", t.toString());
        }
        return false;
    }

    /** true, если в государстве вообще есть живой носитель роли с таким unlock (без привязки к игроку). */
    public static boolean realmHas(String realmId, String capability) {
        try {
            for (MapData m : WorldForgeMod.loadedMaps().values()) {
                if (m.titles == null || !m.titles.realms.containsKey(realmId)) continue;
                CharacterRegistry reg = PeopleMod.registry(m);
                if (reg != null && reg.unlocked(PeopleMod.ROLES, dev.worldforge.people.Courts.realmPlace(realmId), capability)) return true;
            }
        } catch (Throwable t) {
            PeopleMod.LOGGER.error("wf_people: CapabilityCheck.realmHas: {}", t.toString());
        }
        return false;
    }

    private static boolean isPlayer(Character c, String actor, String uuid) {
        Object v = c.props.get("player");
        if (v == null) return false;
        String s = String.valueOf(v);
        return s.equalsIgnoreCase(actor) || s.equals(uuid);
    }

    private static String uuidOf(String actor) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return actor;
        ServerPlayer p = server.getPlayerList().getPlayerByName(actor);
        return p == null ? actor : p.getUUID().toString();
    }
}
