package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, renderer-neutral WorldForge map snapshot. */
public final class WorldForgeMapSnapshot {
    public int schema_version;
    public String world_id;
    public String generated_at;
    public List<Territory> territories = new ArrayList<>();
    public List<Feature> features = new ArrayList<>();
    public List<PoliticalBoundary> political_boundaries = new ArrayList<>();
    /** Downsampled, dimension-wide terrain rasters. Null/empty remains valid for legacy v1 snapshots. */
    public List<Terrain> terrain = new ArrayList<>();
    private transient Map<String,List<Territory>> territoriesByDimension = Map.of();
    private transient Map<String,List<Feature>> featuresByDimension = Map.of();
    private transient Map<String,List<PoliticalBoundary>> boundariesByDimension = Map.of();
    private transient Map<String,Terrain> terrainByDimension = Map.of();

    public void rebuildIndexes() {
        Map<String,List<Territory>> tg=new LinkedHashMap<>();
        if(territories!=null) for(Territory t:territories)
            if(t!=null&&t.dimension_id!=null&&!t.dimension_id.isBlank())
                tg.computeIfAbsent(t.dimension_id,k->new ArrayList<>()).add(t);
        Map<String,List<Feature>> fg=new LinkedHashMap<>();
        if(features!=null) for(Feature f:features)
            if(f!=null&&f.dimension_id!=null&&!f.dimension_id.isBlank())
                fg.computeIfAbsent(f.dimension_id,k->new ArrayList<>()).add(f);
        Map<String,List<PoliticalBoundary>> bg=new LinkedHashMap<>();
        if(political_boundaries!=null) for(PoliticalBoundary b:political_boundaries)
            if(b!=null&&b.dimension_id!=null&&!b.dimension_id.isBlank())
                bg.computeIfAbsent(b.dimension_id,k->new ArrayList<>()).add(b);
        territoriesByDimension=freeze(tg);
        featuresByDimension=freeze(fg);
        boundariesByDimension=freeze(bg);
        Map<String,Terrain> terrainIndex=new LinkedHashMap<>();
        if(terrain!=null)for(Terrain layer:terrain)
            if(layer!=null&&layer.dimension_id!=null&&!layer.dimension_id.isBlank())
                terrainIndex.put(layer.dimension_id,layer);
        terrainByDimension=Collections.unmodifiableMap(terrainIndex);
    }
    public List<Territory> territoriesInDimension(String id) {
        return id==null||id.isBlank()?List.of():territoriesByDimension.getOrDefault(id,List.of());
    }
    public List<Feature> featuresInDimension(String id) {
        return id==null||id.isBlank()?List.of():featuresByDimension.getOrDefault(id,List.of());
    }
    public List<PoliticalBoundary> boundariesInDimension(String id) {
        return id==null||id.isBlank()?List.of():boundariesByDimension.getOrDefault(id,List.of());
    }
    public Terrain terrainInDimension(String id) {
        return id==null||id.isBlank()?null:terrainByDimension.get(id);
    }
    private static <T> Map<String,List<T>> freeze(Map<String,List<T>> src) {
        Map<String,List<T>> out=new LinkedHashMap<>();
        src.forEach((k,v)->out.put(k,List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }
    public static final class Vertex { public double x,y,z; }
    public static final class Territory {
        public String id,name,realm_id,realm_name,government,dimension_id,administrative_title_id,
                control_status,race_id,culture_id;
        public int color,fill_alpha=38;
        /** Legacy single contour retained for existing snapshot producers. */
        public List<Vertex> vertices=new ArrayList<>();
        /** Outer contours, islands and holes; renderer uses even-odd fill. */
        public List<List<Vertex>> rings=new ArrayList<>();
        public Map<String,String> attributes=new LinkedHashMap<>();
    }
    public static final class PoliticalBoundary {
        public String id,name,realm_id,dimension_id,overlord_id,relation,ruler;
        public List<String> subjects=new ArrayList<>();
        public int color;
        public List<List<Vertex>> rings=new ArrayList<>();
        public Map<String,String> attributes=new LinkedHashMap<>();
    }
    /** A bounded regular raster. Sample arrays are row-major and share width*height entries. */
    public static final class Terrain {
        public String dimension_id;
        public int width,height,step_pixels;
        public double blocks_per_pixel,origin_x,origin_z;
        public int[] heights=new int[0];
        public int[] biome_indices=new int[0];
        public int[] river_values=new int[0];
        public String[] biome_palette=new String[0];
        public int[] biome_colors=new int[0];
        public int sampleCount() { return width>0&&height>0?width*height:0; }
        public double sampleX(int index) { return origin_x+((index%width)*step_pixels+step_pixels*0.5)*blocks_per_pixel; }
        public double sampleZ(int index) { return origin_z+((index/width)*step_pixels+step_pixels*0.5)*blocks_per_pixel; }
    }
    public static final class Feature {
        public String id,kind,name,dimension_id,realm_id,territory_id,race_id,culture_id,subtype;
        public double x,y,z;
        public int color;
        /** Optional line or polygon geometry in absolute dimension block coordinates. */
        public List<Vertex> path=new ArrayList<>();
        public List<List<Vertex>> rings=new ArrayList<>();
        public Map<String,String> attributes=new LinkedHashMap<>();
    }
}
