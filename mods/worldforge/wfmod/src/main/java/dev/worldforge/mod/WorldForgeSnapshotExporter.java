package dev.worldforge.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports authoritative WorldForge province contours and political metadata
 * into Atlas' versioned, renderer-neutral snapshot format. Raster boundaries
 * are transferred as independent rings so islands and holes remain intact.
 */
public final class WorldForgeSnapshotExporter {
    @FunctionalInterface
    public interface Contributor {
        void contribute(MinecraftServer server, ServerLevel level, MapData map, List<Object> features);
    }

    private static final java.util.concurrent.CopyOnWriteArrayList<Contributor> CONTRIBUTORS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private WorldForgeSnapshotExporter() {}

    /** Optional addon extension point; contributors append records to the existing Atlas feature collection. */
    public static void registerContributor(Contributor contributor) {
        if (contributor != null && !CONTRIBUTORS.contains(contributor)) CONTRIBUTORS.add(contributor);
    }

    public static void unregisterContributor(Contributor contributor) {
        CONTRIBUTORS.remove(contributor);
    }

    public static Path export(MinecraftServer server, ServerLevel level, MapData map) throws IOException {
        if (server == null || level == null || map == null)
            throw new IllegalArgumentException("WorldForge server, level and map are required");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1);
        root.put("world_id", server.getWorldData().getLevelName());
        root.put("generated_at", Instant.now().toString());

        List<Object> territories = new ArrayList<>();
        List<Object> features = new ArrayList<>();
        List<Object> politicalBoundaries = new ArrayList<>();
        root.put("territories", territories);
        root.put("features", features);
        root.put("political_boundaries", politicalBoundaries);

        int exportedMaps = 0;
        for (ServerLevel candidate : server.getAllLevels()) {
            ChunkGenerator candidateGenerator = candidate.getChunkSource().getGenerator();
            if (!(candidateGenerator instanceof MapChunkGenerator mapGenerator)) continue;
            MapData candidateMap = mapGenerator.data();
            if (candidateMap == null) continue;
            appendMap(server, candidate, candidateMap, territories, features, politicalBoundaries);
            exportedMaps++;
        }
        if (exportedMaps == 0)
            throw new IllegalStateException("No loaded WorldForge map dimensions are available");

