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
                        && replanned.targetId().equals("alternate-settlement"),
                "retryable failure triggers one alternate plan and completes");

        int beforeDuplicateReplan = executions[0];
        ActionExecutionService.Task replanDuplicate = service.execute(new ActionExecutionService.Request(
                "request-replan", "settlement.build", "player-1", "settlement-4", List.of("bridge"), 103),
                r -> null, (r, attempt) -> { executions[0]++; return ActionResult.ok("must not run"); },
                null, 105, file);
        check(replanDuplicate.status() == ActionExecutionService.Status.SUCCEEDED
                        && executions[0] == beforeDuplicateReplan,
                "duplicate original request remains idempotent after execution was replanned");

        int[] protectedExecutions = {0};
        ActionExecutionService.Task replannedDenied = service.execute(new ActionExecutionService.Request(
                "request-replan-denied", "settlement.build", "player-1", "settlement-4", List.of("bridge"), 106),
                r -> r.targetId().equals("forbidden-alternative") ? "alternative is not authorized" : null,
                (r, attempt) -> { protectedExecutions[0]++; return ActionResult.retry("blocked"); },
                (r, failure, attempt) -> new ActionExecutionService.Plan(
                        "settlement.build", "forbidden-alternative", List.of("bridge"), "fallback"),
                107, file);
        check(replannedDenied.status() == ActionExecutionService.Status.FAILED && protectedExecutions[0] == 1,
                "authorization is rechecked after replanning before any alternate side effect");

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

        if (failures > 0) throw new AssertionError(failures + " action execution tests failed");
        System.out.println("ActionExecution: ALL OK");
    }
}
