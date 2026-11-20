package dev.worldforge.mod;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Single authoritative performance configuration for WorldForge runtime.
 *
 * CUSTOM is the migration-safe default: existing maq an-performance.toml values
 * remain effective. Selecting a profile supplies defaults only for settings
 * whose explicit override switch is disabled. Override values are never rewritten
 * when the profile changes.
 */
public final class MaqanPerformanceConfig {
    private MaqanPerformanceConfig() {}

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> PROFILE;

    public static final ForgeConfigSpec.IntValue WORKER_SPAWN_RADIUS;
    public static final ForgeConfigSpec.IntValue WORKER_DESPAWN_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_WORKER_SPAWNS_PER_TICK;
    public static final ForgeConfigSpec.IntValue SETTLEMENT_SIMULATION_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue LIFE_AI_INTERVAL_STEPS;
    public static final ForgeConfigSpec.IntValue LIFE_AI_RESIDENTS_PER_PASS;
    public static final ForgeConfigSpec.IntValue LIFE_AI_URGENT_RESIDENTS_PER_PASS;
    public static final ForgeConfigSpec.IntValue FAMILY_EVENTS_RESIDENTS_PER_TICK;
    public static final ForgeConfigSpec.IntValue TRADE_WORK_ITEMS_PER_TICK;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_WORKER_BODIES_PER_COLONY;

    public static final ForgeConfigSpec.IntValue ARMY_MAX_MATERIALIZED_BODIES;
    public static final ForgeConfigSpec.IntValue ARMY_MAX_MATERIALIZATIONS_PER_PASS;
    public static final ForgeConfigSpec.IntValue ARMY_TACTICAL_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue ARMY_STRATEGIC_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue RUST_BATCH_SIZE;

    public static final ForgeConfigSpec.BooleanValue OVERRIDE_WORKER_SPAWN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_WORKER_DESPAWN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_MAX_WORKER_SPAWNS;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_MAX_WORKER_BODIES;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_SETTLEMENT_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_LIFE_AI_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_ARMY_MAX_BODIES;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_ARMY_MATERIALIZATION_BUDGET;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_ARMY_TACTICAL_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_ARMY_STRATEGIC_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue OVERRIDE_RUST_BATCH_SIZE;

    public static final ForgeConfigSpec.BooleanValue PERFORMANCE_METRICS_ENABLED;
    public static final ForgeConfigSpec.IntValue PERFORMANCE_METRICS_SAMPLE_INTERVAL_TICKS;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("profiles");
        PROFILE = BUILDER.comment("ECONOMY, BALANCED and HIGH provide presets. CUSTOM preserves explicitly configured values.")
                .defineInList("profile", "CUSTOM", List.of("CUSTOM", "ECONOMY", "BALANCED", "HIGH"));
        BUILDER.pop();

        BUILDER.push("simulation_lod");
        WORKER_SPAWN_RADIUS = BUILDER.comment("Manual worker materialization radius in blocks.")
                .defineInRange("workerSpawnRadius", 48, 16, 256);
        WORKER_DESPAWN_RADIUS = BUILDER.comment("Manual worker dematerialization radius in blocks.")
                .defineInRange("workerDespawnRadius", 72, 24, 384);
        MAX_WORKER_SPAWNS_PER_TICK = BUILDER.comment("Maximum worker bodies materialized in one body-management pass.")
                .defineInRange("maxWorkerSpawnsPerPass", 2, 0, 32);
        MAX_ACTIVE_WORKER_BODIES_PER_COLONY = BUILDER.comment("Maximum materialized worker bodies per colony.")
                .defineInRange("maxActiveWorkerBodiesPerColony", 24, 0, 512);
        SETTLEMENT_SIMULATION_INTERVAL_TICKS = BUILDER.comment("Settlement simulation interval; elapsed world time must be preserved.")
                .defineInRange("settlementSimulationIntervalTicks", 20, 5, 200);
        LIFE_AI_INTERVAL_STEPS = BUILDER.comment("Run non-urgent resident life AI every N settlement steps.")
                .defineInRange("lifeAiIntervalSteps", 1, 1, 20);
        LIFE_AI_RESIDENTS_PER_PASS = BUILDER.comment("Maximum residents receiving ordinary life-AI processing per active settlement pass. Does not limit population.")
                .defineInRange("lifeAiResidentsPerPass", 96, 1, 4096);
        LIFE_AI_URGENT_RESIDENTS_PER_PASS = BUILDER.comment("Maximum residents receiving urgent life-AI processing per raid/emergency pass.")
                .defineInRange("lifeAiUrgentResidentsPerPass", 384, 1, 16384);
        FAMILY_EVENTS_RESIDENTS_PER_TICK = BUILDER.comment("Maximum canonical residents advanced by the annual family-event scheduler per server tick. Does not limit population or event depth.")
                .defineInRange("familyEventsResidentsPerTick", 128, 1, 8192);
        TRADE_WORK_ITEMS_PER_TICK = BUILDER.comment("Maximum realm/caravan work items advanced by the economic simulation per server tick. Does not limit world size or trade volume.")
                .defineInRange("tradeWorkItemsPerTick", 64, 1, 4096);

