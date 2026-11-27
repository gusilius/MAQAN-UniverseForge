package dev.worldforge.people.mc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.worldforge.people.mc.PeopleMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reloadable, data-driven catalog for custom mesh NPCs.
 *
 * This is deliberately independent of Character identity and physical entities: a profile
 * selects visuals only. The GLB GPU/runtime renderer consumes these immutable definitions
 * in a later rendering stage; vanilla/modded entities continue using their own renderers.
 */
public final class NpcModelCatalog extends SimplePreparableReloadListener<Map<String, NpcModelCatalog.Definition>> {
    private static final String DIRECTORY = "models/npc";
    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile Map<String, Definition> definitions = Map.of();

    public record Definition(
            ResourceLocation id,
            ResourceLocation model,
            ResourceLocation texture,
            float scale,
            Map<String, String> animations
    ) {
        public Definition {
            animations = Collections.unmodifiableMap(new LinkedHashMap<>(animations));
        }
    }

    public static Optional<Definition> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : Optional.ofNullable(definitions.get(key.toString()));
    }

    public static Map<String, Definition> snapshot() {
        return definitions;
    }

    public static long generation() {
        return GENERATION.get();
    }

    @Override
    protected Map<String, Definition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Definition> loaded = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> manifests = manager.listResources(
                DIRECTORY, location -> location.getPath().endsWith("/model.json"));

        manifests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> readDefinition(manager, entry.getKey(), entry.getValue(), loaded));

        return Collections.unmodifiableMap(loaded);
    }

    private static void readDefinition(
            ResourceManager manager,
            ResourceLocation manifestLocation,
            Resource manifest,
            Map<String, Definition> loaded
    ) {
        try (InputStreamReader reader = new InputStreamReader(manifest.open(), StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String rawId = string(json, "id", defaultId(manifestLocation));
            ResourceLocation id = ResourceLocation.tryParse(rawId);
            if (id == null) throw new IllegalArgumentException("invalid model id: " + rawId);

            String format = string(json, "format", "gltf2");
            if (!"gltf2".equalsIgnoreCase(format))
                throw new IllegalArgumentException("unsupported format '" + format + "' (expected gltf2)");

            ResourceLocation model = resolve(manifestLocation, string(json, "model", ""));
            if (model == null || !model.getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".glb"))
                throw new IllegalArgumentException("model must reference a .glb resource");
            if (manager.getResource(model).isEmpty())
                throw new IllegalArgumentException("GLB resource not found: " + model);

            ResourceLocation texture = null;
            String texturePath = string(json, "texture", "");
            if (!texturePath.isBlank()) {
                texture = resolve(manifestLocation, texturePath);
                if (texture == null || manager.getResource(texture).isEmpty())
                    throw new IllegalArgumentException("texture resource not found: " + texturePath);
            }

            float scale = json.has("scale") ? json.get("scale").getAsFloat() : 1.0F;
            if (!Float.isFinite(scale) || scale <= 0.0F || scale > 8.0F)
                throw new IllegalArgumentException("scale must be finite and in range (0, 8]");

            Map<String, String> animations = new LinkedHashMap<>();
            JsonElement rawAnimations = json.get("animations");
            if (rawAnimations != null && rawAnimations.isJsonObject()) {
                for (Map.Entry<String, JsonElement> animation : rawAnimations.getAsJsonObject().entrySet()) {
                    if (animation.getValue().isJsonPrimitive()
                            && animation.getValue().getAsJsonPrimitive().isString()) {
                        String clip = animation.getValue().getAsString().trim();
                        if (!clip.isEmpty()) animations.put(animation.getKey(), clip);
                    }
                }
            }

            Definition definition = new Definition(id, model, texture, scale, animations);
            if (loaded.putIfAbsent(id.toString(), definition) != null)
                throw new IllegalArgumentException("duplicate model id: " + id);
        } catch (Exception failure) {
            PeopleMod.LOGGER.warn("wf_people: skipped NPC model manifest {}: {}",
                    manifestLocation, failure.getMessage());
        }
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().trim() : fallback;
    }

    private static String defaultId(ResourceLocation manifest) {
        String path = manifest.getPath();
        String prefix = DIRECTORY + "/";
        if (path.startsWith(prefix) && path.endsWith("/model.json"))
            path = path.substring(prefix.length(), path.length() - "/model.json".length());
        return manifest.getNamespace() + ":" + path;
    }

    private static ResourceLocation resolve(ResourceLocation manifest, String raw) {
        if (raw == null || raw.isBlank()) return null;
        ResourceLocation parsed = ResourceLocation.tryParse(raw.trim());
        if (parsed == null) return null;
        return raw.indexOf(':') >= 0
                ? parsed
                : new ResourceLocation(manifest.getNamespace(), parsed.getPath());
    }

    @Override
    protected void apply(Map<String, Definition> prepared, ResourceManager manager, ProfilerFiller profiler) {
        definitions = prepared;
        long generation = GENERATION.incrementAndGet();
        PeopleMod.LOGGER.info("wf_people: loaded {} custom NPC model profiles (resource generation {})",
                prepared.size(), generation);
    }
}
