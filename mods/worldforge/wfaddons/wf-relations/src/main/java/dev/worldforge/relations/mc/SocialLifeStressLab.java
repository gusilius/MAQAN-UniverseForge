package dev.worldforge.relations.mc;

import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.FamilyPlanning;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.relations.NpcSocialSimulation;
import dev.worldforge.relations.Relations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Comparator;
import java.util.Locale;

/**
 * Isolated contour-03 driver. It creates canonical residents in many independent
 * social bands and advances the production autonomous social simulation. It does
 * not replace, merge with, or change the contour-02 population stress driver.
 */
public final class SocialLifeStressLab {
    private static final int GROUP_SIZE = 200;
    private static final int MAX_DAILY_CREATION = 128;
    private static final int MAX_DAYS = 3650;
    private static final int LIFE_SCAN_BATCH = 512;
    private static final int BIRTH_BATCH = 64;
    private static volatile Run active;

    private static final class Pregnancy {
        final long dueDay;
        final String motherId;
        final String fatherId;
        Pregnancy(long dueDay, String motherId, String fatherId) {
            this.dueDay = dueDay; this.motherId = motherId; this.fatherId = fatherId;
        }
    }

    private static final class Run {
        final MinecraftServer server;
        final CharacterRegistry registry;
        final List<Character> population;
        final Relations relations = new Relations();
        final int target;
        final int daysTarget;
        final int initialSize;
        final int startDay;
        final int startYear;
        final long startedNanos = System.nanoTime();
        final long cpuStartedNanos = processCpuNanos();
        final long[] dayNanos;
        final PriorityQueue<Pregnancy> pregnancies = new PriorityQueue<>(Comparator.comparingLong(p -> p.dueDay));
        int created;
        int daysCompleted;
        int conceptionIndex;
        int deathIndex;
        int conceptions;
        int births;
        int deaths;
        int marriedCouples;
        long interactions;
        long maxDayNanos;
        long lifecycleWorkNanos;
        long peakHeapBytes;
        boolean annualLifecyclePending;
        String phase = "CREATING";
        String status = "RUNNING";
        String failure = "";

        Run(MinecraftServer server, CharacterRegistry registry, int target, int days) {
            this.server = server;
            this.registry = registry;
            this.population = registry.all();
            this.relations.hooks = RelationsMc.REL.hooks;
            this.target = target;
            this.daysTarget = days;
            this.initialSize = registry.size();
            this.startDay = Math.max(0, RelationsMc.dayNumber()) + 1_000_000;
            this.startYear = WorldForgeApi.clock().startYear;
            this.dayNanos = new long[days];
        }
    }

    public static synchronized boolean start(MinecraftServer server, int target, int days) {
        if (!Boolean.getBoolean("wf.social.stress.enabled")) throw new IllegalStateException("Start a disposable server with -Dwf.social.stress.enabled=true");
        if (active != null && "RUNNING".equals(active.status)) return false;
        if (!PeopleMod.identityPartitionsReady()) throw new IllegalStateException("Character identity partitions are not ready");
        CharacterRegistry registry = PeopleMod.worldRegistry();
        if (registry == null) throw new IllegalStateException("Canonical world Character registry is unavailable");
        if (target < 2 || target > 1_000_000) throw new IllegalArgumentException("target must be 2..1000000");
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("days must be 1..3650");
        active = new Run(server, registry, target, days);
        RelationsMod.LOGGER.warn("WF_STRESS contour=03_social status=STARTED target={} groupSize={} days={} initialPopulation={}",
                target, GROUP_SIZE, days, registry.size());
        return true;
    }

    /** The opt-in driver owns accelerated social time while active. */
    public static boolean isRunning() {
        Run run = active;
        return run != null && "RUNNING".equals(run.status);
    }

