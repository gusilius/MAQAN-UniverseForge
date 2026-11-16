package dev.worldforge.troops.mc;

import java.util.List;

/**
 * Reusable server-thread spatial hash for one squad snapshot.
 * Stores indices into the caller's stable alive list, never entity references.
 * All buffers grow only when required and are reused on subsequent ticks.
 */
final class TroopSpatialIndex {
    private static final double CELL_SIZE = 8.0;
    private int[] cellX = new int[0], cellZ = new int[0], heads = new int[0], next = new int[0], candidates = new int[0];
    private double[] aura = new double[0], posX = new double[0], posZ = new double[0];
    private boolean[] leaders = new boolean[0];
    private final int[] healIndices = {-1, -1, -1};
    private final double[] healFractions = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    private int mask;

    void rebuild(List<TroopEntity> troops) {
        int count = troops.size();
        ensure(count);
        java.util.Arrays.fill(heads, -1);
        for (int i = 0; i < count; i++) {
            TroopEntity troop = troops.get(i);
            posX[i] = troop.getX(); posZ[i] = troop.getZ();
            int x = floorCell(posX[i]), z = floorCell(posZ[i]);
            int slot = hash(x, z) & mask;
            while (heads[slot] != -1 && (cellX[slot] != x || cellZ[slot] != z)) slot = (slot + 1) & mask;
            if (heads[slot] == -1) { cellX[slot] = x; cellZ[slot] = z; }
            next[i] = heads[slot];
            heads[slot] = i;
        }
    }

    int query(double x, double z, double radius) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(radius) || radius < 0) return 0;
        int minX = floorCell(x - radius), maxX = floorCell(x + radius);
        int minZ = floorCell(z - radius), maxZ = floorCell(z + radius);
        long cells = (long) (maxX - (long) minX + 1) * (maxZ - (long) minZ + 1);
        if (cells > 4096) return 0; // fail closed on pathological query radii
        double r2 = radius * radius;
        int found = 0;
        for (int cz = minZ; ; cz++) {
            for (int cx = minX; ; cx++) {
                int slot = find(cx, cz);
                if (slot >= 0) for (int i = heads[slot]; i >= 0; i = next[i]) {
                    // Exact circle check excludes square-corner false positives.
                    double dx = posX[i] - x, dz = posZ[i] - z;
                    if (dx * dx + dz * dz <= r2) candidates[found++] = i;
                }
                if (cx == maxX) break;
            }
            if (cz == maxZ) break;
        }
        return found;
    }

    void healAround(List<TroopEntity> troops, int healerIndex, double radius, double amount, int maxTargets) {
        if (maxTargets <= 0 || amount <= 0 || healerIndex < 0 || healerIndex >= troops.size()) return;
        int found = query(posX[healerIndex], posZ[healerIndex], radius);
        java.util.Arrays.fill(healIndices, -1);
        java.util.Arrays.fill(healFractions, Double.POSITIVE_INFINITY);
        int limit = Math.min(maxTargets, healIndices.length);
        for (int n = 0; n < found; n++) {
            int index = candidate(n);
            TroopEntity target = troops.get(index);
            double maximum = target.getMaxHealth(), health = target.getHealth();
            double missing = maximum - health;
            if (missing <= 0.01 || maximum <= 0) continue;
            double fraction = health / maximum;
            for (int slot = 0; slot < limit; slot++) {
                if (fraction < healFractions[slot] || (fraction == healFractions[slot] && index < healIndices[slot])) {
                    for (int move = limit - 1; move > slot; move--) {
                        healFractions[move] = healFractions[move - 1];
                        healIndices[move] = healIndices[move - 1];
                    }
                    healFractions[slot] = fraction;
                    healIndices[slot] = index;
                    break;
                }
            }
        }
        for (int slot = 0; slot < limit; slot++) {
            int index = healIndices[slot];
            if (index < 0) break;
            TroopEntity target = troops.get(index);
            float gain = (float) Math.min(target.getMaxHealth() - target.getHealth(), amount);
            if (gain > 0) target.heal(gain);
        }
    }

    int candidate(int index) { return candidates[index]; }
    double[] auraBuffer() { return aura; }
    boolean[] leaderBuffer() { return leaders; }

    private int find(int x, int z) {
        int slot = hash(x, z) & mask;
        for (int probe = 0; probe <= mask; probe++) {
            if (heads[slot] == -1) return -1;
            if (cellX[slot] == x && cellZ[slot] == z) return slot;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void ensure(int count) {
        int capacity = 16;
        while (capacity < Math.max(16, count * 2L)) capacity <<= 1;
        if (heads.length < capacity) {
            heads = new int[capacity]; cellX = new int[capacity]; cellZ = new int[capacity];
            mask = capacity - 1;
        }
        if (next.length < count) next = new int[count];
        if (candidates.length < count) candidates = new int[count];
        if (aura.length < count) aura = new double[count];
        if (posX.length < count) { posX = new double[count]; posZ = new double[count]; }
        if (leaders.length < count) leaders = new boolean[count];
    }

    private static int floorCell(double coordinate) {
        double value = Math.floor(coordinate / CELL_SIZE);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return 0;
        return (int) value;
    }

    private static int hash(int x, int z) {
        int h = x * 0x9E3779B9;
        h ^= Integer.rotateLeft(z * 0x85EBCA6B, 16);
        h ^= h >>> 16; h *= 0x7FEB352D;
        h ^= h >>> 15; h *= 0x846CA68B;
        return h ^ (h >>> 16);
    }
}
