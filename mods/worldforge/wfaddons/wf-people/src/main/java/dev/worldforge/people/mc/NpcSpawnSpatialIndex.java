package dev.worldforge.people.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small uniform-grid index for nearby NPC materialization candidates. */
public final class NpcSpawnSpatialIndex<T> {
    public static final int CELL_SIZE = 128;
    private final Map<Long, List<T>> cells = new HashMap<>();

    public void add(T value, double x, double z) {
        cells.computeIfAbsent(key(cell(x), cell(z)), ignored -> new ArrayList<>()).add(value);
    }

    public List<T> query(double x, double z, double radius) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(radius) || radius < 0)
            return List.of();
        long minX = cell(x - radius), maxX = cell(x + radius);
        long minZ = cell(z - radius), maxZ = cell(z + radius);
        List<T> result = new ArrayList<>();
        for (long cx = minX; cx <= maxX; cx++)
            for (long cz = minZ; cz <= maxZ; cz++) {
                List<T> bucket = cells.get(key(cx, cz));
                if (bucket != null) result.addAll(bucket);
            }
        return result;
    }

    private static long cell(double coordinate) { return (long) Math.floor(coordinate / CELL_SIZE); }
    private static long key(long x, long z) { return (x << 32) ^ (z & 0xffffffffL); }
}
