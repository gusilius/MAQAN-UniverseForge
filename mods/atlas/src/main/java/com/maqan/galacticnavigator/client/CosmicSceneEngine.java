package com.maqan.galacticnavigator.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maqan.galacticnavigator.data.GalaxyCatalog;

import java.util.*;

/**
 * Read-only, immutable scene model shared by every atlas level.
 *
 * This is deliberately an additive layer: GalaxyCatalog remains the source of
 * Cosmic Horizons/datapack data, NativeOrbitalSimulation remains the orbital
 * authority, and existing renderers continue to own their current appearance.
 * Global placement is kept separate from system-local body coordinates.
 */
public final class CosmicSceneEngine {
    public enum PlacementSource { DATAPACK, SCHEMATIC, UNPLACED }
    public enum SceneLevel { UNIVERSE, GALAXY, SECTOR, SYSTEM, BODY }

    public record Vector3(double x, double y, double z) {
        public static final Vector3 ZERO = new Vector3(0, 0, 0);
        public boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }

    public record SceneBody(String id, String name, String kind, String parentId,
                            Vector3 localPosition, PlacementSource positionSource,
                            JsonObject sourceData) {
        public SceneBody {
            sourceData = sourceData == null ? new JsonObject() : sourceData.deepCopy();
        }
        @Override public JsonObject sourceData() { return sourceData.deepCopy(); }
    }

    public record SceneSystem(String id, String name, String galaxyId, String galaxyName,
                              String sectorId, String sectorName, Vector3 universePosition,
                              PlacementSource placementSource, List<SceneBody> bodies,
                              JsonObject sourceData) {
        public SceneSystem {
            bodies = List.copyOf(bodies);
            sourceData = sourceData == null ? new JsonObject() : sourceData.deepCopy();
        }
        @Override public JsonObject sourceData() { return sourceData.deepCopy(); }
    }

    public record SceneSector(String id, String name, String galaxyId,
                              List<SceneSystem> systems) {
        public SceneSector { systems = List.copyOf(systems); }
    }

    public record SceneGalaxy(String id, String name, List<SceneSector> sectors,
                              List<SceneSystem> systems) {
        public SceneGalaxy {
            sectors = List.copyOf(sectors);
            systems = List.copyOf(systems);
        }
    }

    public record SceneSnapshot(String universeId, String universeName, long catalogRevision,
                                double gameTime, List<SceneGalaxy> galaxies,
                                List<SceneSector> sectors, List<SceneSystem> systems,
                                int bodyCount, int datapackPlacedSystems,
                                int schematicSystems, int unplacedSystems) {
        public SceneSnapshot {
            galaxies = List.copyOf(galaxies);
            sectors = List.copyOf(sectors);
            systems = List.copyOf(systems);
        }
        public Optional<SceneSystem> system(String id) {
            return systems.stream().filter(s -> s.id().equals(id)).findFirst();
        }
        public Optional<SceneBody> body(String systemId, String bodyId) {
            return system(systemId).stream().flatMap(s -> s.bodies().stream())
                    .filter(b -> b.id().equals(bodyId)).findFirst();
        }
    }

    private static long revision;
    private static JsonObject lastRoot;

    private CosmicSceneEngine() {}

