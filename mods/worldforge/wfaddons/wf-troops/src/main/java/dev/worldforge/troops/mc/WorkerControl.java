package dev.worldforge.troops.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Worker;
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

    static boolean mayControl(ServerPlayer p, Colony colony) {
        return p.hasPermissions(2) || p.server.isSingleplayerOwner(p.getGameProfile())
                || (colony != null && p.getUUID().toString().equals(colony.owner));
    }

    static String workArea(ServerPlayer p, OrderPacket o) {
        int accepted = 0;
        boolean denied = false;
        String[] modes = {"wood", "stone", "ore", "clear", "level", "harvest", "honey", "herbs"};
        String[] roles = {"lumberjack", "stonemason", "miner", "builder", "builder", "farmer", "beekeeper", "herbalist"};
        String[] labels = {"вырубать целые деревья", "добывать камень", "добывать руду", "расчищать растительность", "выравнивать участок", "собирать зрелые культуры", "собирать мёд", "собирать лекарственные растения"};
        String[] params = o.target().split(":", 4);
        int overlay = 2, level = 0;
        try { if (params.length > 0) overlay = Math.max(0, Math.min(2, Integer.parseInt(params[0]))); }
        catch (NumberFormatException ignored) {}
        int halfX = o.arg(), halfZ = o.arg();
        try { if (params.length > 1) level = Integer.parseInt(params[1]); }
        catch (NumberFormatException ignored) {}
        try { if (params.length > 2) halfX = Math.max(0, Math.min(64, Integer.parseInt(params[2]))); }
        catch (NumberFormatException ignored) {}
        try { if (params.length > 3) halfZ = Math.max(0, Math.min(64, Integer.parseInt(params[3]))); }
        catch (NumberFormatException ignored) {}
        String mode = modes[o.kind()];
        for (String id : o.ids()) {
            Entity entity;
            try { entity = p.serverLevel().getEntity(UUID.fromString(id)); }
            catch (IllegalArgumentException ex) { continue; }
            if (!(entity instanceof WFWorker body)) continue;
            Colony colony = SettleMod.colony(body.colonyId);
            Worker resident = body.worker();
            if (colony == null || resident == null) continue;
            if (!mayControl(p, colony)) { denied = true; continue; }
            String role = roles[o.kind()];
            if (o.arg() == -1) {
                Colony.WorkOrder existing = colony.workOrders.get(resident.id);
                if (existing != null) { existing.overlay = overlay; accepted++; }
                continue;
            }
            boolean gardenerHarvest = "harvest".equals(mode) && resident.canPerform("gardener");
            boolean forestry = "wood".equals(mode) && resident.canPerform("forester");
            if (!resident.canPerform(role) && !gardenerHarvest && !forestry) continue;
            Colony.WorkOrder work = new Colony.WorkOrder();
            work.mode = mode;
            work.x = (int) Math.floor(o.x());
            work.z = (int) Math.floor(o.z());
            work.radius = o.arg();
            work.halfX = halfX;
            work.halfZ = halfZ;
            work.level = level;
            work.overlay = overlay;
            colony.workOrders.put(resident.id, work);
            resident.manualFollow = false;
            resident.manualOrder = false;
            resident.manualHauler = false;
            resident.orderQueue.clear();
            resident.building = 0;
            resident.assignedProfession = gardenerHarvest && !resident.canPerform(role) ? "gardener"
                    : forestry && !resident.canPerform(role) ? "forester" : role;
            resident.status = "приказ: " + labels[o.kind()];
            accepted++;
        }
        if (accepted == 0) return denied ? "Нельзя отдавать приказы рабочим чужого поселения"
                : "Выберите подходящих рабочих (универсальные рабочие могут выполнять эту задачу)";
        return o.arg() == -1
                ? "Видимость назначенных рабочих зон обновлена: " + (overlay == 0 ? "выкл." : overlay == 1 ? "слабая" : "яркая")
                    + " · рабочих: " + accepted
                : "Участок назначен: " + labels[o.kind()] + " · рабочих: " + accepted
                    + " · радиус: " + o.arg() + " · отображение: " + (overlay == 0 ? "выкл." : overlay == 1 ? "слабое" : "яркое");
    }

    static String command(ServerPlayer p, OrderPacket o) {
        ServerLevel lv = p.serverLevel();
        int done = 0;
        boolean denied = false;
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
            Worker resident = w.worker();
            if (c == null || resident == null) continue;
            if (!mayControl(p, c)) { denied = true; continue; }
            if (o.action() == OrderPacket.RELEASE) {
                c.workOrders.remove(w.workerId);
                try { c.s.unassign(w.workerId); done++; } catch (RuntimeException ignored) {}
                continue;
            }
            if (o.action() == OrderPacket.WORK_FOLLOW) {
                c.workOrders.remove(w.workerId);
                resident.manualFollow = true;
                resident.manualFollowOwner = p.getUUID().toString();
                resident.manualOrder = false;
                resident.building = 0;
                resident.orderQueue.clear();
                resident.status = "следует за правителем";
                done++;
                where = "следовать за игроком";
                continue;
            }
            if (o.action() == OrderPacket.WORK_STOP || o.action() == OrderPacket.WORK_HOLD) {
                c.workOrders.remove(w.workerId);
                try {
                    double x = w.getX() - c.cx, z = w.getZ() - c.cz;
                    c.s.holdAt(w.workerId, x, z);
                    done++;
                    where = o.action() == OrderPacket.WORK_STOP ? "остановиться" : "удерживать позицию";
                } catch (RuntimeException ex) { where = ex.getMessage(); }
                continue;
            }
            c.workOrders.remove(w.workerId);
            Building b = c.buildingAt((int) Math.floor(o.x()), (int) Math.floor(o.z()));
            try {
                if (b == null) {
                    c.s.moveTo(w.workerId,o.x()-c.cx,o.z()-c.cz,o.queue());
                    where=o.queue()?"очередь маршрута":"точку назначения";
                } else {
                    c.s.assign(w.workerId, b.uid);
                    where = b.def.name;
                }
                done++;
            } catch (RuntimeException ex) {
                where = ex.getMessage();
            }
        }
        if (done == 0) {
            if (denied) return "Это поселение не ваше: приказы рабочим отдаёт его правитель";
            return o.action() == OrderPacket.RELEASE ? "Рабочие не выбраны" : where.isEmpty() ? "Здесь нет здания своего поселения" : where;
        }
        return o.action() == OrderPacket.RELEASE ? "Освобождено рабочих: " + done : "Отправлено в «" + where + "»: " + done;
    }
}
