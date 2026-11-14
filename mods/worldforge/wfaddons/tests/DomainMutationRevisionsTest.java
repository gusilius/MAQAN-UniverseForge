package dev.worldforge.tests;

import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.EventLog;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;

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

        if (failures > 0) throw new AssertionError(failures + " domain mutation revision checks failed");
        System.out.println("DOMAIN_MUTATION_REVISIONS_STATUS=PASS");
    }
}