        OVERRIDE_WORKER_SPAWN_RADIUS = BUILDER.define("overrideWorkerSpawnRadius", false);
        OVERRIDE_WORKER_DESPAWN_RADIUS = BUILDER.define("overrideWorkerDespawnRadius", false);
        OVERRIDE_MAX_WORKER_SPAWNS = BUILDER.define("overrideMaxWorkerSpawnsPerPass", false);
        OVERRIDE_MAX_WORKER_BODIES = BUILDER.define("overrideMaxActiveWorkerBodiesPerColony", false);
        OVERRIDE_SETTLEMENT_INTERVAL = BUILDER.define("overrideSettlementSimulationIntervalTicks", false);
        OVERRIDE_LIFE_AI_INTERVAL = BUILDER.define("overrideLifeAiIntervalSteps", false);
        BUILDER.pop();

        BUILDER.push("army_simulation");
        ARMY_MAX_MATERIALIZED_BODIES = BUILDER.comment("Maximum simultaneously materialized physical troop entities in the active simulation area. Does not limit strategic roster size.")
                .defineInRange("maxMaterializedBodies", 96, 0, 4096);
        ARMY_MAX_MATERIALIZATIONS_PER_PASS = BUILDER.comment("Maximum troop bodies materialized during one server pass.")
                .defineInRange("maxMaterializationsPerPass", 2, 0, 128);
        ARMY_TACTICAL_INTERVAL_TICKS = BUILDER.comment("Tactical decision cadence in ticks. Critical combat events must bypass this cadence.")
                .defineInRange("tacticalIntervalTicks", 10, 1, 100);
        ARMY_STRATEGIC_INTERVAL_TICKS = BUILDER.comment("Strategic aggregate army update cadence in ticks.")
                .defineInRange("strategicIntervalTicks", 40, 5, 1200);
        RUST_BATCH_SIZE = BUILDER.comment("Maximum immutable snapshots per Rust compute batch; not a limit on the number of soldiers in the strategic roster.")
                .defineInRange("rustBatchSize", 256, 16, 8192);

        OVERRIDE_ARMY_MAX_BODIES = BUILDER.define("overrideMaxMaterializedBodies", false);
        OVERRIDE_ARMY_MATERIALIZATION_BUDGET = BUILDER.define("overrideMaxMaterializationsPerPass", false);
        OVERRIDE_ARMY_TACTICAL_INTERVAL = BUILDER.define("overrideTacticalIntervalTicks", false);
        OVERRIDE_ARMY_STRATEGIC_INTERVAL = BUILDER.define("overrideStrategicIntervalTicks", false);
        OVERRIDE_RUST_BATCH_SIZE = BUILDER.define("overrideRustBatchSize", false);
        BUILDER.pop();

