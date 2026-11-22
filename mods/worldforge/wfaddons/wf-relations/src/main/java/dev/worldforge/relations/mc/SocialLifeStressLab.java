package dev.worldforge.relations.mc;

import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.FamilyPlanning;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private static volatile Run active;

    private static final class Run {
        final MinecraftServer server;
        final CharacterRegistry registry;
        final int target;
        final int daysTarget;
        final int initialSize;
        final int startDay;
        final int startYear;
        final long startedNanos = System.nanoTime();
        final long[] dayNanos;
        int created;
        int daysCompleted;
        long interactions;
        int conceptions;
        int births;
        long maxDayNanos;
        String status = "RUNNING";
        String failure = "";

        Run(MinecraftServer server, CharacterRegistry registry, int target, int days) {
            this.server = server;
            this.registry = registry;
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

    public static String status() {
        Run run = active;
        if (run == null) return "NOT_STARTED";
        return "status=" + run.status + " created=" + run.created + "/" + run.target
                + " days=" + run.daysCompleted + "/" + run.daysTarget
                + " interactions=" + run.interactions + " bonds=" + npcBondCount(RelationsMc.REL)
                + " failure=" + run.failure;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Run run = active;
        if (run == null || run.server != event.getServer() || !"RUNNING".equals(run.status)) return;
        try {
            if (run.created < run.target) {
                createBatch(run);
                if (run.created == run.target) {
                    RelationsMod.LOGGER.warn("WF_STRESS contour=03_social population_created={} registrySize={} groups={}",
                            run.created, run.registry.size(), groupCount(run.target));
                }
                return;
            }
            // One accelerated simulation day per 20 server ticks. The same production
            // NpcSocialSimulation.advance entry point and its real indices/cursors are used.
            if (run.server.getTickCount() % 20 != 0) return;
            long started = System.nanoTime();
            int day = run.startDay + run.daysCompleted;
            int year = run.startYear + Math.floorDiv(day, 365);
            int processed = NpcSocialSimulation.advance(RelationsMc.REL, day, year);
            long elapsed = System.nanoTime() - started;
            run.dayNanos[run.daysCompleted] = elapsed;
            run.maxDayNanos = Math.max(run.maxDayNanos, elapsed);
            run.interactions += processed;
            run.daysCompleted++;
            if (run.daysCompleted % 30 == 0 || run.daysCompleted == run.daysTarget) {
                RelationsMod.LOGGER.warn("WF_STRESS contour=03_social progress day={}/{} interactions={} bonds={} dayMs={}",
                        run.daysCompleted, run.daysTarget, run.interactions,
                        npcBondCount(RelationsMc.REL), elapsed / 1_000_000.0);
            }
            if (run.daysCompleted >= run.daysTarget) finish(run);
        } catch (Throwable failure) {
            run.status = "FAILED";
            run.failure = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
            RelationsMod.LOGGER.error("WF_STRESS contour=03_social failed", failure);
            writeReport(run);
        }
    }

    private static void createBatch(Run run) {
        int end = Math.min(run.target, run.created + MAX_DAILY_CREATION);
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            while (run.created < end) {
                int ordinal = run.created;
                int group = ordinal / GROUP_SIZE;
                int year = run.startYear + Math.floorDiv(run.startDay, 365);
                Character person = run.registry.create("Social Stress Citizen " + ordinal,
                        "social-stress-house-" + group, "social-stress-culture", (ordinal & 1) == 0 ? "f" : "m",
                        year - 25);
                String polity = "social-stress-polity-" + group;
                String band = "social-stress-band-" + group;
                person.props.put("emergence.polity_id", polity);
                person.props.put("emergence.band_id", band);
                person.props.put("identity.language", "common");
                person.props.put("identity.faith", "stress-faith");
                person.props.put("social.class", (ordinal % 4 == 0) ? "noble" : "commoner");
                run.created++;
            }
        }
    }

    private static void finish(Run run) {
        processFamilyLife(run);
        long bonds = npcBondCount(RelationsMc.REL);
        long reciprocal = reciprocalNpcBonds(RelationsMc.REL);
        long married = marriedNpcBonds(RelationsMc.REL);
        boolean passed = run.created == run.target && run.registry.size() >= run.initialSize + run.target
                && run.daysCompleted == run.daysTarget && run.interactions > 0
                && bonds > 0 && reciprocal == bonds && married > 0
                && run.conceptions == run.births;
        run.status = passed ? "PASS" : "FAILED";
        if (!passed) run.failure = "social_and_family_lifecycle_invariants_not_satisfied";
        RelationsMc.save(run.server);
        boolean saveLoadVerified = false;
        try {
            Path saved = run.server.getWorldPath(LevelResource.ROOT).resolve("wf_relations.json");
            if (Files.isRegularFile(saved)) {
                Relations restored = new Relations();
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

    /** Resolve autonomous marriages into real pregnancies and canonical births. */
    private static void processFamilyLife(Run run) {
        int baseDay = run.startDay + run.daysCompleted;
        int baseYear = run.startYear + Math.floorDiv(baseDay, 365);
        long seed = 0x4D4151414E5F3033L;
        for (Character mother : run.registry.all()) {
            if (!mother.alive || !"f".equalsIgnoreCase(mother.gender)
                    || mother.spouse == null || mother.spouse.isBlank()) continue;
            Character father = run.registry.get(mother.spouse);
            if (father == null || !"m".equalsIgnoreCase(father.gender)
                    || !mother.id.equals(father.spouse)) continue;
            mother.props.put("family.fertility", 250);
            mother.props.put("family.conception_modifier", 250);
            father.props.put("family.wants_children", true);
            mother.props.put("family.wants_children", true);
            boolean conceived = false;
            int conceptionYear = baseYear;
            long conceptionDay = baseDay;
            for (int attempt = 0; attempt < 5 && !conceived; attempt++) {
                conceptionYear = baseYear + attempt;
                conceptionDay = baseDay + attempt * 365L;
                conceived = FamilyPlanning.tryConceive(mother, father, conceptionYear,
                        conceptionDay, seed + mother.globalId.hashCode());
            }
            if (!conceived) continue;
            run.conceptions++;
            long dueDay = conceptionDay + 270L;
            if (!father.id.equals(FamilyPlanning.dueFatherId(mother, dueDay)))
                throw new IllegalStateException("Pregnancy due-father identity mismatch for " + mother.globalId);
            Character child = run.registry.addChild(father, mother,
                    "Social Stress Child " + run.births, (run.births & 1) == 0 ? "f" : "m",
                    conceptionYear + 1);
            FamilyPlanning.complete(mother, father, conceptionYear + 1, child.id);
            if (!mother.children.contains(child.id) || !father.children.contains(child.id)
                    || !father.id.equals(child.father) || !mother.id.equals(child.mother))
                throw new IllegalStateException("Canonical family links failed for " + child.globalId);
            run.births++;
        }
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
            long bonds = npcBondCount(RelationsMc.REL);
            long reciprocal = reciprocalNpcBonds(RelationsMc.REL);
            long married = marriedNpcBonds(RelationsMc.REL);
            String json = String.format(Locale.ROOT,
                    "{\"contour\":\"03_families_social\",\"status\":\"%s\",\"failure\":\"%s\","
                    + "\"target\":%d,\"created\":%d,\"initial_registry_size\":%d,\"final_registry_size\":%d,"
                    + "\"groups\":%d,\"group_size\":%d,\"days_target\":%d,\"days_completed\":%d,"
                    + "\"autonomous_interactions\":%d,\"npc_directed_bonds\":%d,\"reciprocal_bonds\":%d,"
                    + "\"married_directed_bonds\":%d,\"day_ms_p50\":%.3f,\"day_ms_p95\":%.3f,"
                    + "\"day_ms_p99\":%.3f,\"day_ms_max\":%.3f,\"heap_used_bytes\":%d,"
                    + "\"heap_committed_bytes\":%d,\"process_cpu_nanos\":%d,\"elapsed_seconds\":%.3f}\n",
                    run.status, run.failure, run.target, run.created, run.initialSize, run.registry.size(),
                    groupCount(run.target), GROUP_SIZE, run.daysTarget, run.daysCompleted, run.interactions,
                    run.conceptions, run.births, bonds, reciprocal, married, percentile(samples, .50) / 1_000_000.0,
                    percentile(samples, .95) / 1_000_000.0, percentile(samples, .99) / 1_000_000.0,
                    run.maxDayNanos / 1_000_000.0, heap, runtime.totalMemory(), cpu,
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

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0L;
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
