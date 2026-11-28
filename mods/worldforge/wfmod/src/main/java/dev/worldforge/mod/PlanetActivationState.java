package dev.worldforge.mod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Durable, save-local activation ledger for planetary surface dimensions.
 *
 * Registration is not activation: only a surface explicitly visited by a player
 * enters this ledger. The ledger stores stable dimension-to-map bindings so a
 * datapack update cannot silently redirect an already visited planet.
 */
public final class PlanetActivationState {
    public static final String FORMAT = "maqan-planet-activation/1";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final java.util.regex.Pattern DIMENSION =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final java.util.regex.Pattern MAP_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.:-]{1,256}");

    private final Map<String, String> activeMapsByDimension;
    private final Set<String> spawnInitializedDimensions;

    private PlanetActivationState(Map<String, String> active, Set<String> spawnInitialized) {
        this.activeMapsByDimension = Collections.unmodifiableMap(new TreeMap<>(active));
        this.spawnInitializedDimensions = Collections.unmodifiableSet(new TreeSet<>(spawnInitialized));
    }

    public static PlanetActivationState empty() {
        return new PlanetActivationState(Map.of(), Set.of());
    }

    public boolean isDimensionActive(String dimension) {
        return activeMapsByDimension.containsKey(dimension);
    }

    public boolean isMapActive(String mapId) {
        return activeMapsByDimension.containsValue(mapId);
    }

    public boolean isSpawnInitialized(String dimension) {
        return spawnInitializedDimensions.contains(dimension);
    }

    public Map<String, String> activeMapsByDimension() {
        return activeMapsByDimension;
    }

    /** Returns this instance for an idempotent repeat; rejects identity rebinding. */
    public PlanetActivationState activate(String dimension, String mapId) {
        requireDimension(dimension);
        requireMapId(mapId);
        String existing = activeMapsByDimension.get(dimension);
        if (existing != null) {
            if (!existing.equals(mapId))
                throw new IllegalStateException("Planet dimension " + dimension
                        + " is already bound to map " + existing + ", not " + mapId);
            return this;
        }
        for (Map.Entry<String, String> entry : activeMapsByDimension.entrySet()) {
            if (entry.getValue().equals(mapId))
                throw new IllegalStateException("Map package " + mapId
                        + " is already active in dimension " + entry.getKey());
        }
        Map<String, String> next = new TreeMap<>(activeMapsByDimension);
        next.put(dimension, mapId);
        return new PlanetActivationState(next, spawnInitializedDimensions);
    }

    public PlanetActivationState markSpawnInitialized(String dimension) {
        if (!isDimensionActive(dimension))
            throw new IllegalStateException("Cannot initialize spawn for an inactive planet: " + dimension);
        if (spawnInitializedDimensions.contains(dimension)) return this;
        Set<String> next = new TreeSet<>(spawnInitializedDimensions);
        next.add(dimension);
        return new PlanetActivationState(activeMapsByDimension, next);
    }

    public static PlanetActivationState load(Path file) throws IOException {
        if (file == null) throw new IOException("Planet activation path is missing");
        if (Files.isSymbolicLink(file)) throw new IOException("Planet activation file cannot be a symbolic link");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return empty();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Planet activation path is not a regular file");
        long size = Files.size(file);
        if (size <= 0 || size > MAX_FILE_BYTES) throw new IOException("Planet activation file has invalid size: " + size);

        Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("Planet activation file is corrupt", error);
        }
        if (!(parsed instanceof Map<?, ?> root)
                || !FORMAT.equals(root.get("format"))
                || !(root.get("schema_version") instanceof Number version)
                || version.intValue() != SCHEMA_VERSION
                || !(root.get("active_dimensions") instanceof List<?> dimensions)
                || !(root.get("spawn_initialized_dimensions") instanceof List<?> spawnRows))
            throw new IOException("Unsupported planet activation format");

        Map<String, String> active = new TreeMap<>();
        for (Object item : dimensions) {
            if (!(item instanceof Map<?, ?> row)
                    || !(row.get("dimension") instanceof String dimension)
                    || !(row.get("map_id") instanceof String mapId))
                throw new IOException("Invalid planet activation entry");
            try {
                requireDimension(dimension);
                requireMapId(mapId);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid planet activation identity", invalid);
            }
            if (active.putIfAbsent(dimension, mapId) != null)
                throw new IOException("Duplicate planet dimension in activation file: " + dimension);
        }
        Set<String> spawn = new TreeSet<>();
        for (Object item : spawnRows) {
            if (!(item instanceof String dimension))
                throw new IOException("Invalid planet spawn initialization entry");
            try { requireDimension(dimension); }
            catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid planet spawn dimension", invalid);
            }
            if (!active.containsKey(dimension) || !spawn.add(dimension))
                throw new IOException("Spawn initialization references an inactive or duplicate dimension: " + dimension);
        }
        Set<String> mapIds = new HashSet<>();
        for (String mapId : active.values())
            if (!mapIds.add(mapId)) throw new IOException("One map package is bound to multiple active dimensions: " + mapId);
        return new PlanetActivationState(active, spawn);
    }

    public void save(Path file) throws IOException {
        if (file == null) throw new IOException("Planet activation path is missing");
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Planet activation file has no parent directory");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(target))
            throw new IOException("Planet activation path cannot contain symbolic links");

        List<Object> dimensions = new ArrayList<>();
        for (Map.Entry<String, String> entry : activeMapsByDimension.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dimension", entry.getKey());
            row.put("map_id", entry.getValue());
            dimensions.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("schema_version", SCHEMA_VERSION);
        root.put("active_dimensions", dimensions);
        root.put("spawn_initialized_dimensions", new ArrayList<>(spawnInitializedDimensions));
        byte[] bytes = (Json.write(root) + "\n").getBytes(StandardCharsets.UTF_8);

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new IOException("Planet activation temporary path is unsafe");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireDimension(String dimension) {
        if (dimension == null || dimension.length() > 256 || !DIMENSION.matcher(dimension).matches())
            throw new IllegalArgumentException("Invalid dimension resource key: " + dimension);
    }

    private static void requireMapId(String mapId) {
        if (mapId == null || !MAP_ID.matcher(mapId).matches())
            throw new IllegalArgumentException("Invalid planet map ID: " + mapId);
    }
}
