package dev.worldforge.people.mc;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.RoleDefs;
import dev.worldforge.people.bridge.DialogueBridge;
import net.minecraft.server.level.ServerPlayer;\nimport net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Мягкий мост к wf-dialogue. Этот класс трогается ТОЛЬКО если ModList.isLoaded("wf_dialogue").
 * DialogueMc вызывается через рефлексию, чтобы не зависеть от него при компиляции.
 */
final class DialogueHook {
    private DialogueHook() {}

    static void register() {
        DialogueBridge.register();
    }

    /** true, если вызов дошёл до DialogueMc.open без исключения. */
    static boolean open(ServerPlayer player, WFNpc body, Character npc, String dialogueId, RoleDefs defs, TitleData d, Set<String> flags) {
        try {
            Ctx ctx = DialogueBridge.context(npc, defs, d, player.getGameProfile().getName(), player.getUUID().toString(), flags);
            Class<?> c = Class.forName("dev.worldforge.dialogue.mc.DialogueMc");
            Method m = c.getMethod("openCharacter", ServerPlayer.class, String.class, Map.class, Map.class);
            m.invoke(null, player, dialogueId, ctx.vars, npc.props);
            return true;
        } catch (Throwable t) {
            PeopleMod.LOGGER.error("wf_people: не удалось открыть диалог '{}': {}", dialogueId, t.toString());
            return false;
        }
    }
}
