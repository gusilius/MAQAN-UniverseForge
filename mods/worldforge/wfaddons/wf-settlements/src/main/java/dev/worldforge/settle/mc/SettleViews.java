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
        java.util.List<dev.worldforge.settle.ResidentRow> residents = new java.util.ArrayList<>();
        if (net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) {
            int year = dev.worldforge.people.mc.PeopleMod.YEAR;
            for (String id : c.residentCharacterIds) {
                dev.worldforge.people.Character person = dev.worldforge.people.mc.PeopleMod.authority().getGlobal(id);
                if (person == null || !person.alive) continue;
                boolean working = c.s.workers.stream().anyMatch(worker -> id.equals(worker.person));
                residents.add(dev.worldforge.settle.ResidentRow.of(person, year, working));
            }
            residents.sort(java.util.Comparator.comparingInt((dev.worldforge.settle.ResidentRow row) -> row.age)
                    .thenComparing(row -> row.name, String.CASE_INSENSITIVE_ORDER));
        }
        m.put("residents", dev.worldforge.settle.ResidentRow.toJson(residents));
        WFNet.sendView(sp, view, Json.write(m));
    }
}