    public static String status() {
        Run run = active;
        if (run == null) return "NOT_STARTED";
        return "status=" + run.status + " phase=" + run.phase + " created=" + run.created + "/" + run.target
                + " days=" + run.daysCompleted + "/" + run.daysTarget
                + " interactions=" + run.interactions + " births=" + run.births + " deaths=" + run.deaths
                + " pregnancies=" + run.pregnancies.size() + " bonds=" + npcBondCount(run.relations)
                + " failure=" + run.failure;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Run run = active;
        if (run == null || run.server != event.getServer() || !"RUNNING".equals(run.status)) return;
        try {
            sampleRuntime(run);
            switch (run.phase) {
                case "CREATING" -> {
                    createBatch(run);
                    if (run.created == run.target) {
                        run.phase = "SIMULATING";
                        RelationsMod.LOGGER.warn("WF_STRESS contour=03_social population_created={} registrySize={} groups={} marriedCouples={}",
                                run.created, run.registry.size(), groupCount(run.target), run.marriedCouples);
                    }
                }
                case "PROCESSING_BIRTHS" -> processBirthsBatch(run);
                case "PROCESSING_CONCEPTIONS" -> processConceptionsBatch(run);
                case "PROCESSING_DEATHS" -> processDeathsBatch(run);
                case "SIMULATING" -> {
                    if (run.daysCompleted >= run.daysTarget && run.phase.equals("SIMULATING")) finish(run);
                    else if (run.server.getTickCount() % 20 == 0) advanceSocialDay(run);
                }
                default -> throw new IllegalStateException("Unknown social stress phase " + run.phase);
            }
        } catch (Throwable failure) {
            run.status = "FAILED";
            run.failure = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
            RelationsMod.LOGGER.error("WF_STRESS contour=03_social failed phase={}", run.phase, failure);
            writeReport(run);
        }
    }

