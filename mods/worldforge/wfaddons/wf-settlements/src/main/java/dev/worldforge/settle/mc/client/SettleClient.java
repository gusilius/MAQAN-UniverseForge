package dev.worldforge.settle.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.mc.SettleViews;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/** Клиентская сторона окон поселения: разобрать присланное и открыть экран либо обновить уже открытый. */
public final class SettleClient {
    private SettleClient() {}

    public static void register() {
        ClientViews.register(SettleViews.PANEL, SettleClient::open);
    }

    @SuppressWarnings("unchecked")
    private static void open(String payload) {
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            Defs d = new Defs();
            for (Object o : (List<Object>) m.get("defs")) d.add((String) o);
            Settlement s = Settlement.fromJson((String) m.get("s"), d);
            List<dev.worldforge.settle.CityRow> cities = m.get("cities") == null ? List.of() : dev.worldforge.settle.CityRow.fromJson((String) m.get("cities"));
            dev.worldforge.settle.TradeNetwork net = m.get("routes") == null ? new dev.worldforge.settle.TradeNetwork() : dev.worldforge.settle.TradeNetwork.fromJson((String) m.get("routes"));
            dev.worldforge.settle.SettlementMap map = m.get("map") instanceof String json ? dev.worldforge.settle.SettlementMap.fromJson(json) : new dev.worldforge.settle.SettlementMap();
            List<dev.worldforge.settle.ResidentRow> residents = m.get("residents") instanceof String json
                    ? dev.worldforge.settle.ResidentRow.fromJson(json) : List.of();
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof SettleScreen sc) {
                sc.setModel(s); sc.setCities(cities, net); sc.setMap(map); sc.setResidents(residents);
            } else {
                SettleScreen sc = new SettleScreen(s, ClientViews::command);
                sc.setCities(cities, net); sc.setMap(map); sc.setResidents(residents); mc.setScreen(sc);
            }
        } catch (RuntimeException e) {
            dev.worldforge.settle.mc.SettleMod.LOGGER.error("wf_settle: не удалось открыть окно: {}", e.toString());
        }
    }
}
