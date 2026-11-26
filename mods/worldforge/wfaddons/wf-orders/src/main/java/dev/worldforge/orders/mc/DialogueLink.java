package dev.worldforge.orders.mc;

import dev.worldforge.orders.bridge.OrdersDialogueBridge;

/** Подключение к wf_dialogue. Класс (и OrdersDialogueBridge) трогается только если мод wf_dialogue загружен. */
final class DialogueLink {
    private DialogueLink() {}

    static void register() {
        OrdersDialogueBridge.orders = OrdersMod.ORDERS;
        OrdersDialogueBridge.today = OrdersMod::day;
        OrdersDialogueBridge.register();
    }
}
