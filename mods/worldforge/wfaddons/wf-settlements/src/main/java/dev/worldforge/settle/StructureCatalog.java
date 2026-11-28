package dev.worldforge.settle;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Runtime reader for the settlement structure catalogue.
 *
 * The catalogue describes structure classes and the identity dimensions used by
 * the existing Defs compatibility resolver. Individual building definitions and
 * schematic payloads remain owned by their existing datapack/library registries.
 */
public final class StructureCatalog {
    private final int schemaVersion;
    private final String registryId;
    private final Map<String, Set<String>> structuresByClass;
    private final Set<String> identityDimensions;
    private final Map<String, String> catalogScopes;
    private final boolean loaded;

    private StructureCatalog(int schemaVersion, String registryId,
                              Map<String, Set<String>> structuresByClass,
                              Set<String> identityDimensions, Map<String, String> catalogScopes,
                              boolean loaded) {
        this.schemaVersion = schemaVersion;
        this.registryId = registryId;
        this.structuresByClass = Collections.unmodifiableMap(new LinkedHashMap<>(structuresByClass));
        this.identityDimensions = Collections.unmodifiableSet(new LinkedHashSet<>(identityDimensions));
        this.catalogScopes = Collections.unmodifiableMap(new LinkedHashMap<>(catalogScopes));
        this.loaded = loaded;
    }

    public static StructureCatalog empty() {
        return new StructureCatalog(0, "", Map.of(), Set.of(), Map.of(), false);
    }

    @SuppressWarnings("unchecked")
    public static StructureCatalog load(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map<?, ?> rawRoot)) throw new IllegalArgumentException("Structure catalogue root must be an object");
        Map<String, Object> root = (Map<String, Object>) rawRoot;
        int version = root.get("schema_version") instanceof Number n ? n.intValue() : 0;
        if (version < 1) throw new IllegalArgumentException("Structure catalogue schema_version must be >= 1");
        String id = String.valueOf(root.getOrDefault("registry_id", ""));
        Map<String, Set<String>> classes = new LinkedHashMap<>();
        Object rawClasses = root.get("structure_classes");
        if (!(rawClasses instanceof Map<?, ?> classMap) || classMap.isEmpty())
            throw new IllegalArgumentException("Structure catalogue must define structure_classes");
        for (Map.Entry<?, ?> entry : classMap.entrySet()) {
            if (!(entry.getKey() instanceof String classId) || classId.isBlank() || !(entry.getValue() instanceof List<?> values))
                throw new IllegalArgumentException("Invalid structure class entry");
            Set<String> members = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String member) || member.isBlank())
                    throw new IllegalArgumentException("Invalid member in structure class " + classId);
                members.add(member);
            }
            if (members.isEmpty()) throw new IllegalArgumentException("Empty structure class " + classId);
            classes.put(classId, Collections.unmodifiableSet(members));
        }

        Set<String> dimensions = new LinkedHashSet<>();
        Map<String, String> scopes = new LinkedHashMap<>();
        Object rawIdentity = root.get("identity_partitioning");
        if (rawIdentity instanceof Map<?, ?> identity) {
            Object rawDimensions = identity.get("selection_dimensions");
            if (rawDimensions instanceof List<?> values) for (Object value : values)
                if (value instanceof String dimension && !dimension.isBlank()) dimensions.add(dimension);
            Object rawScopes = identity.get("catalog_scopes");
            if (rawScopes instanceof Map<?, ?> values) for (Map.Entry<?, ?> entry : values.entrySet())
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value)
                    scopes.put(key, value);
        }
        if (dimensions.isEmpty()) throw new IllegalArgumentException("Structure catalogue has no identity selection_dimensions");
        return new StructureCatalog(version, id, classes, dimensions, scopes, true);
    }

    public boolean loaded() { return loaded; }
    public int schemaVersion() { return schemaVersion; }
    public String registryId() { return registryId; }
    public Set<String> structureClasses() { return structuresByClass.keySet(); }

    /** Matches a registered Minecraft structure ID to this data-driven class catalogue. */
    public boolean matchesStructureClass(String classId, String registryId) {
        if (classId == null || registryId == null || registryId.isBlank()) return false;
        Set<String> members = structuresByClass.get(classId);
        if (members == null) return false;
        String path = registryId.toLowerCase(Locale.ROOT);
        int namespace = path.indexOf(':');
        if (namespace >= 0) path = path.substring(namespace + 1);
        for (String member : members) {
            String candidate = member.toLowerCase(Locale.ROOT);
            if (path.equals(candidate) || path.startsWith(candidate + "_")
                    || path.endsWith("_" + candidate) || path.contains("_" + candidate + "_")
                    || path.equals(candidate.replace('_', '-'))) return true;
        }
        return false;
    }

    public Set<String> identityDimensions() { return identityDimensions; }
    public Map<String, String> catalogScopes() { return catalogScopes; }
    public boolean recognizesClass(String classId) { return !loaded || structuresByClass.containsKey(classId); }

    public boolean recognizesStructure(Defs.Building building) {
        return building == null || building.structureClass == null || building.structureClass.isBlank()
                || recognizesClass(building.structureClass);
    }

    public List<String> validate(Defs definitions) {
        List<String> problems = new ArrayList<>();
        if (!loaded) {
            problems.add("Structure catalogue was not loaded; legacy building definitions remain available");
            return problems;
        }
        for (Defs.Building building : definitions.buildings.values()) {
            if (!recognizesStructure(building))
                problems.add("Building " + building.id + " references unknown structure_class '" + building.structureClass + "'");
        }
        return problems;
    }
}
