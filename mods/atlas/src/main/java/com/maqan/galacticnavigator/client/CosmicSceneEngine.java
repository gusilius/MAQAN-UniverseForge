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

    private static final double SPATIAL_CELL_SIZE = 256.0;
    private static final long MAX_SPATIAL_BUCKETS_PER_QUERY = 4096L;

    public record SceneSnapshot(String universeId, String universeName, long catalogRevision,
                                double gameTime, List<SceneGalaxy> galaxies,
                                List<SceneSector> sectors, List<SceneSystem> systems,
                                int bodyCount, int datapackPlacedSystems,
                                int schematicSystems, int unplacedSystems,
                                Map<String, SceneSystem> systemIndex,
                                Map<String, Map<String, SceneBody>> bodyIndex,
                                Map<String, List<SceneSystem>> galaxyIndex,
                                Map<String, List<SceneSystem>> sectorIndex,
                                Map<String, List<SceneSystem>> spatialCellIndex,
                                Map<String, Map<String, SceneBody>> parentBodyIndex,
                                Map<String, Map<String, List<SceneBody>>> childBodyIndex,
                                Map<String, List<SceneBody>> rootBodyIndex,
                                Map<String, Set<String>> unresolvedParentIndex,
                                Map<String, Set<String>> cyclicBodyIndex) {
        public SceneSnapshot {
            galaxies = List.copyOf(galaxies);
            sectors = List.copyOf(sectors);
            systems = List.copyOf(systems);

            Map<String, SceneSystem> byId = new LinkedHashMap<>();
            Map<String, Map<String, SceneBody>> bodiesBySystem = new LinkedHashMap<>();
            Map<String, List<SceneSystem>> byGalaxy = new LinkedHashMap<>();
            Map<String, List<SceneSystem>> bySector = new LinkedHashMap<>();
            Map<String, List<SceneSystem>> bySpatialCell = new LinkedHashMap<>();
            for (SceneSystem system : systems) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                byId.putIfAbsent(system.id(), system);
                Map<String, SceneBody> bodyMap = new LinkedHashMap<>();
                for (SceneBody body : system.bodies()) {
                    if (body != null && body.id() != null && !body.id().isBlank())
                        bodyMap.putIfAbsent(body.id(), body);
                }
                bodiesBySystem.put(system.id(), Collections.unmodifiableMap(bodyMap));
                byGalaxy.computeIfAbsent(normalizeGroup(system.galaxyId()), ignored -> new ArrayList<>()).add(system);
                bySector.computeIfAbsent(normalizeGroup(system.sectorId()), ignored -> new ArrayList<>()).add(system);
                if (system.placementSource() != PlacementSource.UNPLACED
                        && system.universePosition() != null && system.universePosition().finite()) {
                    Vector3 position = system.universePosition();
                    String cell = spatialCellKey(position.x(), position.y(), position.z());
                    if (cell != null)
                        bySpatialCell.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(system);
                }
            }
            systemIndex = Collections.unmodifiableMap(byId);
            bodyIndex = Collections.unmodifiableMap(bodiesBySystem);
            galaxyIndex = immutableGroups(byGalaxy);
            sectorIndex = immutableGroups(bySector);
            spatialCellIndex = immutableGroups(bySpatialCell);

            Map<String, Map<String, SceneBody>> parentsBySystem = new LinkedHashMap<>();
            Map<String, Map<String, List<SceneBody>>> childrenBySystem = new LinkedHashMap<>();
            Map<String, List<SceneBody>> rootsBySystem = new LinkedHashMap<>();
            Map<String, Set<String>> unresolvedBySystem = new LinkedHashMap<>();
            Map<String, Set<String>> cyclesBySystem = new LinkedHashMap<>();
            for (SceneSystem system : systems) {
                if (system == null || system.id() == null) continue;
                Map<String, SceneBody> bodies = bodyIndex.getOrDefault(system.id(), Map.of());
                Map<String, SceneBody> resolvedParents = new LinkedHashMap<>();
                Map<String, List<SceneBody>> children = new LinkedHashMap<>();
                List<SceneBody> roots = new ArrayList<>();
                Set<String> unresolved = new LinkedHashSet<>();
                Map<String, String> foldedIds = new HashMap<>();
                bodies.keySet().forEach(id -> foldedIds.putIfAbsent(
                        id.toLowerCase(Locale.ROOT), id));

                for (SceneBody body : bodies.values()) {
                    String parentId = body.parentId() == null ? "" : body.parentId().trim();
                    if (parentId.isEmpty()) {
                        roots.add(body);
                        continue;
                    }
                    String resolvedId = bodies.containsKey(parentId)
                            ? parentId : foldedIds.get(parentId.toLowerCase(Locale.ROOT));
                    if (resolvedId == null) {
                        unresolved.add(body.id());
                        continue;
                    }
                    SceneBody parent = bodies.get(resolvedId);
                    resolvedParents.put(body.id(), parent);
                    children.computeIfAbsent(resolvedId, ignored -> new ArrayList<>()).add(body);
                }

                Set<String> cyclic = findCycles(bodies.keySet(), resolvedParents);
                Map<String, List<SceneBody>> immutableChildren = new LinkedHashMap<>();
                children.forEach((id, list) -> immutableChildren.put(id, List.copyOf(list)));
                parentsBySystem.put(system.id(), Collections.unmodifiableMap(resolvedParents));
                childrenBySystem.put(system.id(), Collections.unmodifiableMap(immutableChildren));
                rootsBySystem.put(system.id(), List.copyOf(roots));
                unresolvedBySystem.put(system.id(), Collections.unmodifiableSet(unresolved));
                cyclesBySystem.put(system.id(), Collections.unmodifiableSet(cyclic));
            }
            parentBodyIndex = Collections.unmodifiableMap(parentsBySystem);
            childBodyIndex = Collections.unmodifiableMap(childrenBySystem);
            rootBodyIndex = Collections.unmodifiableMap(rootsBySystem);
            unresolvedParentIndex = Collections.unmodifiableMap(unresolvedBySystem);
            cyclicBodyIndex = Collections.unmodifiableMap(cyclesBySystem);
        }

        private static Set<String> findCycles(Set<String> bodyIds,
                                              Map<String, SceneBody> parents) {
            Set<String> completed = new HashSet<>();
            Set<String> cycles = new LinkedHashSet<>();
            for (String start : bodyIds) {
                if (completed.contains(start)) continue;
                List<String> path = new ArrayList<>();
                Map<String, Integer> pathPositions = new HashMap<>();
                String current = start;
                while (current != null && !completed.contains(current)) {
                    Integer cycleStart = pathPositions.putIfAbsent(current, path.size());
                    if (cycleStart != null) {
                        cycles.addAll(path.subList(cycleStart, path.size()));
                        break;
                    }
                    path.add(current);
                    SceneBody parent = parents.get(current);
                    current = parent == null ? null : parent.id();
                }
                completed.addAll(path);
            }
            return cycles;
        }

        /** Backwards-compatible constructor for callers that only provide scene data. */
        public SceneSnapshot(String universeId, String universeName, long catalogRevision,
                             double gameTime, List<SceneGalaxy> galaxies,
                             List<SceneSector> sectors, List<SceneSystem> systems,
                             int bodyCount, int datapackPlacedSystems,
                             int schematicSystems, int unplacedSystems) {
            this(universeId, universeName, catalogRevision, gameTime, galaxies, sectors, systems,
                    bodyCount, datapackPlacedSystems, schematicSystems, unplacedSystems,
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        public Optional<SceneSystem> system(String id) {
            return Optional.ofNullable(systemIndex.get(id));
        }

        public Optional<SceneBody> body(String systemId, String bodyId) {
            return Optional.ofNullable(bodyIndex.getOrDefault(systemId, Map.of()).get(bodyId));
        }

        public List<SceneSystem> systemsInGalaxy(String galaxyId) {
            return galaxyIndex.getOrDefault(normalizeGroup(galaxyId), List.of());
        }

        public List<SceneSystem> systemsInSector(String sectorId) {
            return sectorIndex.getOrDefault(normalizeGroup(sectorId), List.of());
        }

        /** Stable body identity is scoped to its owning star system. */
        public static String bodyKey(String systemId, String bodyId) {
            return (systemId == null ? "" : systemId) + "::" + (bodyId == null ? "" : bodyId);
        }

        public Optional<SceneBody> parentBody(String systemId, String bodyId) {
            return Optional.ofNullable(parentBodyIndex
                    .getOrDefault(systemId, Map.of()).get(bodyId));
        }

        public List<SceneBody> childBodies(String systemId, String bodyId) {
            return childBodyIndex.getOrDefault(systemId, Map.of())
                    .getOrDefault(bodyId, List.of());
        }

        public List<SceneBody> rootBodies(String systemId) {
            return rootBodyIndex.getOrDefault(systemId, List.of());
        }

        public boolean hasUnresolvedParent(String systemId, String bodyId) {
            return unresolvedParentIndex.getOrDefault(systemId, Set.of()).contains(bodyId);
        }

        public boolean isInParentCycle(String systemId, String bodyId) {
            return cyclicBodyIndex.getOrDefault(systemId, Set.of()).contains(bodyId);
        }

        /**
         * Returns systems inside a sphere using a uniform 3D spatial hash.
         * Very large query volumes fall back to a linear scan to avoid iterating
         * millions of empty cells. Unplaced systems are never treated as origin.
         */
        public List<SceneSystem> systemsWithin(Vector3 center, double radius) {
            if (center == null || !center.finite() || !Double.isFinite(radius) || radius < 0)
                return List.of();
            double radiusSquared = radius * radius;
            if (!Double.isFinite(radiusSquared)) return systemsWithinLinear(center, radiusSquared);

            long minX = cellCoordinate(center.x() - radius);
            long maxX = cellCoordinate(center.x() + radius);
            long minY = cellCoordinate(center.y() - radius);
            long maxY = cellCoordinate(center.y() + radius);
            long minZ = cellCoordinate(center.z() - radius);
            long maxZ = cellCoordinate(center.z() + radius);
            double bucketCount = ((double) maxX - minX + 1)
                    * ((double) maxY - minY + 1) * ((double) maxZ - minZ + 1);
            if (!Double.isFinite(bucketCount) || bucketCount > MAX_SPATIAL_BUCKETS_PER_QUERY)
                return systemsWithinLinear(center, radiusSquared);

            LinkedHashSet<SceneSystem> candidates = new LinkedHashSet<>();
            for (long x = minX; ; x++) {
                for (long y = minY; ; y++) {
                    for (long z = minZ; ; z++) {
                        List<SceneSystem> bucket = spatialCellIndex.get(cellKey(x, y, z));
                        if (bucket != null) candidates.addAll(bucket);
                        if (z == maxZ) break;
                    }
                    if (y == maxY) break;
                }
                if (x == maxX) break;
            }
            return filterWithin(candidates, center, radiusSquared);
        }

        private List<SceneSystem> systemsWithinLinear(Vector3 center, double radiusSquared) {
            return filterWithin(systems, center, radiusSquared);
        }

        private static List<SceneSystem> filterWithin(Collection<SceneSystem> candidates,
                                                       Vector3 center, double radiusSquared) {
            List<SceneSystem> matches = new ArrayList<>();
            for (SceneSystem system : candidates) {
                if (system.placementSource() == PlacementSource.UNPLACED) continue;
                Vector3 position = system.universePosition();
                if (position == null || !position.finite()) continue;
                double dx = position.x() - center.x();
                double dy = position.y() - center.y();
                double dz = position.z() - center.z();
                if (dx * dx + dy * dy + dz * dz <= radiusSquared) matches.add(system);
            }
            return List.copyOf(matches);
        }

        private static long cellCoordinate(double coordinate) {
            double cell = Math.floor(coordinate / SPATIAL_CELL_SIZE);
            if (cell <= Long.MIN_VALUE) return Long.MIN_VALUE;
            if (cell >= Long.MAX_VALUE) return Long.MAX_VALUE;
            return (long) cell;
        }

        private static String spatialCellKey(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return cellKey(cellCoordinate(x), cellCoordinate(y), cellCoordinate(z));
        }

        private static String cellKey(long x, long y, long z) {
            return x + ":" + y + ":" + z;
        }

        private static String normalizeGroup(String value) {
            return value == null || value.isBlank() ? "__unassigned" : value;
        }

        private static Map<String, List<SceneSystem>> immutableGroups(
                Map<String, List<SceneSystem>> source) {
            Map<String, List<SceneSystem>> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(result);
        }
    }

    private record CatalogSystem(String id, JsonObject data,
                                 List<Map.Entry<String, JsonObject>> bodies) {}

    private static long revision;
    private static JsonObject lastRoot;
    private static List<CatalogSystem> cachedCatalog = List.of();
    private static Map<String, CatalogSystem> cachedSystems = Map.of();

    private CosmicSceneEngine() {}

    /**
     * GalaxyCatalog publishes replacement roots on resource reload. Build the
     * structural index once per root; per-frame captures reuse its immutable
     * entry lists and only refresh time-dependent positions.
     */
    private static void refreshCatalogIndex(JsonObject root) {
        if (root == lastRoot) return;
        lastRoot = root;
        revision++;
        List<CatalogSystem> systems = new ArrayList<>();
        Map<String, CatalogSystem> byId = new LinkedHashMap<>();
        if (root != null && root.has("universes") && root.get("universes").isJsonObject()) {
            JsonObject universes = root.getAsJsonObject("universes");
            for (Map.Entry<String, JsonElement> systemEntry : universes.entrySet()) {
                if (!systemEntry.getValue().isJsonObject()) continue;
                String id = systemEntry.getKey();
                JsonObject data = systemEntry.getValue().getAsJsonObject();
                List<Map.Entry<String, JsonObject>> bodies = new ArrayList<>();
                JsonElement rawBodies = data.get("planets");
                if (rawBodies != null && rawBodies.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> bodyEntry
                            : rawBodies.getAsJsonObject().entrySet()) {
                        if (bodyEntry.getValue().isJsonObject()) {
                            bodies.add(new AbstractMap.SimpleImmutableEntry<>(
                                    bodyEntry.getKey(), bodyEntry.getValue().getAsJsonObject()));
                        }
                    }
                }
                CatalogSystem indexed = new CatalogSystem(id, data, List.copyOf(bodies));
                systems.add(indexed);
                byId.put(id, indexed);
            }
        }
        cachedCatalog = List.copyOf(systems);
        cachedSystems = Collections.unmodifiableMap(byId);
    }

    /**
     * Captures all currently loaded systems in the active universe. No system
     * or body is truncated to fit a preview budget.
     */
    public static synchronized SceneSnapshot capture(String universeId, double gameTime) {
        JsonObject root = GalaxyCatalog.ROOT;
        refreshCatalogIndex(root);
        CatalogSystem selectedCatalog = cachedSystems.get(universeId);
        JsonObject universe = selectedCatalog == null ? new JsonObject() : selectedCatalog.data();
        String universeName = GalaxyCatalog.text(universe, "name", universeId);
        List<SceneSystem> systems = new ArrayList<>();
        int bodyCount = 0, datapack = 0, schematic = 0, unplaced = 0;

        List<Map.Entry<String, JsonObject>> selectedBodies = selectedCatalog == null
                ? List.of() : selectedCatalog.bodies();
        for (CatalogSystem catalogSystem : cachedCatalog) {
            String systemId = catalogSystem.id();
            JsonObject systemData = catalogSystem.data();
            List<Map.Entry<String, JsonObject>> sourceBodies = catalogSystem.bodies();
            if (!belongsToUniverse(systemData, sourceBodies, universeId, universe,
                    selectedBodies)) continue;
            List<SceneBody> bodies = new ArrayList<>(sourceBodies.size());
            for (Map.Entry<String, JsonObject> entry : sourceBodies) {
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
            String galaxyId = groupValue(sourceBodies, systemData, "galaxy_id", "galaxy");
            String galaxyName = groupLabel(sourceBodies, systemData, galaxyId, "galaxy_name", "galaxy");
            String sectorId = groupValue(sourceBodies, systemData, "sector_id", "sector");
            String sectorName = groupLabel(sourceBodies, systemData, sectorId, "sector_name", "sector");
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

    private static boolean belongsToUniverse(JsonObject system,
                                             List<Map.Entry<String, JsonObject>> systemBodies,
                                             String selectedId, JsonObject selectedUniverse,
                                             List<Map.Entry<String, JsonObject>> selectedBodies) {
        String explicit = firstText(system, "universe_id", "universe", "cosmos_id");
        if (explicit.isBlank()) {
            for (Map.Entry<String, JsonObject> body : systemBodies) {
                explicit = firstText(body.getValue(), "universe_id", "universe", "cosmos_id");
                if (!explicit.isBlank()) break;
            }
        }
        if (explicit.isBlank()) return true;
        String selectedGroup = firstText(selectedUniverse, "universe_id", "universe", "cosmos_id");
        if (selectedGroup.isBlank()) {
            for (Map.Entry<String, JsonObject> body : selectedBodies) {
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

    private static String groupValue(List<Map.Entry<String, JsonObject>> bodies,
                                     JsonObject system, String... keys) {
        String value = firstText(system, keys);
        if (!value.isBlank()) return value;
        for (Map.Entry<String, JsonObject> body : bodies) {
            value = firstText(body.getValue(), keys);
            if (!value.isBlank()) return value;
        }
        return "__unassigned";
    }

    private static String groupLabel(List<Map.Entry<String, JsonObject>> bodies,
                                     JsonObject system, String fallback, String... keys) {
        String label = firstText(system, keys);
        if (!label.isBlank()) return label;
        for (Map.Entry<String, JsonObject> body : bodies) {
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
