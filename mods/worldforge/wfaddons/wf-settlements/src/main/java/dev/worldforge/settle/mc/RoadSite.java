package dev.worldforge.settle.mc;

import dev.worldforge.settle.SettlementMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Materializes planned road segments only near players and only over replaceable natural ground. */
public final class RoadSite {
    private RoadSite() {}
    private static final int PER_TICK = 96;

    public static void tick(ServerLevel level, Colony colony) {
        int budget = PER_TICK;
        for (SettlementMap.Road road : colony.map.roads.values()) {
            while (road.builtSegments < road.path.size() - 1 && budget > 0) {
                SettlementMap.Node a = road.path.get(road.builtSegments);
                SettlementMap.Node b = road.path.get(road.builtSegments + 1);
                int ax = colony.cx + a.gx() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
                int az = colony.cz + a.gz() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
                int bx = colony.cx + b.gx() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
                int bz = colony.cz + b.gz() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2;
                if (!BuildingSite.playerNear(level, (ax + bx) / 2.0, (az + bz) / 2.0, BuildingSite.NEAR)) break;
                if (!level.hasChunkAt(new BlockPos(ax, level.getMinBuildHeight(), az))
                        || !level.hasChunkAt(new BlockPos(bx, level.getMinBuildHeight(), bz))) break;
                if (!buildSegment(level, road.surface, ax, az, bx, bz, budget)) break;
                int length = Math.abs(bx - ax) + Math.abs(bz - az);
                budget -= Math.max(1, length * 3);
                road.builtSegments++;
            }
            if (budget <= 0) return;
        }
    }

    private static boolean buildSegment(ServerLevel level, String surface, int ax, int az, int bx, int bz, int budget) {
        boolean alongX = az == bz;
        int length = Math.abs(bx - ax) + Math.abs(bz - az);
        int sx = Integer.signum(bx - ax), sz = Integer.signum(bz - az);
        int previousY = Integer.MIN_VALUE;
        for (int i = 0; i <= length; i++) {
            int x = ax + sx * i, z = az + sz * i;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (previousY != Integer.MIN_VALUE && Math.abs(y - previousY) > 1) return false;
            previousY = y;
            for (int w = -1; w <= 1; w++) {
                int px = x + (alongX ? 0 : w), pz = z + (alongX ? w : 0);
                BlockPos pos = new BlockPos(px, y - 1, pz);
                if (!level.hasChunkAt(pos)) return false;
                BlockState old = level.getBlockState(pos);
                if (old.isAir() || !old.getFluidState().isEmpty()) return false;
                if (old.is(Blocks.GRASS_BLOCK) || old.is(Blocks.DIRT) || old.is(Blocks.COARSE_DIRT)
                        || old.is(Blocks.ROOTED_DIRT) || old.is(Blocks.SAND) || old.is(Blocks.GRAVEL)
                        || old.is(Blocks.SNOW) || old.is(Blocks.DIRT_PATH)) {
                    BlockState road = switch (surface) {
                        case "cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
                        case "dirt" -> Blocks.DIRT_PATH.defaultBlockState();
                        default -> Blocks.GRAVEL.defaultBlockState();
                    };
                    level.setBlock(pos, road, 3);
                }
            }
        }
        return true;
    }
}
