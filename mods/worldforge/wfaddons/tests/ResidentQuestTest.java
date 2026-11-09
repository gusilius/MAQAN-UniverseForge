package dev.worldforge.tests;

import dev.worldforge.settle.ResidentQuest;
import java.util.Map;

public final class ResidentQuestTest {
    private static int failures;
    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.out.println("FAIL " + message);
        }
    }

    public static void main(String[] args) {
        ResidentQuest quest = new ResidentQuest("bread_supply_c1", "Принести 3 хлеба", 3, 12);
        check(quest.status() == ResidentQuest.Status.OFFERED, "new contract starts as offered");
        check(!quest.addProgress(1, 12), "offered contract cannot receive unaccepted progress");
        check(quest.accept(), "offered contract can be accepted");
        check(!quest.accept(), "contract cannot be accepted twice");
        check(!quest.addProgress(2, 12), "partial delivery does not complete contract");
        check(quest.progress() == 2 && quest.status() == ResidentQuest.Status.ACCEPTED,
                "partial delivery persists progress");
        check(quest.addProgress(4, 13), "delivery completes at required threshold");
        check(quest.progress() == 3 && quest.complete() && quest.completedYear() == 13,
                "completion clamps progress and records completion year");
        check(!quest.addProgress(1, 14), "completed contract rejects duplicate delivery");
        ResidentQuest restored = ResidentQuest.fromMap(quest.toMap());
        check(restored.complete() && restored.progress() == 3 && restored.completedYear() == 13,
                "contract survives save and restore");

        ResidentQuest abandoned = new ResidentQuest("other", "Другое поручение", 2, 12);
        check(abandoned.abandon() && abandoned.status() == ResidentQuest.Status.ABANDONED,
                "offered contract can be abandoned");
        check(!abandoned.accept(), "abandoned contract cannot be accepted");

        boolean badProgressRejected = false;
        try {
            ResidentQuest.fromMap(Map.of("id", "bad", "objective", "bad", "required", 2,
                    "created_year", 1, "progress", 3, "completed_year", 0, "status", "ACCEPTED"));
        } catch (IllegalArgumentException expected) { badProgressRejected = true; }
        check(badProgressRejected, "corrupt progress is rejected on restore");

        boolean badCompletionRejected = false;
        try {
            ResidentQuest.fromMap(Map.of("id", "bad", "objective", "bad", "required", 2,
                    "created_year", 1, "progress", 1, "completed_year", 2, "status", "COMPLETED"));
        } catch (IllegalArgumentException expected) { badCompletionRejected = true; }
        check(badCompletionRejected, "inconsistent completion state is rejected");

        System.out.println("ResidentQuestTest: " + (failures == 0 ? "OK" : failures + " failure(s)"));
        if (failures > 0) throw new AssertionError("ResidentQuestTest failed: " + failures);
    }
}
