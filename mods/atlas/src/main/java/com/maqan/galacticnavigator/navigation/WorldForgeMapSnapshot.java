package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.List;

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
