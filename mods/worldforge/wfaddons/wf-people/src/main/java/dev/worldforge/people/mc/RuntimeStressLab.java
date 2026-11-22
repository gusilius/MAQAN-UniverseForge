package dev.worldforge.people.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.mod.api.WorldForgeApi;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Opt-in in-game load driver. It creates canonical Characters and invokes the same
 * domain mutations and durable write path used by the running game. The normal server
 * tick remains responsible for population simulation; this class never calls the
 * simulation batch directly.
 *
 * Enable only on a disposable stress server with -Dwf.stress.enabled=true.
 */
public final class RuntimeStressLab {
    private static final int[] MILESTONES = {100, 1_000, 10_000, 100_000, 500_000, 1_000_000};
    private static final int MAX_SAMPLES = 60_000;
    private static volatile Run activeRun;
    private static volatile long tickStartNanos;

    private static final class Run {
        final MinecraftServer server;
        final CharacterRegistry registry;
        final int initialPopulation;
        final int target;
        final int batchPerTick;
        final long startedNanos = System.nanoTime();
        final long[] tickNanos = new long[MAX_SAMPLES];
        int sampleCount;
        int sampleCursor;
        int created;
        int nextMilestone;
        long creationFinishedDay = -1L;
        long verificationTargetDay = -1L;
        int verificationIndex;
        int simulationProcessed;
        int simulationPending;
        long maxTickNanos;
        String status = "RUNNING";
        String failure = "";

