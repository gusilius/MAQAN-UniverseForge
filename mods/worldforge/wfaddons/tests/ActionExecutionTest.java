package dev.worldforge.tests;

import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ActionExecutionTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (value) System.out.println("ok   " + message);
        else { failures++; System.out.println("FAIL " + message); }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("wf-action-test");
        Path file = dir.resolve("actions.json");
        ActionExecutionService service = new ActionExecutionService();
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                "request-1", "settlement.build", "player-1", "settlement-4", List.of("house"), 100);
        int[] executions = {0};
        ActionExecutionService.Task queued = service.enqueue(request, 100, file);
        check(queued.status() == ActionExecutionService.Status.QUEUED, "submission creates a durable QUEUED task");
        ActionExecutionService.Task completed = service.process("request-1", r -> null,
                (r, attempt) -> { executions[0]++; return ActionResult.ok("built"); },
                null, 101, file);
        check(completed.status() == ActionExecutionService.Status.SUCCEEDED && executions[0] == 1,
                "queued task executes once and reaches SUCCEEDED");
        check(completed.receiptId().equals("receipt:request-1") && service.receipts().size() == 1,
                "successful execution creates a durable receipt");
        ActionExecutionService.Task duplicate = service.execute(request, r -> null,
                (r, attempt) -> { executions[0]++; return ActionResult.ok("duplicate"); }, null, 102, file);
        check(duplicate.status() == ActionExecutionService.Status.SUCCEEDED && executions[0] == 1,
                "duplicate request id returns original result without repeating domain effects");
        boolean collisionRejected = false;
        try {
            service.enqueue(new ActionExecutionService.Request("request-1", "settlement.demolish",
                    "player-1", "settlement-4", List.of(), 100), 102, file);
        } catch (IllegalStateException expected) { collisionRejected = true; }
        check(collisionRejected, "reused request id with a different payload is rejected");

        ActionExecutionService.Task denied = service.execute(new ActionExecutionService.Request(
                "request-denied", "settlement.build", "intruder", "settlement-4", List.of("house"), 102),
                r -> "not owner", (r, attempt) -> { executions[0]++; return ActionResult.ok("bad"); },
                null, 102, file);
        check(denied.status() == ActionExecutionService.Status.FAILED && executions[0] == 1,
                "authorization denial is terminal and executor is never invoked");

        int[] plans = {0};
        ActionExecutionService.Task rejected = service.execute(new ActionExecutionService.Request(
                "request-rejected", "military.client_order", "player-1", "selection", List.of("invalid-order"), 102),
                r -> null, (r, attempt) -> ActionResult.fail("order rejected"), null, 103, file);
        check(rejected.status() == ActionExecutionService.Status.FAILED
                        && rejected.receiptId().equals("receipt:request-rejected")
                        && service.receipts().stream().anyMatch(receipt -> receipt.requestId().equals("request-rejected")
                        && receipt.outcome() == ActionExecutionService.Status.FAILED),
                "rejected domain action is recorded as FAILED with a failure receipt");

        ActionExecutionService.Task replanned = service.execute(new ActionExecutionService.Request(
                "request-replan", "settlement.build", "player-1", "settlement-4", List.of("bridge"), 103),
                r -> r.targetId().equals("blocked") ? "target unavailable" : null,
                (r, attempt) -> {
                    executions[0]++;
                    return r.targetId().equals("settlement-4") ? ActionResult.retry("path blocked") : ActionResult.ok("built elsewhere");
                },
                (r, failure, attempt) -> {
                    plans[0]++;
                    return new ActionExecutionService.Plan("settlement.build", "alternate-settlement",
                            List.of("bridge"), "alternate reachable settlement");
                }, 104, file);
        check(replanned.status() == ActionExecutionService.Status.SUCCEEDED && plans[0] == 1
                        && service.receipts().stream().anyMatch(r -> r.requestId().equals("request-replan")
                        && r.targetId().equals("alternate-settlement")),
                "retryable failure triggers one alternate plan and receipt records the actual target");

        int beforeDuplicateReplan = executions[0];
        ActionExecutionService.Task replanDuplicate = service.execute(new ActionExecutionService.Request(
                "request-replan", "settlement.build", "player-1", "settlement-4", List.of("bridge"), 103),
                r -> null, (r, attempt) -> { executions[0]++; return ActionResult.ok("must not run"); },
                null, 105, file);
        check(replanDuplicate.status() == ActionExecutionService.Status.SUCCEEDED
                        && executions[0] == beforeDuplicateReplan,
                "duplicate original request remains idempotent after execution was replanned");

        int[] protectedExecutions = {0};
        boolean invalidPlanRejected = false;
        try {
            new ActionExecutionService.Plan("settlement.build", "alternate", java.util.Collections.nCopies(65, "x"), "too many arguments");
        } catch (IllegalArgumentException expected) { invalidPlanRejected = true; }
        check(invalidPlanRejected, "replanner cannot create a plan exceeding the request argument limit");
        boolean invalidReasonRejected = false;
        try {
            new ActionExecutionService.Plan("settlement.build", "alternate", List.of("bridge"), "x".repeat(1025));
        } catch (IllegalArgumentException expected) { invalidReasonRejected = true; }
        check(invalidReasonRejected, "replanner reason is bounded before it can enter task execution");

        ActionExecutionService.Task replannedDenied = service.execute(new ActionExecutionService.Request(
                "request-replan-denied", "settlement.build", "player-1", "settlement-4", List.of("bridge"), 106),
                r -> r.targetId().equals("forbidden-alternative") ? "alternative is not authorized" : null,
                (r, attempt) -> { protectedExecutions[0]++; return ActionResult.retry("blocked"); },
                (r, failure, attempt) -> new ActionExecutionService.Plan(
                        "settlement.build", "forbidden-alternative", List.of("bridge"), "fallback"),
                107, file);
        check(replannedDenied.status() == ActionExecutionService.Status.FAILED && protectedExecutions[0] == 1,
                "authorization is rechecked after replanning before any alternate side effect");

        int[] partialEffects = {0};
        ActionExecutionService.Task partial = service.execute(new ActionExecutionService.Request(
                "request-partial", "genesis.confirm", "player-1", "player-1", List.of("human", "village"), 108),
                r -> null, (r, attempt) -> {
                    partialEffects[0]++;
                    throw new IllegalStateException("simulated failure after a partial domain mutation");
                }, null, 109, file);
        check(partial.status() == ActionExecutionService.Status.FAILED && partialEffects[0] == 1
                        && service.receipts().stream().anyMatch(r -> r.requestId().equals("request-partial")
                        && r.outcome() == ActionExecutionService.Status.FAILED),
                "exception during a domain mutation is recorded as FAILED with a receipt");
        ActionExecutionService.Task partialDuplicate = service.execute(new ActionExecutionService.Request(
                "request-partial", "genesis.confirm", "player-1", "player-1", List.of("human", "village"), 108),
                r -> null, (r, attempt) -> { partialEffects[0]++; return ActionResult.ok("must not replay"); },
                null, 110, file);
        check(partialDuplicate.status() == ActionExecutionService.Status.FAILED && partialEffects[0] == 1,
                "retrying the same Genesis operation ID never replays uncertain partial side effects");

        Path blockedParent = dir.resolve("blocked-parent");
        Files.writeString(blockedParent, "not a directory");
        Path blockedJournal = blockedParent.resolve("actions.json");
        ActionExecutionService blockedEnqueue = new ActionExecutionService();
        ActionExecutionService.Request blockedRequest = new ActionExecutionService.Request(
                "request-no-journal", "genesis.confirm", "player-2", "player-2", List.of("draft"), 111);
        boolean enqueueFailed = false;
        try { blockedEnqueue.enqueue(blockedRequest, 111, blockedJournal); }
        catch (IllegalStateException expected) { enqueueFailed = true; }
        int[] forbiddenReplay = {0};
        ActionExecutionService.Task enqueueTombstone = blockedEnqueue.execute(blockedRequest,
                r -> null, (r, attempt) -> { forbiddenReplay[0]++; return ActionResult.ok("must not execute"); },
                null, 112, blockedJournal);
        check(enqueueFailed && enqueueTombstone.status() == ActionExecutionService.Status.FAILED
                        && !enqueueTombstone.receiptId().isEmpty() && forbiddenReplay[0] == 0,
                "failed durable enqueue creates a terminal tombstone and cannot execute on same-process retry");

        Path runningBlockedFile = dir.resolve("running-blocked.json");
        ActionExecutionService runningBlocked = new ActionExecutionService();
        ActionExecutionService.Request runningBlockedRequest = new ActionExecutionService.Request(
                "request-running-no-journal", "settlement.build", "player-3", "settlement-5", List.of("hall"), 112);
        runningBlocked.enqueue(runningBlockedRequest, 112, runningBlockedFile);
        Files.delete(runningBlockedFile);
        Files.createDirectory(runningBlockedFile);
        int[] unstartedEffects = {0};
        ActionExecutionService.Task runningTombstone = runningBlocked.process(
                "request-running-no-journal", r -> null,
                (r, attempt) -> { unstartedEffects[0]++; return ActionResult.ok("must not run"); },
                null, 113, runningBlockedFile);
        check(runningTombstone.status() == ActionExecutionService.Status.FAILED
                        && !runningTombstone.receiptId().isEmpty()
                        && runningBlocked.receipts().stream().anyMatch(r -> r.requestId().equals("request-running-no-journal")
                        && r.outcome() == ActionExecutionService.Status.FAILED)
                        && unstartedEffects[0] == 0,
                "failed RUNNING checkpoint produces an in-memory FAILED receipt and never enters the executor");

        ActionExecutionService restored = new ActionExecutionService();
        restored.load(file);
        check(restored.task("request-1") != null && restored.task("request-1").status() == ActionExecutionService.Status.SUCCEEDED
                        && restored.receipts().size() == service.receipts().size(),
                "tasks and receipts survive durable save/load");

        ActionExecutionService cancelService = new ActionExecutionService();
        cancelService.enqueue(new ActionExecutionService.Request(
                "request-cancel", "settlement.build", "player-1", "settlement-4", List.of("house"), 105), 105, file);
        check(!cancelService.cancel("request-cancel", "intruder", false, 106, file),
                "another player cannot cancel someone else's task");
        check(cancelService.cancel("request-cancel", "player-1", false, 106, file)
                        && cancelService.task("request-cancel").status() == ActionExecutionService.Status.CANCELLED
                        && cancelService.receipts().size() == 1,
                "owner can cancel queued task and receives a terminal receipt");

        ActionExecutionService interrupted = new ActionExecutionService();
        interrupted.enqueue(new ActionExecutionService.Request(
                "request-interrupted", "settlement.build", "player-1", "settlement-4", List.of("house"), 110), 110, null);
        ActionExecutionService afterRestart = new ActionExecutionService();
        afterRestart.loadJson(interrupted.toJson());
        int[] replayed = {0};
        ActionExecutionService.Task recovered = afterRestart.execute(new ActionExecutionService.Request(
                "request-interrupted", "settlement.build", "player-1", "settlement-4", List.of("house"), 110),
                r -> null, (r, attempt) -> { replayed[0]++; return ActionResult.ok("duplicate"); },
                null, 111, null);
        check(recovered.status() == ActionExecutionService.Status.FAILED
                        && recovered.message().contains("server restart")
                        && afterRestart.receipts().size() == 1 && replayed[0] == 0,
                "interrupted queued task is reconciled to FAILED with receipt and is never replayed automatically");

        if (failures > 0) throw new AssertionError(failures + " action execution tests failed");
        System.out.println("ActionExecution: ALL OK");
    }
}
