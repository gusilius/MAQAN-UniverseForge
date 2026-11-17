package dev.worldforge.settle.mc;

import dev.worldforge.settle.Worker;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.people.Character;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Тела рабочих: по одному WFWorker на Worker, пока рядом (64 блока) есть игрок; когда игроки ушли (80 блоков) - тела убираются. */
public final class Bodies {
    private Bodies() {}

    public static final double SPAWN = SimulationLod.WORKER_SPAWN_RADIUS, DESPAWN = SimulationLod.WORKER_RETAIN_RADIUS;
    /** "поселение#рабочий" -> UUID живого тела. Тело, которого здесь нет, само исчезает (см. WFWorker.tick). */
    public static final Map<String, UUID> LIVE = new HashMap<>();

    public static String key(String colony, int worker) { return colony + "#" + worker; }

    private static boolean hasLoadedVanillaVillagerBody(Worker worker, ServerLevel level) {
        if (worker == null || worker.person == null || worker.person.isBlank()) return false;
        Character person = PeopleMod.authority().getGlobal(worker.person);
        if (person == null) return false;
        Object raw = person.props.get("population.entity_bindings");
        if (!(raw instanceof Map<?, ?> bindings) || !(bindings.get("villager") instanceof String uuid)) return false;
        try {
            Entity body = level.getEntity(UUID.fromString(uuid));
            return body instanceof net.minecraft.world.entity.npc.Villager villager && villager.isAlive();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static void tick(MinecraftServer server) {
        int spawned = 0;
        for (Colony c : SettleMod.COLONIES.values()) {
            ServerLevel lv = c.level(server);
            if (lv == null) continue;
            String prefix = c.s.id + "#";
            String dimension = lv.dimension().location().toString();
            boolean keep = SimulationLod.shouldRetainWorkers(dimension, c.cx, c.cz);
            boolean spawn = SimulationLod.shouldSpawnWorkers(dimension, c.cx, c.cz);
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
            if (!spawn) continue;
            for (Worker w : c.s.workers) {
                if (spawned >= 4) return;                                          // не больше нескольких за проход
                String k = key(c.s.id, w.id);
                UUID u = LIVE.get(k);
                Entity body = u == null ? null : lv.getEntity(u);
                if (hasLoadedVanillaVillagerBody(w, lv)) {
                    if (body != null) body.discard();
                    LIVE.remove(k);
                    continue;
                }
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
            }
        }
    }
}
