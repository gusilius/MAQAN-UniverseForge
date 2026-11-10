package dev.worldforge.tests;

import dev.worldforge.genesis.GenesisActionIds;
import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.ActionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GenesisActionIdsTest {
    private static int failures;
    private static void check(boolean value, String message) {
        if (value) System.out.println("ok   " + message);
        else { failures++; System.out.println("FAIL " + message); }
    }

    public static void main(String[] args) throws Exception {
        Path journalFile = Files.createTempDirectory("wf-genesis-action-test").resolve("actions.json");
        String player = "12345678-1234-4234-8234-123456789abc";
        String draft = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        String reset = GenesisActionIds.resetDraft("operator-1", player, draft);
        check(reset.equals(GenesisActionIds.resetDraft("operator-1", player, draft)),
                "Genesis reset request ID is stable across command ticks and retries");
        check(!reset.equals(GenesisActionIds.resetDraft("operator-2", player, draft))
                        && !reset.equals(GenesisActionIds.resetDraft("operator-1", player, "different-draft")),
                "reset ID changes when actor or draft changes");

        String confirm = GenesisActionIds.confirm(player, "session-a", draft, 0);
        check(confirm.equals(GenesisActionIds.confirm(player, "session-a", draft, 0)),
                "Genesis confirmation ID is stable within a server session");
        check(!confirm.equals(GenesisActionIds.confirm(player, "session-b", draft, 0))
                        && !confirm.equals(GenesisActionIds.confirm(player, "session-a", "different-draft", 0)),
                "confirmation ID is scoped to the server session and exact draft");
        check(!GenesisActionIds.confirm(player, "session-a", draft, 1).equals(confirm)
                        && GenesisActionIds.confirm(player, "session-a", draft, 1)
                        .equals(GenesisActionIds.confirm(player, "session-a", draft, 1)),
                "explicit recovery attempt IDs are stable and distinct from the original confirmation");
        boolean invalid = false;
        try { GenesisActionIds.confirm(player, "session-a", draft, -1); }
        catch (IllegalArgumentException expected) { invalid = true; }
        check(invalid, "negative Genesis recovery sequence is rejected");

        ActionExecutionService journal = new ActionExecutionService();
        int[] effects = {0};
        ActionExecutionService.Request partial = new ActionExecutionService.Request(
                confirm, "genesis.confirm", "player:" + player, player, List.of("human", "village", draft), 100);
        ActionExecutionService.Task failed = journal.execute(partial,
                r -> r.actorId().equals("player:" + player) && r.targetId().equals(player) ? null : "unauthorized",
                (r, attempt) -> { effects[0]++; return ActionResult.fail("partial application needs reconciliation"); },
                null, 100, journalFile);
        ActionExecutionService.Task duplicate = journal.execute(partial,
                r -> null, (r, attempt) -> { effects[0]++; return ActionResult.ok("must not replay"); },
                null, 101, journalFile);
        check(failed.status() == ActionExecutionService.Status.FAILED
                        && duplicate.status() == ActionExecutionService.Status.FAILED && effects[0] == 1,
                "same Genesis confirmation request cannot replay uncertain partial effects");

        String recoveryId = GenesisActionIds.confirm(player, "session-b", draft, 1);
        ActionExecutionService.Request recovery = new ActionExecutionService.Request(
                recoveryId, "genesis.confirm", "player:" + player, player, List.of("human", "village", draft), 102);
        ActionExecutionService.Task recovered = journal.execute(recovery,
                r -> r.actorId().equals("player:" + player) && r.targetId().equals(player) ? null : "unauthorized",
                (r, attempt) -> { effects[0]++; return ActionResult.ok("reconciled"); },
                null, 102, journalFile);
        ActionExecutionService.Task recoveryDuplicate = journal.execute(recovery,
                r -> null, (r, attempt) -> { effects[0]++; return ActionResult.ok("must not replay"); },
                null, 103, journalFile);
        check(recovered.status() == ActionExecutionService.Status.SUCCEEDED
                        && recoveryDuplicate.status() == ActionExecutionService.Status.SUCCEEDED && effects[0] == 2,
                "one explicit post-reconciliation recovery can succeed and remains idempotent");

        ActionExecutionService.Request forged = new ActionExecutionService.Request(
                GenesisActionIds.resetDraft("operator-1", player, draft),
                "genesis.reset_draft", "intruder", player, List.of(player), 104);
        ActionExecutionService.Task denied = journal.execute(forged,
                r -> r.actorId().equals("operator-1") ? null : "operator authorization required",
                (r, attempt) -> { effects[0]++; return ActionResult.ok("must not run"); },
                null, 104, journalFile);
        check(denied.status() == ActionExecutionService.Status.FAILED && effects[0] == 2,
                "unauthorized Genesis reset never reaches its executor");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
