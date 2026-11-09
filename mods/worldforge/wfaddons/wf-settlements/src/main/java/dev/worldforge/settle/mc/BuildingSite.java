package dev.worldforge.settle.mc;

import dev.worldforge.settle.Building;
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
            int target = done ? total : (int) Math.floor(total * Math.max(0, Math.min(1, b.progress / b.def.work)));
            int cap = PER_CALL;
            while (site.placed < target && cap-- > 0) {
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
        }
    }

    /** Player placement that matches a blueprint block contributes directly to construction progress. */
    public static boolean assist(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState placedState) {
        for (Colony colony : SettleMod.COLONIES.values()) {
            if (colony == null || !level.dimension().location().toString().equals(colony.dim)) continue;
            if (!player.hasPermissions(2) && !player.server.isSingleplayerOwner(player.getGameProfile())
                    && !player.getUUID().toString().equals(colony.owner)) continue;
            for (Building building : colony.s.buildings) {
                if (building.state != Building.State.BUILDING) continue;
                Colony.Site site = colony.sites.get(building.uid);
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
        if (d == null || site.placed == 0) return;
        Blueprints.Plan plan = Blueprints.get(lv, d);
        for (int i = Math.min(site.placed, plan.blocks().size()) - 1; i >= 0; i--) {
            Blueprints.Entry en = plan.blocks().get(i);
            if (en.y() == 0) continue;                                                                // пол остаётся фундаментом
            BlockPos pos = new BlockPos(site.x + en.x(), site.y + en.y(), site.z + en.z());
            if (lv.hasChunkAt(pos) && lv.getBlockState(pos) == en.state()) lv.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