    /**
     * Captures all currently loaded systems in the active universe. No system
     * or body is truncated to fit a preview budget.
     */
    public static synchronized SceneSnapshot capture(String universeId, double gameTime) {
        JsonObject root = GalaxyCatalog.ROOT;
        if (root != lastRoot) {
            lastRoot = root;
            revision++;
        }
        JsonObject universe = GalaxyCatalog.universe(universeId);
        String universeName = GalaxyCatalog.text(universe, "name", universeId);
        List<SceneSystem> systems = new ArrayList<>();
        int bodyCount = 0, datapack = 0, schematic = 0, unplaced = 0;

        for (String systemId : GalaxyCatalog.universes()) {
            JsonObject systemData = GalaxyCatalog.universe(systemId);
            if (!belongsToUniverse(systemId, systemData, universeId, universe)) continue;
            List<SceneBody> bodies = new ArrayList<>();
            for (Map.Entry<String, JsonObject> entry : GalaxyCatalog.objects(systemId)) {
                JsonObject source = entry.getValue();
                String bodyId = GalaxyCatalog.text(source, "catalog_id", entry.getKey());
                String kind = firstText(source, "kind", "display_type", "type");
                String parent = firstText(source, "orbit_parent_id", "orbit_parent",
                        "parent_id", "parent", "parent_body", "central_body");
                Vector3 local = orbitalPosition(systemId, systemData, source, gameTime);
                bodies.add(new SceneBody(bodyId,
                        GalaxyCatalog.text(source, "name", entry.getKey()), kind, parent,
                        local, hasBodyCoordinates(source) && local.finite()
                                ? PlacementSource.DATAPACK : PlacementSource.UNPLACED,
                        source));
            }
            if (bodies.isEmpty()) continue;
            bodyCount += bodies.size();
            Vector3 placement = globalPosition(systemData);
            PlacementSource placementSource = placement == null
                    ? PlacementSource.UNPLACED : PlacementSource.DATAPACK;
            if (placement == null) placement = Vector3.ZERO;
            if (placementSource == PlacementSource.DATAPACK) datapack++;
            else unplaced++;
            String galaxyId = groupValue(systemId, systemData, "galaxy_id", "galaxy");
            String galaxyName = groupLabel(systemId, systemData, galaxyId, "galaxy_name", "galaxy");
            String sectorId = groupValue(systemId, systemData, "sector_id", "sector");
            String sectorName = groupLabel(systemId, systemData, sectorId, "sector_name", "sector");
            systems.add(new SceneSystem(systemId,
                    GalaxyCatalog.text(systemData, "name", systemId),
                    galaxyId, galaxyName, sectorId, sectorName, placement,
                    placementSource, bodies, systemData));
        }

        // Deterministic schematic placement is only assigned to the scene copy;
        // source JSON and the Cosmic Horizons catalogue are never rewritten.
        assignSchematicPositions(systems);
        for (SceneSystem system : systems)
            if (system.placementSource() == PlacementSource.SCHEMATIC) schematic++;

        Map<String, List<SceneSystem>> galaxyGroups = groupSystems(systems, true);
        List<SceneGalaxy> galaxies = new ArrayList<>();
        for (Map.Entry<String, List<SceneSystem>> entry : galaxyGroups.entrySet()) {
            List<SceneSystem> members = entry.getValue();
            String name = members.stream().map(SceneSystem::galaxyName)
                    .filter(s -> !s.isBlank()).findFirst().orElse(entry.getKey());
            Map<String, List<SceneSystem>> sectorGroups = groupSystems(members, false);
            List<SceneSector> nestedSectors = toSectors(sectorGroups, entry.getKey());
            galaxies.add(new SceneGalaxy(entry.getKey(), name, nestedSectors, members));
        }
        List<SceneSector> sectors = toSectors(groupSystems(systems, false), "");
        return new SceneSnapshot(universeId, universeName, revision, gameTime,
                galaxies, sectors, systems, bodyCount, datapack, schematic, unplaced);
    }

    private static boolean belongsToUniverse(String systemId, JsonObject system,
                                             String selectedId, JsonObject selectedUniverse) {
        String explicit = firstText(system, "universe_id", "universe", "cosmos_id");
        if (explicit.isBlank()) {
            for (Map.Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
                explicit = firstText(body.getValue(), "universe_id", "universe", "cosmos_id");
                if (!explicit.isBlank()) break;
            }
        }
        if (explicit.isBlank()) return true;
        String selectedGroup = firstText(selectedUniverse, "universe_id", "universe", "cosmos_id");
        if (selectedGroup.isBlank()) {
            for (Map.Entry<String, JsonObject> body : GalaxyCatalog.objects(selectedId)) {
                selectedGroup = firstText(body.getValue(), "universe_id", "universe", "cosmos_id");
                if (!selectedGroup.isBlank()) break;
            }
        }
        String selectedName = GalaxyCatalog.text(selectedUniverse, "id", selectedId);
        return explicit.equalsIgnoreCase(selectedId)
                || explicit.equalsIgnoreCase(selectedName)
                || (!selectedGroup.isBlank() && explicit.equalsIgnoreCase(selectedGroup));
    }

    private static boolean hasBodyCoordinates(JsonObject body) {
        return body.has("map_pos") || body.has("pos") || body.has("x")
                || body.has("xpos") || body.has("y") || body.has("ypos")
                || body.has("z") || body.has("zpos");
    }

