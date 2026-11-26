package com.maqan.galacticnavigator.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Read-only political facts for a planet. A planetary sovereign is exposed only
 * when the source explicitly declares both full-planet scope and full control.
 * This class never infers conquest from a few region polygons or territory labels.
 */
public final class PlanetPoliticalMetadata {
    private final String owner;
    private final String government;
    private final String capital;
    private final String source;
    private final String updated;
    private final String controlStatus;

    private PlanetPoliticalMetadata(String owner, String government, String capital,
                                    String source, String updated, String controlStatus) {
        this.owner = owner;
        this.government = government;
        this.capital = capital;
        this.source = source;
        this.updated = updated;
        this.controlStatus = controlStatus;
    }

    public static PlanetPoliticalMetadata from(JsonObject body) {
        if (body == null || !body.has("political_data")
                || !body.get("political_data").isJsonObject()) return null;

        JsonObject data = body.getAsJsonObject("political_data");
        String scope = normalized(text(data, "control_scope"));
        String status = normalized(text(data, "control_status"));
        boolean fullPlanet = scope.equals("FULL_PLANET");
        boolean fullyControlled = status.equals("FULLY_CONTROLLED")
                || (status.equals("SOVEREIGN") && bool(data, "fully_controlled"));
        if (!fullPlanet || !fullyControlled) return null;

        String owner = text(data, "owner");
        if (owner.isBlank()) return null;

        return new PlanetPoliticalMetadata(owner,
                first(data, "government", "government_type", "political_system"),
                first(data, "capital", "capital_city"),
                first(data, "source", "reference"),
                first(data, "updated", "as_of", "last_verified"),
                "Fully controlled · entire planet");
    }

    public String owner() { return owner; }
    public String government() { return government; }
    public String capital() { return capital; }
    public String source() { return source; }
    public String updated() { return updated; }
    public String controlStatus() { return controlStatus; }

    private static String first(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = text(object, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key)) return "";
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try { return value.getAsString().trim(); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static boolean bool(JsonObject object, String key) {
        try { return object.has(key) && object.get(key).getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static String normalized(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
