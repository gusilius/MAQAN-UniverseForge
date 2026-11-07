package com.maqan.galacticnavigator.data;

import com.google.gson.*;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Reads the exact cosmic_data JSON list accepted and synchronized by Cosmic Horizons.
 * Server-enabled datapacks remain authoritative; this adapter does not invent systems
 * or fall back to a bundled galaxy when Cosmic Horizons is unavailable.
 */
public final class CosmicHorizonsScanner {
    private static String lastFingerprint = "";
    private static boolean hasFingerprint;
    private static Level lastLevel;
    private static int pollTicks;

    private CosmicHorizonsScanner() {}

    public static synchronized void refreshFromCosmicHorizons(Level level) {
        if (level == null || !level.isClientSide) return;
        if (level != lastLevel) {
            lastLevel = level;
            hasFingerprint = false;
            lastFingerprint = "";
            GalaxyCatalog.clearCosmicHorizons("Waiting for Cosmic Horizons sync");
        }
        if (++pollTicks % 20 != 0) return;
        try {
            Class<?> variables = Class.forName(
                    "net.lointain.cosmos.network.CosmosModVariables$WorldVariables");
            Method get = variables.getMethod("get", net.minecraft.world.level.LevelAccessor.class);
            Object instance = get.invoke(null, level);
            if (instance == null) {
                GalaxyCatalog.clearCosmicHorizons("Cosmic Horizons data is not synchronized yet");
                return;
            }
            Field field = variables.getField("datapack");
            Object raw = field.get(instance);
            if (!(raw instanceof ListTag list)) {
                GalaxyCatalog.clearCosmicHorizons("Cosmic Horizons datapack list is unavailable");
                return;
            }

            List<String> json = new ArrayList<>(list.size());
            StringBuilder fingerprint = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                String value = list.getString(i);
                json.add(value);
                // Compare the actual payload, not a 32-bit hash that can collide.
                fingerprint.append(value.length()).append(':').append(value).append(';');
            }
            String nextFingerprint = fingerprint.toString();
            if (hasFingerprint && nextFingerprint.equals(lastFingerprint)) return;
            GalaxyCatalog.replaceCosmicHorizons(adapt(json));
            lastFingerprint = nextFingerprint;
            hasFingerprint = true;
        } catch (ClassNotFoundException ignored) {
            GalaxyCatalog.clearCosmicHorizons("Cosmic Horizons is not installed");
        } catch (ReflectiveOperationException | RuntimeException error) {
            GalaxyCatalog.clearCosmicHorizons("Waiting for Cosmic Horizons data: " +
                    error.getClass().getSimpleName());
            // The CH sync packet may not have arrived yet; retry on the next poll.
        }
    }

    private static JsonObject adapt(List<String> jsonResources) {
        JsonObject root = new JsonObject();
        JsonObject universes = new JsonObject();
        root.add("universes", universes);
        int ordinal = 0;

        for (String json : jsonResources) {
            try {
                JsonObject source = JsonParser.parseString(json).getAsJsonObject();
                JsonObject physical = object(source, "planet_data");
                JsonObject gui = object(source, "gui_data");
                JsonObject bodies = new JsonObject();

                ordinal++;
                String systemName = string(source, "local_id", "Star system " + ordinal);
                if (systemName.isBlank()) systemName = "Star system " + ordinal;
                // attached_dimention_id is a destination dimension, not a unique system key.
                // Multiple datapack systems may intentionally share the same dimension.
                String systemKey = uniqueKey(systemName, universes);
                String attached = string(source, "attached_dimention_id",
                        string(source, "attached_dimension_id", ""));

                for (Map.Entry<String, JsonElement> entry : physical.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonObject body = entry.getValue().getAsJsonObject().deepCopy();
                    String id = entry.getKey();
                    body.addProperty("id", id);
                    body.addProperty("name", string(body, "name", id));
                    body.addProperty("kind", normalizeKind(fancy(body.get("type"), ""), "", ""));
                    body.addProperty("system", systemName);
                    body.addProperty("system_id", systemKey);
                    if (!attached.isEmpty() && !body.has("dimension"))
                        body.addProperty("dimension", attached);
                    copyCoordinate(body, "xpos", "x");
                    copyCoordinate(body, "ypos", "y");
                    copyCoordinate(body, "zpos", "z");
                    if (body.has("x") && body.has("y") && body.has("z")) {
                        JsonArray pos = new JsonArray();
                        pos.add(body.get("x")); pos.add(body.get("y")); pos.add(body.get("z"));
                        body.add("map_pos", pos);
                    }
                    bodies.add(id, body);
                }

                // Keep every GUI category's metadata and the original JSON intact.
                for (Map.Entry<String, JsonElement> categoryEntry : gui.entrySet()) {
                    if (!categoryEntry.getValue().isJsonObject()) continue;
                    JsonObject category = categoryEntry.getValue().getAsJsonObject();
                    JsonObject objectData = object(category, "object_data");
                    String categoryId = categoryEntry.getKey();
                    String categoryTitle = fancy(category.get("title"), categoryId);
                    String travelDimension = string(category, "travel_dimension", attached);
                    String categoryUnlockingDimension = string(category, "unlocking_dimension", "");

                    for (Map.Entry<String, JsonElement> guiEntry : objectData.entrySet()) {
                        if (!guiEntry.getValue().isJsonObject()) continue;
                        JsonObject visual = guiEntry.getValue().getAsJsonObject();
                        String guiId = guiEntry.getKey();
                        String bodyKey = guiId;
                        if (bodies.has(guiId) && bodies.getAsJsonObject(guiId).has("gui_object_id")) {
                            String previousCategory = string(bodies.getAsJsonObject(guiId), "gui_category", "");
                            if (!previousCategory.equals(categoryId)) bodyKey = categoryId + "::" + guiId;
                        }

                        JsonObject body;
                        if (bodies.has(bodyKey)) {
                            body = bodies.getAsJsonObject(bodyKey).deepCopy();
                        } else if (!bodyKey.equals(guiId) && physical.has(guiId)
                                && physical.get(guiId).isJsonObject()) {
                            body = physical.getAsJsonObject(guiId).deepCopy();
                        } else {
                            body = new JsonObject();
                        }

                        // Hierarchy metadata may be authored on the cosmic_data system,
                        // GUI category, or individual GUI body. Preserve it without inventing values.
                        String[] hierarchyFields = {"universe_id", "universe", "cosmos_id",
                                "galaxy_id", "galaxy_name", "galaxy", "sector_id", "sector_name", "sector",
                                "galaxy_pos", "universe_pos", "sector_pos", "system_pos",
                                "universe_galaxy_pos", "galaxy_map_pos", "galaxy_location",
                                "hyperspace_routes", "jump_routes", "travel_routes", "system_routes",
                                "connected_systems", "connections", "routes"};
                        for (String field : hierarchyFields) {
                            if (!body.has(field)) copyIfPresent(source, body, field);
                            if (!body.has(field)) copyIfPresent(category, body, field);
                            if (!body.has(field)) copyIfPresent(visual, body, field);
                        }

                        // MAQAN spatial-environment metadata. Values are preserved verbatim
                        // from system/category/body JSON; absent fields are never synthesized.
                        String[] environmentFields = {
                                "volume_center", "volume_position", "volume_radius", "volume_extent",
                                "volume_width", "volume_height", "volume_depth", "volume_shape",
                                "volume_render_scale", "volume_opacity", "volume_color",
                                "volume_orientation", "nebula_center", "nebula_position", "nebula_extent",
                                "collision_radius", "physical_radius", "orbit_clearance",
                                "minimum_orbit_separation", "collision_avoidance",
                                "belt_color", "belt_inner_radius", "belt_outer_radius",
                                "belt_center_radius", "belt_rotation_period_ticks", "belt_phase_degrees",
                                "belt_inclination_degrees", "belt_node_degrees", "belt_thickness",
                                "belt_width", "belt_density", "asteroid_count",
                                "particle_count", "particle_density", "nebula_particle_count", "nebula_sample_count",
                                "nebula_color", "nebula_density",
                                "nebula_radius", "nebula_type", "field_radius", "opacity",
                                "tail_direction", "tail_orientation", "tail_length", "coma_radius",
                                "coma_color", "dust_tail_color", "ion_tail_color",
                                "hazard_level", "radiation_level", "navigation_penalty"
                        };
                        for (String field : environmentFields) {
                            if (!body.has(field)) copyIfPresent(source, body, field);
                            if (!body.has(field)) copyIfPresent(category, body, field);
                            if (!body.has(field)) copyIfPresent(visual, body, field);
                        }

                        String physicalKind = string(body, "kind", fancy(body.get("type"), ""));
                        String displayType = fancy(visual.get("type"), "");
                        String classification = fancy(visual.get("category"), "");
                        // Cosmic Horizons commonly separates stellar objects into their own
                        // GUI category (not every star has a primitive type field).
                        if (classification.isBlank() && isStellarCategory(categoryId))
                            classification = "star";
                        for (String field : new String[]{"texture_id", "texture", "texture_location",
                                "planet_texture", "scale", "ponder_scale",
                                "nebula_color", "nebula_density", "nebula_radius", "nebula_type",
                                "composition", "opacity", "temperature", "stellar_class",
                                "spectral_class", "luminosity", "black_hole_mass",
                                "event_horizon_radius", "accretion_disk", "accretion_disk_color", "pulsar_period",
                                "magnetic_field", "tail_length", "coma_radius", "dust_tail_color",
                                "ion_tail_color", "belt_center_radius", "belt_rotation_period_ticks",
                                "belt_phase_degrees", "belt_width", "belt_density", "asteroid_count",
                                "nebula_particle_count", "nebula_sample_count", "field_radius",
                                "yaw", "pitch", "roll", "yaw_speed", "pitch_speed", "roll_speed",
                                "ringed", "ring_data", "life", "atmosphere", "atmosphere_color",
                                "atmosphere_y", "cloud_data", "cloud_color", "alpha_data",
                                "inverse_texture_id", "light_data", "layer", "glowing", "bloom_color",
                                "core_color", "conditions", "size", "category", "type", "name",
                                "travel_x", "travel_y", "travel_z", "unlocking_dimension",
                                // Optional MAQAN orbital metadata; Cosmic Horizons itself
                                // does not define orbital periods or parent-body links.
                                "parent", "parent_id", "parent_body", "parent_dimension_id",
                                "satellite_of", "central_body", "orbit_parent", "orbit_parent_id",
                                "orbit_radius", "orbital_distance",
                                "orbital_period", "orbit_period", "period_ticks", "orbital_period_ticks",
                                "orbit_period_ticks", "period_seconds", "orbital_period_seconds",
                                "orbit_period_seconds", "period_days", "orbital_period_days",
                                "orbit_period_days", "orbital_speed", "orbit_speed", "orbit_phase",
                                "phase_degrees", "orbit_phase_degrees", "orbit_inclination",
                                "inclination_degrees", "orbit_inclination_degrees", "inclination", "eccentricity",
                                "ascending_node", "ascending_node_degrees",
                                "argument_of_periapsis", "argument_of_periapsis_degrees",
                                "semi_major_axis", "maqan_orbit", "orbit_data",
                                "axial_rotation_period_ticks", "rotation_period_ticks",
                                "sidereal_period_ticks", "axial_tilt_degrees", "axial_tilt",
                                "mass", "speed", "astronomical_compute",
                                // Navigation infrastructure and local/tactical map metadata.
                                "object_type", "navigation_type", "subtype", "poi_type", "signal_type",
                                "target_type", "contact_type", "lagrange_point", "lagrange", "jump_point",
                                "jump_gate", "wormhole", "jump_destination", "destination_system",
                                "destination_id", "connected_system", "connected_system_id",
                                "local_position", "surface_position", "orbit_position", "velocity",
                                "heading", "direction", "signature", "electromagnetic_signature",
                                "thermal_signature", "cross_section", "faction_id", "owner_id",
                                "security_status", "danger_level", "service_type", "services",
                                "landing_zones", "poi", "wreck", "anomaly", "beacon", "ship_class",
                                "targetable", "contact", "range", "radius", "sector_coordinates",
                                "surface_coordinates", "altitude", "bearing", "threat_level",
                                "sensor_range", "last_seen", "expires_at", "political_data"}) {
                            if (visual.has(field)) body.add(field, visual.get(field).deepCopy());
                        }

                        body.addProperty("id", guiId);
                        body.addProperty("catalog_id", bodyKey);
                        body.addProperty("gui_object_id", guiId);
                        body.addProperty("gui_category", categoryId);
                        body.addProperty("gui_category_title", categoryTitle);
                        body.addProperty("name", fancy(visual.get("name"),
                                string(body, "name", guiId)));
                        body.addProperty("display_type", displayType);
                        body.addProperty("classification", classification);
                        body.addProperty("kind", normalizeKind(physicalKind, classification, displayType));
                        body.addProperty("system", systemName);
                        body.addProperty("system_id", systemKey);
                        if (!travelDimension.isEmpty()) body.addProperty("travel_dimension", travelDimension);
                        if (!attached.isEmpty() && !body.has("dimension"))
                            body.addProperty("dimension", attached);
                        if (!categoryUnlockingDimension.isEmpty())
                            body.addProperty("category_unlocking_dimension", categoryUnlockingDimension);
                        copyIfPresent(category, body, "origin_x");
                        copyIfPresent(category, body, "origin_y");
                        copyIfPresent(category, body, "origin_z");
                        copyIfPresent(category, body, "background");
                        copyIfPresent(category, body, "order");
                        bodies.add(bodyKey, body);
                    }
                }

                // Cosmic Horizons also declares the luminous Sun in sky_data,
                // separate from planet_data/gui_data. Preserve that stellar object
                // as the central body when this same resource describes a populated
                // solar-system catalogue. Dimension-only sky_data files are not
                // promoted into standalone star systems.
                JsonObject skyData = object(source, "sky_data");
                if (!bodies.isEmpty()) {
                    for (Map.Entry<String, JsonElement> skyEntry : skyData.entrySet()) {
                        if (!skyEntry.getValue().isJsonObject()) continue;
                        String skyId = skyEntry.getKey();
                        String skyKey = skyId.toLowerCase(Locale.ROOT);
                        JsonObject skyBody = skyEntry.getValue().getAsJsonObject().deepCopy();
                        String skyType = string(skyBody, "type", "").toLowerCase(Locale.ROOT);
                        String stellarClass = string(skyBody, "stellar_class",
                                string(skyBody, "spectral_class", ""));
                        boolean stellar = skyKey.equals("sun") || skyKey.equals("sol")
                                || skyKey.contains("star") || skyKey.contains("stellar")
                                || !stellarClass.isBlank()
                                || Set.of("star", "sun", "stellar", "yellow dwarf", "red dwarf",
                                        "white dwarf", "red giant", "blue giant", "neutron star")
                                        .contains(skyType);
                        if (!stellar || bodies.has(skyId)) continue;
                        skyBody.addProperty("id", skyId);
                        skyBody.addProperty("catalog_id", skyId);
                        skyBody.addProperty("name", string(skyBody, "name",
                                skyKey.equals("sun") ? "Sun" : skyId));
                        skyBody.addProperty("kind", "star");
                        skyBody.addProperty("type", "star");
                        skyBody.addProperty("system", systemName);
                        skyBody.addProperty("system_id", systemKey);
                        if (!attached.isEmpty() && !skyBody.has("dimension"))
                            skyBody.addProperty("dimension", attached);
                        // A stellar body without an authored orbital position is
                        // the central origin, not an invented distant coordinate.
                        if (!hasCoordinate(skyBody, "x", "xpos")
                                && !hasCoordinate(skyBody, "y", "ypos")
                                && !hasCoordinate(skyBody, "z", "zpos")) {
                            skyBody.addProperty("x", 0.0);
                            skyBody.addProperty("y", 0.0);
                            skyBody.addProperty("z", 0.0);
                            JsonArray origin = new JsonArray();
                            origin.add(0.0); origin.add(0.0); origin.add(0.0);
                            skyBody.add("map_pos", origin);
                        }
                        bodies.add(skyId, skyBody);
                    }
                }

                JsonObject universe = new JsonObject();
                universe.addProperty("name", systemName);
                universe.addProperty("kind", "star system");
                universe.addProperty("dimension", attached);
                universe.add("planet_data", physical.deepCopy());
                universe.add("gui_data", gui.deepCopy());
                universe.add("sky_data", skyData.deepCopy());
                JsonObject dimensionData = object(source, "dimensional_data");
                JsonObject atmosphereData = object(dimensionData, "atmospheric_data");
                universe.addProperty("travel_to_dimension",
                        string(atmosphereData, "travel_to", ""));
                universe.add("gui_categories", gui.deepCopy());
                for (String field : new String[]{"universe_id", "universe", "cosmos_id",
                        "galaxy_id", "galaxy_name", "galaxy", "sector_id", "sector_name", "sector",
                        "galaxy_pos", "universe_pos", "sector_pos", "system_pos",
                        "universe_galaxy_pos", "galaxy_map_pos", "galaxy_location",
                        "hyperspace_routes", "jump_routes", "travel_routes", "system_routes",
                        "connected_systems", "connections", "routes"}) {
                    copyIfPresent(source, universe, field);
                }
                universe.add("planets", bodies);
                universes.add(systemKey, universe);
            } catch (RuntimeException ignored) {
                // Match Cosmic Horizons' behavior: skip malformed cosmic_data entries.
            }
        }
        // Cosmic Horizons stores the visible Sun in a planet dimension's
        // sky_data, while that dimension points back to its solar system through
        // dimensional_data.atmospheric_data.travel_to. Reattach that star to the
        // unique matching system instead of treating the dimension as a system.
        Map<String, List<JsonObject>> systemsByDimension = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : universes.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject system = entry.getValue().getAsJsonObject();
            JsonObject bodies = object(system, "planets");
            String dimension = string(system, "dimension", "");
            if (!bodies.isEmpty() && !dimension.isBlank())
                systemsByDimension.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(system);
        }
        for (Map.Entry<String, JsonElement> entry : universes.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject dimension = entry.getValue().getAsJsonObject();
            JsonObject sky = object(dimension, "sky_data");
            if (sky.isEmpty()) continue;
            String destination = string(dimension, "travel_to_dimension", "");
            List<JsonObject> targets = systemsByDimension.get(destination);
            if (destination.isBlank() || targets == null || targets.size() != 1) continue;
            JsonObject targetBodies = object(targets.get(0), "planets");
            for (Map.Entry<String, JsonElement> skyEntry : sky.entrySet()) {
                if (!skyEntry.getValue().isJsonObject()) continue;
                String id = skyEntry.getKey();
                String normalizedId = id.toLowerCase(Locale.ROOT);
                JsonObject visual = skyEntry.getValue().getAsJsonObject();
                String type = string(visual, "type", "").toLowerCase(Locale.ROOT);
                boolean star = normalizedId.equals("sun") || normalizedId.equals("sol")
                        || normalizedId.contains("star") || normalizedId.contains("stellar")
                        || !string(visual, "stellar_class", string(visual, "spectral_class", "")).isBlank()
                        || Set.of("star", "sun", "stellar", "yellow dwarf", "red dwarf",
                                "white dwarf", "red giant", "blue giant", "neutron star").contains(type);
                if (!star || targetBodies.has(id)) continue;
                JsonObject body = visual.deepCopy();
                body.addProperty("id", id);
                body.addProperty("catalog_id", id);
                body.addProperty("name", string(body, "name", normalizedId.equals("sun") ? "Sun" : id));
                body.addProperty("kind", "star");
                body.addProperty("type", "star");
                body.addProperty("system", string(targets.get(0), "name", "Star system"));
                body.addProperty("system_id", string(targets.get(0), "name", ""));
                if (!body.has("x") && !body.has("xpos")) body.addProperty("x", 0.0);
                if (!body.has("y") && !body.has("ypos")) body.addProperty("y", 0.0);
                if (!body.has("z") && !body.has("zpos")) body.addProperty("z", 0.0);
                if (!body.has("map_pos")) {
                    JsonArray origin = new JsonArray();
                    origin.add(0.0); origin.add(0.0); origin.add(0.0);
                    body.add("map_pos", origin);
                }
                targetBodies.add(id, body);
            }
        }
        return root;
    }

    private static String uniqueKey(String preferred, JsonObject used) {
        if (!used.has(preferred)) return preferred;
        int suffix = 2;
        while (used.has(preferred + " #" + suffix)) suffix++;
        return preferred + " #" + suffix;
    }

    private static boolean isStellarCategory(String categoryId) {
        String key = categoryId == null ? "" : categoryId.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return key.equals("star") || key.equals("stars") || key.equals("sun")
                || key.equals("suns") || key.equals("solar") || key.equals("solar_bodies")
                || key.contains("stellar") || key.startsWith("star_") || key.endsWith("_stars");
    }

    private static String normalizeKind(String physicalKind, String classification, String displayType) {
        String raw = (physicalKind + " " + classification + " " + displayType)
                .toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        if (raw.contains("black hole") || raw.contains("blackhole")) return "blackhole";
        if (raw.contains("nebula") || raw.contains("molecular cloud") || raw.contains("gas cloud")
                || raw.contains("dust cloud") || raw.contains("supernova remnant")) return "nebula";
        if (raw.contains("quasar")) return "quasar";
        if (raw.contains("pulsar") || raw.contains("neutron star")) return "neutron star";
        if (raw.contains("white dwarf")) return "white dwarf";
        if (raw.contains("red giant")) return "red giant";
        if (raw.contains("protostar")) return "protostar";
        if (raw.contains("lagrange")) return "lagrange";
        if (raw.contains("jump point") || raw.contains("jumppoint") || raw.contains("jump gate")
                || raw.contains("stargate") || raw.contains("wormhole") || raw.contains("hyperspace gate"))
            return "jump point";
        if (raw.contains("ship") || raw.contains("vessel") || raw.contains("craft")
                || raw.contains("contact") || raw.contains("target")) return "ship contact";
        if (raw.contains("wreck") || raw.contains("debris")) return "wreck";
        if (raw.contains("anomaly") || raw.contains("distress") || raw.contains("signal")) return "anomaly";
        if (raw.contains("beacon") || raw.contains("poi") || raw.contains("point of interest")) return "beacon";
        if (raw.contains("station") || raw.contains("shipyard") || raw.contains("outpost")
                || raw.contains("megastucture") || raw.contains("megastructure")) return "station";
        if (raw.contains("moon") || raw.contains("satellite")) return "moon";
        if (raw.contains("star") || raw.contains("sun") || raw.contains("dwarf")
                || raw.contains("giant") || raw.contains("neutron")) return "star";
        if (raw.contains("asteroid") || raw.contains("belt")) return "asteroid";
        if (raw.contains("comet")) return "comet";
        if (raw.contains("planet")) return "planet";
        // Unknown custom object types remain generic objects instead of being
        // silently misrepresented as planets in the system map.
        return "object";
    }

    private static void copyIfPresent(JsonObject source, JsonObject target, String key) {
        if (source.has(key) && !source.get(key).isJsonNull())
            target.add(key, source.get(key).deepCopy());
    }

    private static JsonObject object(JsonObject source, String key) {
        return source.has(key) && source.get(key).isJsonObject()
                ? source.getAsJsonObject(key) : new JsonObject();
    }

    private static String string(JsonObject source, String key, String fallback) {
        try {
            return source.has(key) && !source.get(key).isJsonNull()
                    ? source.get(key).getAsString() : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static String fancy(JsonElement element, String fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject()) return string(element.getAsJsonObject(), "text", fallback);
        return fallback;
    }

    private static boolean hasCoordinate(JsonObject object, String... keys) {
        for (String key : keys) if (object.has(key) && object.get(key).isJsonPrimitive()) return true;
        return false;
    }

    private static void copyCoordinate(JsonObject object, String from, String to) {
        if (object.has(from) && object.get(from).isJsonPrimitive())
            object.add(to, object.get(from).deepCopy());
    }
}
