package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.FamilyPlanning;
import dev.worldforge.relations.Relations;

/**
 * Combined contour 02+03 load gate.
 *
 * Uses the canonical CharacterRegistry, FamilyPlanning, and wf-relations bond
 * persistence. It deliberately creates real family and directed social records;
 * it is not a synthetic counter-only population benchmark.
 */
public final class PopulationLifeStressTest {
    private static int failures;

    private static void check(boolean condition, String message) {
        System.out.println((condition ? "ok   " : "FAIL ") + message);
        if (!condition) failures++;
    }

    private static int couplesToCreate() {
        String configured = System.getenv("WF_LIFE_STRESS_COUPLES");
        if (configured == null || configured.isBlank()) return 42_000;
        try {
            int value = Integer.parseInt(configured);
            if (value < 1 || value > 100_000) throw new NumberFormatException("out of range");
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("WF_LIFE_STRESS_COUPLES must be between 1 and 100000", invalid);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public static void main(String[] args) {
        final int couples = couplesToCreate();
        final int year = 300;
        final long conceptionDay = 100_000L;
        final long seed = 0x4D4151414E5F3032L;
        final long started = System.nanoTime();

        CharacterRegistry registry = new CharacterRegistry();
        Relations relations = new Relations();
        int conceptions = 0;
        int births = 0;
        String sampleFatherId = "";
        String sampleMotherId = "";
        String sampleChildId = "";

        System.out.println("POPULATION_LIFE_STRESS_START couples=" + couples
                + " expected_base_population=" + (couples * 2L));

        for (int i = 0; i < couples; i++) {
            Character father = registry.create("Stress Father " + i, "stress-house-" + i,
                    "stress-culture", "m", year - 27);
            Character mother = registry.create("Stress Mother " + i, "stress-house-" + i,
                    "stress-culture", "f", year - 25);
            registry.marry(father.id, mother.id, year);

            Relations.Bond fatherToMother = relations.bond("npc:" + father.globalId, "npc:" + mother.globalId);
            Relations.Bond motherToFather = relations.bond("npc:" + mother.globalId, "npc:" + father.globalId);
            fatherToMother.affinity = motherToFather.affinity = 35;
            fatherToMother.love = motherToFather.love = 40;
            fatherToMother.married = motherToFather.married = true;
            fatherToMother.met = motherToFather.met = 1;

            if (FamilyPlanning.tryConceive(mother, father, year, conceptionDay, seed + i)) {
                conceptions++;
                checkDueOnlyAtBirth(mother, father, conceptionDay);
                Character child = registry.addChild(father, mother, "Stress Child " + i,
                        (i & 1) == 0 ? "f" : "m", year + 1);
                FamilyPlanning.complete(mother, father, year + 1, child.id);
                births++;

                link(relations, father, child);
                link(relations, mother, child);
                if (sampleChildId.isEmpty()) {
                    sampleFatherId = father.id;
                    sampleMotherId = mother.id;
                    sampleChildId = child.id;
                }
            }

            if ((i + 1) % 10_000 == 0 || i + 1 == couples) {
                System.out.println("POPULATION_LIFE_STRESS_PROGRESS couples=" + (i + 1) + "/" + couples
                        + " population=" + registry.size() + " conceptions=" + conceptions
                        + " births=" + births + " directed_bonds=" + relations.keys().size()
                        + " heap_used_bytes=" + usedHeap());
            }
        }

        registry.refreshLifeStages(year + 1);
        long simulationNanos = System.nanoTime() - started;
        check(registry.size() == couples * 2 + births,
                "canonical registry contains every parent and born child: " + registry.size());
        check(conceptions == births, "every due conception resolves into exactly one canonical birth");
        check(relations.keys().size() == couples * 2L + births * 4L,
                "directed spouse and parent-child social bonds are present: " + relations.keys().size());

        if (!sampleChildId.isEmpty()) {
            Character father = registry.get(sampleFatherId);
            Character mother = registry.get(sampleMotherId);
            Character child = registry.get(sampleChildId);
            check(father != null && mother != null && child != null,
                    "sample family resolves through canonical Character IDs");
            check(child != null && sampleFatherId.equals(child.father)
                            && sampleMotherId.equals(child.mother),
                    "child parentage remains canonical and reciprocal");
            check(father != null && father.children.contains(sampleChildId)
                            && mother != null && mother.children.contains(sampleChildId),
                    "both parents retain the child in their family records");
            check(mother != null && !FamilyPlanning.isPregnant(mother),
                    "completed birth clears persisted pregnancy state");
            check(father != null && mother != null
                            && father.id.equals(mother.spouse) && mother.id.equals(father.spouse),
                    "marriage remains reciprocal after family growth");
        }

        // Exercise the same JSON persistence surfaces used by the two domain systems.
        String registryJson = registry.toJson();
        CharacterRegistry restoredRegistry = new CharacterRegistry();
        restoredRegistry.fromJson(registryJson, "population-life-stress-world");
        String relationsJson = relations.toJson();
        Relations restoredRelations = new Relations();
        restoredRelations.fromJson(relationsJson);

        check(restoredRegistry.size() == registry.size(),
                "canonical population count survives full registry save/load");
        check(restoredRegistry.get(sampleChildId) != null
                        && restoredRegistry.get(sampleChildId).father.equals(sampleFatherId)
                        && restoredRegistry.get(sampleChildId).mother.equals(sampleMotherId),
                "family lineage survives registry save/load");
        check(restoredRelations.keys().size() == relations.keys().size(),
                "directed social graph survives wf-relations save/load");
        if (!sampleFatherId.isEmpty()) {
            Character restoredFather = restoredRegistry.get(sampleFatherId);
            Character restoredMother = restoredRegistry.get(sampleMotherId);
            Character restoredChild = restoredRegistry.get(sampleChildId);
            Relations.Bond spouse = restoredRelations.peek("npc:" + restoredFather.globalId,
                    "npc:" + restoredMother.globalId);
            Relations.Bond parentChild = restoredRelations.peek("npc:" + restoredFather.globalId,
                    "npc:" + restoredChild.globalId);
            check(spouse != null && spouse.married && spouse.affinity == 35,
                    "spouse relationship state survives social save/load");
            check(parentChild != null && parentChild.met == 1,
                    "parent-child social bond survives social save/load");
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("POPULATION_LIFE_STRESS_RESULT status=" + (failures == 0 ? "PASS" : "FAIL")
                + " couples=" + couples + " population=" + registry.size()
                + " conceptions=" + conceptions + " births=" + births
                + " directed_bonds=" + relations.keys().size()
                + " simulation_ms=" + (simulationNanos / 1_000_000L)
                + " elapsed_ms=" + elapsedMillis + " heap_used_bytes=" + usedHeap());
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    private static void checkDueOnlyAtBirth(Character mother, Character father, long startDay) {
        check(FamilyPlanning.dueFatherId(mother, startDay + 269L).isEmpty(),
                "pregnancy does not resolve before full gestation");
        check(FamilyPlanning.dueFatherId(mother, startDay + 270L).equals(father.id),
                "pregnancy resolves to its canonical father at the due day");
    }

    private static void link(Relations relations, Character first, Character second) {
        Relations.Bond forward = relations.bond("npc:" + first.globalId, "npc:" + second.globalId);
        Relations.Bond reverse = relations.bond("npc:" + second.globalId, "npc:" + first.globalId);
        forward.affinity = reverse.affinity = 20;
        forward.met = reverse.met = 1;
    }
}
