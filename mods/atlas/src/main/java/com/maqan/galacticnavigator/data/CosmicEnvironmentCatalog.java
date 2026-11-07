package com.maqan.galacticnavigator.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maqan.galacticnavigator.client.NativeOrbitalSimulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * System-scoped view of authored spatial-environment objects.
 *
 * This is a data bridge, not a second scanner or renderer. It reads the
 * normalized GalaxyCatalog and samples moving objects through the same
 * NativeOrbitalSimulation used by the Navigator and Cosmic Horizons adapter.
 * Coordinates are always tagged with their source system.
 *
 * Volume and belt measurements are optional MAQAN metadata. Missing values
 * remain unknown: no physical dimensions, units, chunks, or dimensions are
 * inferred or generated here.
 */
public final class CosmicEnvironmentCatalog {
    private CosmicEnvironmentCatalog() {}

    public enum Kind { NEBULA, ASTEROID_BELT, COMET, OTHER }

    public record Vector3(double x, double y, double z) {
        public boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
        public boolean isPositiveExtent() {
            return isFinite() && x > 0.0 && y > 0.0 && z > 0.0;
        }
        public Vector3 normalized() {
            if (!isFinite()) return null;
            double length = Math.sqrt(x * x + y * y + z * z);
            return length > 1.0E-12 ? new Vector3(x / length, y / length, z / length) : null;
        }
    }

    public record EnvironmentObject(
            String systemId,
            String bodyId,
            Kind kind,
            String name,
            Vector3 position,
            Vector3 volumeCenter,
            Vector3 volumeExtent,
            double volumeRadius,
            double volumeRenderScale,
            double opacity,
            String volumeShape,
            String volumeOrientation,
            double innerBeltRadius,
            double outerBeltRadius,
            double beltCenterRadius,
            double beltThickness,
            double beltWidth,
            double beltDensity,
            double beltRotationPeriodTicks,
            double beltPhaseRadians,
            int particleCount,
            double particleDensity,
            double beltInclinationDegrees,
            double beltNodeDegrees,
            Vector3 tailDirection,
            double tailLength,
            double comaRadius,
            String color,
            String sourceMetadata
    ) {
        public boolean hasVolume() {
            return volumeCenter != null && volumeCenter.isFinite()
                    && ((volumeExtent != null && volumeExtent.isPositiveExtent())
                    || (Double.isFinite(volumeRadius) && volumeRadius > 0.0));
        }
        public boolean hasBeltGeometry() {
            return Double.isFinite(innerBeltRadius) && Double.isFinite(outerBeltRadius)
                    && outerBeltRadius > innerBeltRadius && innerBeltRadius >= 0.0;
        }
        public boolean hasBeltPresentationData() {
            return hasBeltGeometry()
                    || Double.isFinite(beltCenterRadius) && beltCenterRadius > 0.0
                    && Double.isFinite(beltWidth) && beltWidth > 0.0
                    || Double.isFinite(beltDensity) && beltDensity > 0.0
                    || particleCount > 0
                    || Double.isFinite(particleDensity) && particleDensity > 0.0;
        }
        public boolean hasTailDirection() {
            return tailDirection != null && tailDirection.isFinite()
                    && (tailDirection.x() != 0.0 || tailDirection.y() != 0.0
                    || tailDirection.z() != 0.0);
        }
    }

