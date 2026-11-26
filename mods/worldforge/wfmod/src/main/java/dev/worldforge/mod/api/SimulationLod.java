package dev.worldforge.mod.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared far-to-near simulation policy.
 *
 * Domain simulation remains authoritative and continues at every distance.
 * This class only decides how much Minecraft presence is materialized around
 * players: strategic/regional simulation stays abstract; local residents and
 * work crews become entities near players; interaction detail is reserved for
 * the immediate vicinity. All addons consume the same published observer set.
 */
public final class SimulationLod {
    public static final double INTERACTION_RADIUS = 32.0;
    public static final double PEOPLE_SPAWN_RADIUS = 96.0;
    public static final double PEOPLE_RETAIN_RADIUS = 160.0;
    public static final double WORKER_SPAWN_RADIUS = 64.0;
    public static final double WORKER_RETAIN_RADIUS = 80.0;
    public static final double LOCAL_RADIUS = PEOPLE_RETAIN_RADIUS;
    public static final double REGIONAL_RADIUS = 512.0;

    public enum Band { INTERACTIVE, LOCAL, REGIONAL, STRATEGIC }

    /** Immutable player observation in one Minecraft dimension. */
    public record Observer(String dimension, double x, double z) {
        public Observer {
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
            if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("observer coordinates must be finite");
        }
    }

    private static volatile Map<String, List<Observer>> observersByDimension = Map.of();
    private static volatile long revision;

    private SimulationLod() {}

    /** Publishes a stable, immutable observation snapshot once per server second. */
    public static synchronized void publish(Collection<Observer> observations) {
        Map<String, List<Observer>> grouped = new LinkedHashMap<>();
        if (observations != null) {
            List<Observer> ordered = new ArrayList<>(observations);
            ordered.sort(Comparator.comparing(Observer::dimension)
                    .thenComparingDouble(Observer::x).thenComparingDouble(Observer::z));
            for (Observer observer : ordered)
                if (observer != null) grouped.computeIfAbsent(observer.dimension(), ignored -> new ArrayList<>()).add(observer);
        }
        Map<String, List<Observer>> immutable = new LinkedHashMap<>();
        grouped.forEach((dimension, list) -> immutable.put(dimension, List.copyOf(list)));
        observersByDimension = Map.copyOf(immutable);
        revision++;
    }

    public static synchronized void clear() {
        observersByDimension = Map.of();
        revision++;
    }

    public static long revision() { return revision; }

    public static int observerCount(String dimension) {
        return observersByDimension.getOrDefault(dimension, List.of()).size();
    }

    /** Distance to the nearest active player; infinity means no player in this dimension. */
    public static double nearestDistanceSquared(String dimension, double x, double z) {
        if (dimension == null || !Double.isFinite(x) || !Double.isFinite(z)) return Double.POSITIVE_INFINITY;
        double nearest = Double.POSITIVE_INFINITY;
        for (Observer observer : observersByDimension.getOrDefault(dimension, List.of())) {
            double dx = observer.x() - x;
            double dz = observer.z() - z;
            nearest = Math.min(nearest, dx * dx + dz * dz);
        }
        return nearest;
    }

    public static Band band(String dimension, double x, double z) {
        double distanceSquared = nearestDistanceSquared(dimension, x, z);
        if (distanceSquared <= INTERACTION_RADIUS * INTERACTION_RADIUS) return Band.INTERACTIVE;
        if (distanceSquared <= LOCAL_RADIUS * LOCAL_RADIUS) return Band.LOCAL;
        if (distanceSquared <= REGIONAL_RADIUS * REGIONAL_RADIUS) return Band.REGIONAL;
        return Band.STRATEGIC;
    }

    public static boolean shouldSpawnPeople(String dimension, double x, double z) {
        return within(dimension, x, z, PEOPLE_SPAWN_RADIUS);
    }

    public static boolean shouldRetainPeople(String dimension, double x, double z) {
        return within(dimension, x, z, PEOPLE_RETAIN_RADIUS);
    }

    public static boolean shouldSpawnWorkers(String dimension, double x, double z) {
        return within(dimension, x, z, WORKER_SPAWN_RADIUS);
    }

    public static boolean shouldRetainWorkers(String dimension, double x, double z) {
        return within(dimension, x, z, WORKER_RETAIN_RADIUS);
    }

    /** Physical buildings, roads and work crews are updated only in the local band. */
    public static boolean shouldMaterializeSettlement(String dimension, double x, double z) {
        Band band = band(dimension, x, z);
        return band == Band.INTERACTIVE || band == Band.LOCAL;
    }

    private static boolean within(String dimension, double x, double z, double radius) {
        return nearestDistanceSquared(dimension, x, z) <= radius * radius;
    }
}
