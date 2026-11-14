package dev.worldforge.tests;

import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.EventLog;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.people.CharacterStats;

public final class DomainMutationRevisionsTest {
    private static int failures;
    private static void check(boolean value, String label) {
        System.out.println((value ? "ok   " : "FAIL ") + label);
        if (!value) failures++;
    }

    public static void main(String[] args) {
        DomainMutationRevisions revisions = new DomainMutationRevisions();
        var initial = revisions.snapshot().orElseThrow();
        try (var ignored = revisions.begin(DomainMutationRevisions.Domain.CHARACTERS)) {
            check(revisions.snapshot().isEmpty(), "snapshot refuses to cross an in-progress canonical write");
        }
        check(revisions.isCurrent(initial), "aborted write scope does not advance committed revisions");

        try (var mutation = revisions.begin(DomainMutationRevisions.Domain.CHARACTERS)) {
            mutation.commit();
        }
        var afterCharacter = revisions.snapshot().orElseThrow();
        check(afterCharacter.revision(DomainMutationRevisions.Domain.CHARACTERS) == 1,
                "successful mutation advances exactly its own domain lane");
        check(!revisions.isCurrent(initial), "committed domain mutation invalidates the prior snapshot");

        try (var outer = revisions.begin(DomainMutationRevisions.Domain.ORIGINS);
             var inner = revisions.begin(DomainMutationRevisions.Domain.SETTLEMENTS)) {
            outer.commit();
            check(revisions.snapshot().isEmpty(), "snapshot remains unavailable while any writer is active");
            inner.commit();
        }
        var multi = revisions.snapshot().orElseThrow();
        check(multi.revision(DomainMutationRevisions.Domain.ORIGINS) == 1
                        && multi.revision(DomainMutationRevisions.Domain.SETTLEMENTS) == 1,
                "multiple canonical domains are represented independently in one revision vector");

        WorldForgeApi.resetForTests();
        var eventBefore = WorldForgeApi.revisions().snapshot().orElseThrow();
        WorldForgeApi.postEvent("people", "revision fixture", java.util.List.of(), 10L);
        var eventAfter = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(eventAfter.revision(DomainMutationRevisions.Domain.EVENTS_ACTIONS)
                        == eventBefore.revision(DomainMutationRevisions.Domain.EVENTS_ACTIONS) + 1,
                "canonical EventLog publication advances the shared EVENTS_ACTIONS lane exactly once");

        CharacterRegistry people = new CharacterRegistry();
        var peopleBefore = WorldForgeApi.revisions().snapshot().orElseThrow();
        Character founder = people.create("Founder", "House A", "human", "m", 1);
        var born = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(born.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == peopleBefore.revision(DomainMutationRevisions.Domain.CHARACTERS) + 1,
                "canonical Character creation advances the shared CHARACTERS lane");
        people.appoint(founder.id, "steward", "capital", 2);
        var appointed = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(appointed.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        > born.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "canonical post assignment advances the shared CHARACTERS lane");
        check(people.bindEntity(founder.id, "npc", "entity-1"),
                "canonical Character entity binding succeeds");
        var bound = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(bound.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        > appointed.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "entity binding advances the shared CHARACTERS lane");
        people.unbindEntity(founder.id, "npc", "entity-1");
        var unbound = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(unbound.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        > bound.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "entity unbinding advances the shared CHARACTERS lane");

        people.appoint(founder.id, "steward", "capital", 3);
        var afterNoOpAppointment = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterNoOpAppointment.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == unbound.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "idempotent appointment does not invalidate an unchanged Character snapshot");
        check(people.bindEntity(founder.id, "npc", "entity-2"),
                "replacement entity binding succeeds");
        var replacementBinding = WorldForgeApi.revisions().snapshot().orElseThrow();
        people.bindEntity(founder.id, "npc", "entity-2");
        var repeatedBinding = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(repeatedBinding.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == replacementBinding.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "idempotent entity binding does not advance the Character lane");
        people.unbindEntity(founder.id, "npc", "not-the-bound-entity");
        var ignoredUnbind = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(ignoredUnbind.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == repeatedBinding.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "ignored entity unbind does not invalidate the Character snapshot");
        people.unbindEntity(founder.id, "NPC", "entity-2");
        var normalizedUnbind = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(normalizedUnbind.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == repeatedBinding.revision(DomainMutationRevisions.Domain.CHARACTERS) + 1,
                "entity unbinding normalizes body-kind casing and records the real mutation");

        people.migrate(founder.id, "settlement-a", 1);
        var afterMigration = WorldForgeApi.revisions().snapshot().orElseThrow();
        people.migrate(founder.id, "settlement-a", 2);
        var repeatedMigration = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(repeatedMigration.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterMigration.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "same-destination migration is a no-op and does not invalidate snapshots");

        people.refreshLifeStages(1);
        var afterAgeRefresh = WorldForgeApi.revisions().snapshot().orElseThrow();
        people.refreshLifeStages(1);
        var repeatedAgeRefresh = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(repeatedAgeRefresh.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterAgeRefresh.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "repeating the same calendar refresh does not create redundant Character revisions");

