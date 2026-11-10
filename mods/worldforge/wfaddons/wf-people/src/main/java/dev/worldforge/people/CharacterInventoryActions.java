package dev.worldforge.people;

import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.ActionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Domain adapter for inventory mutations. UI, commands and NPC interactions submit stable
 * request IDs here; the one shared ActionExecutionService owns authorization, idempotency,
 * terminal status and receipts. CharacterInventory remains the single data model.
 */
public final class CharacterInventoryActions {
    private CharacterInventoryActions() { }

    public static ActionExecutionService.Task createContainer(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character owner, Character actor, String containerId, List<String> accessGlobalIds,
            long nowMinute, Path durableFile) {
        if (!owner.globalId.equals(actor.globalId))
            throw new IllegalArgumentException("Only the canonical container owner can create a shared container");
        return execute(journal, request, "inventory.container.create", owner, actor,
                List.of(containerId), nowMinute, durableFile,
                () -> CharacterInventory.createContainer(owner, containerId, accessGlobalIds),
                "Container created");
    }

    public static ActionExecutionService.Task deposit(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character owner, Character actor, String containerId, String itemId, int amount,
            long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.container.deposit", owner, actor,
                List.of(containerId, itemId, Integer.toString(amount)), nowMinute, durableFile,
                () -> CharacterInventory.deposit(owner, containerId, actor, itemId, amount),
                "Items deposited");
    }

    public static ActionExecutionService.Task withdraw(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character owner, Character actor, String containerId, String itemId, int amount,
            long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.container.withdraw", owner, actor,
                List.of(containerId, itemId, Integer.toString(amount)), nowMinute, durableFile,
                () -> CharacterInventory.withdraw(owner, containerId, actor, itemId, amount),
                "Items withdrawn");
    }

    public static ActionExecutionService.Task transfer(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character from, Character to, String itemId, int amount,
            long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.character.transfer", to, from,
                List.of(itemId, Integer.toString(amount)), nowMinute, durableFile,
                () -> CharacterInventory.transfer(from, to, itemId, amount),
                "Items transferred");
    }

    public static ActionExecutionService.Task equip(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character person, String slot, String itemId, long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.equipment.equip", person, person,
                List.of(slot, itemId), nowMinute, durableFile,
                () -> CharacterInventory.equip(person, slot, itemId),
                "Item equipped");
    }

    public static ActionExecutionService.Task unequip(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character person, String slot, long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.equipment.unequip", person, person,
                List.of(slot), nowMinute, durableFile,
                () -> CharacterInventory.unequip(person, slot),
                "Item unequipped");
    }

    public static ActionExecutionService.Task claimEstate(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character deceased, Character claimant, long nowMinute, Path durableFile) {
        return execute(journal, request, "inventory.estate.claim", deceased, claimant,
                List.of(deceased.globalId), nowMinute, durableFile,
                () -> CharacterInventory.claimEstate(deceased, claimant),
                "Estate claimed");
    }

    public static ActionExecutionService.Task depositExact(
            ActionExecutionService journal, ActionExecutionService.Request request,
            CharacterAuthority authority, Character owner, Character actor,
            String containerId, String itemId, String data,
            int playerSlot, int amount, long nowMinute, Path durableFile,
            CharacterInventoryTransactions.Persister persist) {
        String dataHash = java.util.UUID.nameUUIDFromBytes(
                (data == null ? "" : data).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        List<String> args = List.of(containerId, Integer.toString(playerSlot), itemId, dataHash, Integer.toString(amount));
        validateRequest(request, "inventory.container.deposit_exact", owner, actor, args);
        return journal.execute(request,
                req -> actor.alive && CharacterInventory.canAccessContainer(owner, containerId, actor)
                        ? null : "Inventory actor is dead or has no access to this container",
                (req, attempt) -> CharacterInventoryTransactions.executeExact(
                        req.requestId(), authority, owner, actor, containerId, itemId, data,
                        playerSlot, amount, true, persist)
                        ? ActionResult.ok("Items deposited")
                        : ActionResult.fail("Transfer outcome is not yet fully durable; reconciliation marker retained"),
                null, nowMinute, durableFile);
    }

    public static ActionExecutionService.Task withdrawExact(
            ActionExecutionService journal, ActionExecutionService.Request request,
            CharacterAuthority authority, Character owner, Character actor,
            String containerId, String itemId, String data,
            int amount, long nowMinute, Path durableFile,
            CharacterInventoryTransactions.Persister persist) {
        String dataHash = java.util.UUID.nameUUIDFromBytes(
                (data == null ? "" : data).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        List<String> args = List.of(containerId, itemId, dataHash, Integer.toString(amount));
        validateRequest(request, "inventory.container.withdraw_exact", owner, actor, args);
        return journal.execute(request,
                req -> actor.alive && CharacterInventory.canAccessContainer(owner, containerId, actor)
                        ? null : "Inventory actor is dead or has no access to this container",
                (req, attempt) -> CharacterInventoryTransactions.executeExact(
                        req.requestId(), authority, owner, actor, containerId, itemId, data,
                        -1, amount, false, persist)
                        ? ActionResult.ok("Items withdrawn")
                        : ActionResult.fail("Transfer outcome is not yet fully durable; reconciliation marker retained"),
                null, nowMinute, durableFile);
    }

    public static ActionExecutionService.Task grantAccess(
            ActionExecutionService journal, ActionExecutionService.Request request,
            Character owner, Character actor, String containerId, String granteeGlobalId,
            long nowMinute, Path durableFile, java.util.function.BooleanSupplier persist) {
        if (!owner.globalId.equals(actor.globalId))
            throw new IllegalArgumentException("Only the canonical container owner can grant access");
        return executeDurable(journal, request, "inventory.container.grant_access", owner, actor,
                List.of(containerId, granteeGlobalId), nowMinute, durableFile,
                () -> CharacterInventory.grantContainerAccess(owner, containerId, granteeGlobalId),
                persist, "Container access granted");
    }

    private static void validateRequest(ActionExecutionService.Request request, String actionId,
                                        Character target, Character actor, List<String> args) {
        Objects.requireNonNull(request); Objects.requireNonNull(target); Objects.requireNonNull(actor);
        if (!request.actionId().equals(actionId)
                || !request.actorId().equals("character:" + actor.globalId)
                || !request.targetId().equals(target.globalId)
                || !request.args().equals(args))
            throw new IllegalArgumentException("Inventory action request does not match its canonical actor, target or payload");
    }

    public static ActionExecutionService.Task executeDurable(
            ActionExecutionService journal, ActionExecutionService.Request request,
            String actionId, Character target, Character actor, List<String> args,
            long nowMinute, Path durableFile, java.util.function.BooleanSupplier mutation,
            java.util.function.BooleanSupplier persist, String successMessage) {
        Objects.requireNonNull(journal); Objects.requireNonNull(request);
        Objects.requireNonNull(target); Objects.requireNonNull(actor); Objects.requireNonNull(persist);
        if (!request.actionId().equals(actionId)
                || !request.actorId().equals("character:" + actor.globalId)
                || !request.targetId().equals(target.globalId)
                || !request.args().equals(args))
            throw new IllegalArgumentException("Inventory action request does not match its canonical actor, target or payload");
        return journal.execute(request,
                req -> actor.alive ? null : "Inventory actor is no longer alive",
                (req, attempt) -> {
                    if (!mutation.getAsBoolean())
                        return ActionResult.fail("Inventory operation was rejected by ownership, access or capacity rules");
                    if (!persist.getAsBoolean())
                        return ActionResult.fail("Inventory mutation could not be durably committed; reconciliation is required");
                    return ActionResult.ok(successMessage);
                }, null, nowMinute, durableFile);
    }

    private static ActionExecutionService.Task execute(
            ActionExecutionService journal, ActionExecutionService.Request request,
            String actionId, Character target, Character actor, List<String> args,
            long nowMinute, Path durableFile, java.util.function.BooleanSupplier mutation,
            String successMessage) {
        return executeDurable(journal, request, actionId, target, actor, args,
                nowMinute, durableFile, mutation, () -> true, successMessage);
    }
}
