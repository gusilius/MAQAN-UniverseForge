package dev.worldforge.mod.client;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One extensible visual catalog shared by political-map, RTS and Diorama adapters.
 * A profile is a visual identity; an asset pack can supply the actual models/textures/animations.
 */
public final class WorldObjectVisualCatalog extends SimplePreparableReloadListener<Map<String, WorldObjectVisualCatalog.Entry>> {
    public record Profile(String id, String category, String icon, float mapScale,
                          String mapTexture, String mapItem, String rtsModel, String dioramaModel,
                          float iconLod, float modelLod) {
        public Profile {
            id = clean(id); category = clean(category); icon = clean(icon);
            mapTexture = clean(mapTexture); mapItem = clean(mapItem);
            rtsModel = clean(rtsModel); dioramaModel = clean(dioramaModel);
            if (id.isBlank() || category.isBlank() || icon.isBlank())
                throw new IllegalArgumentException("visual profile id/category/icon are required");
            if (!Float.isFinite(mapScale) || mapScale < 12 || mapScale > 96)
                throw new IllegalArgumentException("mapScale must be in [12,96]");
            if (!Float.isFinite(iconLod) || !Float.isFinite(modelLod) || iconLod < 0 || modelLod < iconLod)
                throw new IllegalArgumentException("LOD thresholds must be finite and modelLod >= iconLod");
        }
    }

    /** User-facing catalog card metadata. Asset references are namespaced resource IDs, never file paths. */
    public record Entry(Profile profile, String displayName, String description, String previewTexture,
                        String assetPack, String rig, String animationSet, List<String> tags,
                        List<String> compatibleTypes, boolean selectable, boolean defaultForCategory) {
        public Entry {
            if (profile == null) throw new IllegalArgumentException("profile");
            displayName = clean(displayName).isBlank() ? profile.id() : clean(displayName);
            description = clean(description);
            previewTexture = clean(previewTexture);
            assetPack = clean(assetPack);
            rig = clean(rig);
            animationSet = clean(animationSet);
            tags = immutable(tags);
            compatibleTypes = immutable(compatibleTypes);
        }
        public String id() { return profile.id(); }
        public String category() { return profile.category(); }
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(v -> clean(v).toLowerCase(Locale.ROOT)).filter(v -> !v.isBlank()).distinct().toList();
    }

    private static final Map<String, Entry> BUILT_INS = builtIns();
    private static final Map<String, Entry> RUNTIME = new ConcurrentHashMap<>();
    private static volatile Map<String, Entry> entries = BUILT_INS;
    private static volatile Map<String, Profile> profiles = profilesOf(BUILT_INS);

    public static void register(Profile profile) {
        if (profile == null) throw new IllegalArgumentException("profile");
        register(new Entry(profile, profile.id(), "", "", "", "", "", List.of(),
                List.of(), true, false));
    }

    /** Runtime registration is useful for add-ons that provide their own asset/model packs. */
    public static void register(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("entry");
        RUNTIME.put(key(entry.id()), entry);
        publish();
    }

    public static void unregister(String id) {
        if (id != null) RUNTIME.remove(key(id));
        publish();
    }

    public static Map<String, Profile> snapshot() { return profiles; }
    public static Map<String, Entry> catalogSnapshot() { return entries; }

    public static Profile find(String id) {
        Entry entry = findEntry(id);
        return entry == null ? null : entry.profile();
    }

    public static Entry findEntry(String id) {
        if (id == null || id.isBlank()) return null;
        return entries.get(key(id));
    }