    private static Vector3 orbitalPosition(String systemId, JsonObject system,
                                           JsonObject body, double gameTime) {
        double x = NativeOrbitalSimulation.coordinate(systemId, system, body, 0, gameTime);
        double y = NativeOrbitalSimulation.coordinate(systemId, system, body, 1, gameTime);
        double z = NativeOrbitalSimulation.coordinate(systemId, system, body, 2, gameTime);
        return new Vector3(x, y, z);
    }

    private static Vector3 globalPosition(JsonObject system) {
        // Do not reuse galaxy placement metadata as a star-system coordinate.
        // GalaxyCatalog's galaxy_map_pos/galaxy_location belong to the galaxy layer.
        for (String key : new String[]{"universe_system_pos", "galaxy_system_pos",
                "map_pos_global"}) {
            if (!system.has(key) || system.get(key).isJsonNull()) continue;
            Vector3 value = vector(system.get(key));
            if (value != null && value.finite()) return value;
        }
        return null;
    }

    private static Vector3 vector(JsonElement element) {
        try {
            if (element.isJsonArray() && element.getAsJsonArray().size() >= 3) {
                return new Vector3(element.getAsJsonArray().get(0).getAsDouble(),
                        element.getAsJsonArray().get(1).getAsDouble(),
                        element.getAsJsonArray().get(2).getAsDouble());
            }
            if (element.isJsonObject()) {
                JsonObject o = element.getAsJsonObject();
                return new Vector3(number(o, "x"), number(o, "y"), number(o, "z"));
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static double number(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsDouble() : Double.NaN;
    }

    private static void assignSchematicPositions(List<SceneSystem> systems) {
        int missing = 0;
        for (int i = 0; i < systems.size(); i++) {
            SceneSystem system = systems.get(i);
            if (system.placementSource() != PlacementSource.UNPLACED) continue;
            double angle = missing * 2.399963229728653;
            double radius = Math.sqrt(missing + .5);
            Vector3 position = new Vector3(Math.cos(angle) * radius, 0,
                    Math.sin(angle) * radius);
            systems.set(i, new SceneSystem(system.id(), system.name(), system.galaxyId(),
                    system.galaxyName(), system.sectorId(), system.sectorName(), position,
                    PlacementSource.SCHEMATIC, system.bodies(), system.sourceData()));
            missing++;
        }
    }

    private static Map<String, List<SceneSystem>> groupSystems(List<SceneSystem> systems,
                                                                boolean byGalaxy) {
        Map<String, List<SceneSystem>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SceneSystem system : systems) {
            String key = byGalaxy ? system.galaxyId() : system.sectorId();
            if (key == null || key.isBlank()) key = byGalaxy ? "__unassigned_galaxy" : "__unassigned_sector";
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(system);
        }
        return groups;
    }

    private static List<SceneSector> toSectors(Map<String, List<SceneSystem>> groups,
                                                String galaxyId) {
        List<SceneSector> result = new ArrayList<>();
        for (Map.Entry<String, List<SceneSystem>> entry : groups.entrySet()) {
            String name = entry.getValue().stream().map(SceneSystem::sectorName)
                    .filter(s -> !s.isBlank()).findFirst().orElse(entry.getKey());
            result.add(new SceneSector(entry.getKey(), name, galaxyId,
                    entry.getValue()));
        }
        return result;
    }

    private static String groupValue(String systemId, JsonObject system, String... keys) {
        String value = firstText(system, keys);
        if (!value.isBlank()) return value;
        for (Map.Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
            value = firstText(body.getValue(), keys);
            if (!value.isBlank()) return value;
        }
        return "__unassigned";
    }

    private static String groupLabel(String systemId, JsonObject system, String fallback,
                                      String... keys) {
        String label = firstText(system, keys);
        if (!label.isBlank()) return label;
        for (Map.Entry<String, JsonObject> body : GalaxyCatalog.objects(systemId)) {
            label = firstText(body.getValue(), keys);
            if (!label.isBlank()) return label;
        }
        return fallback;
    }

    private static String firstText(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = GalaxyCatalog.text(object, key, "");
            if (!value.isBlank()) return value.trim();
        }
        return "";
    }
}
