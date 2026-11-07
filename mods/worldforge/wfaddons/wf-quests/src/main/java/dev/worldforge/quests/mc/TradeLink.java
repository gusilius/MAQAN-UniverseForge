package dev.worldforge.quests.mc;

import dev.worldforge.trade.mc.TradeMod;

/** Мягкая связь с wf_trade: события мира (чума, неурожай, обвал шахты) меняют выпуск товаров на рынке государства. */
final class TradeLink {
    private TradeLink() {}

    static void shock(String realm, String good, double factor, int days) { TradeMod.TRADE.shock(realm, good, factor, days); }
}
