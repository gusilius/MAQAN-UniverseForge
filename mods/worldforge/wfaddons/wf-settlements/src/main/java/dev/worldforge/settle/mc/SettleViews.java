package dev.worldforge.settle.mc;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.WFNet;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Сервер -> клиент: состояние поселения и описания зданий для окон wf_settle:panel и wf_settle:hud. */
public final class SettleViews {
    public static final String PANEL = "wf_settle:panel", HUD = "wf_settle:hud";

    private SettleViews() {}

    public static void send(ServerPlayer sp, Colony c, String view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defs", new java.util.ArrayList<Object>(SettleMod.DEFS_RAW));
        m.put("s", c.s.toJson());
        java.util.List<dev.worldforge.settle.CityRow> rows = new java.util.ArrayList<>();
        for (Colony x : SettleMod.COLONIES.values()) rows.add(dev.worldforge.settle.CityRow.of(x.s));
        m.put("cities", dev.worldforge.settle.CityRow.toJson(rows));
        m.put("routes", SettleMod.NETWORK.toJson());
        m.put("map", c.map.toJson());
        WFNet.sendView(sp, view, Json.write(m));
    }
}
