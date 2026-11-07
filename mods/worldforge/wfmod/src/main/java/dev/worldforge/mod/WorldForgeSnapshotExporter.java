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
 * Exports the authoritative WorldForge map metadata into Atlas' versioned,
 * renderer-neutral snapshot format. Geometry is deliberately not fabricated:
 * political titles are exported at their real capitals until polygon contours
 * can be transferred without losing islands or holes.
 */
public final class WorldForgeSnapshotExporter {
    private WorldForgeSnapshotExporter() {}

    public static Path export(MinecraftServer server, ServerLevel level, MapData map) throws IOException {
        if (server == null || level == null || map == null)
            throw new IllegalArgumentException("WorldForge server, level and map are required");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", 1);
        String dimensionId = level.dimension().location().toString();
        root.put("world_id", server.getWorldData().getLevelName() + "@" + dimensionId + "#" + map.name);
        root.put("generated_at", Instant.now().toString());

        List<Object> territories = new ArrayList<>();
        List<Object> features = new ArrayList<>();
        root.put("territories", territories);
        root.put("features", features);

        ChunkGenerator rawGenerator = level.getChunkSource().getGenerator();
        MapChunkGenerator generator = rawGenerator instanceof MapChunkGenerator
                ? (MapChunkGenerator) rawGenerator : null;

        if (map.titles != null) {
            for (TitleData.Title title : map.titles.allTitles()) {
                if (title == null || title.key == null || title.key.isBlank()) continue;
                Map<String, Object> attrs = new LinkedHashMap<>();
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
                String realmName = "";
                if (title.tier == 0) {
                    TitleData.Realm realm = map.titles.realmOf(title);
                    if (realm != null) {
                        realmId = realm.id == null ? "" : realm.id;
                        realmName = realm.name == null ? "" : realm.name;
                        attrs.put("realm_ruler", safe(realm.ruler));
                        attrs.put("realm_relation", safe(realm.relation));
                        attrs.put("realm_overlord_id", safe(realm.overlordId));
                        copyProperties(attrs, "realm.", realm.props);
                    }
                }
                BlockPos capital = generator == null
                        ? new BlockPos(title.capX, map.seaLevel, title.capZ)
                        : generator.surfaceAt(title.capX, title.capZ);
                Map<String, Object> feature = feature(
                        "worldforge:" + map.name + ":title:" + title.key,
                        title.tier == 0 ? "province" : "administrative_title",
                        title.name, dimensionId, title.capX, capital.getY(), title.capZ,
                        title.rgb, realmId, "", attrs);
                if (!realmName.isBlank()) feature.put("realm_name", realmName);
                features.add(feature);
            }
        }

        if (map.regions != null) {
            for (RegionData.Region region : map.regions.list) {
                if (region == null) continue;
                BlockPos capital = generator == null
                        ? new BlockPos(region.capX, map.seaLevel, region.capZ)
                        : generator.surfaceAt(region.capX, region.capZ);
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

        for (Markers.Marker marker : EditorManager.markers(server, map).list()) {
            if (marker == null || marker.id == null || marker.id.isBlank()) continue;
            BlockPos pos = generator == null
                    ? new BlockPos(marker.x, map.seaLevel, marker.z)
                    : generator.surfaceAt(marker.x, marker.z);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("map_id", map.name);
            attrs.put("marker_type", safe(marker.type));
            attrs.put("description", safe(marker.desc));
            features.add(feature("worldforge:" + map.name + ":marker:" + marker.id,
                    "marker", marker.name, dimensionId, marker.x, pos.getY(), marker.z,
                    marker.rgb, "", safe(marker.type), attrs));
        }

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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