        Run(MinecraftServer server, CharacterRegistry registry, int target, int batchPerTick) {
            this.server = server;
            this.registry = registry;
            this.initialPopulation = registry.size();
            this.target = target;
            this.batchPerTick = batchPerTick;
        }
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("wfstress")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("population")
                        .then(Commands.literal("start")
                                .then(Commands.argument("target", IntegerArgumentType.integer(1, 1_000_000))
                                        .then(Commands.argument("batch", IntegerArgumentType.integer(1, 128))
                                                .executes(context -> start(context.getSource().getServer(),
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "target"),
                                                        IntegerArgumentType.getInteger(context, "batch"))))))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("stop").executes(context -> stop(context.getSource())))));
    }

    private static int start(MinecraftServer server, net.minecraft.commands.CommandSourceStack source,
                             int target, int batchPerTick) {
        if (!Boolean.getBoolean("wf.stress.enabled")) {
            source.sendFailure(Component.literal("StressLab выключен. Запустите отдельный сервер с -Dwf.stress.enabled=true."));
            return 0;
        }
        if (activeRun != null && "RUNNING".equals(activeRun.status)) {
            source.sendFailure(Component.literal("Population stress уже выполняется."));
            return 0;
        }
        if (!PeopleMod.identityPartitionsReady()) {
            source.sendFailure(Component.literal("Character identity partitions ещё не готовы; нагрузка не запущена."));
            return 0;
        }
        CharacterRegistry registry = PeopleMod.worldRegistry();
        if (registry == null) {
            source.sendFailure(Component.literal("World Character registry недоступен."));
            return 0;
        }
        activeRun = new Run(server, registry, target, batchPerTick);
        PeopleMod.LOGGER.warn("WF_STRESS contour=02_population status=STARTED target={} batchPerTick={} initialPopulation={} save={}",
                target, batchPerTick, registry.size(),
                server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
        source.sendSuccess(() -> Component.literal("Population stress запущен: +" + target
                + " канонических персонажей, batch/tick=" + batchPerTick + "."), true);
        return 1;
    }

    private static int status(net.minecraft.commands.CommandSourceStack source) {
        Run run = activeRun;
        if (run == null) {
            source.sendSuccess(() -> Component.literal("Population stress не запускался в этом процессе."), false);
            return 1;
        }
        String line = "Population stress: " + run.status + ", created=" + run.created + "/" + run.target
                + ", registry=" + run.registry.size() + ", failure=" + run.failure;
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int stop(net.minecraft.commands.CommandSourceStack source) {
        Run run = activeRun;
        if (run == null || "REPORT_WRITTEN".equals(run.status) || "REPORT_FAILED".equals(run.status)) {
            source.sendFailure(Component.literal("Активного Population stress нет."));
            return 0;
        }
        if (!"PASS".equals(run.status) && !"DEGRADED".equals(run.status)) run.status = "STOPPED";
        writeReport(run);
        source.sendSuccess(() -> Component.literal("Population stress остановлен на " + run.created
                + "/" + run.target + ". Отчёт записан в worldforge/stresslab."), true);
        return 1;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            return;
        }
        Run run = activeRun;
        if (run == null || run.server != event.getServer()) return;

        if ("RUNNING".equals(run.status)) {
            try {
                int count = Math.min(run.batchPerTick, run.target - run.created);
                for (int i = 0; i < count; i++) createOne(run);
                while (run.nextMilestone < MILESTONES.length
                        && run.created >= MILESTONES[run.nextMilestone]) {
                    int milestone = MILESTONES[run.nextMilestone++];
                    PeopleMod.LOGGER.info("WF_STRESS contour=02_population milestone={} created={} registrySize={}",
                            milestone, run.created, run.registry.size());
                }
                if (run.created >= run.target) {
                    run.creationFinishedDay = WorldForgeApi.clock().dayNumber();
                    run.status = "WAITING_FOR_REAL_SIMULATION";
                    PeopleMod.LOGGER.warn("WF_STRESS contour=02_population creation_complete={} creation_day={}; waiting for the normal world calendar and tick scheduler",
                            run.created, run.creationFinishedDay);
                }
            } catch (Exception failure) {
                run.status = "FAILED";
                run.failure = failure.getClass().getName();
                PeopleMod.LOGGER.error("WF_STRESS contour=02_population failed after {} canonical creations",
                        run.created, failure);
            }
        } else if ("WAITING_FOR_REAL_SIMULATION".equals(run.status)) {
            if (WorldForgeApi.clock().dayNumber() > run.creationFinishedDay) {
                run.verificationTargetDay = WorldForgeApi.clock().dayNumber();
                run.status = "VERIFYING_SIMULATION_RECEIPTS";
                PeopleMod.LOGGER.warn("WF_STRESS contour=02_population simulation_verification_started targetWorldDay={}",
                        run.verificationTargetDay);
            }
        } else if ("VERIFYING_SIMULATION_RECEIPTS".equals(run.status)) {
            verifySimulationReceipts(run, 512);
        }

        long elapsed = Math.max(0L, System.nanoTime() - tickStartNanos);
        run.tickNanos[run.sampleCursor] = elapsed;
        run.sampleCursor = (run.sampleCursor + 1) % MAX_SAMPLES;
        if (run.sampleCount < MAX_SAMPLES) run.sampleCount++;
        run.maxTickNanos = Math.max(run.maxTickNanos, elapsed);
        if ("PASS".equals(run.status) || "DEGRADED".equals(run.status)
                || "FAILED".equals(run.status) || "STOPPED".equals(run.status)) writeReport(run);
    }

    private static void verifySimulationReceipts(Run run, int budget) {
        int end = Math.min(run.target, run.verificationIndex + budget);
        for (int i = run.verificationIndex; i < end; i++) {
            Character character = run.registry.all().get(run.initialPopulation + i);
            Object raw = character.props.get("population.last_simulated_world_day");
            long receipt;
            try {
                receipt = raw instanceof String value ? Long.parseLong(value) : -1L;
            } catch (NumberFormatException malformed) {
                receipt = -1L;
            }
            if (receipt >= run.verificationTargetDay) run.simulationProcessed++;
            else run.simulationPending++;
        }
        run.verificationIndex = end;
        if (run.verificationIndex >= run.target) {
            run.status = run.simulationPending == 0 ? "PASS" : "DEGRADED";
            if (run.simulationPending > 0) run.failure = "population_simulation_backlog";
            PeopleMod.LOGGER.warn("WF_STRESS contour=02_population simulation_receipts targetDay={} processed={} pending={} result={}",
                    run.verificationTargetDay, run.simulationProcessed, run.simulationPending, run.status);
        }
    }

    private static void createOne(Run run) throws Exception {
        int ordinal = run.created + 1;
        Character character = run.registry.create("Stress Citizen " + ordinal, "stress-house",
                "stress-culture", (ordinal & 1) == 0 ? "f" : "m", PeopleMod.YEAR - 25);
        PopulationSimulation.addDuty(character, PopulationSimulation.Duty.WORKER);
        PopulationSimulation.setCommand(character, "stress_work");
        for (PopulationSimulation.Need need : PopulationSimulation.Need.values()) {
            int deficit = Math.floorMod(ordinal * (need.ordinal() + 17), PopulationSimulation.MAX_NEED + 1);
            PopulationSimulation.setNeed(character, need, deficit);
        }
        if (!PeopleMod.persistCharacter(character, run.server))
            throw new IllegalStateException("Production Character persistence rejected " + character.globalId);
        if (run.registry.getByGlobalId(character.globalId) != character)
            throw new IllegalStateException("Canonical global identity lookup failed for " + character.globalId);
        run.created++;
    }

    private static void writeReport(Run run) {
        if ("REPORT_WRITTEN".equals(run.status)) return;
        String finalStatus = run.status;
        try {
            Path directory = run.server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("stresslab");
            Files.createDirectories(directory);
            Path report = directory.resolve("contour-02-population.json");
            long[] samples = new long[run.sampleCount];
            if (run.sampleCount < MAX_SAMPLES) {
                System.arraycopy(run.tickNanos, 0, samples, 0, run.sampleCount);
            } else {
                for (int i = 0; i < MAX_SAMPLES; i++)
                    samples[i] = run.tickNanos[(run.sampleCursor + i) % MAX_SAMPLES];
            }
            Arrays.sort(samples);
            Runtime runtime = Runtime.getRuntime();
            long usedHeap = runtime.totalMemory() - runtime.freeMemory();
            long cpuNanos = processCpuNanos();
            long elapsedNanos = Math.max(1L, System.nanoTime() - run.startedNanos);
            String json = String.format(Locale.ROOT,
                    "{\"contour\":\"02_population\",\"status\":\"%s\",\"failure\":\"%s\","
                    + "\"target\":%d,\"created\":%d,\"initial_registry_size\":%d,\"final_registry_size\":%d,"
                    + "\"durable_character_writes\":%d,\"batch_per_tick\":%d,\"elapsed_seconds\":%.3f,"
                    + "\"tick_ms_p50\":%.3f,\"tick_ms_p95\":%.3f,\"tick_ms_p99\":%.3f,\"tick_ms_max\":%.3f,"
                    + "\"heap_used_bytes\":%d,\"heap_committed_bytes\":%d,\"process_cpu_nanos\":%d,"
                    + "\"population_processed_world_day\":%d,\"creation_finished_day\":%d,\"simulation_target_day\":%d,\"simulation_processed\":%d,\"simulation_pending\":%d,\"sample_count\":%d}\n",
                    finalStatus, run.failure, run.target, run.created, run.initialPopulation, run.registry.size(),
                    run.created, run.batchPerTick, elapsedNanos / 1_000_000_000.0,
                    percentile(samples, 0.50) / 1_000_000.0, percentile(samples, 0.95) / 1_000_000.0,
                    percentile(samples, 0.99) / 1_000_000.0, run.maxTickNanos / 1_000_000.0,
                    usedHeap, runtime.totalMemory(), cpuNanos, PeopleMod.populationProcessedDay(), run.creationFinishedDay,
                    run.verificationTargetDay, run.simulationProcessed, run.simulationPending, run.sampleCount);
            Files.writeString(report, json, StandardCharsets.UTF_8);
            run.status = "REPORT_WRITTEN";
            PeopleMod.LOGGER.warn("WF_STRESS contour=02_population status={} created={} registrySize={} report={}",
                    finalStatus, run.created, run.registry.size(), report.toAbsolutePath());
        } catch (Exception reportFailure) {
            run.status = "REPORT_FAILED";
            run.failure = reportFailure.getClass().getName();
            PeopleMod.LOGGER.error("WF_STRESS contour=02_population could not write its evidence report", reportFailure);
        }
    }

    private static long percentile(long[] sorted, double quantile) {
        if (sorted.length == 0) return 0L;
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended)
            return extended.getProcessCpuTime();
        return -1L;
    }
}
