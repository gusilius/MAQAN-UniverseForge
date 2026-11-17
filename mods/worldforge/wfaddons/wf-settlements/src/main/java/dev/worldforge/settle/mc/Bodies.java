package dev.worldforge.settle.mc;

import dev.worldforge.settle.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Materializes nearby worker bodies within configured distance and per-pass/per-colony budgets. */
public final class Bodies {
    private Bodies() {}

    /** "поселение#рабочий" -> UUID живого тела. Тело, которого здесь нет, само исчезает (см. WFWorker.tick). */
    public static final Map<String, UUID> LIVE = new HashMap<>();

    public static String key(String colony, int worker) { return colony + "#" + worker; }

    public static void tick(MinecraftServer server) {
        int spawnBudget = dev.worldforge.mod.MaqanPerformanceConfig.maxWorkerSpawnsPerPass();
        int spawned = 0;
        for (Colony c : SettleMod.COLONIES.values()) {
            ServerLevel lv = c.level(server);
            if (lv == null) continue;
            String prefix = c.s.id + "#";
            boolean keep = BuildingSite.playerNear(lv, c.cx, c.cz, dev.worldforge.mod.MaqanPerformanceConfig.workerDespawnRadius());
            boolean spawn = BuildingSite.playerNear(lv, c.cx, c.cz, dev.worldforge.mod.MaqanPerformanceConfig.workerSpawnRadius());
            Iterator<Map.Entry<String, UUID>> it = LIVE.entrySet().iterator();
            while (it.hasNext()) {                                                 // убрать лишние тела: игроков нет рядом или рабочего больше нет
                Map.Entry<String, UUID> e = it.next();
                if (!e.getKey().startsWith(prefix)) continue;
                boolean alive = false;
                try {
                    alive = c.s.worker(Integer.parseInt(e.getKey().substring(prefix.length()))) != null;
                } catch (NumberFormatException ignored) {
                }
                if (!keep || !alive) {
                    Entity body = lv.getEntity(e.getValue());
                    if (body != null) body.discard();
                    it.remove();
                }
            }
            if (!spawn || spawned >= spawnBudget) continue;
            int colonyBodyCount = 0;
            for (String liveKey : LIVE.keySet()) if (liveKey.startsWith(prefix)) colonyBodyCount++;
            int colonyBodyLimit = dev.worldforge.mod.MaqanPerformanceConfig.maxActiveWorkerBodiesPerColony();
            if (colonyBodyCount >= colonyBodyLimit) continue;
            for (Worker w : c.s.workers) {
                if (spawned >= spawnBudget) break;
                if (colonyBodyCount >= colonyBodyLimit) break;                                          // не больше нескольких за проход
                String k = key(c.s.id, w.id);
                UUID u = LIVE.get(k);
                Entity body = u == null ? null : lv.getEntity(u);
                if (body != null && body.isAlive()) continue;
                int x = c.cx + lv.random.nextInt(9) - 4, z = c.cz + lv.random.nextInt(9) - 4;
                if (!lv.hasChunkAt(new BlockPos(x, 64, z))) continue;
                int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                WFWorker e = SettleMod.WORKER.get().create(lv);
                if (e == null) continue;
                e.colonyId = c.s.id;
                e.workerId = w.id;
                e.moveTo(x + 0.5, y, z + 0.5, lv.random.nextFloat() * 360f, 0f);
                LIVE.put(k, e.getUUID());                                          // сначала запись, потом добавление: проверка тела пройдёт
                e.refreshName();
                lv.addFreshEntity(e);
                spawned++;
                colonyBodyCount++;
            }
        }
    }
}
