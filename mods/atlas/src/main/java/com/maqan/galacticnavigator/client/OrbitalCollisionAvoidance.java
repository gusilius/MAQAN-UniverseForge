package com.maqan.galacticnavigator.client;

import com.google.gson.*;
import java.util.*;

/**
 * Conservative sibling-orbit spacing shared by the Navigator and Cosmic Horizons
 * world adapter through NativeOrbitalSimulation. Radial orbit envelopes are
 * separated where possible; source datapack JSON is never mutated.
 */
final class OrbitalCollisionAvoidance {
    private OrbitalCollisionAvoidance() {}

    static double separateSiblingOrbit(JsonObject objects, JsonObject body,
                                        String parentId, JsonObject parent,
                                        double desiredRadius, double eccentricity) {
        if (objects == null || body == null || parent == null
                || !Double.isFinite(desiredRadius) || desiredRadius <= 0.0
                || !bool(body, "collision_avoidance", true)) return desiredRadius;

        String currentId = identity(body);
        Map<String, Lane> unique = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject candidate = entry.getValue().getAsJsonObject();
            String id = identity(candidate);
            if (id.isBlank() || id.equals(currentId)
                    || !sameParent(candidate, parentId, parent, objects)) continue;
            double dx = coordinate(candidate, 0) - coordinate(parent, 0);
            double dy = coordinate(candidate, 1) - coordinate(parent, 1);
            double dz = coordinate(candidate, 2) - coordinate(parent, 2);
            double sourceRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double radius = firstNumber(candidate, sourceRadius,
                    "orbit_radius", "orbital_distance", "semi_major_axis");
            if (!Double.isFinite(radius) || radius <= 0.0) continue;
            Lane lane = new Lane(id, radius,
                    clamp(number(candidate, "eccentricity", 0.0), 0.0, 0.95),
                    collisionRadius(candidate, radius),
                    Math.max(0.0, number(candidate, "orbit_clearance",
                            number(candidate, "minimum_orbit_separation", 0.0))));
            unique.putIfAbsent(id, lane);
        }

        List<Lane> lanes = new ArrayList<>(unique.values());
        lanes.add(new Lane(currentId, desiredRadius, clamp(eccentricity, 0.0, 0.95),
                collisionRadius(body, desiredRadius),
                Math.max(0.0, number(body, "orbit_clearance",
                        number(body, "minimum_orbit_separation", 0.0)))));
        lanes.sort(Comparator.comparingDouble(Lane::authoredRadius).thenComparing(Lane::id));

