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
    private transient Map<String,List<Territory>> territoriesByDimension = Map.of();
    private transient Map<String,List<Feature>> featuresByDimension = Map.of();

    public void rebuildIndexes() {
        Map<String,List<Territory>> tg=new LinkedHashMap<>();
        if(territories!=null) for(Territory t:territories)
            if(t!=null&&t.dimension_id!=null&&!t.dimension_id.isBlank())
                tg.computeIfAbsent(t.dimension_id,k->new ArrayList<>()).add(t);
        Map<String,List<Feature>> fg=new LinkedHashMap<>();
        if(features!=null) for(Feature f:features)
            if(f!=null&&f.dimension_id!=null&&!f.dimension_id.isBlank())
                fg.computeIfAbsent(f.dimension_id,k->new ArrayList<>()).add(f);
        territoriesByDimension=freeze(tg); featuresByDimension=freeze(fg);
    }
    public List<Territory> territoriesInDimension(String id) {
        return id==null||id.isBlank()?List.of():territoriesByDimension.getOrDefault(id,List.of());
    }
    public List<Feature> featuresInDimension(String id) {
        return id==null||id.isBlank()?List.of():featuresByDimension.getOrDefault(id,List.of());
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
    public static final class Feature {
        public String id,kind,name,dimension_id,realm_id,territory_id,race_id,culture_id,subtype;
        public double x,y,z;
        public int color;
        public Map<String,String> attributes=new LinkedHashMap<>();
    }
}
