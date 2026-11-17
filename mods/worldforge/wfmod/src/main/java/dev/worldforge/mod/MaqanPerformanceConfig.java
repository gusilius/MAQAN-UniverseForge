package dev.worldforge.mod;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Shared performance controls for WorldForge addons.
 *
 * Values are deliberately conservative for low-memory clients/servers. Addons
 * must read these values at runtime instead of keeping independent hard-coded
 * simulation radii and materialization budgets.
 */
public final class MaqanPerformanceConfig {
    private MaqanPerformanceConfig() {}

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue WORKER_SPAWN_RADIUS;
    public static final ForgeConfigSpec.IntValue WORKER_DESPAWN_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_WORKER_SPAWNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue SETTLEMENT_SIMULATION_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue LIFE_AI_INTERVAL_STEPS;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_WORKER_BODIES_PER_COLONY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_OFFSCREEN_SIMULATION;
    public static final ForgeConfigSpec.BooleanValue PAUSE_UNLOADED_COLONIES;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("simulation_lod");
        WORKER_SPAWN_RADIUS = BUILDER.comment("Distance in blocks at which worker bodies may materialize near a player.")
                .defineInRange("workerSpawnRadius", 48, 16, 256);
        WORKER_DESPAWN_RADIUS = BUILDER.comment("Distance in blocks beyond which worker bodies may dematerialize. Must exceed spawn radius.")
                .defineInRange("workerDespawnRadius", 72, 24, 384);
        MAX_WORKER_SPAWNS_PER_TICK = BUILDER.comment("Maximum worker entities materialized per server tick across all settlements.")
                .defineInRange("maxWorkerSpawnsPerTick", 2, 0, 32);
        MAX_ACTIVE_WORKER_BODIES_PER_COLONY = BUILDER.comment("Maximum simultaneously materialized worker bodies per colony. Zero means no worker bodies.")
                .defineInRange("maxActiveWorkerBodiesPerColony", 24, 0, 512);
        SETTLEMENT_SIMULATION_INTERVAL_TICKS = BUILDER.comment("Interval between settlement simulation steps. Higher values reduce CPU use; world-time progress remains elapsed-time based.")
                .defineInRange("settlementSimulationIntervalTicks", 20, 5, 200);
        LIFE_AI_INTERVAL_STEPS = BUILDER.comment("Run resident life-AI every N settlement steps; urgent/player/military tasks must bypass this cadence.")
                .defineInRange("lifeAiIntervalSteps", 1, 1, 20);
        ENABLE_OFFSCREEN_SIMULATION = BUILDER.comment("Keep off-screen settlements and work orders progressing through abstract simulation.")
                .define("enableOffscreenSimulation", true);
        PAUSE_UNLOADED_COLONIES = BUILDER.comment("If true, pause physical construction requiring block access while its dimension/chunks are unavailable; strategic work state remains saved.")
                .define("pauseUnloadedColonies", true);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    public static int workerSpawnRadius() { return WORKER_SPAWN_RADIUS.get(); }
    public static int workerDespawnRadius() { return Math.max(WORKER_SPAWN_RADIUS.get() + 8, WORKER_DESPAWN_RADIUS.get()); }
    public static int maxWorkerSpawnsPerTick() { return MAX_WORKER_SPAWNS_PER_TICK.get(); }
    public static int maxActiveWorkerBodiesPerColony() { return MAX_ACTIVE_WORKER_BODIES_PER_COLONY.get(); }
    public static int settlementSimulationIntervalTicks() { return SETTLEMENT_SIMULATION_INTERVAL_TICKS.get(); }
    public static int lifeAiIntervalSteps() { return LIFE_AI_INTERVAL_STEPS.get(); }
    public static boolean offscreenSimulationEnabled() { return ENABLE_OFFSCREEN_SIMULATION.get(); }
    public static boolean pauseUnloadedColonies() { return PAUSE_UNLOADED_COLONIES.get(); }
}