        // The parent itself is the innermost obstacle (star/planet surface).
        double parentCollisionRadius = collisionRadius(parent, desiredRadius);
        double previousApoapsis = 0.0;
        double previousCollisionRadius = 0.0;
        for (Lane lane : lanes) {
            double e = lane.eccentricity();
            double safeRadius = lane.authoredRadius();
            double parentClearance = Math.max(1.0, safeRadius * 0.005);
            double minimumPeriapsis = parentCollisionRadius + lane.collisionRadius()
                    + Math.max(parentClearance, lane.authoredClearance());
            if (previousApoapsis > 0.0) {
                double siblingClearance = Math.max(lane.collisionRadius() + previousCollisionRadius,
                        Math.max(1.0, Math.max(safeRadius, previousApoapsis) * 0.005));
                siblingClearance = Math.max(siblingClearance, lane.authoredClearance());
                minimumPeriapsis = Math.max(minimumPeriapsis, previousApoapsis + siblingClearance);
            }
            if (safeRadius * (1.0 - e) < minimumPeriapsis)
                safeRadius = minimumPeriapsis / (1.0 - e);
            if (lane.id().equals(currentId))
                return Double.isFinite(safeRadius) ? Math.max(desiredRadius, safeRadius) : desiredRadius;

            previousApoapsis = safeRadius * (1.0 + e);
            previousCollisionRadius = lane.collisionRadius();
        }
        return desiredRadius;
    }

    private static boolean sameParent(JsonObject candidate, String parentId,
                                      JsonObject parent, JsonObject objects) {
        String reference = firstString(candidate, "orbit_parent_id", "orbit_parent",
                "parent_id", "parent", "satellite_of", "parent_body", "central_body");
        if (!reference.isBlank()) {
            if (reference.equals(parentId) || reference.equals(identity(parent))) return true;
            // Match NativeOrbitalSimulation's conservative dimension fallback:
            // a dimension reference is accepted only when exactly one catalogue
            // body owns that dimension.
            int matches = 0;
            String dimensionOwner = "";
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject possible = entry.getValue().getAsJsonObject();
                String dimension = string(possible, "dimension",
                        string(possible, "dimension_id",
                                string(possible, "attached_dimension_id", "")));
                if (reference.equals(dimension)) {
                    matches++;
                    dimensionOwner = entry.getKey();
                }
            }
            return matches == 1 && dimensionOwner.equals(parentId);
        }
        if (isMoon(candidate)) {
            Set<String> planets = new LinkedHashSet<>();
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                if (entry.getValue().isJsonObject() && isPlanet(entry.getValue().getAsJsonObject()))
                    planets.add(identity(entry.getValue().getAsJsonObject()));
            }
            return planets.size() == 1 && planets.contains(identity(parent)) && isPlanet(parent);
        }
        if (!isOrbitingBody(candidate)) return false;
        Set<String> stars = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (entry.getValue().isJsonObject() && isStar(entry.getValue().getAsJsonObject()))
                stars.add(identity(entry.getValue().getAsJsonObject()));
        }
        return stars.size() == 1 && stars.contains(identity(parent)) && isStar(parent);
    }

    private static boolean isMoon(JsonObject body) {
        String kind = (string(body, "kind", "") + " " + string(body, "display_type", "")
                + " " + string(body, "type", "")).toLowerCase(Locale.ROOT);
        return kind.contains("moon") || kind.contains("satellite");
    }

    private static boolean isPlanet(JsonObject body) {
        String kind = (string(body, "kind", "") + " " + string(body, "display_type", "")
                + " " + string(body, "type", "")).toLowerCase(Locale.ROOT);
        return kind.contains("planet") && !isStar(body);
    }

    private static boolean isOrbitingBody(JsonObject body) {
        String kind = (string(body, "kind", "") + " " + string(body, "display_type", "")
                + " " + string(body, "type", "")).toLowerCase(Locale.ROOT);
        return kind.contains("planet") || kind.contains("asteroid") || kind.contains("comet");
    }

    private static boolean isStar(JsonObject body) {
        String kind = (string(body, "kind", "") + " " + string(body, "display_type", "")
                + " " + string(body, "type", "")).toLowerCase(Locale.ROOT);
        return kind.contains("star") || kind.contains("sun");
    }

    private static String identity(JsonObject body) {
        // GUI aliases of one physical body can have distinct catalogue keys.
        // Deduplicate by the shared GUI object id before falling back to catalog id.
        return string(body, "gui_object_id",
                string(body, "id", string(body, "catalog_id", "")));
    }

    private static double collisionRadius(JsonObject body, double orbitRadius) {
        double value = firstNumber(body, Double.NaN, "collision_radius", "physical_radius");
        return Double.isFinite(value) && value > 0.0 ? value : Math.max(0.5, orbitRadius * 0.0025);
    }

    private static double coordinate(JsonObject body, int axis) {
        String key = axis == 0 ? "x" : axis == 1 ? "y" : "z";
        String source = axis == 0 ? "xpos" : axis == 1 ? "ypos" : "zpos";
        return number(body, key, number(body, source, 0.0));
    }

    private static String firstString(JsonObject body, String... keys) {
        for (String key : keys) {
            String value = string(body, key, "");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static double firstNumber(JsonObject body, double fallback, String... keys) {
        for (String key : keys) {
            double value = number(body, key, Double.NaN);
            if (Double.isFinite(value)) return value;
        }
        return fallback;
    }

    private static double number(JsonObject body, String key, double fallback) {
        try { return body.has(key) && !body.get(key).isJsonNull()
                ? body.get(key).getAsDouble() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonObject body, String key, boolean fallback) {
        try { return body.has(key) ? body.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String string(JsonObject body, String key, String fallback) {
        try { return body.has(key) ? body.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Lane(String id, double authoredRadius, double eccentricity,
                        double collisionRadius, double authoredClearance) {}
}
