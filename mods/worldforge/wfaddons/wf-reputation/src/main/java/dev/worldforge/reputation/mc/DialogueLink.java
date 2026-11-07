package dev.worldforge.reputation.mc;

import dev.worldforge.dialogue.mc.DialogueMc;
import dev.worldforge.reputation.bridge.DialogueBridge;

/** Подключение к wf_dialogue. Этот класс (и DialogueBridge) трогается только если мод wf_dialogue загружен. */
final class DialogueLink {
    private DialogueLink() {}

    static void register() {
        DialogueBridge.register();
        DialogueMc.addContextProvider((player, ctx) -> {
            var store = ReputationMod.store();
            if (store != null) ctx.service(dev.worldforge.reputation.ReputationStore.class, store);
        });
    }
}
