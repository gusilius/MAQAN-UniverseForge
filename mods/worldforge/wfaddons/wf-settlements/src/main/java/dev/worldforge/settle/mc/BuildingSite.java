package dev.worldforge.settle.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Worker;
import dev.worldforge.settle.Defs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Постройка здания блоками: число поставленных блоков чертежа следует за Building.progress (снизу вверх).
 * Пока рядом (96 блоков) нет игрока, стройка в мире стоит; готовое здание достраивается целиком, как только
 * чанки загружены, а недостроенное догоняется, когда игрок подходит. Снесённое здание убирается (кроме пола).
 */
public final class BuildingSite {
    private BuildingSite() {}

    public static final double NEAR = 96;
    private static final int PER_CALL = 400;

    public static boolean playerNear(ServerLevel lv, double x, double z, double r) {
        for (ServerPlayer p : lv.players()) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    public static void tick(ServerLevel lv, Colony c) {
        Iterator<Map.Entry<Integer, Colony.Site>> it = c.sites.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Colony.Site> e = it.next();
            if (c.s.building(e.getKey()) == null) {
                demolish(lv, e.getValue());
                it.remove();
            }
        }
        for (Building b : new ArrayList<>(c.s.buildings)) {
            if (b.state == Building.State.PLANNED) continue;
            boolean done = b.state == Building.State.DONE;
            int wx = c.worldX(b), wz = c.worldZ(b);
            if (!done && !playerNear(lv, wx, wz, NEAR)) continue;
            Colony.Site site = c.sites.get(b.uid);
            Blueprints.Plan plan = Blueprints.get(lv, b.def);
            if (site == null) {
                if (!lv.hasChunkAt(new BlockPos(wx, 64, wz))) continue;
                site = new Colony.Site();
                site.def = b.def.id;
                site.sx = plan.sx(); site.sy = plan.sy(); site.sz = plan.sz();
                site.x = wx - site.sx / 2; site.z = wz - site.sz / 2;
                int ground = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);       // первый свободный блок над землёй
                site.y = Math.max(lv.getMinBuildHeight() + 1, ground - 1);                           // пол чертежа заменяет верхний слой земли
                c.sites.put(b.uid, site);
            }
            int total = plan.blocks().size();
            if (done && !site.physicalPending && site.placed < total) {
                site.physicalPending = true;
                site.wasPaused = b.paused;
                b.paused = true;
                b.status = "строится: укладка блоков";
            }
            if (site.physicalPending) ensureBuilder(c, b);
            int buildersHere = buildersAtSite(lv, c, b);
            int target = done ? total : (int) Math.floor(total * Math.max(0, Math.min(1, b.progress / b.def.work)));
            int cap = buildersHere > 0 ? Math.min(PER_CALL, buildersHere * 4) : site.assisted.size();
            while (site.placed < target && cap-- > 0) {
                if (buildersHere == 0 && !site.assisted.contains(site.placed)) break;
                Blueprints.Entry en = plan.blocks().get(site.placed);
                BlockPos pos = new BlockPos(site.x + en.x(), site.y + en.y(), site.z + en.z());
                if (!lv.hasChunkAt(pos)) break;                                                       // чанк выгружен - ждём
                if (site.assisted.remove(site.placed)) {
                    if (lv.getBlockState(pos) == en.state()) { site.placed++; continue; }
                }
                if (!place(lv, pos, en.state())) {
                    b.status = "препятствие на чертеже: расчистите блоки";
                    break; // Never advance the blueprint cursor past a block that was not placed.
                }
                site.placed++;
            }
            if (site.physicalPending && site.placed >= total) {
                b.paused = site.wasPaused;
                b.status = b.paused ? "пауза" : (b.def.produces() ? "работает" : "готово");
                site.physicalPending = false;
                releaseBuilderCrew(c, b);
            }
        }
    }

    private static void ensureBuilder(Colony colony, Building building) {
        if (colony.s.workers.stream().anyMatch(w -> w.building == building.uid && w.workRole().equals("builder"))) return;
        for (Worker worker : colony.s.workers) {
            if (worker.building != 0 || worker.manualOrder || worker.manualFollow || worker.manualHauler
                    || !worker.lifeAction.isBlank() || !worker.canPerform("builder")) continue;
            worker.building = building.uid;
            worker.assignedProfession = "builder";
            worker.status = "идёт укладывать блоки чертежа";
            return;
        }
        // If every resident has been auto-assigned again, temporarily borrow a builder-capable
        // specialist from the most staffed completed workplace instead of deadlocking the site.
        Worker pick = null;
        int bestStaff = 1;
        for (Worker worker : colony.s.workers) {
            if (worker.building == 0 || worker.manualOrder || worker.manualFollow || worker.manualHauler
                    || !worker.lifeAction.isBlank() || !worker.canPerform("builder")) continue;
            Building from = colony.s.building(worker.building);
            if (from == null || from.state != Building.State.DONE) continue;
            int staff = colony.s.assigned(from.uid).size();
            if (staff > bestStaff || (staff == bestStaff && pick != null && worker.id > pick.id)) {
                pick = worker;
                bestStaff = staff;
            }
        }
        if (pick != null) {
            pick.building = building.uid;
            pick.assignedProfession = "builder";
            pick.status = "временно помогает завершить стройку";
        }
    }

    /** Returns a reachable standing position beside the next blueprint block, so builders physically walk around the structure. */
    public static BlockPos builderWorkPoint(ServerLevel level, Colony colony, Building building, WFWorker worker) {
        Colony.Site site = colony.sites.get(building.uid);
        if (site == null) return colony.workPoint(level, building);
        Blueprints.Plan plan = Blueprints.get(level, building.def);
        int index = Math.max(0, site.placed);
        while (index < plan.blocks().size() && site.assisted.contains(index)) index++;
        if (index >= plan.blocks().size()) return colony.workPoint(level, building);
        Blueprints.Entry entry = plan.blocks().get(index);
        BlockPos block = new BlockPos(site.x + entry.x(), site.y + entry.y(), site.z + entry.z());
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        BlockPos above = block.above();
        if (builderStandable(level, above)) candidates.add(above);
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = block.relative(direction);
            if (builderStandable(level, side)) candidates.add(side);
        }
        return candidates.stream().distinct().filter(pos -> {
            var path = worker.getNavigation().createPath(pos, 0);
            return path != null && path.canReach();
        }).min(java.util.Comparator.comparingDouble(pos ->
            worker.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)))
            .orElseGet(() -> colony.workPoint(level, building));
    }

    private static boolean builderStandable(ServerLevel level, BlockPos feet) {
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())) return false;
        BlockState at = level.getBlockState(feet);
        BlockState floor = level.getBlockState(feet.below());
        return (at.isAir() || at.canBeReplaced()) && at.getFluidState().isEmpty() && floor.isSolid();
    }
    private static int buildersAtSite(ServerLevel level, Colony colony, Building building) {
        Colony.Site site = colony.sites.get(building.uid);
        if (site == null) return 0;
        int count = 0;
        for (Worker worker : colony.s.workers) {
            if (worker.building != building.uid || !worker.workRole().equals("builder")) continue;
            java.util.UUID id = Bodies.LIVE.get(Bodies.key(colony.s.id, worker.id));
            Entity entity = id == null ? null : level.getEntity(id);
            if (entity instanceof WFWorker body && entity.isAlive()) {
                BlockPos workPoint = builderWorkPoint(level, colony, building, body);
                if (entity.distanceToSqr(workPoint.getX() + 0.5, workPoint.getY(), workPoint.getZ() + 0.5) <= 36) count++;
            }
        }
        return count;
    }

    private static void releaseBuilderCrew(Colony colony, Building building) {
        for (Worker worker : colony.s.workers) if (worker.building == building.uid && worker.workRole().equals("builder")) {
            worker.building = 0;
            worker.assignedProfession = "";
            worker.status = "свободен";
        }
    }

    /** Player placement that matches a blueprint block contributes directly to construction progress. */
    public static boolean assist(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState placedState) {
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || !level.dimension().location().toString().equals(colony.dim)) continue;
            if (!player.hasPermissions(2) && !player.server.isSingleplayerOwner(player.getGameProfile())
                    && !player.getUUID().toString().equals(colony.owner)) continue;
            for (Building building : colony.s.buildings) {
                Colony.Site site = colony.sites.get(building.uid);
                if (building.state != Building.State.BUILDING && (site == null || !site.physicalPending)) continue;
                if (site == null || pos.getX() < site.x || pos.getX() >= site.x + site.sx
                        || pos.getY() < site.y || pos.getY() >= site.y + site.sy
                        || pos.getZ() < site.z || pos.getZ() >= site.z + site.sz) continue;
                Blueprints.Plan plan = Blueprints.get(level, building.def);
                for (int i = Math.max(0, site.placed); i < plan.blocks().size(); i++) {
                    Blueprints.Entry entry = plan.blocks().get(i);
                    if (site.x + entry.x() != pos.getX() || site.y + entry.y() != pos.getY()
                            || site.z + entry.z() != pos.getZ() || !entry.state().equals(placedState)) continue;
                    site.assisted.add(i);
                    int contributed = Math.min(plan.blocks().size(), Math.max(site.placed, i + 1));
                    building.progress = Math.max(building.progress, building.def.work * contributed / plan.blocks().size());
                    building.status = "строится: помощь игрока";
                    return true;
                }
            }
        }
        return false;
    }

    public static final class Events {
        @SubscribeEvent
        public void onPlace(BlockEvent.EntityPlaceEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player)
                    || !(event.getLevel() instanceof ServerLevel level)) return;
            assist(player, level, event.getPos(), event.getPlacedBlock());
        }
    }

    /** Ставит блок, не трогая чужие постройки: заменяются только воздух, заменяемые блоки, листва и природный грунт в рамке здания. */
    private static boolean place(ServerLevel lv, BlockPos pos, BlockState st) {
        BlockState cur = lv.getBlockState(pos);
        if (cur == st) return true;
        if (cur.isAir() || cur.canBeReplaced() || cur.is(BlockTags.LEAVES) || cur.is(BlockTags.DIRT) || cur.is(BlockTags.SAND)
                || cur.is(BlockTags.BASE_STONE_OVERWORLD) || cur.is(Blocks.GRAVEL) || cur.is(Blocks.SNOW)) {
            return lv.setBlock(pos, st, 3);
        }
        return false;
    }

    private static void demolish(ServerLevel lv, Colony.Site site) {
        Defs.Building d = SettleMod.DEFS == null ? null : SettleMod.DEFS.buildings.get(site.def);
        if (d == null || site.placed == 0 && site.assisted.isEmpty()) return;
        Blueprints.Plan plan = Blueprints.get(lv, d);
        java.util.Set<Integer> placed = new java.util.HashSet<>(site.assisted);
        for (int i = 0; i < Math.min(site.placed, plan.blocks().size()); i++) placed.add(i);
        for (int i : placed) {
            if (i < 0 || i >= plan.blocks().size()) continue;
            Blueprints.Entry en = plan.blocks().get(i);
            if (en.y() == 0) continue;                                                                // пол остаётся фундаментом
            BlockPos pos = new BlockPos(site.x + en.x(), site.y + en.y(), site.z + en.z());
            if (lv.hasChunkAt(pos) && lv.getBlockState(pos) == en.state()) lv.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
