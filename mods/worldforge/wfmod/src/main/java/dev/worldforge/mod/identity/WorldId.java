package dev.worldforge.mod.identity;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one saved WorldForge server world. */
public record WorldId(UUID value) {
    public WorldId {
        Objects.requireNonNull(value, "value");
    }

    public static WorldId parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("WorldId is empty");
        return new WorldId(UUID.fromString(value.trim()));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
