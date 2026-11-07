package dev.worldforge.quests.mc;

import dev.worldforge.quests.bridge.QuestsDialogueBridge;

/** Подключение к wf_dialogue; класс трогается только если мод загружен. */
final class DialogueLink {
    private DialogueLink() {}

    static void register() {
        QuestsDialogueBridge.quests = QuestsMod.QUESTS;
        QuestsDialogueBridge.today = QuestsMod::day;
        QuestsDialogueBridge.orgKind = o -> {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_orders")) return "";
            return OrdersLink.kind(o);
        };
        QuestsDialogueBridge.register();
    }
}
