package dev.worldforge.settle.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spatial broad-phase for physical settlement work.
 *
 * The durable colony registry remains global. This index is only a short-lived
 * lookup structure for nearby physical materialization, rebuilt at a coarse
 * cadence so a site tick never scans every colony.
 */
final class ColonyInterestIndex {
    private static final int CELL_SIZE = 256;
    private static final long REBUILD_INTERVAL_TICKS = 100L;
    private static long builtAt = Long.MIN_VALUE;
    private static volatile boolean dirty = true;
    private static Map<String, Map<Long, List<Colony>>> byDimension = Map.of();

    private ColonyInterestIndex() {}

    static synchronized void clear() {
        builtAt = Long.MIN_VALUE;
        dirty = true;
        byDimension = Map.of();
    }

    static void invalidate() { dirty = true; }

    static synchronized void refresh(Collection<Colony> colonies, long tick) {
        if (!dirty && builtAt != Long.MIN_VALUE && tick >= builtAt
                && tick - builtAt < REBUILD_INTERVAL_TICKS) return;
        Map<String, Map<Long, List<Colony>>> mutable = new HashMap<>();
        for (Colony colony : colonies) {
            if (colony == null || colony.s == null || colony.dim == null || colony.dim.isBlank()) continue;
            int cellX = Math.floorDiv(colony.cx, CELL_SIZE);
            int cellZ = Math.floorDiv(colony.cz, CELL_SIZE);
            long key = cellKey(cellX, cellZ);
            mutable.computeIfAbsent(colony.dim, ignored -> new HashMap<>())
                    .computeIfAbsent(key, ignored -> new ArrayList<>()).add(colony);
        }
        Map<String, Map<Long, List<Colony>>> frozen = new HashMap<>();
        mutable.forEach((dimension, cells) -> {
            Map<Long, List<Colony>> immutableCells = new HashMap<>();
            cells.forEach((key, values) -> {
                values.sort(java.util.Comparator.comparing(c -> c.s.id));
                immutableCells.put(key, List.copyOf(values));
            });
            frozen.put(dimension, Map.copyOf(immutableCells));
        });
        byDimension = Map.copyOf(frozen);
        builtAt = tick;
        dirty = false;
    }

    static List<Colony> nearby(MinecraftServer server, double radius) {
        if (server == null || !Double.isFinite(radius) || radius < 0) return List.of();
        Map<String, Map<Long, List<Colony>>> snapshot = byDimension;
        double radiusSquared = radius * radius;
        Map<String, Colony> found = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, List<Colony>> cells = snapshot.get(level.dimension().location().toString());
            if (cells == null || cells.isEmpty()) continue;
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) continue;
                double px = player.getX(), pz = player.getZ();
                int minCellX = Math.floorDiv((int)Math.floor(px - radius), CELL_SIZE);
                int maxCellX = Math.floorDiv((int)Math.floor(px + radius), CELL_SIZE);
                int minCellZ = Math.floorDiv((int)Math.floor(pz - radius), CELL_SIZE);
                int maxCellZ = Math.floorDiv((int)Math.floor(pz + radius), CELL_SIZE);
                for (int cx = minCellX; cx <= maxCellX; cx++) {
                    for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                        for (Colony colony : cells.getOrDefault(cellKey(cx, cz), List.of())) {
                            double dx = colony.cx - px, dz = colony.cz - pz;
                            if (dx * dx + dz * dz <= radiusSquared)
                                found.putIfAbsent(colony.s.id, colony);
                        }
                    }
                }
            }
        }
        return List.copyOf(found.values());
    }

    private static long cellKey(int x, int z) {
        return ((long)x << 32) | (z & 0xffff_ffffL);
    }
}