        Path output = FMLPaths.CONFIGDIR.get().resolve("maqan").resolve("worldforge")
                .resolve("map_snapshot.json");
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    /** Appends one currently loaded WorldForge dimension to the shared world snapshot. */
    private static void appendMap(MinecraftServer server, ServerLevel level, MapData map,
            List<Object> territories, List<Object> features, List<Object> politicalBoundaries) {
        String dimensionId = level.dimension().location().toString();
        MapChunkGenerator generator = (MapChunkGenerator) level.getChunkSource().getGenerator();
        
        if (map.titles != null) {
            Map<Integer, List<List<WorldForgeProvinceContourBuilder.Point>>> contours =
                    WorldForgeProvinceContourBuilder.build(map.titles.pixels(), map.titles.w, map.titles.h);
            for (TitleData.Title province : map.titles.allTitles()) {
                if (province == null || province.tier != 0) continue;
                List<List<WorldForgeProvinceContourBuilder.Point>> provinceRings = contours.get(province.id);
                if (provinceRings == null || provinceRings.isEmpty()) continue;
                TitleData.Realm realm = map.titles.realmOf(province);
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("map_id", map.name);
                attrs.put("province_numeric_id", Integer.toString(province.id));
                attrs.put("area_blocks", Long.toString(province.areaBlocks));
                attrs.put("area_pct", Double.toString(province.areaPct));
                attrs.put("neighbors", joinIds(map.titles, province.neighbors));
                attrs.put("parent_title_id", province.parent > 0 && map.titles.get(province.parent) != null
                        ? map.titles.get(province.parent).key : "");
                copyProperties(attrs, "title.", province.props);
        
                Map<String, Object> territory = new LinkedHashMap<>();
                territory.put("id", "worldforge:" + map.name + ":province:" + province.key);
                territory.put("name", safe(province.name));
                territory.put("realm_id", realm == null ? "" : safe(realm.id));
                territory.put("realm_name", realm == null ? "" : safe(realm.name));
                territory.put("dimension_id", dimensionId);
                territory.put("administrative_title_id", safe(province.key));
                territory.put("color", province.rgb);
                territory.put("fill_alpha", 38);
                if (realm != null) {
                    territory.put("government", stringProperty(realm.props, "government"));
                    territory.put("control_status", stringProperty(realm.props, "control_status"));
                    territory.put("race_id", stringProperty(realm.props, "race"));
                    territory.put("culture_id", stringProperty(realm.props, "culture"));
                    attrs.put("realm_ruler", safe(realm.ruler));
                    attrs.put("realm_relation", safe(realm.relation));
                    attrs.put("realm_overlord_id", safe(realm.overlordId));
                    copyProperties(attrs, "realm.", realm.props);
                }
                territory.put("attributes", attrs);
                List<Object> rings = new ArrayList<>();
                for (List<WorldForgeProvinceContourBuilder.Point> ring : provinceRings) {
                    List<Object> vertices = new ArrayList<>();
                    for (WorldForgeProvinceContourBuilder.Point point : ring) {
                        double x = (point.x() - map.titles.w * 0.5) * map.titles.bpp;
                        double z = (point.z() - map.titles.h * 0.5) * map.titles.bpp;
                        int y = generator == null ? map.seaLevel
                                : generator.surfaceAt((int) Math.round(x), (int) Math.round(z)).getY();
                        Map<String, Object> vertex = new LinkedHashMap<>();
                        vertex.put("x", x);
                        vertex.put("y", y);
                        vertex.put("z", z);
                        vertices.add(vertex);
                    }
                    rings.add(vertices);
                }
                territory.put("rings", rings);
                territory.put("vertices", rings.get(0));
                territories.add(territory);
            }
        
            // Aggregate the authoritative province raster by its resolved realm LUT.
            // This creates real state borders without maintaining a second political model.
            List<TitleData.Realm> realmList = map.titles.realmList();
            int[] realmLut = map.titles.realmLut();
            if (realmList.size() > 65_535)
                throw new IllegalStateException("WorldForge realm count exceeds contour label capacity");
            short[] realmPixels = new short[map.titles.w * map.titles.h];
            short[] provincePixels = map.titles.pixels();
            for (int i = 0; i < provincePixels.length; i++) {
                int provinceId = provincePixels[i] & 0xFFFF;
                int realmIndex = provinceId > 0 && provinceId < realmLut.length
                        ? realmLut[provinceId] : 0;
                realmPixels[i] = (short) realmIndex;
            }
            Map<Integer, List<List<WorldForgeProvinceContourBuilder.Point>>> realmContours =
                    WorldForgeProvinceContourBuilder.build(realmPixels, map.titles.w, map.titles.h);
            for (int realmIndex = 0; realmIndex < realmList.size(); realmIndex++) {
                TitleData.Realm realm = realmList.get(realmIndex);
                if (realm == null || realm.id == null || realm.id.isBlank()) continue;
                List<List<WorldForgeProvinceContourBuilder.Point>> realmRings =
                        realmContours.get(realmIndex + 1);
                if (realmRings == null || realmRings.isEmpty()) continue;
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("map_id", map.name);
                attrs.put("relation", safe(realm.relation));
                attrs.put("overlord_id", safe(realm.overlordId));
                attrs.put("subjects", String.join(",", realm.subjectIds));
                attrs.put("area_blocks", Long.toString(realm.areaBlocks));
                attrs.put("title_keys", String.join(",", realm.titleKeys));
                copyProperties(attrs, "realm.", realm.props);
                Map<String, Object> boundary = new LinkedHashMap<>();
                boundary.put("id", "worldforge:" + map.name + ":realm:" + realm.id);
                boundary.put("name", safe(realm.name));
                boundary.put("realm_id", safe(realm.id));
                boundary.put("dimension_id", dimensionId);
                boundary.put("overlord_id", safe(realm.overlordId));
                boundary.put("relation", safe(realm.relation));
                boundary.put("ruler", safe(realm.ruler));
                boundary.put("subjects", new ArrayList<>(realm.subjectIds));
                boundary.put("color", realm.rgb & 0xFFFFFF);
                boundary.put("attributes", attrs);
                List<Object> rings = new ArrayList<>();
                for (List<WorldForgeProvinceContourBuilder.Point> ring : realmRings) {
                    List<Object> vertices = new ArrayList<>();
                    for (WorldForgeProvinceContourBuilder.Point point : ring) {
                        double x = (point.x() - map.titles.w * 0.5) * map.titles.bpp;
                        double z = (point.z() - map.titles.h * 0.5) * map.titles.bpp;
                        int y = generator == null ? map.seaLevel
                                : generator.surfaceAt((int) Math.round(x), (int) Math.round(z)).getY();
                        Map<String, Object> vertex = new LinkedHashMap<>();
                        vertex.put("x", x);
                        vertex.put("y", y);
                        vertex.put("z", z);
                        vertices.add(vertex);
                    }
                    rings.add(vertices);
                }
                boundary.put("rings", rings);
                politicalBoundaries.add(boundary);
            }
        
            // Keep higher-level titles as selectable map features, linked to the province layer.
            for (TitleData.Title title : map.titles.allTitles()) {
                if (title == null || title.tier == 0 || title.key == null || title.key.isBlank()) continue;
                BlockPos capital = generator.surfaceAt(title.capX, title.capZ);
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("map_id", map.name);
                attrs.put("title_id", title.key);
                attrs.put("numeric_id", Integer.toString(title.id));
                attrs.put("tier", Integer.toString(title.tier));
                attrs.put("parent_title_id", title.parent > 0 && map.titles.get(title.parent) != null
                        ? map.titles.get(title.parent).key : "");
                attrs.put("area_blocks", Long.toString(title.areaBlocks));
                attrs.put("area_pct", Double.toString(title.areaPct));
                attrs.put("children", joinIds(map.titles, title.children));
                attrs.put("neighbors", joinIds(map.titles, title.neighbors));
                copyProperties(attrs, "title.", title.props);
                String realmId = "";
                if (title.tier == 1) {
                    TitleData.Realm realm = map.titles.realmOf(title);
                    if (realm != null) {
                        realmId = safe(realm.id);
                        attrs.put("realm_name", safe(realm.name));
                        attrs.put("realm_ruler", safe(realm.ruler));
                        attrs.put("realm_overlord_id", safe(realm.overlordId));
                        attrs.put("realm_relation", safe(realm.relation));
                        copyProperties(attrs, "realm.", realm.props);
                    }
                }
                features.add(feature("worldforge:" + map.name + ":title:" + title.key,
                        "administrative_title", title.name, dimensionId, title.capX, capital.getY(),
                        title.capZ, title.rgb, realmId, "", attrs));
            }
        }
        
        if (map.regions != null) {
            for (RegionData.Region region : map.regions.list) {
                if (region == null) continue;
                BlockPos capital = generator.surfaceAt(region.capX, region.capZ);
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("map_id", map.name);
                attrs.put("region_numeric_id", Integer.toString(region.id));
                attrs.put("area_blocks", Long.toString(region.areaBlocks));
                attrs.put("area_pct", Double.toString(region.areaPct));
                attrs.put("neighbors", join(region.neighbors));
                copyProperties(attrs, "region.", region.props);
                features.add(feature("worldforge:" + map.name + ":region:" + region.id,
                        "region_capital", region.name, dimensionId, region.capX, capital.getY(),
                        region.capZ, region.rgb, "", "", attrs));
            }
        }
        
        for (Contributor contributor : CONTRIBUTORS) {
            try {
                contributor.contribute(server, level, map, features);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("WorldForge Atlas snapshot contributor failed in "
                        + dimensionId, ex);
            }
        }
        
        for (Markers.Marker marker : EditorManager.markers(server, map).list()) {
            if (marker == null || marker.id == null || marker.id.isBlank()) continue;
            BlockPos pos = generator.surfaceAt(marker.x, marker.z);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("map_id", map.name);
            attrs.put("marker_type", safe(marker.type));
            attrs.put("description", safe(marker.desc));
            features.add(feature("worldforge:" + map.name + ":marker:" + marker.id,
                    "marker", marker.name, dimensionId, marker.x, pos.getY(), marker.z,
                    marker.rgb, "", safe(marker.type), attrs));
        }
    }