    /**
     * Immutable snapshot for exactly one Cosmic Horizons system. gameTime must
     * be the same render-time sample used by the caller's orbital presentation.
     */
    public static List<EnvironmentObject> snapshot(String systemId, double gameTime) {
        if (systemId == null || systemId.isBlank()) return List.of();
        JsonObject system = GalaxyCatalog.universe(systemId);
        if (system.size() == 0 || !system.has("planets")
                || !system.get("planets").isJsonObject()) return List.of();

        JsonObject bodies = system.getAsJsonObject("planets");
        List<EnvironmentObject> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : bodies.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject body = entry.getValue().getAsJsonObject();
            Kind kind = classify(body);
            if (kind == Kind.OTHER) continue;

            Vector3 position = sampledPosition(systemId, system, body, gameTime);
            Vector3 center = firstVector(body, "volume_center", "volume_position",
                    "nebula_center", "nebula_position");
            if (center == null) center = position;

            Vector3 extent = firstVector(body, "volume_extent", "nebula_extent");
            if (extent == null) {
                double width = number(body, Double.NaN, "volume_width", "nebula_width");
                double height = number(body, Double.NaN, "volume_height", "nebula_height");
                double depth = number(body, Double.NaN, "volume_depth", "nebula_depth");
                if (Double.isFinite(width) && Double.isFinite(height) && Double.isFinite(depth))
                    extent = new Vector3(width, height, depth);
            }

            Vector3 tail = firstVector(body, "tail_direction", "tail_orientation");
            if (kind == Kind.COMET && tail == null)
                tail = directionAwayFromParent(systemId, system, bodies, body, position, gameTime);

            result.add(new EnvironmentObject(
                    systemId,
                    entry.getKey(),
                    kind,
                    string(body, "name", entry.getKey()),
                    position,
                    center,
                    extent,
                    number(body, Double.NaN, "volume_radius", "nebula_radius", "field_radius"),
                    number(body, Double.NaN, "volume_render_scale"),
                    number(body, 1.0, "volume_opacity", "opacity"),
                    string(body, "volume_shape", ""),
                    jsonString(body, "volume_orientation"),
                    number(body, Double.NaN, "belt_inner_radius"),
                    number(body, Double.NaN, "belt_outer_radius"),
                    number(body, Double.NaN, "belt_center_radius"),
                    number(body, Double.NaN, "belt_thickness"),
                    number(body, Double.NaN, "belt_width"),
                    number(body, Double.NaN, "belt_density"),
                    number(body, Double.NaN, "belt_rotation_period_ticks"),
                    Math.toRadians(number(body, 0.0, "belt_phase_degrees")),
                    kind == Kind.NEBULA
                            ? integer(body, "nebula_particle_count", "particle_count")
                            : kind == Kind.ASTEROID_BELT
                            ? integer(body, "asteroid_count", "particle_count") : 0,
                    number(body, Double.NaN, "particle_density"),
                    number(body, Double.NaN, "belt_inclination_degrees"),
                    number(body, Double.NaN, "belt_node_degrees"),
                    tail,
                    number(body, Double.NaN, "tail_length"),
                    number(body, Double.NaN, "coma_radius"),
                    string(body, "volume_color",
                            string(body, "nebula_color", string(body, "belt_color", ""))),
                    "GalaxyCatalog.universes[" + systemId + "].planets[" + entry.getKey() + "]"
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private static Vector3 directionAwayFromParent(String systemId, JsonObject system,
                                                   JsonObject bodies, JsonObject comet,
                                                   Vector3 cometPosition, double gameTime) {
        String parentRef = firstString(comet, "orbit_parent_id", "orbit_parent",
                "parent_id", "parent", "parent_body", "central_body", "satellite_of");
        JsonObject parent = null;
        if (!parentRef.isBlank()) {
            for (Map.Entry<String, JsonElement> entry : bodies.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (entry.getKey().equals(parentRef)
                        || parentRef.equals(string(candidate, "catalog_id", ""))
                        || parentRef.equals(string(candidate, "id", ""))) {
                    parent = candidate;
                    break;
                }
            }
        }
        if (parent == null) {
            for (Map.Entry<String, JsonElement> entry : bodies.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject candidate = entry.getValue().getAsJsonObject();
                if (!isStar(candidate)) continue;
                if (parent != null) return null; // ambiguous multi-star system
                parent = candidate;
            }
        }
        if (parent == null) return null;
        Vector3 parentPosition = sampledPosition(systemId, system, parent, gameTime);
        return new Vector3(cometPosition.x() - parentPosition.x(),
                cometPosition.y() - parentPosition.y(),
                cometPosition.z() - parentPosition.z()).normalized();
    }

    private static boolean isStar(JsonObject body) {
        String value = (string(body, "kind", "") + " "
                + string(body, "display_type", "") + " "
                + string(body, "classification", "") + " "
                + string(body, "type", ""))
                .toLowerCase(Locale.ROOT);
        return value.contains("star") || value.contains("sun")
                || value.contains("white dwarf") || value.contains("red giant")
                || value.contains("neutron");
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key, "");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Vector3 sampledPosition(String systemId, JsonObject system,
                                           JsonObject body, double gameTime) {
        return new Vector3(
                NativeOrbitalSimulation.coordinate(systemId, system, body, 0, gameTime),
                NativeOrbitalSimulation.coordinate(systemId, system, body, 1, gameTime),
                NativeOrbitalSimulation.coordinate(systemId, system, body, 2, gameTime)
        );
    }

    private static Kind classify(JsonObject body) {
        String value = (string(body, "kind", "") + " "
                + string(body, "display_type", "") + " "
                + string(body, "classification", "") + " "
                + string(body, "type", ""))
                .toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        if (value.contains("nebula") || value.contains("molecular cloud")
                || value.contains("gas cloud") || value.contains("dust cloud")
                || value.contains("supernova remnant")) return Kind.NEBULA;
        if (value.contains("asteroid belt") || value.contains("debris belt")
                || value.contains("asteroid field") || value.contains("belt")
                || hasBeltMetadata(body)) return Kind.ASTEROID_BELT;
        if (value.contains("comet")) return Kind.COMET;
        return Kind.OTHER;
    }

    private static boolean hasBeltMetadata(JsonObject body) {
        return body.has("belt_inner_radius") || body.has("belt_outer_radius")
                || body.has("belt_center_radius") || body.has("belt_width") || body.has("belt_density")
                || body.has("asteroid_count") || body.has("particle_count")
                || body.has("particle_density");
    }

    private static Vector3 firstVector(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;
            JsonElement value = object.get(key);
            Vector3 vector = null;
            if (value.isJsonArray()) {
                JsonArray array = value.getAsJsonArray();
                if (array.size() >= 3)
                    vector = new Vector3(asDouble(array.get(0)), asDouble(array.get(1)),
                            asDouble(array.get(2)));
            } else if (value.isJsonObject()) {
                JsonObject coordinates = value.getAsJsonObject();
                vector = new Vector3(number(coordinates, Double.NaN, "x"),
                        number(coordinates, Double.NaN, "y"),
                        number(coordinates, Double.NaN, "z"));
            }
            if (vector != null && vector.isFinite()) return vector;
        }
        return null;
    }

    private static String jsonString(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? "" : value.toString();
        } catch (RuntimeException ignored) { return ""; }
    }

    private static int integer(JsonObject object, String... keys) {
        for (String key : keys) {
            try {
                if (object.has(key) && !object.get(key).isJsonNull())
                    return Math.max(0, object.get(key).getAsInt());
            } catch (RuntimeException ignored) { }
        }
        return 0;
    }

    private static double asDouble(JsonElement element) {
        try { return element.getAsDouble(); }
        catch (RuntimeException ignored) { return Double.NaN; }
    }

    private static double number(JsonObject object, double fallback, String... keys) {
        for (String key : keys) {
            try {
                if (object.has(key) && !object.get(key).isJsonNull()) {
                    double value = object.get(key).getAsDouble();
                    if (Double.isFinite(value)) return value;
                }
            } catch (RuntimeException ignored) { }
        }
        return fallback;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }
}
