package dev.worldforge.mod.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Crash-safe persistence for a world's stable identity.
 *
 * Existing malformed identity files fail closed: silently replacing one would fork
 * every persisted Character/settlement reference into a different world.
 */
public final class WorldIdentityStore {
    private WorldIdentityStore() { }

    public static WorldId loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) {
            try {
                return WorldId.parse(Files.readString(file, StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid WorldId at " + file + "; refusing to generate a replacement", ex);
            }
        }

        Files.createDirectories(file.toAbsolutePath().getParent());
        WorldId created = new WorldId(UUID.randomUUID());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, created + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                try {
                    Files.move(temp, file);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    Files.deleteIfExists(temp);
                    return loadOrCreate(file);
                }
            } catch (java.nio.file.FileAlreadyExistsException race) {
                Files.deleteIfExists(temp);
                return loadOrCreate(file);
            }
            return created;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
