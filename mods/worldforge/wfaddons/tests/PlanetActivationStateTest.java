package dev.worldforge.tests;

import dev.worldforge.mod.PlanetActivationState;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PlanetActivationStateTest {
    private static int failures;
    private static void check(boolean ok, String message) {
        System.out.println((ok ? "ok   " : "FAIL ") + message);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("maqan-planet-activation-");
        Path file = dir.resolve("worldforge/planet-activation.json");
        try {
            PlanetActivationState empty = PlanetActivationState.load(file);
            check(!empty.isDimensionActive("worldforge:real-map")
                    && !empty.isMapActive("real-map"), "unvisited planet remains dormant by default");

            PlanetActivationState active = empty.activate("worldforge:real-map", "real-map");
            check(active.isDimensionActive("worldforge:real-map")
                    && active.isMapActive("real-map"), "first visit activates exactly its dimension and map");

            check(active.activate("worldforge:real-map", "real-map") == active,
                    "repeated activation is idempotent");

            boolean dimensionRebindRejected = false;
            try { active.activate("worldforge:real-map", "different-map"); }
            catch (IllegalStateException expected) { dimensionRebindRejected = true; }
            check(dimensionRebindRejected, "dimension cannot silently change planet identity");

            boolean duplicateMapRejected = false;
            try { active.activate("worldforge:second-copy", "real-map"); }
            catch (IllegalStateException expected) { duplicateMapRejected = true; }
            check(duplicateMapRejected, "one map package cannot be mounted as two active planets");

            PlanetActivationState initialized = active.markSpawnInitialized("worldforge:real-map");
            check(initialized.isSpawnInitialized("worldforge:real-map"),
                    "first arrival can persist its safe respawn point");

            initialized.save(file);
            PlanetActivationState restored = PlanetActivationState.load(file);
            check(restored.isDimensionActive("worldforge:real-map")
                    && restored.isMapActive("real-map")
                    && restored.isSpawnInitialized("worldforge:real-map"),
                    "activation and spawn state survive restart");

            boolean corruptRejected = false;
            Path corrupt = dir.resolve("corrupt.json");
            Files.writeString(corrupt, "{not-json");
            try { PlanetActivationState.load(corrupt); }
            catch (Exception expected) { corruptRejected = true; }
            check(corruptRejected, "corrupt activation state fails closed instead of activating every planet");

            System.out.println(failures == 0 ? "ALL PLANET ACTIVATION TESTS PASSED"
                    : "PLANET ACTIVATION TEST FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("Planet activation state tests failed: " + failures);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