        BUILDER.push("diagnostics");
        PERFORMANCE_METRICS_ENABLED = BUILDER.comment("Collect low-overhead rolling performance counters.")
                .define("enabled", true);
        PERFORMANCE_METRICS_SAMPLE_INTERVAL_TICKS = BUILDER.comment("Sampling interval for performance diagnostics.")
                .defineInRange("sampleIntervalTicks", 20, 1, 1200);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private static int preset(int custom, boolean override, int economy, int balanced, int high) {
        if ("CUSTOM".equals(PROFILE.get()) || override) return custom;
        return switch (PROFILE.get()) {
            case "ECONOMY" -> economy;
            case "HIGH" -> high;
            default -> balanced;
        };
    }

    public static String profile() { return PROFILE.get(); }

    public static int workerSpawnRadius() {
        return preset(WORKER_SPAWN_RADIUS.get(), OVERRIDE_WORKER_SPAWN_RADIUS.get(), 32, 48, 64);
    }
    public static int workerDespawnRadius() {
        int spawn = workerSpawnRadius();
        return Math.max(spawn + 8, preset(WORKER_DESPAWN_RADIUS.get(), OVERRIDE_WORKER_DESPAWN_RADIUS.get(), 56, 72, 96));
    }
    public static int maxWorkerSpawnsPerPass() {
        return preset(MAX_WORKER_SPAWNS_PER_TICK.get(), OVERRIDE_MAX_WORKER_SPAWNS.get(), 1, 2, 4);
    }
    public static int maxActiveWorkerBodiesPerColony() {
        return preset(MAX_ACTIVE_WORKER_BODIES_PER_COLONY.get(), OVERRIDE_MAX_WORKER_BODIES.get(), 12, 24, 48);
    }
    public static int settlementSimulationIntervalTicks() {
        return preset(SETTLEMENT_SIMULATION_INTERVAL_TICKS.get(), OVERRIDE_SETTLEMENT_INTERVAL.get(), 40, 20, 10);
    }
    public static int lifeAiIntervalSteps() {
        return preset(LIFE_AI_INTERVAL_STEPS.get(), OVERRIDE_LIFE_AI_INTERVAL.get(), 2, 1, 1);
    }
    public static int lifeAiResidentsPerPass() { return LIFE_AI_RESIDENTS_PER_PASS.get(); }
    public static int lifeAiUrgentResidentsPerPass() { return LIFE_AI_URGENT_RESIDENTS_PER_PASS.get(); }
    public static int familyEventsResidentsPerTick() { return FAMILY_EVENTS_RESIDENTS_PER_TICK.get(); }
    public static int tradeWorkItemsPerTick() { return TRADE_WORK_ITEMS_PER_TICK.get(); }
    public static int armyMaxMaterializedBodies() {
        return preset(ARMY_MAX_MATERIALIZED_BODIES.get(), OVERRIDE_ARMY_MAX_BODIES.get(), 48, 96, 256);
    }
    public static int armyMaxMaterializationsPerPass() {
        return preset(ARMY_MAX_MATERIALIZATIONS_PER_PASS.get(), OVERRIDE_ARMY_MATERIALIZATION_BUDGET.get(), 1, 2, 8);
    }
    public static int armyTacticalIntervalTicks() {
        return preset(ARMY_TACTICAL_INTERVAL_TICKS.get(), OVERRIDE_ARMY_TACTICAL_INTERVAL.get(), 20, 10, 5);
    }
    public static int armyStrategicIntervalTicks() {
        return preset(ARMY_STRATEGIC_INTERVAL_TICKS.get(), OVERRIDE_ARMY_STRATEGIC_INTERVAL.get(), 100, 40, 20);
    }
    public static int rustBatchSize() {
        return preset(RUST_BATCH_SIZE.get(), OVERRIDE_RUST_BATCH_SIZE.get(), 128, 256, 1024);
    }
    public static boolean performanceMetricsEnabled() { return PERFORMANCE_METRICS_ENABLED.get(); }
    public static int performanceMetricsSampleIntervalTicks() { return PERFORMANCE_METRICS_SAMPLE_INTERVAL_TICKS.get(); }
}
