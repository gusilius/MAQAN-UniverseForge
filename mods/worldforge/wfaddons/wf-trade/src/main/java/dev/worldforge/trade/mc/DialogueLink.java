package dev.worldforge.trade.mc;

import dev.worldforge.trade.bridge.TradeDialogueBridge;

/** Подключение к wf_dialogue; класс трогается только если мод загружен. */
final class DialogueLink {
    private DialogueLink() {}

    static void register() {
        TradeDialogueBridge.trade = TradeMod.TRADE;
        TradeDialogueBridge.register();
    }
}
