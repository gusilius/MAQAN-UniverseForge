package dev.worldforge.trade.bridge;

import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.DialogueApi;
import dev.worldforge.trade.Trade;
import dev.worldforge.trade.TradeDefs;

import java.util.*;

/**
 * Мост к диалогам (мягкая зависимость): торговля с жителями. Рынок - государство собеседника (npc_realm_id), игрок - player_id.
 * Условие: trade_market_exists. Эффекты: trade_prices (что в цене), trade_route (куда выгодно везти), trade_sell и trade_buy (good, qty - личная торговля предметами).
 * Результат кладётся в переменную reply.
 */
public final class TradeDialogueBridge {
    private TradeDialogueBridge() {}

    public static Trade trade;

    private static String pl(Ctx c) { return c.vars.getOrDefault("player_id", ""); }
    private static String realm(Ctx c) { return c.vars.getOrDefault("npc_realm_id", ""); }

    private static String name(String good) { TradeDefs.Good g = trade.defs.goods.get(good); return g == null ? good : g.name(); }
    private static String num(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    /** Дефицитные и дешёвые товары рынка: цена к базовой. */
    public static String prices(String realm) {
        List<TradeDefs.Good> gs = new ArrayList<>();
        for (TradeDefs.Good g : trade.defs.goods.values()) if (!g.contraband() && trade.consumption(realm, g) >= 0.4) gs.add(g);   // ходовые товары: у редкостей цена мало что говорит
        gs.sort(Comparator.comparingDouble(g -> trade.price(realm, g.id()) / g.base()));
        StringBuilder sb = new StringBuilder("В избытке (дёшево): ");
        for (int i = 0; i < 4 && i < gs.size(); i++) sb.append(i > 0 ? ", " : "").append(gs.get(i).name()).append(" ").append(num(trade.price(realm, gs.get(i).id())));
        sb.append("\nДорого (нужно): ");
        for (int i = 0; i < 4 && i < gs.size(); i++) { TradeDefs.Good g = gs.get(gs.size() - 1 - i); sb.append(i > 0 ? ", " : "").append(g.name()).append(" ").append(num(trade.price(realm, g.id()))); }
        return sb.toString();
    }

    public static String route(String realm) {
        Object[] r = trade.bestRoute(realm, 0.1);
        if (r == null) return "Сейчас выгодных путей отсюда нет: цены у соседей слишком похожи.";
        String good = (String) r[1], to = (String) r[0];
        return "Везите " + name(good) + " в " + to + ": там " + num(trade.price(to, good)) + " против " + num(trade.price(realm, good)) + " здесь (выгода около " + Math.round((double) r[2] * 100) + "% после пошлины).";
    }

    public static String deal(String player, String realm, String good, int qty, boolean sell) {
        boolean ok = sell ? trade.playerSell(player, realm, good, qty) : trade.playerBuy(player, realm, good, qty);
        if (!ok) return trade.lastError;
        return (sell ? "Продано: " : "Куплено: ") + qty + " x " + name(good) + ". Цена теперь " + num(trade.price(realm, good)) + ".";
    }

    private static int qty(Map<String, Object> a) { Object q = a.get("qty"); return q instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(q == null ? "1" : q).trim()); }

    public static void register() {
        DialogueApi.registerCondition("trade_market_exists", (c, a) -> trade != null && !realm(c).isEmpty() && !trade.defs.goods.isEmpty());
        DialogueApi.registerEffect("trade_prices", (c, a) -> c.var("reply", prices(realm(c))));
        DialogueApi.registerEffect("trade_route", (c, a) -> c.var("reply", route(realm(c))));
        DialogueApi.registerEffect("trade_sell", (c, a) -> c.var("reply", deal(pl(c), realm(c), String.valueOf(a.get("good")), qty(a), true)));
        DialogueApi.registerEffect("trade_buy", (c, a) -> c.var("reply", deal(pl(c), realm(c), String.valueOf(a.get("good")), qty(a), false)));
    }
}
