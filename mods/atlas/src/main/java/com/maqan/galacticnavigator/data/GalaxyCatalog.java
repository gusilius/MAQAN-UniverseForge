package com.maqan.galacticnavigator.data;

import com.google.gson.*;
import java.util.*;

/** In-memory view of the active Cosmic Horizons solar-system resources. */
public final class GalaxyCatalog {
    public static volatile JsonObject ROOT = emptyRoot();
    public static volatile String STATUS = "Waiting for Cosmic Horizons resources";
    private static boolean loaded;
    private GalaxyCatalog() {}

    private static JsonObject emptyRoot() {
        JsonObject root = new JsonObject();
        root.add("universes", new JsonObject());
        return root;
    }

    public static synchronized void ensureLoaded() {
        if (!loaded) {
            ROOT = emptyRoot();
            STATUS = "Waiting for resource reload";
            loaded = true;
        }
    }

    /** Called only by CosmicHorizonsScanner after the active resource reload completes. */
    public static synchronized void replaceCosmicHorizons(JsonObject data) {
        JsonObject next = data != null && data.has("universes") && data.get("universes").isJsonObject()
                ? data.deepCopy() : emptyRoot();
        ROOT = next;
        loaded = true;
        STATUS = "Cosmic Horizons · " + countPlanets() + " bodies";
    }

    /** Clears stale data when the active level changes or Cosmic Horizons sync is unavailable. */
    public static synchronized void clearCosmicHorizons(String reason) {
        ROOT = emptyRoot();
        loaded = true;
        STATUS = reason == null || reason.isBlank()
                ? "Waiting for Cosmic Horizons data" : reason;
    }

    public static int countPlanets() {
        int count = 0;
        if (ROOT.has("universes") && ROOT.get("universes").isJsonObject())
            for (Map.Entry<String, JsonElement> entry : ROOT.getAsJsonObject("universes").entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonObject() && value.getAsJsonObject().has("planets")
                        && value.getAsJsonObject().get("planets").isJsonObject())
                    count += value.getAsJsonObject().getAsJsonObject("planets").size();
            }
        return count;
    }

    public static List<String> universes() {
        List<String> ids = new ArrayList<>();
        if (ROOT.has("universes") && ROOT.get("universes").isJsonObject())
            ROOT.getAsJsonObject("universes").entrySet().forEach(e -> ids.add(e.getKey()));
        return ids;
    }

    public static JsonObject universe(String id) {
        if (!ROOT.has("universes") || !ROOT.get("universes").isJsonObject()) return new JsonObject();
        JsonElement value = ROOT.getAsJsonObject("universes").get(id);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    public static List<Map.Entry<String, JsonObject>> objects(String universeId) {
        List<Map.Entry<String, JsonObject>> result = new ArrayList<>();
        JsonObject universe = universe(universeId);
        if (universe.has("planets") && universe.get("planets").isJsonObject())
            for (Map.Entry<String, JsonElement> entry : universe.getAsJsonObject("planets").entrySet())
                if (entry.getValue().isJsonObject())
                    result.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue().getAsJsonObject()));
        return result;
    }

    public static String text(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    public static double coordinate(JsonObject object, int axis) {
        try {
            JsonArray pos = object.has("map_pos") && object.get("map_pos").isJsonArray()
                    ? object.getAsJsonArray("map_pos") : object.getAsJsonArray("pos");
            if (pos == null || pos.size() <= axis) {
                String key = axis == 0 ? "x" : axis == 1 ? "y" : "z";
                if (object.has(key) && !object.get(key).isJsonNull())
                    return object.get(key).getAsDouble();
                String sourceKey = axis == 0 ? "xpos" : axis == 1 ? "ypos" : "zpos";
                return object.has(sourceKey) && !object.get(sourceKey).isJsonNull()
                        ? object.get(sourceKey).getAsDouble() : 0.0;
            }
            return pos.get(axis).getAsDouble();
        } catch (Exception ignored) { return 0.0; }
    }
}
