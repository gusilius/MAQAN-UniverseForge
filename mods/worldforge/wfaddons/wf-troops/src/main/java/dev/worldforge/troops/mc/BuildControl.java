package dev.worldforge.troops.mc;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.WFNet;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.mc.SettleMod;
import dev.worldforge.settle.mc.WFWorker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.*;

/** Стройка из командного режима: меню зданий для камеры сверху и закладка здания в точке, куда указал игрок. Загружается только если есть wf_settle. */
final class BuildControl {
    static final String VIEW = "wf_troops:builds";

    private BuildControl() {}

    /** Список зданий ближайшего поселения, склад и занятые места: клиент рисует меню и призрак с проверкой. */
    static void sendList(ServerPlayer p, double x, double z) {
        Colony c = SettleMod.nearest(p.serverLevel(), x, z);
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == null || SettleMod.DEFS == null) {
            m.put("error", "Рядом нет поселения. Основать: /wfsettle found <имя>");
            WFNet.sendView(p, VIEW, Json.write(m));
            return;
        }
        m.put("colony", c.s.name);
        m.put("cx", c.cx);
        m.put("cz", c.cz);
        List<Object> defs = new ArrayList<>();
        for (Defs.Building d : SettleMod.DEFS.buildings.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", d.id);
            e.put("name", d.name);
            e.put("category", d.category);
            e.put("size", List.of(d.sizeX, d.sizeZ));
            e.put("cost", new LinkedHashMap<>(d.cost));
            defs.add(e);
        }
        m.put("defs", defs);
        Map<String, Object> stock = new LinkedHashMap<>();
        for (var e : c.s.stock.entrySet()) stock.put(e.getKey(), (int) Math.floor(e.getValue()));
        m.put("stock", stock);
        List<Object> taken = new ArrayList<>();
        for (Building b : c.s.buildings) taken.add(Map.of("x", c.worldX(b), "z", c.worldZ(b), "sx", b.def.sizeX, "sz", b.def.sizeZ));
        m.put("taken", taken);
        WFNet.sendView(p, VIEW, Json.write(m));
    }

    /** Закладывает здание с центром в (wx, wz) и отправляет выбранных рабочих его строить. Возвращает сообщение игроку. */
    static String place(ServerPlayer p, String defId, int wx, int wz, List<String> workerIds) {
        Colony c = SettleMod.nearest(p.serverLevel(), wx, wz);
        if (c == null) return "Рядом нет поселения";
        Building b;
        try {
            b = c.s.place(defId, wx - c.cx, wz - c.cz);
        } catch (Settlement.OrderException ex) {
            return ex.getMessage();
        }
        int builders = 0;
        for (String id : workerIds) {
            try {
                Entity e = p.serverLevel().getEntity(UUID.fromString(id));
                if (!(e instanceof WFWorker w) || w.worker() == null) continue;
                c.s.assign(w.workerId, b.uid);
                builders++;
            } catch (RuntimeException ignored) {}
        }
        return "Заложено: " + b.def.name + (builders > 0 ? ", строителей отправлено: " + builders : ". Назначьте строителей: выберите рабочих и ПКМ по стройке");
    }
}
