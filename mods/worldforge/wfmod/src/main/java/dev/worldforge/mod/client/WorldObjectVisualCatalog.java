package dev.worldforge.mod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.mod.Markers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shared presentation profiles for political-map objects, RTS and Diorama adapters. */
public final class WorldObjectVisualCatalog extends SimplePreparableReloadListener<Map<String, WorldObjectVisualCatalog.Profile>> {
    private static final Map<String, Profile> BUILT_INS = builtIns();
    private static final Map<String, Profile> RUNTIME = new ConcurrentHashMap<>();
    private static volatile Map<String, Profile> profiles = BUILT_INS;

    public record Profile(String id, String category, String icon, float mapScale,
                          String mapTexture, String rtsModel, String dioramaModel,
                          float iconLod, float modelLod) {
        public Profile {
            id = clean(id); category = clean(category); icon = clean(icon);
            mapTexture = clean(mapTexture); rtsModel = clean(rtsModel); dioramaModel = clean(dioramaModel);
            if (id.isBlank() || category.isBlank() || icon.isBlank())
                throw new IllegalArgumentException("visual profile id/category/icon are required");
            if (!Float.isFinite(mapScale) || mapScale < 12 || mapScale > 96)
                throw new IllegalArgumentException("mapScale must be in [12,96]");
            if (!Float.isFinite(iconLod) || !Float.isFinite(modelLod) || iconLod < 0 || modelLod < iconLod)
                throw new IllegalArgumentException("LOD thresholds must be finite and modelLod >= iconLod");
        }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public static void register(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        RUNTIME.put(profile.id(), profile);
        publish();
    }
    public static void unregister(String id) {
        if (id != null) RUNTIME.remove(id.trim());
        publish();
    }
    public static Map<String, Profile> snapshot() { return profiles; }
    public static Profile find(String id) {
        if (id == null || id.isBlank()) return null;
        return profiles.get(id.trim().toLowerCase(Locale.ROOT));
    }
    public static Profile resolve(Markers.Marker marker) {
        if (marker != null) {
            Profile explicit = find(marker.visualProfile);
            if (explicit != null) return explicit;
            Profile byType = find("worldforge:" + clean(marker.type).toLowerCase(Locale.ROOT));
            if (byType != null) return byType;
        }
        return profiles.get("worldforge:other");
    }
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new WorldObjectVisualCatalog());
    }

    @Override
    protected Map<String, Profile> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Profile> loaded = new LinkedHashMap<>(BUILT_INS);
        manager.listResources("worldforge/visuals", id -> id.getPath().endsWith(".json"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    try (InputStreamReader reader = new InputStreamReader(entry.getValue().open(), StandardCharsets.UTF_8)) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        String id = string(json, "id", defaultId(entry.getKey()));
                        String mapTexture = string(json, "mapTexture", "");
                        if (!mapTexture.isBlank()) {
                            ResourceLocation textureId = ResourceLocation.tryParse(mapTexture);
                            if (textureId == null || manager.getResource(textureId).isEmpty()) {
                                dev.worldforge.mod.WorldForgeMod.LOGGER.warn(
                                        "Visual profile {} references missing map texture {}; using vector fallback", id, mapTexture);
                                mapTexture = "";
                            }
                        }
                        String rtsModel = string(json, "rtsModel", "");
                        Profile profile = new Profile(id, string(json, "category", "world-object"),
                                string(json, "icon", "other"), number(json, "mapScale", 34),
                                mapTexture, rtsModel, string(json, "dioramaModel", rtsModel),
                                number(json, "iconLod", 0), number(json, "modelLod", 2));
                        loaded.put(profile.id(), profile);
                    } catch (Exception failure) {
                        dev.worldforge.mod.WorldForgeMod.LOGGER.warn(
                                "World object visual profile {} was ignored: {}", entry.getKey(), failure.toString());
                    }
                });
        return Collections.unmodifiableMap(loaded);
    }

    @Override
    protected void apply(Map<String, Profile> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Profile> merged = new LinkedHashMap<>(prepared);
        merged.putAll(RUNTIME);
        profiles = Collections.unmodifiableMap(merged);
        dev.worldforge.mod.WorldForgeMod.LOGGER.info("Loaded {} shared world-object visual profiles", profiles.size());
    }

    private static String defaultId(ResourceLocation location) {
        String path = location.getPath(), prefix = "worldforge/visuals/";
        if (path.startsWith(prefix)) path = path.substring(prefix.length());
        if (path.endsWith(".json")) path = path.substring(0, path.length() - 5);
        return location.getNamespace() + ":" + path.replace('/', '.');
    }
    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : fallback;
    }
    private static float number(JsonObject json, String key, float fallback) {
        JsonElement value = json.get(key);
        try { return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    private static Map<String, Profile> builtIns() {
        Map<String, Profile> result = new LinkedHashMap<>();
        add(result, "capital", "settlement", "capital", 44);
        add(result, "city", "settlement", "city", 38);
        add(result, "settlement", "settlement", "settlement", 32);
        add(result, "village", "settlement", "village", 29);
        add(result, "castle", "fortification", "castle", 38);
        add(result, "port", "infrastructure", "port", 32);
        add(result, "mine", "industry", "mine", 28);
        add(result, "temple", "civic", "temple", 31);
        add(result, "ruin", "landmark", "ruin", 28);
        add(result, "camp", "mobile-object", "camp", 27);
        add(result, "caravan", "mobile-object", "caravan", 27);
        add(result, "army", "army", "army", 34);
        add(result, "other", "world-object", "other", 26);
        return Collections.unmodifiableMap(result);
    }
    private static void add(Map<String, Profile> into, String id, String category, String icon, float scale) {
        Profile profile = new Profile("worldforge:" + id, category, icon, scale, "", "", "", 0, 2);
        into.put(profile.id(), profile);
    }
    private static void publish() {
        Map<String, Profile> merged = new LinkedHashMap<>(profiles);
        merged.putAll(RUNTIME);
        profiles = Collections.unmodifiableMap(merged);
    }
}