    private static Map<String, Object> feature(String id, String kind, String name,
            String dimension, double x, double y, double z, int color,
            String realmId, String subtype, Map<String, String> attributes) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("kind", kind);
        value.put("name", safe(name));
        value.put("dimension_id", dimension);
        value.put("x", x);
        value.put("y", y);
        value.put("z", z);
        value.put("realm_id", safe(realmId));
        value.put("subtype", safe(subtype));
        value.put("color", color & 0xFFFFFF);
        value.put("attributes", attributes);
        return value;
    }

    private static String joinIds(TitleData data, int[] ids) {
        List<String> values = new ArrayList<>();
        if (ids != null) for (int id : ids) {
            TitleData.Title title = data.get(id);
            if (title != null && title.key != null) values.add(title.key);
        }
        return String.join(",", values);
    }

    private static String join(int[] ids) {
        if (ids == null || ids.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int id : ids) {
            if (out.length() > 0) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    private static void copyProperties(Map<String, String> target, String prefix,
            Map<String, Object> properties) {
        if (properties == null) return;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            target.put(prefix + entry.getKey(), entry.getValue() instanceof String
                    ? (String) entry.getValue() : Json.write(entry.getValue()));
        }
    }

    private static String stringProperty(Map<String, Object> properties, String key) {
        if (properties == null) return "";
        Object value = properties.get(key);
        return value instanceof String ? (String) value : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