        PopulationSimulation.setNeed(founder, PopulationSimulation.Need.HUNGER, 40);
        var afterNeedWrite = WorldForgeApi.revisions().snapshot().orElseThrow();
        PopulationSimulation.setNeed(founder, PopulationSimulation.Need.HUNGER, 40);
        var afterRepeatedNeed = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterRepeatedNeed.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterNeedWrite.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "idempotent population need assignment does not advance Character revision");

        long beforePopulationBatch = afterRepeatedNeed.revision(DomainMutationRevisions.Domain.CHARACTERS);
        boolean batchHidesSnapshot;
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            batchHidesSnapshot = WorldForgeApi.revisions().snapshot().isEmpty();
            PopulationSimulation.setNeed(founder, PopulationSimulation.Need.HUNGER, 55);
            PopulationSimulation.setCommand(founder, "defend_capital");
            PopulationSimulation.setTrainingTarget(founder, "tactics");
        }
        check(batchHidesSnapshot,
                "active Character mutation batch prevents concurrent snapshot capture before writes commit");
        var afterPopulationBatch = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterPopulationBatch.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == beforePopulationBatch + 1,
                "multiple nested population writes coalesce into one canonical Character revision");

        long beforeDailyAdvance = afterPopulationBatch.revision(DomainMutationRevisions.Domain.CHARACTERS);
        PopulationSimulation.advance(people, 2, 4);
        var afterDailyAdvance = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterDailyAdvance.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == beforeDailyAdvance + 1,
                "one daily population batch commits one Character revision for the complete world tick");

        long beforeProfession = afterDailyAdvance.revision(DomainMutationRevisions.Domain.CHARACTERS);
        PopulationSimulation.assignProfession(founder, "farmer", 4);
        var afterProfession = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterProfession.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == beforeProfession + 1
                        && PopulationSimulation.profession(founder).equals("farmer"),
                "canonical population profession write advances the shared Character lane");

        long beforeHire = afterProfession.revision(DomainMutationRevisions.Domain.CHARACTERS);
        people.hire(founder.id, "settlement:capital", "farmer", 4);
        var afterHire = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterHire.revision(DomainMutationRevisions.Domain.CHARACTERS) == beforeHire + 1,
                "compound hire operation commits one Character revision across profession, duty, employer and post");
        people.fire(founder.id, "settlement:capital", 5);
        var afterFire = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterFire.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterHire.revision(DomainMutationRevisions.Domain.CHARACTERS) + 1,
                "compound fire operation commits one Character revision across employer, duty and post release");

        long beforeRace = afterFire.revision(DomainMutationRevisions.Domain.CHARACTERS);
        CharacterDevelopment.setRace(founder, "elf_forest");
        var afterRace = WorldForgeApi.revisions().snapshot().orElseThrow();
        CharacterDevelopment.setRace(founder, "elf_forest");
        var afterRepeatedRace = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterRace.revision(DomainMutationRevisions.Domain.CHARACTERS) == beforeRace + 1
                        && afterRepeatedRace.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterRace.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "Character development setter records real changes and ignores idempotent writes");

        long beforeExperience = afterRepeatedRace.revision(DomainMutationRevisions.Domain.CHARACTERS);
        CharacterDevelopment.recordExperience(founder, CharacterDevelopment.Experience.BATTLE_VICTORY,
                "revision-test:victory", "deterministic revision fixture", 100, 2);
        var afterExperience = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterExperience.revision(DomainMutationRevisions.Domain.CHARACTERS) == beforeExperience + 1,
                "compound psychological experience commits one Character revision despite nested writes");

        long beforeTrait = afterExperience.revision(DomainMutationRevisions.Domain.CHARACTERS);
        check(CharacterStats.addTrait(founder, "brave"), "canonical Character trait addition succeeds");
        var afterTrait = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(!CharacterStats.addTrait(founder, "brave")
                        && afterTrait.revision(DomainMutationRevisions.Domain.CHARACTERS) == beforeTrait + 1,
                "CharacterStats trait mutation advances one revision and duplicate trait is a no-op");

        long beforePsychologyInit = afterTrait.revision(DomainMutationRevisions.Domain.CHARACTERS);
        CharacterDevelopment.ensurePsychology(founder);
        var afterPsychologyInit = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterPsychologyInit.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == beforePsychologyInit + 1
                        && CharacterDevelopment.stress(founder) == 0,
                "lazy psychology initialization persists stable facets and advances one Character revision");
        CharacterDevelopment.ensurePsychology(founder);
        var afterRepeatedPsychologyInit = WorldForgeApi.revisions().snapshot().orElseThrow();
        check(afterRepeatedPsychologyInit.revision(DomainMutationRevisions.Domain.CHARACTERS)
                        == afterPsychologyInit.revision(DomainMutationRevisions.Domain.CHARACTERS),
                "repeated psychology initialization is idempotent and does not churn Character revisions");

        if (failures > 0) throw new AssertionError(failures + " domain mutation revision checks failed");
        System.out.println("DOMAIN_MUTATION_REVISIONS_STATUS=PASS");
    }
}
