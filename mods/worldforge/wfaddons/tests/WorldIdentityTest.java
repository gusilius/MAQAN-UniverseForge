package dev.worldforge.tests;

import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.mod.identity.WorldIdentityStore;

import java.nio.file.Files;
import java.nio.file.Path;

/** Persistence and fail-closed migration tests for stable world identity. */
public final class WorldIdentityTest {
    private static int failures;

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wf-world-id-test");
        Path file = root.resolve("worldforge").resolve("world_id.txt");
        try {
            WorldId first = WorldIdentityStore.loadOrCreate(file);
            WorldId second = WorldIdentityStore.loadOrCreate(file);
            check(first.equals(second), "WorldId remains stable across repeated server starts");
            check(Files.readString(file).trim().equals(first.toString()), "WorldId is durably stored in the world save");

            Path broken = root.resolve("broken").resolve("world_id.txt");
            Files.createDirectories(broken.getParent());
            Files.writeString(broken, "not-a-uuid");
            boolean rejected = false;
            try { WorldIdentityStore.loadOrCreate(broken); }
            catch (java.io.IOException expected) { rejected = true; }
            check(rejected && Files.readString(broken).equals("not-a-uuid"),
                    "corrupt identity fails closed and is never silently replaced");
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        if (failures > 0) throw new AssertionError(failures + " WorldIdentity tests failed");
        System.out.println("WorldIdentity: ALL OK");
    }
}