    private static void createBatch(Run run) {
        int end = Math.min(run.target, run.created + MAX_DAILY_CREATION);
        int year = run.startYear + Math.floorDiv(run.startDay, 365);
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            while (run.created < end) {
                int ordinal = run.created;
                int pair = ordinal / 2;
                int group = ordinal / GROUP_SIZE;
                int age = (pair % 20 == 19) ? 82 : 25;
                Character person = run.registry.create("Social Stress Citizen " + ordinal,
                        "social-stress-house-" + group, "social-stress-culture",
                        (ordinal & 1) == 0 ? "f" : "m", year - age);
                String polity = "social-stress-polity-" + group;
                String band = "social-stress-band-" + group;
                person.props.put("emergence.polity_id", polity);
                person.props.put("emergence.band_id", band);
                person.props.put("identity.language", "common");
                person.props.put("identity.faith", "stress-faith");
                person.props.put("social.class", (ordinal % 4 == 0) ? "noble" : "commoner");
                if ((ordinal & 1) == 1) {
                    Character wife = run.population.get(run.initialSize + ordinal - 1);
                    run.registry.marry(wife.id, person.id, year);
                    wife.props.put("family.fertility", 250);
                    wife.props.put("family.conception_modifier", 250);
                    wife.props.put("family.wants_children", true);
                    person.props.put("family.wants_children", true);
                    addReciprocalBond(wife, person, true);
                    run.marriedCouples++;
                }
                run.created++;
            }
        }
    }

    private static void advanceSocialDay(Run run) {
        long started = System.nanoTime();
        int day = run.startDay + run.daysCompleted;
        int year = run.startYear + Math.floorDiv(day, 365);
        run.relations.tick(day);
        int processed = NpcSocialSimulation.advance(run.relations, day, year);
        long elapsed = System.nanoTime() - started;
        run.dayNanos[run.daysCompleted] = elapsed;
        run.maxDayNanos = Math.max(run.maxDayNanos, elapsed);
        run.interactions += processed;
        run.daysCompleted++;
        run.annualLifecyclePending = run.daysCompleted % 365 == 0;
        run.conceptionIndex = 0;
        run.deathIndex = 0;
        if (!run.pregnancies.isEmpty() && run.pregnancies.peek().dueDay <= day) {
            run.phase = "PROCESSING_BIRTHS";
        } else if (run.annualLifecyclePending) {
            run.phase = "PROCESSING_CONCEPTIONS";
        }
        if (run.daysCompleted % 30 == 0 || run.daysCompleted == run.daysTarget)
            RelationsMod.LOGGER.warn("WF_STRESS contour=03_social progress day={}/{} interactions={} bonds={} dayMs={} heapBytes={} births={} deaths={}",
                    run.daysCompleted, run.daysTarget, run.interactions, npcBondCount(run.relations),
                    elapsed / 1_000_000.0, usedHeapBytes(), run.births, run.deaths);
    }

    private static void processBirthsBatch(Run run) {
        long started = System.nanoTime();
        int processed = 0;
        long day = run.startDay + run.daysCompleted - 1L;
        int year = run.startYear + Math.floorDiv((int) Math.min(Integer.MAX_VALUE, day), 365);
        while (processed < BIRTH_BATCH && !run.pregnancies.isEmpty()
                && run.pregnancies.peek().dueDay <= day) {
            Pregnancy pregnancy = run.pregnancies.remove();
            Character mother = run.registry.get(pregnancy.motherId);
            Character father = run.registry.get(pregnancy.fatherId);
            if (mother == null || father == null || !mother.alive || !father.alive
                    || !father.id.equals(FamilyPlanning.dueFatherId(mother, day))) {
                if (mother != null) FamilyPlanning.cancel(mother, father, "stress_lifecycle_parent_unavailable", year);
                processed++;
                continue;
            }
            Character child = run.registry.addChild(father, mother, "Social Stress Child " + run.births,
                    (run.births & 1) == 0 ? "f" : "m", year);
            child.props.put("population.last_simulated_world_day", Long.toString(day));
            addReciprocalBond(father, child, false);
            addReciprocalBond(mother, child, false);
            FamilyPlanning.complete(mother, father, year, child.id);
            if (!mother.children.contains(child.id) || !father.children.contains(child.id)
                    || !father.id.equals(child.father) || !mother.id.equals(child.mother))
                throw new IllegalStateException("Canonical family links failed for " + child.globalId);
            run.births++;
            processed++;
        }
        run.lifecycleWorkNanos += System.nanoTime() - started;
        if (run.pregnancies.isEmpty() || run.pregnancies.peek().dueDay > day) {
            run.phase = run.annualLifecyclePending ? "PROCESSING_CONCEPTIONS" : "SIMULATING";
            if (!run.annualLifecyclePending) recordLifecycleCost(run);
        }
    }

    private static void processConceptionsBatch(Run run) {
        long started = System.nanoTime();
        int end = Math.min(run.target, run.conceptionIndex + LIFE_SCAN_BATCH);
        long day = run.startDay + run.daysCompleted - 1L;
        int year = run.startYear + Math.floorDiv((int) Math.min(Integer.MAX_VALUE, day), 365);
        // Do not create pregnancies whose due date lies beyond this test horizon.
        for (int i = run.conceptionIndex; i < end; i++) {
            Character mother = run.population.get(run.initialSize + i);
            if (!mother.alive || !"f".equalsIgnoreCase(mother.gender) || mother.spouse == null || mother.spouse.isBlank()) continue;
            Character father = run.registry.get(mother.spouse);
            if (father == null || !father.alive || !mother.id.equals(father.spouse)) continue;
            if (run.daysTarget - run.daysCompleted < 270) continue;
            if (FamilyPlanning.tryConceive(mother, father, year, day,
                    0x4D4151414E5F3033L + mother.globalId.hashCode())) {
                long due = FamilyPlanning.dueDay(mother);
                if (due < day || due > run.startDay + run.daysTarget)
                    throw new IllegalStateException("Pregnancy due day outside the simulated life horizon");
                run.pregnancies.add(new Pregnancy(due, mother.id, father.id));
                run.conceptions++;
            }
        }
        run.lifecycleWorkNanos += System.nanoTime() - started;
        run.conceptionIndex = end;
        if (run.conceptionIndex >= run.target) {
            run.deathIndex = 0;
            run.phase = "PROCESSING_DEATHS";
        }
    }

    private static void processDeathsBatch(Run run) {
        long started = System.nanoTime();
        int end = Math.min(run.target, run.deathIndex + LIFE_SCAN_BATCH);
        int year = run.startYear + Math.floorDiv(run.startDay + run.daysCompleted, 365);
        for (int i = run.deathIndex; i < end; i++) {
            Character person = run.population.get(run.initialSize + i);
            if (person.alive && year - person.birthYear >= 80) {
                PopulationSimulation.die(run.registry, person.id, year, "stress_lifecycle_old_age");
                run.deaths++;
            }
        }
        run.lifecycleWorkNanos += System.nanoTime() - started;
        run.deathIndex = end;
        if (run.deathIndex >= run.target) {
            run.annualLifecyclePending = false;
            run.phase = "SIMULATING";
            recordLifecycleCost(run);
        }
    }

    private static void recordLifecycleCost(Run run) {
        if (run.daysCompleted > 0) {
            int index = run.daysCompleted - 1;
            run.dayNanos[index] += run.lifecycleWorkNanos;
            run.maxDayNanos = Math.max(run.maxDayNanos, run.dayNanos[index]);
        }
        run.lifecycleWorkNanos = 0L;
    }

    private static void addReciprocalBond(Character first, Character second, boolean married) {
        Relations.Bond forward = run.relations.bond("npc:" + first.globalId, "npc:" + second.globalId);
        Relations.Bond reverse = run.relations.bond("npc:" + second.globalId, "npc:" + first.globalId);
        forward.met = reverse.met = 1;
        forward.affinity = reverse.affinity = married ? 75 : 35;
        forward.love = reverse.love = married ? 60 : 20;
        forward.married = reverse.married = married;
    }

    private static void finish(Run run) {
        long bonds = npcBondCount(run.relations);
        long reciprocal = reciprocalNpcBonds(run.relations);
        long married = marriedNpcBonds(run.relations);
        boolean passed = run.created == run.target && run.registry.size() >= run.initialSize + run.target
                && run.daysCompleted == run.daysTarget && run.interactions > 0
                && run.marriedCouples > 0 && bonds > 0 && reciprocal == bonds && married > 0
                && run.conceptions == run.births && run.pregnancies.isEmpty();
        run.status = passed ? "PASS" : "FAILED";
        if (!passed) run.failure = "social_and_family_lifecycle_invariants_not_satisfied";
        boolean saveLoadVerified = false;
        try {
            Path saved = run.server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("stresslab")
                    .resolve("contour-03-relations-checkpoint.json");
            Files.createDirectories(saved.getParent());
            Files.writeString(saved, run.relations.toJson(), StandardCharsets.UTF_8);
            if (Files.isRegularFile(saved)) {
                Relations restored = new Relations();
                restored.hooks = run.relations.hooks;
                restored.fromJson(Files.readString(saved, StandardCharsets.UTF_8));
                saveLoadVerified = npcBondCount(restored) == bonds;
            }
        } catch (Exception saveFailure) {
            RelationsMod.LOGGER.error("WF_STRESS contour=03_social save/load verification failed", saveFailure);
        }
        if (!saveLoadVerified) {
            run.status = "FAILED";
            run.failure = "relations_save_load_verification_failed";
        }
        writeReport(run);
    }

    private static void sampleRuntime(Run run) {
        long heap = usedHeapBytes();
        run.peakHeapBytes = Math.max(run.peakHeapBytes, heap);
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long npcBondCount(Relations relations) {
        return relations.keys().stream().filter(key -> key.startsWith("npc:")).count();
    }

    private static long reciprocalNpcBonds(Relations relations) {
        long valid = 0;
        for (String key : relations.keys()) {
            if (!key.startsWith("npc:")) continue;
            int split = key.indexOf('|');
            if (split <= 0 || split == key.length() - 1) continue;
            String first = key.substring(0, split), second = key.substring(split + 1);
            if (first.equals(second)) continue;
            Relations.Bond bond = relations.peek(first, second);
            Relations.Bond reverse = relations.peek(second, first);
            if (bond != null && reverse != null && bond.met > 0 && reverse.met > 0
                    && bond.affinity == reverse.affinity && bond.love == reverse.love) valid++;
        }
        return valid;
    }

    private static long marriedNpcBonds(Relations relations) {
        return relations.keys().stream().filter(key -> key.startsWith("npc:"))
                .map(key -> {
                    int split = key.indexOf('|');
                    return split < 0 ? null : relations.peek(key.substring(0, split), key.substring(split + 1));
                }).filter(bond -> bond != null && bond.married).count();
    }

    private static int groupCount(int target) {
        return (target + GROUP_SIZE - 1) / GROUP_SIZE;
    }

    private static void writeReport(Run run) {
        try {
            Path directory = run.server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("stresslab");
            Files.createDirectories(directory);
            long[] samples = Arrays.copyOf(run.dayNanos, run.daysCompleted);
            Arrays.sort(samples);
            Runtime runtime = Runtime.getRuntime();
            long heap = runtime.totalMemory() - runtime.freeMemory();
            long cpu = -1L;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean)
                cpu = bean.getProcessCpuTime();
            long bonds = npcBondCount(run.relations);
            long reciprocal = reciprocalNpcBonds(run.relations);
            long married = marriedNpcBonds(run.relations);
            String json = String.format(Locale.ROOT,
                    "{\"contour\":\"03_families_social\",\"status\":\"%s\",\"failure\":\"%s\","
                    + "\"target\":%d,\"created\":%d,\"initial_registry_size\":%d,\"final_registry_size\":%d,"
                    + "\"groups\":%d,\"group_size\":%d,\"days_target\":%d,\"days_completed\":%d,"
                    + "\"autonomous_interactions\":%d,\"married_couples\":%d,\"conceptions\":%d,\"births\":%d,\"deaths\":%d,"
                    + "\"pending_pregnancies\":%d,\"npc_directed_bonds\":%d,\"reciprocal_bonds\":%d,"
                    + "\"married_directed_bonds\":%d,\"day_ms_p50\":%.3f,\"day_ms_p95\":%.3f,"
                    + "\"day_ms_p99\":%.3f,\"day_ms_max\":%.3f,\"heap_used_bytes\":%d,"
                    + "\"peak_heap_used_bytes\":%d,\"heap_committed_bytes\":%d,\"process_cpu_nanos\":%d,\"elapsed_seconds\":%.3f}\n",
                    run.status, run.failure, run.target, run.created, run.initialSize, run.registry.size(),
                    groupCount(run.target), GROUP_SIZE, run.daysTarget, run.daysCompleted, run.interactions,
                    run.marriedCouples, run.conceptions, run.births, run.deaths, run.pregnancies.size(),
                    bonds, reciprocal, married, percentile(samples, .50) / 1_000_000.0,
                    percentile(samples, .95) / 1_000_000.0, percentile(samples, .99) / 1_000_000.0,
                    run.maxDayNanos / 1_000_000.0, heap, run.peakHeapBytes, runtime.totalMemory(),
                    Math.max(0L, processCpuNanos() - run.cpuStartedNanos),
                    (System.nanoTime() - run.startedNanos) / 1_000_000_000.0);
            Path report = directory.resolve("contour-03-social.json");
            Files.writeString(report, json, StandardCharsets.UTF_8);
            RelationsMod.LOGGER.warn("WF_STRESS contour=03_social status={} created={} days={} interactions={} bonds={} reciprocal={} married={} report={}",
                    run.status, run.created, run.daysCompleted, run.interactions, bonds, reciprocal, married, report.toAbsolutePath());
        } catch (Exception failure) {
            run.status = "REPORT_FAILED";
            run.failure = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
            RelationsMod.LOGGER.error("WF_STRESS contour=03_social report write failed", failure);
        }
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended)
            return extended.getProcessCpuTime();
        return -1L;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0L;
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
