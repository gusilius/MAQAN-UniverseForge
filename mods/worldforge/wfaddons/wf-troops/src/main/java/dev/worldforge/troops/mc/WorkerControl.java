package dev.worldforge.troops.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.mc.SettleMod;
import dev.worldforge.settle.mc.WFWorker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** Управление рабочими и ремесленниками из командного режима. Загружается только если есть wf_settle. */
final class WorkerControl {
    private WorkerControl() {}

    /** Есть ли среди выбранных рабочие (иначе пакет игнорируется молча: он уходит при каждом ПКМ по земле). */
    static boolean anyWorker(ServerPlayer p, OrderPacket o) {
        for (String id : o.ids()) {
            try {
                if (p.serverLevel().getEntity(UUID.fromString(id)) instanceof WFWorker) return true;
            } catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    static String command(ServerPlayer p, OrderPacket o) {
        ServerLevel lv = p.serverLevel();
        int done = 0;
        String where = "";
        for (String id : o.ids()) {
            Entity e;
            try {
                e = lv.getEntity(UUID.fromString(id));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!(e instanceof WFWorker w)) continue;
            Colony c = SettleMod.colony(w.colonyId);
            if (c == null || w.worker() == null) continue;
            if (o.action() == OrderPacket.RELEASE) {
                try { c.s.unassign(w.workerId); done++; } catch (RuntimeException ignored) {}
                continue;
            }
            Building b = c.buildingAt((int) Math.floor(o.x()), (int) Math.floor(o.z()));
            if (b == null) continue;
            try {
                c.s.assign(w.workerId, b.uid);
                where = b.def.name;
                done++;
            } catch (RuntimeException ex) {
                where = ex.getMessage();                       // здание заполнено / не подходит профессия
            }
        }
        if (done == 0) return o.action() == OrderPacket.RELEASE ? "Рабочие не выбраны" : where.isEmpty() ? "Здесь нет здания своего поселения" : where;
        return o.action() == OrderPacket.RELEASE ? "Освобождено рабочих: " + done : "Отправлено в «" + where + "»: " + done;
    }
}
