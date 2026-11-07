package dev.worldforge.dialogue.mc.client;

import dev.worldforge.dialogue.mc.DialogueNet;
import net.minecraft.client.Minecraft;

/** Приём пакетов диалога на клиенте (главный поток). Грузится только на стороне клиента. */
public final class DialogueClient {
    private DialogueClient() {}

    public static void onView(DialogueNet.OpenView v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen ds) ds.update(v);
        else mc.setScreen(new DialogueScreen(v));
    }

    public static void onClose(DialogueNet.Close c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof DialogueScreen ds && ds.sessionId() == c.sessionId) mc.setScreen(null);   // setScreen не вызывает onClose(): повторного запроса не будет
    }
}
