package dev.worldforge.tests;

import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.EventLog;
import dev.worldforge.mod.api.WorldForgeApi;

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

        if (failures > 0) throw new AssertionError(failures + " domain mutation revision checks failed");
        System.out.println("DOMAIN_MUTATION_REVISIONS_STATUS=PASS");
    }
}