    /** Returns only selectable profiles valid for the requested object type, sorted for stable UI. */
    public static List<Entry> choicesFor(String objectType) {
        String type = clean(objectType).toLowerCase(Locale.ROOT);
        String category = categoryForType(type);
        return entries.values().stream()
                .filter(Entry::selectable)
                .filter(e -> e.compatibleTypes().isEmpty()
                        ? e.category().equals(category) || e.category().equals("world-object")
                        : e.compatibleTypes().contains(type))
                .sorted(java.util.Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Entry::id))
                .toList();
    }

    /** The automatic choice is deterministic and can always be overridden by a user selection. */
    public static Entry defaultFor(String objectType) {
        List<Entry> choices = choicesFor(objectType);
        if (choices.isEmpty()) return findEntry("worldforge:other");
        String category = categoryForType(clean(objectType).toLowerCase(Locale.ROOT));
        Entry exactType = findEntry("worldforge:" + clean(objectType).toLowerCase(Locale.ROOT));
        if (exactType != null && choices.stream().anyMatch(e -> e.id().equals(exactType.id()))) return exactType;
        return choices.stream().filter(Entry::defaultForCategory).findFirst()
                .orElseGet(() -> choices.stream().filter(e -> e.category().equals(category)).findFirst()
                        .orElse(choices.get(0)));
    }

    public static String normalizeSelection(String objectType, String requestedProfile) {
        if (requestedProfile == null || requestedProfile.isBlank()
                || "auto".equalsIgnoreCase(requestedProfile) || "automatic".equalsIgnoreCase(requestedProfile)) {
            Entry fallback = defaultFor(objectType);
            return fallback == null ? "worldforge:other" : fallback.id();
        }
        Entry explicit = findEntry(requestedProfile);
        if (explicit == null || !choicesFor(objectType).stream().anyMatch(e -> e.id().equals(explicit.id()))) {
            Entry fallback = defaultFor(objectType);
            return fallback == null ? "worldforge:other" : fallback.id();
        }
        return explicit.id();
    }

    public static Profile resolve(Markers.Marker marker) {
        if (marker != null) {
            String selected = normalizeSelection(marker.type, marker.visualProfile);
            Profile explicit = find(selected);
            if (explicit != null) return explicit;
        }
        Entry fallback = defaultFor(marker == null ? "other" : marker.type);
        return fallback == null ? profiles.get("worldforge:other") : fallback.profile();
    }

    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new WorldObjectVisualCatalog());
    }

    @Override
    protected Map<String, Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Entry> loaded = new LinkedHashMap<>(BUILT_INS);
        manager.listResources("worldforge/visuals", id -> id.getPath().endsWith(".json"))
                .entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(resource -> {
                    try (InputStreamReader reader = new InputStreamReader(resource.getValue().open(), StandardCharsets.UTF_8)) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        String id = string(json, "id", defaultId(resource.getKey()));
                        String mapTexture = resourceId(manager, string(json, "mapTexture", ""), id, "map texture");
                        String previewTexture = resourceId(manager, string(json, "previewTexture", ""), id, "preview texture");
                        String mapItem = string(json, "mapItem", "");
                        if (!mapItem.isBlank() && ResourceLocation.tryParse(mapItem) == null) mapItem = "";
                        Profile profile = new Profile(id, string(json, "category", "world-object"),
                                string(json, "icon", "other"), number(json, "mapScale", 34),
                                mapTexture, mapItem, string(json, "rtsModel", ""),
                                string(json, "dioramaModel", string(json, "rtsModel", "")),
                                number(json, "iconLod", 0), number(json, "modelLod", 2));
                        Entry entry = new Entry(profile, string(json, "displayName", id),
                                string(json, "description", ""), previewTexture,
                                string(json, "assetPack", ""), string(json, "rig", ""),
                                string(json, "animationSet", ""), strings(json, "tags"),
                                strings(json, "compatibleTypes"), bool(json, "selectable", true),
                                bool(json, "defaultForCategory", false));
                        loaded.put(key(id), entry);
                    } catch (Exception failure) {
                        dev.worldforge.mod.WorldForgeMod.LOGGER.warn(
                                "World object visual profile {} was ignored: {}", resource.getKey(), failure.toString());
                    }
                });
        return Collections.unmodifiableMap(loaded);
    }

    @Override
    protected void apply(Map<String, Entry> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Entry> merged = new LinkedHashMap<>(prepared);
        merged.putAll(RUNTIME);
        entries = Collections.unmodifiableMap(merged);
        profiles = profilesOf(entries);
        dev.worldforge.mod.WorldForgeMod.LOGGER.info("Loaded {} shared world-object visual catalog entries", entries.size());
    }

    private static String resourceId(ResourceManager manager, String value, String owner, String label) {
        if (value.isBlank()) return "";
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || manager.getResource(id).isEmpty()) {
            dev.worldforge.mod.WorldForgeMod.LOGGER.warn(
                    "Visual profile {} references missing/invalid {}; using generated fallback", owner, label);
            return "";
        }
        return value;
    }

    private static String key(String id) { return clean(id).toLowerCase(Locale.ROOT); }
    private static Map<String, Profile> profilesOf(Map<String, Entry> source) {
        Map<String, Profile> result = new LinkedHashMap<>();
        source.forEach((id, entry) -> result.put(id, entry.profile()));
        return Collections.unmodifiableMap(result);
    }

    private static String categoryForType(String type) {
        return switch (type) {
            case "capital", "city", "settlement", "village" -> "settlement";
            case "castle", "fort" -> "fortification";
            case "port" -> "infrastructure";
            case "mine" -> "industry";
            case "temple" -> "civic";
            case "ruin" -> "landmark";
            case "camp", "caravan" -> "mobile-object";
            case "army", "commander", "corps", "unit" -> "army";
            default -> "world-object";
        };
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

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        try { return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static List<String> strings(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) return List.of();
        List<String> result = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement element : array) if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            result.add(element.getAsString());
        return result;
    }

    private static Map<String, Entry> builtIns() {
        Map<String, Entry> result = new LinkedHashMap<>();
        add(result, "capital", "settlement", "capital", "Столица", 44, "minecraft:gold_block", List.of("capital"), true);
        add(result, "city", "settlement", "city", "Город", 38, "minecraft:bricks", List.of("city", "settlement"), true);
        add(result, "settlement", "settlement", "settlement", "Поселение", 32, "minecraft:oak_planks", List.of("settlement"), false);
        add(result, "village", "settlement", "village", "Деревня", 29, "minecraft:oak_planks", List.of("village"), true);
        add(result, "castle", "fortification", "castle", "Замок", 38, "minecraft:stone_bricks", List.of("castle", "fort"), true);
        add(result, "port", "infrastructure", "port", "Порт", 32, "minecraft:barrel", List.of("port"), true);
        add(result, "mine", "industry", "mine", "Шахта", 28, "minecraft:iron_pickaxe", List.of("mine"), true);
        add(result, "temple", "civic", "temple", "Храм", 31, "minecraft:chiseled_stone_bricks", List.of("temple"), true);
        add(result, "ruin", "landmark", "ruin", "Руины", 28, "minecraft:mossy_stone_bricks", List.of("ruin"), true);
        add(result, "camp", "mobile-object", "camp", "Лагерь", 27, "minecraft:campfire", List.of("camp"), true);
        add(result, "caravan", "mobile-object", "caravan", "Караван", 27, "minecraft:chest", List.of("caravan"), true);
        add(result, "army", "army", "army", "Армия · стандарт", 34, "minecraft:shield",
                List.of("army", "commander", "corps", "unit"), true);
        add(result, "other", "world-object", "other", "Другой объект", 26, "minecraft:compass", List.of(), true);
        return Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, Entry> into, String id, String category, String icon,
                            String displayName, float scale, String mapItem,
                            List<String> compatibleTypes, boolean defaultForCategory) {
        Profile profile = new Profile("worldforge:" + id, category, icon, scale, "", mapItem, "", "", 0, 2);
        Entry entry = new Entry(profile, displayName, "", "", "", "", "", List.of(),
                compatibleTypes, true, defaultForCategory);
        into.put(key(entry.id()), entry);
    }

    private static void publish() {
        Map<String, Entry> merged = new LinkedHashMap<>(entries);
        merged.putAll(RUNTIME);
        entries = Collections.unmodifiableMap(merged);
        profiles = profilesOf(entries);
    }
}
