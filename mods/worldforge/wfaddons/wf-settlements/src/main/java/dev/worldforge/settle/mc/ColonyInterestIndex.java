package dev.worldforge.settle.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental spatial broad-phase for physical settlement work.
 *
 * The canonical colony registry is never traversed to refresh this index.
 * Insertions/removals update one cell in O(1); player queries visit only the
 * handful of cells intersecting their physical interest radius.
 */
final class ColonyInterestIndex {
    private static final int CELL_SIZE = 256;
    private record Cell(String dimension, long key) {}
    private static final Map<String, Map<Long, Map<String, Colony>>> byDimension = new HashMap<>();
    private static final Map<String, Cell> cellByColony = new HashMap<>();

    private ColonyInterestIndex() {}

    static synchronized void clear() {
        byDimension.clear();
        cellByColony.clear();
    }

    static synchronized void upsert(Colony colony) {
        if (colony == null || colony.s == null || colony.s.id == null
                || colony.dim == null || colony.dim.isBlank()) return;
        remove(colony.s.id);
        int cellX = Math.floorDiv(colony.cx, CELL_SIZE);
        int cellZ = Math.floorDiv(colony.cz, CELL_SIZE);
        long key = cellKey(cellX, cellZ);
        byDimension.computeIfAbsent(colony.dim, ignored -> new HashMap<>())
                .computeIfAbsent(key, ignored -> new HashMap<>())
                .put(colony.s.id, colony);
        cellByColony.put(colony.s.id, new Cell(colony.dim, key));
    }

    static synchronized void remove(String colonyId) {
        if (colonyId == null) return;
        Cell cell = cellByColony.remove(colonyId);
        if (cell == null) return;
        Map<Long, Map<String, Colony>> cells = byDimension.get(cell.dimension());
        if (cells == null) return;
        Map<String, Colony> members = cells.get(cell.key());
        if (members != null) {
            members.remove(colonyId);
            if (members.isEmpty()) cells.remove(cell.key());
        }
        if (cells.isEmpty()) byDimension.remove(cell.dimension());
    }

    /** Query one geographic neighborhood without walking the canonical registry. */
    static synchronized List<Colony> nearbyAt(String dimension, double x, double z, double radius) {
        if (dimension == null || dimension.isBlank() || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(radius) || radius < 0) return List.of();
        Map<Long, Map<String, Colony>> cells = byDimension.get(dimension);
        if (cells == null || cells.isEmpty()) return List.of();
        double radiusSquared = radius * radius;
        int minCellX = Math.floorDiv((int)Math.floor(x - radius), CELL_SIZE);
        int maxCellX = Math.floorDiv((int)Math.floor(x + radius), CELL_SIZE);
        int minCellZ = Math.floorDiv((int)Math.floor(z - radius), CELL_SIZE);
        int maxCellZ = Math.floorDiv((int)Math.floor(z + radius), CELL_SIZE);
        Map<String, Colony> found = new LinkedHashMap<>();
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                Map<String, Colony> members = cells.get(cellKey(cx, cz));
                if (members == null) continue;
                for (Colony colony : members.values()) {
                    double dx = colony.cx - x, dz = colony.cz - z;
                    if (dx * dx + dz * dz <= radiusSquared) found.putIfAbsent(colony.s.id, colony);
                }
            }
        }
        return List.copyOf(found.values());
    }

    static synchronized List<Colony> nearby(MinecraftServer server, double radius) {
        if (server == null || !Double.isFinite(radius) || radius < 0) return List.of();
        double radiusSquared = radius * radius;
        Map<String, Colony> found = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            Map<Long, Map<String, Colony>> cells = byDimension.get(level.dimension().location().toString());
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
                        Map<String, Colony> members = cells.get(cellKey(cx, cz));
                        if (members == null) continue;
                        for (Colony colony : members.values()) {
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
