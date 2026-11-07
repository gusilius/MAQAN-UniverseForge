package com.maqan.galacticnavigator.navigation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only client cache for an explicitly exported WorldForge map snapshot.
 *
 * This is a bridge boundary, not a live server transport. A later optional
 * WorldForge integration module can feed the same validated snapshot directly
 * from a server-authoritative network packet.
 */
public final class NavigatorWorldForgeMapStore {
    private static final Gson GSON = new GsonBuilder().create();
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_TERRITORIES = 10_000;
    private static final int MAX_FEATURES = 50_000;
    private static final int MAX_VERTICES_PER_TERRITORY = 4_096;

    private static volatile WorldForgeMapSnapshot snapshot;
    private static volatile String status = "WorldForge snapshot not loaded";
    /** Timestamp of the last successfully validated and published snapshot. */
    private static volatile long lastLoadedAt;
    /** Timestamp of the most recent reload attempt, successful or not. */
    private static volatile long lastAttemptAt;
    /** True when the last reload attempt failed or the source file disappeared. */
    private static volatile boolean stale;

    private NavigatorWorldForgeMapStore() {}

    public static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("maqan").resolve("worldforge")
                .resolve("map_snapshot.json");
    }

    /** Reloads the optional snapshot. Invalid input is rejected as a whole, never partially trusted. */
    public static synchronized boolean reload() {
        lastAttemptAt = System.currentTimeMillis();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            stale = snapshot != null;
            status = stale
                    ? "WorldForge snapshot missing; keeping last known-good data"
                    : "No WorldForge map snapshot";
            return false;
        }
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_FILE_BYTES)
                throw new IllegalArgumentException("Snapshot size must be between 1 byte and 8 MiB");
            WorldForgeMapSnapshot parsed;
            try (Reader reader = Files.newBufferedReader(path)) {
                parsed = GSON.fromJson(reader, WorldForgeMapSnapshot.class);
            }
            validate(parsed);
            parsed.rebuildIndexes();
            snapshot = parsed;
            stale = false;
            status = "WorldForge · " + parsed.features.size() + " objects · "
                    + parsed.territories.size() + " territories";
            lastLoadedAt = System.currentTimeMillis();
            return true;
        } catch (Exception error) {
            // Keep the last known-good snapshot available during transient or malformed updates.
            stale = snapshot != null;
            status = stale
                    ? "WorldForge snapshot rejected; keeping last known-good data: " + safeMessage(error)
                    : "WorldForge snapshot rejected: " + safeMessage(error);
            return false;
        }
    }

    public static WorldForgeMapSnapshot current() { return snapshot; }
    public static String status() { return status; }
    /** Last successful import time; remains unchanged when reload fails. */
    public static long lastLoadedAt() { return lastLoadedAt; }
    /** Last reload attempt time, regardless of its outcome. */
    public static long lastAttemptAt() { return lastAttemptAt; }
    /** Whether the displayed snapshot is retained from an earlier successful import. */
    public static boolean isStale() { return stale; }

    public static List<WorldForgeMapSnapshot.Territory> territories(String dimensionId) {
        WorldForgeMapSnapshot value = snapshot;
        if (value == null || value.territories == null) return Collections.emptyList();
        return value.territoriesInDimension(dimensionId);
    }

    public static List<WorldForgeMapSnapshot.Feature> features(String dimensionId) {
        WorldForgeMapSnapshot value = snapshot;
        if (value == null || value.features == null) return Collections.emptyList();
        return value.featuresInDimension(dimensionId);
    }

    private static void validate(WorldForgeMapSnapshot value) {
        if (value == null) throw new IllegalArgumentException("Empty JSON document");
        if (value.schema_version != 1)
            throw new IllegalArgumentException("Unsupported schema_version: " + value.schema_version);
        if (value.territories == null) value.territories = new ArrayList<>();
        if (value.features == null) value.features = new ArrayList<>();
        if (value.territories.size() > MAX_TERRITORIES || value.features.size() > MAX_FEATURES)
            throw new IllegalArgumentException("Snapshot contains too many map records");

        Set<String> territoryIds = new HashSet<>();
        for (WorldForgeMapSnapshot.Territory territory : value.territories) {
            if (territory == null) throw new IllegalArgumentException("Null territory record");
            territory.id = required(territory.id, "territory.id");
            territory.name = required(territory.name, "territory.name");
            territory.dimension_id = required(territory.dimension_id, "territory.dimension_id");
            if (!territoryIds.add(territory.id))
                throw new IllegalArgumentException("Duplicate territory id: " + territory.id);
            if (territory.vertices == null || territory.vertices.size() < 3
                    || territory.vertices.size() > MAX_VERTICES_PER_TERRITORY)
                throw new IllegalArgumentException("Invalid vertex count for territory " + territory.id);
            territory.fill_alpha = Math.max(0, Math.min(160, territory.fill_alpha));
            for (WorldForgeMapSnapshot.Vertex vertex : territory.vertices) {
                if (vertex == null || !finite(vertex.x) || !finite(vertex.y) || !finite(vertex.z))
                    throw new IllegalArgumentException("Non-finite territory vertex: " + territory.id);
            }
        }

        Set<String> featureIds = new HashSet<>();
        for (WorldForgeMapSnapshot.Feature feature : value.features) {
            if (feature == null) throw new IllegalArgumentException("Null feature record");
            feature.id = required(feature.id, "feature.id");
            feature.kind = required(feature.kind, "feature.kind");
            feature.name = required(feature.name, "feature.name");
            feature.dimension_id = required(feature.dimension_id, "feature.dimension_id");
            if (!featureIds.add(feature.id))
                throw new IllegalArgumentException("Duplicate feature id: " + feature.id);
            if (!finite(feature.x) || !finite(feature.y) || !finite(feature.z))
                throw new IllegalArgumentException("Non-finite feature coordinates: " + feature.id);
            if (feature.attributes == null) feature.attributes = new java.util.LinkedHashMap<>();
            if (feature.attributes.size() > 64)
                throw new IllegalArgumentException("Too many attributes on feature " + feature.id);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (value.length() > 256) throw new IllegalArgumentException(field + " is too long");
        return value.trim();
    }

    private static boolean finite(double value) { return Double.isFinite(value); }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
