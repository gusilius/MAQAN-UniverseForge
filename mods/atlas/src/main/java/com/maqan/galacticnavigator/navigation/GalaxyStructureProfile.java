package com.maqan.galacticnavigator.navigation;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * User-owned galaxy partition profiles. Profiles are presentation/organization
 * metadata only: they never create or modify Cosmic Horizons dimensions.
 */
public final class GalaxyStructureProfile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("maqan_galactic_navigator/map_profiles.json");
    private static volatile List<Profile> profiles = List.of();
    private GalaxyStructureProfile() {}

    public enum Layout { HIERARCHY, QUADRANTS, CONCENTRIC_RINGS, SEGMENTUMS, CUSTOM_REGIONS }

    public record Region(String id, String name, String parentId, double minX, double maxX,
                         double minZ, double maxZ, Set<String> systemIds, int color) {
        public Region {
            id = safe(id); name = safe(name); parentId = safe(parentId);
            systemIds = systemIds == null ? Set.of() : Set.copyOf(systemIds);
            if (!Double.isFinite(minX) || !Double.isFinite(maxX)
                    || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
                    || minX > maxX || minZ > maxZ)
                throw new IllegalArgumentException("Invalid region bounds");
        }
        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public record Profile(String id, String name, Layout layout, double centerX, double centerZ,
                          List<Double> ringRadii, List<String> regionNames, List<Region> regions) {
        public Profile {
            id = safe(id); name = safe(name);
            layout = layout == null ? Layout.HIERARCHY : layout;
            ringRadii = ringRadii == null ? List.of() : List.copyOf(ringRadii);
            regionNames = regionNames == null ? List.of() : List.copyOf(regionNames);
            regions = regions == null ? List.of() : List.copyOf(regions);
            if (!Double.isFinite(centerX) || !Double.isFinite(centerZ))
                throw new IllegalArgumentException("Profile center must be finite");
            double previous = 0;
            for (double radius : ringRadii) {
                if (!Double.isFinite(radius) || radius <= previous)
                    throw new IllegalArgumentException("Ring radii must be positive and ascending");
                previous = radius;
            }
        }
    }

    public static synchronized void reload() {
        try {
            if (!Files.exists(FILE)) {
                profiles = List.of();
                return;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new JsonParseException("Root must be an object");
            JsonArray array = parsed.getAsJsonObject().getAsJsonArray("profiles");
            List<Profile> loaded = new ArrayList<>();
            if (array != null) for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject o = element.getAsJsonObject();
                List<Double> rings = new ArrayList<>();
                if (o.has("ring_radii") && o.get("ring_radii").isJsonArray())
                    for (JsonElement e : o.getAsJsonArray("ring_radii")) rings.add(e.getAsDouble());
                List<String> names = strings(o.get("region_names"));
                List<Region> regions = new ArrayList<>();
                if (o.has("regions") && o.get("regions").isJsonArray())
                    for (JsonElement e : o.getAsJsonArray("regions")) if (e.isJsonObject()) {
                        JsonObject r = e.getAsJsonObject();
                        regions.add(new Region(str(r,"id",""), str(r,"name",""), str(r,"parent_id",""),
                                num(r,"min_x",0), num(r,"max_x",0), num(r,"min_z",0), num(r,"max_z",0),
                                new LinkedHashSet<>(strings(r.get("system_ids"))), (int)num(r,"color",0x6688AACC)));
                    }
                Layout layout;
                try { layout = Layout.valueOf(str(o,"layout","HIERARCHY").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ex) { layout = Layout.HIERARCHY; }
                loaded.add(new Profile(str(o,"id","custom"), str(o,"name","Custom map"),
                        layout, num(o,"center_x",0), num(o,"center_z",0), rings, names, regions));
            }
            profiles = List.copyOf(loaded);
        } catch (Exception ex) {
            profiles = List.of();
        }
    }

    public static List<Profile> all() { return profiles; }
    public static Optional<Profile> find(String id) {
        return profiles.stream().filter(p -> p.id().equals(id)).findFirst();
    }
    public static synchronized void save(Profile profile) throws IOException {
        Objects.requireNonNull(profile, "profile");
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.id().equals(profile.id()));
        next.add(profile);
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Profile p : next) array.add(toJson(p));
        root.add("profiles", array);
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        profiles = List.copyOf(next);
    }
    public static synchronized void delete(String id) throws IOException {
        List<Profile> next = new ArrayList<>(profiles);
        next.removeIf(p -> p.id().equals(id));
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Profile p : next) array.add(toJson(p));
        root.add("profiles", array);
        Files.createDirectories(FILE.getParent());
        Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        profiles = List.copyOf(next);
    }
    public static Optional<Region> regionFor(Profile profile, String systemId, double x, double z) {
        if (profile == null) return Optional.empty();
        return profile.regions().stream()
                .filter(r -> r.systemIds().contains(systemId) || r.contains(x,z))
                .min(Comparator.comparingDouble(r -> (r.maxX()-r.minX())*(r.maxZ()-r.minZ())));
    }
    private static JsonObject toJson(Profile p) {
        JsonObject o = new JsonObject();
        o.addProperty("id",p.id()); o.addProperty("name",p.name()); o.addProperty("layout",p.layout().name());
        o.addProperty("center_x",p.centerX()); o.addProperty("center_z",p.centerZ());
        JsonArray rings = new JsonArray(); p.ringRadii().forEach(rings::add); o.add("ring_radii",rings);
        JsonArray names = new JsonArray(); p.regionNames().forEach(names::add); o.add("region_names",names);
        JsonArray regions = new JsonArray();
        for (Region r : p.regions()) {
            JsonObject v = new JsonObject(); v.addProperty("id",r.id()); v.addProperty("name",r.name());
            v.addProperty("parent_id",r.parentId()); v.addProperty("min_x",r.minX()); v.addProperty("max_x",r.maxX());
            v.addProperty("min_z",r.minZ()); v.addProperty("max_z",r.maxZ()); v.addProperty("color",r.color());
            JsonArray ids = new JsonArray(); r.systemIds().forEach(ids::add); v.add("system_ids",ids); regions.add(v);
        }
        o.add("regions",regions); return o;
    }
    private static List<String> strings(JsonElement e) {
        List<String> result = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (JsonElement v : e.getAsJsonArray())
            if (v.isJsonPrimitive()) result.add(v.getAsString());
        return result;
    }
    private static String str(JsonObject o,String k,String d) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }
        catch (RuntimeException ex) { return d; }
    }
    private static double num(JsonObject o,String k,double d) {
        try { return o.has(k) ? o.get(k).getAsDouble() : d; }
        catch (RuntimeException ex) { return d; }
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
