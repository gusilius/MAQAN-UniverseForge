package dev.worldforge.quests.mc;

import dev.worldforge.orders.OrgDef;
import dev.worldforge.orders.mc.OrdersMod;

/** Мягкая связь с wf_orders: звание, влияние, вид организации. Трогается только если мод загружен. */
final class OrdersLink {
    private OrdersLink() {}

    static String kind(String org) { OrgDef d = OrdersMod.ORDERS.defs.get(org); return d == null ? "" : d.kind; }
    static int rank(String player, String org) { return OrdersMod.ORDERS.rankOf(player, org); }
    static void influence(String player, String org, int n) { OrdersMod.ORDERS.addInfluence(player, org, n); }
}
