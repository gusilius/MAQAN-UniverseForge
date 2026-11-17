package dev.worldforge.people;

import dev.worldforge.mod.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable world-scoped fallback origin for legacy Characters without per-Character receipts. */
public final class PopulationWorldBaseline {
    private static final String FORMAT = "wf-population-baseline/1";
    private PopulationWorldBaseline() { }

    public static long loadOrInitialize(Path file, String worldId, long currentWorldDay) throws IOException {
        if (file == null) throw new IllegalArgumentException("baseline file is required");
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId is required");
        if (currentWorldDay < 0) throw new IllegalArgumentException("currentWorldDay cannot be negative");
        if (Files.exists(file)) {
            Object parsed = Json.parse(Files.readString(file));
            if (!(parsed instanceof Map<?, ?> saved)
                    || !FORMAT.equals(saved.get("format"))
                    || !worldId.equals(saved.get("world_id")))
                throw new IllegalStateException("Population baseline format or world identity mismatch");
            Object rawDay = saved.get("world_day");
            long baseline;
            try {
                if (!(rawDay instanceof String text)) throw new NumberFormatException("world_day must be a decimal string");
                baseline = Long.parseLong(text);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Population baseline world_day is invalid", ex);
            }
            if (baseline < 0 || baseline > currentWorldDay)
                throw new IllegalStateException("Population baseline is outside the loaded world clock");
            return baseline;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("format", FORMAT);
        state.put("world_id", worldId);
        state.put("world_day", Long.toString(currentWorldDay));
        writeAtomic(file, Json.write(state));
        return currentWorldDay;
    }

    private static void writeAtomic(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
