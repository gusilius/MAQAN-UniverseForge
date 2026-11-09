package dev.worldforge.people.mc;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.RoleDefs;
import dev.worldforge.people.bridge.DialogueBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/**
 * Мягкий мост к wf-dialogue. Этот класс трогается только если ModList сообщает,
 * что wf_dialogue загружен. Рефлексия сохраняет необязательную зависимость.
 */
final class DialogueHook {
    private DialogueHook() {}

    static void register() {
        DialogueBridge.register();
    }

    static boolean hasSession(ServerPlayer player) {
        try {
            Class<?> dialogue = Class.forName("dev.worldforge.dialogue.mc.DialogueMc");
            return Boolean.TRUE.equals(dialogue.getMethod("hasSession", ServerPlayer.class).invoke(null, player));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Открывает диалог с памятью канонического Character и привязкой к физическому телу NPC. */
    static boolean open(ServerPlayer player, WFNpc body, Character npc, String dialogueId,
                        RoleDefs definitions, TitleData data, Set<String> flags) {
        try {
            Ctx context = DialogueBridge.context(npc, definitions, data,
                    player.getGameProfile().getName(), player.getUUID().toString(), flags);
            Class<?> dialogue = Class.forName("dev.worldforge.dialogue.mc.DialogueMc");
            Method method = dialogue.getMethod("openCharacterAt", ServerPlayer.class, String.class,
                    Map.class, Map.class, Vec3.class, double.class);
            Object result = method.invoke(null, player, dialogueId, context.vars, npc.props,
                    body == null ? null : body.position(), 8.0);
            return Boolean.TRUE.equals(result);
        } catch (Throwable error) {
            PeopleMod.LOGGER.error("wf_people: не удалось открыть диалог '{}': {}", dialogueId, error.toString());
            return false;
        }
    }
}
