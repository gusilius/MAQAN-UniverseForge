package com.maqan.galacticnavigator.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration for the in-world Cosmic Horizons orbital overlay.
 * The simulation is client-rendered and uses the synchronized Minecraft game clock.
 */
public final class CosmicOrbitSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("maqan_galactic_navigator").resolve("cosmic_orbits.json");
    private static Config config;
    private CosmicOrbitSettings() {}

    public static synchronized Config get() {
        if (config == null) {
            config = load();
            if (!Files.exists(FILE)) write(config);
        }
        return config;
    }

    public static synchronized void reload() {
        config = load();
    }

    public static synchronized void resetDefaults() {
        config = new Config();
        config.normalize();
        write(config);
    }

    /**
     * One-time migration from the former Java Preferences orbit editor.
     * New edits are stored only in cosmic_orbits.json.
     */
    public static synchronized void migrateLegacyOverrides(String systemId, String bodyId) {
        if (systemId == null || systemId.isBlank() || bodyId == null || bodyId.isBlank()) return;
        BodyOverride override = get().body(systemId, bodyId);
        boolean changed = false;
        String legacyParent = NavigatorPreferences.consumeManualParent(systemId, bodyId);
        if (!legacyParent.isBlank() && override.parentId.isBlank()) {
            override.parentId = legacyParent;
            changed = true;
        }
        double legacyRadius = NavigatorPreferences.consumeManualOrbitRadius(systemId, bodyId);
        if (legacyRadius > 0.0 && override.orbitRadius <= 0.0) {
            override.orbitRadius = legacyRadius;
            changed = true;
        }
        if (changed) save();
    }

    public static synchronized void save() {
        write(get());
    }

    private static void write(Config current) {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(current, writer);
            }
        } catch (Exception error) {
            System.err.println("[MAQAN] Could not save cosmic orbit settings: " + error.getMessage());
        }
    }

    private static Config load() {
        try {
            if (Files.isRegularFile(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Config loaded = GSON.fromJson(reader, Config.class);
                    if (loaded != null) {
                        loaded.normalize();
                        return loaded;
                    }
                }
            }
        } catch (Exception error) {
            System.err.println("[MAQAN] Could not read cosmic orbit settings; defaults will be used: "
                    + error.getMessage());
        }
        Config defaults = new Config();
        defaults.normalize();
        return defaults;
    }

    public static final class Config {
        /** Enable actual in-world celestial-body rendering in Cosmic Horizons system dimensions. */
        public boolean enabled = true;
        /** Calculate orbital positions from game time. */
        public boolean orbitalMotion = true;
        /** Rotate each body's surface around its own axis. */
        public boolean axialRotation = true;
        /** Global simulation speed: 1.0 = one Minecraft tick per game tick. */
        public double simulationSpeed = 1.0;
        /** Fallback period when a datapack does not define one. */
        public double defaultPlanetOrbitTicks = 24000.0;
        public double defaultMoonOrbitTicks = 2400.0;
        public double defaultAxialRotationTicks = 2400.0;
        /** Body-specific overrides, keyed as systemId/bodyId. */
        public Map<String, BodyOverride> bodies = new LinkedHashMap<>();

        void normalize() {
            if (!Double.isFinite(simulationSpeed)) simulationSpeed = 1.0;
            simulationSpeed = clamp(simulationSpeed, 0.0, 100.0);
            if (!Double.isFinite(defaultPlanetOrbitTicks) || defaultPlanetOrbitTicks <= 0)
                defaultPlanetOrbitTicks = 24000.0;
            if (!Double.isFinite(defaultMoonOrbitTicks) || defaultMoonOrbitTicks <= 0)
                defaultMoonOrbitTicks = 2400.0;
            if (!Double.isFinite(defaultAxialRotationTicks) || defaultAxialRotationTicks <= 0)
                defaultAxialRotationTicks = 2400.0;
            if (bodies == null) bodies = new LinkedHashMap<>();
            bodies.values().removeIf(value -> value == null);
            bodies.values().forEach(BodyOverride::normalize);
        }

        public BodyOverride body(String systemId, String bodyId) {
            if (bodies == null) bodies = new LinkedHashMap<>();
            return bodies.computeIfAbsent(systemId + "/" + bodyId, ignored -> new BodyOverride());
        }

        public BodyOverride findBody(String systemId, String bodyId) {
            if (bodies == null) return null;
            return bodies.get(systemId + "/" + bodyId);
        }
    }

    public static final class BodyOverride {
        /** Zero means use datapack data or automatic defaults. */
        public String parentId = "";
        public boolean useOrbitOverrides;
        public boolean useAxialTiltOverride;
        public double orbitPeriodTicks;
        public double orbitRadius;
        public double phaseDegrees;
        public double inclinationDegrees;
        public double eccentricity;
        public double axialRotationPeriodTicks;
        public double axialTiltDegrees;
        public boolean orbitEnabled = true;
        public boolean rotationEnabled = true;

        void normalize() {
            if (parentId == null) parentId = "";
            parentId = parentId.trim();
            orbitPeriodTicks = finiteNonNegative(orbitPeriodTicks);
            orbitRadius = finiteNonNegative(orbitRadius);
            phaseDegrees = finite(phaseDegrees);
            inclinationDegrees = finite(inclinationDegrees);
            eccentricity = clamp(finite(eccentricity), 0.0, 0.95);
            axialRotationPeriodTicks = finiteNonNegative(axialRotationPeriodTicks);
            axialTiltDegrees = finite(axialTiltDegrees);
        }
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0.0; }
    private static double finiteNonNegative(double value) { return Double.isFinite(value) && value > 0 ? value : 0.0; }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
