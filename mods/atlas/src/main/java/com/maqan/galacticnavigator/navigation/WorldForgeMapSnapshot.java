package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned, renderer-neutral import format for WorldForge map exports.
 * Coordinates are Minecraft block coordinates in an explicitly named dimension.
 */
public final class WorldForgeMapSnapshot {
    public int schema_version;
    public String world_id;
    public String generated_at;
    public List<Territory> territories = new ArrayList<>();
    public List<Feature> features = new ArrayList<>();

    private transient Map<String, List<Territory>> territoriesByDimension = Map.of();
    private transient Map<String, List<Feature>> featuresByDimension = Map.of();

    /** Rebuilds immutable dimension lookup tables after validation/deserialization. */
    public void rebuildIndexes() {
        Map<String, List<Territory>> territoryGroups = new LinkedHashMap<>();
        if (territories != null) for (Territory territory : territories) {
            if (territory == null || territory.dimension_id == null
                    || territory.dimension_id.isBlank()) continue;
            territoryGroups.computeIfAbsent(territory.dimension_id, ignored -> new ArrayList<>())
                    .add(territory);
        }
        Map<String, List<Feature>> featureGroups = new LinkedHashMap<>();
        if (features != null) for (Feature feature : features) {
            if (feature == null || feature.dimension_id == null
                    || feature.dimension_id.isBlank()) continue;
            featureGroups.computeIfAbsent(feature.dimension_id, ignored -> new ArrayList<>())
                    .add(feature);
        }
        territoriesByDimension = freeze(territoryGroups);
        featuresByDimension = freeze(featureGroups);
    }

    public List<Territory> territoriesInDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) return List.of();
        return territoriesByDimension.getOrDefault(dimensionId, List.of());
    }

    public List<Feature> featuresInDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) return List.of();
        return featuresByDimension.getOrDefault(dimensionId, List.of());
    }

    private static <T> Map<String, List<T>> freeze(Map<String, List<T>> source) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    public static final class Vertex {
        public double x;
        public double y;
        public double z;
    }

    public static final class Territory {
        public String id;
        public String name;
        public String realm_id;
        public String realm_name;
        public String government;
        public String dimension_id;
        public String administrative_title_id;
        public String control_status;
        public int color;
        public int fill_alpha = 38;
        public List<Vertex> vertices = new ArrayList<>();
    }

    public static final class Feature {
        public String id;
        public String kind;
        public String name;
        public String dimension_id;
        public double x;
        public double y;
        public double z;
        public String realm_id;
        public String territory_id;
        public String race_id;
        public String culture_id;
        public String subtype;
        public int color;
        public java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
    }
}
