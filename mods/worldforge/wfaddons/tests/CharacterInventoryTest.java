package dev.worldforge.tests;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import dev.worldforge.people.CharacterInventoryActions;
import dev.worldforge.people.CharacterInventoryTransactions;
import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.mod.identity.WorldId;
import java.util.List;
import java.util.UUID;

/** Pure regression tests for Character-owned item stacks and death estates. */
public final class CharacterInventoryTest {
    private static int failures;

    private static void ok(boolean condition, String label) {
        System.out.println((condition ? "ok   " : "FAIL ") + label);
        if (!condition) failures++;
    }

    public static void main(String[] args) {
        ok(CharacterInventory.playerSyncDecision(false, false, false)
                        == CharacterInventory.PlayerSyncDecision.UNCHANGED,
                "unchanged player projection does not trigger an inventory rewrite");
        ok(CharacterInventory.playerSyncDecision(false, true, false)
                        == CharacterInventory.PlayerSyncDecision.IMPORT_PHYSICAL,
                "physical-only inventory changes are imported into the canonical Character ledger");
        ok(CharacterInventory.playerSyncDecision(true, false, false)
                        == CharacterInventory.PlayerSyncDecision.PROJECT_LEDGER,
                "ledger-only inventory changes are projected back into the live player body");
        ok(CharacterInventory.playerSyncDecision(true, true, false)
                        == CharacterInventory.PlayerSyncDecision.CONFLICT,
                "simultaneous physical and ledger changes fail closed instead of overwriting either copy");
        ok(CharacterInventory.playerSyncDecision(false, false, true)
                        == CharacterInventory.PlayerSyncDecision.IMPORT_PHYSICAL,
                "a dirty persistence marker retries the physical snapshot even when fingerprints match");

        CharacterRegistry registry = new CharacterRegistry();
        Character malformedInventory = registry.create("Malformed Inventory", "", "north", "m", 270);
        malformedInventory.props.put("inventory.items", List.of("not-an-item-record"));
        boolean malformedInventoryRejected = false;
        try { CharacterInventory.items(malformedInventory); }
        catch (IllegalStateException expected) { malformedInventoryRejected = true; }
        ok(malformedInventoryRejected
                        && ((List<?>) malformedInventory.props.get("inventory.items")).size() == 1,
                "malformed Character inventory entries fail closed and remain in the ledger");

        Character malformedCount = registry.create("Fractional Count", "", "north", "m", 270);
        malformedCount.props.put("inventory.items", List.of(java.util.Map.of(
                "id", "minecraft:diamond", "count", 1.5, "data", "")));
        boolean fractionalCountRejected = false;
        try { CharacterInventory.items(malformedCount); }
        catch (IllegalStateException expected) { fractionalCountRejected = true; }
        ok(fractionalCountRejected,
                "fractional inventory counts are rejected instead of being truncated");

        Character malformedEquipment = registry.create("Malformed Equipment", "", "north", "m", 270);
        malformedEquipment.props.put("equipment.slots", java.util.Map.of("head", java.util.Map.of(
                "id", "minecraft:diamond_helmet", "count", 2, "data", "")));
        boolean malformedEquipmentRejected = false;
        try { CharacterInventory.equipment(malformedEquipment); }
        catch (IllegalStateException expected) { malformedEquipmentRejected = true; }
        ok(malformedEquipmentRejected,
                "equipment slots reject malformed multi-item stacks without silently dropping gear");

        Character malformedContainer = registry.create("Malformed Container", "", "north", "m", 270);
        CharacterInventory.createContainer(malformedContainer, "broken_store", List.of());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> malformedContainers =
                (java.util.Map<String, Object>) malformedContainer.props.get("inventory.containers");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> brokenStore =
                (java.util.Map<String, Object>) malformedContainers.get("broken_store");
        brokenStore.put("items", List.of("not-an-item-record"));
        boolean malformedContainerRejected = false;
        try { CharacterInventory.containerItems(malformedContainer, "broken_store"); }
        catch (IllegalStateException expected) { malformedContainerRejected = true; }
        ok(malformedContainerRejected
                        && ((List<?>) brokenStore.get("items")).size() == 1,
                "malformed shared-container item entries fail closed without erasing stored records");

        Character owner = registry.create("Aldric", "", "north", "m", 270);
        Character spouse = registry.create("Mira", "", "north", "f", 272);
        owner.spouse = spouse.id;
        spouse.spouse = owner.id;

        CharacterInventory.add(owner, "minecraft:iron_sword", 1, "{Damage:7}");
        CharacterInventory.add(owner, "minecraft:bread", 12);
        CharacterInventory.add(owner, "minecraft:bread", 8);
        ok(CharacterInventory.count(owner, "minecraft:bread") == 20,
                "same item and payload merge into one owned stack");
        ok(CharacterInventory.items(owner).size() == 2,
                "different NBT payloads remain separate item stacks");
        ok(!CharacterInventory.remove(owner, "minecraft:bread", 21)
                        && CharacterInventory.count(owner, "minecraft:bread") == 20,
                "insufficient removal leaves inventory unchanged");
        ok(CharacterInventory.transfer(owner, spouse, "minecraft:iron_sword", 1)
                        && CharacterInventory.count(owner, "minecraft:iron_sword") == 0
                        && CharacterInventory.count(spouse, "minecraft:iron_sword") == 1,
                "item transfer changes source and destination exactly once");
        ok(!CharacterInventory.transfer(owner, spouse, "minecraft:bread", 0),
                "invalid transfer amount is rejected");

        Character equipped = registry.create("Garrick", "", "north", "m", 260);
        CharacterInventory.add(equipped, "minecraft:iron_sword", 2, "{Damage:4}");
        ok(CharacterInventory.equip(equipped, "mainhand", "minecraft:iron_sword")
                        && CharacterInventory.count(equipped, "minecraft:iron_sword") == 1
                        && CharacterInventory.equipment(equipped).get("mainhand").data().equals("{Damage:4}"),
                "equipping consumes one owned stack and preserves its item data");
        CharacterInventory.add(equipped, "minecraft:diamond_sword", 1, "{Damage:2}");
        ok(CharacterInventory.equip(equipped, "mainhand", "minecraft:diamond_sword")
                        && CharacterInventory.count(equipped, "minecraft:iron_sword") == 2
                        && CharacterInventory.equipment(equipped).get("mainhand").id().equals("minecraft:diamond_sword"),
                "replacing equipment returns the previous item to inventory");
        ok(CharacterInventory.unequip(equipped, "mainhand")
                        && CharacterInventory.count(equipped, "minecraft:diamond_sword") == 1
                        && CharacterInventory.equipment(equipped).isEmpty(),
                "unequipping returns the exact item to Character inventory");

        Character slotOwner = registry.create("Inventory Keeper", "", "north", "m", 240);
        CharacterInventory.replaceSnapshot(slotOwner,
                java.util.List.of(new CharacterInventory.Item("minecraft:torch", 16, "{Damage:0}", 0),
                        new CharacterInventory.Item("minecraft:bread", 7, "", 8)),
                java.util.Map.of());
        String slotSave = registry.toJson();
        CharacterRegistry slotReload = new CharacterRegistry();
        slotReload.fromJson(slotSave, "world|slot-test");
        Character restoredSlots = slotReload.get(slotOwner.id);
        ok(CharacterInventory.items(restoredSlots).stream().anyMatch(item -> item.id().equals("minecraft:torch") && item.slot() == 0)
                        && CharacterInventory.items(restoredSlots).stream().anyMatch(item -> item.id().equals("minecraft:bread") && item.slot() == 8),
                "canonical Character persistence preserves exact hotbar and inventory slot positions");

        Character externallyGranted = registry.create("External Grant Recipient", "", "north", "f", 250);
        CharacterInventory.add(externallyGranted, "minecraft:emerald", 5);
        CharacterInventory.replacePlayerSnapshot(externallyGranted,
                java.util.List.of(new CharacterInventory.Item("minecraft:bread", 3, "", 4)),
                java.util.Map.of());
        ok(CharacterInventory.count(externallyGranted, "minecraft:emerald") == 5
                        && CharacterInventory.items(externallyGranted).stream().anyMatch(item -> item.id().equals("minecraft:bread") && item.slot() == 4),
                "physical player snapshot reconciliation preserves externally granted slotless Character inventory");

        CharacterRegistry containerRegistry = new CharacterRegistry();
        Character containerOwner = containerRegistry.create("Lord Rowan", "", "north", "m", 240);
        Character authorized = containerRegistry.create("Quartermaster", "", "north", "f", 250);
        Character outsider = containerRegistry.create("Stranger", "", "north", "m", 255);
        ok(CharacterInventory.createContainer(containerOwner, "keep_armory", java.util.List.of(authorized.globalId)),
                "shared container is created on its canonical Character owner");
        CharacterInventory.add(authorized, "minecraft:iron_sword", 2, "{Damage:3}");
        ok(CharacterInventory.deposit(containerOwner, "keep_armory", authorized, "minecraft:iron_sword", 1)
                        && CharacterInventory.count(authorized, "minecraft:iron_sword") == 1
                        && CharacterInventory.containerItems(containerOwner, "keep_armory").size() == 1,
                "authorized Character deposits an NBT-preserving item into shared storage");
        ok(!CharacterInventory.withdraw(containerOwner, "keep_armory", outsider, "minecraft:iron_sword", 1)
                        && CharacterInventory.count(outsider, "minecraft:iron_sword") == 0,
                "shared container rejects an unauthorized Character");
        ok(CharacterInventory.withdraw(containerOwner, "keep_armory", authorized, "minecraft:iron_sword", 1)
                        && CharacterInventory.count(authorized, "minecraft:iron_sword") == 2
                        && CharacterInventory.containerItems(containerOwner, "keep_armory").isEmpty(),
                "authorized Character withdraws the exact item stack");

        Character exactOwner = containerRegistry.create("Exact Store Owner", "", "north", "m", 240);
        Character exactActor = containerRegistry.create("Exact Stack Carrier", "", "north", "f", 250);
        CharacterInventory.createContainer(exactOwner, "exact_store", java.util.List.of());
        CharacterInventory.replaceSnapshot(exactActor,
                java.util.List.of(new CharacterInventory.Item("minecraft:iron_sword", 3, "{Damage:7}", 5)),
                java.util.Map.of());
        ok(CharacterInventory.grantContainerAccess(exactOwner, "exact_store", exactActor.globalId)
                        && CharacterInventory.canAccessContainer(exactOwner, "exact_store", exactActor),
                "container owner grants access using the grantee's global Character UUID");
        ok(CharacterInventory.depositExact(exactOwner, "exact_store", exactActor,
                        "minecraft:iron_sword", "{Damage:7}", 5, 2)
                        && CharacterInventory.count(exactActor, "minecraft:iron_sword") == 1
                        && CharacterInventory.items(exactActor).get(0).slot() == 5
                        && CharacterInventory.containerItems(exactOwner, "exact_store").get(0).data().equals("{Damage:7}"),
                "exact deposit removes only the selected inventory slot and preserves the NBT payload");
        ok(!CharacterInventory.withdrawExact(exactOwner, "exact_store", exactActor,
                        "minecraft:iron_sword", "{Damage:3}", 1)
                        && CharacterInventory.containerItems(exactOwner, "exact_store").get(0).count() == 2,
                "exact withdrawal rejects a stale or mismatched NBT payload");
        ok(CharacterInventory.withdrawExact(exactOwner, "exact_store", exactActor,
                        "minecraft:iron_sword", "{Damage:7}", 1)
                        && CharacterInventory.count(exactActor, "minecraft:iron_sword") == 2
                        && CharacterInventory.containerItems(exactOwner, "exact_store").get(0).count() == 1,
                "exact withdrawal moves one matching NBT stack back to the Character ledger");
        ok(CharacterInventory.revokeContainerAccess(exactOwner, "exact_store", exactActor.globalId)
                        && !CharacterInventory.canAccessContainer(exactOwner, "exact_store", exactActor)
                        && !CharacterInventory.withdrawExact(exactOwner, "exact_store", exactActor,
                        "minecraft:iron_sword", "{Damage:7}", 1),
                "revoked global access immediately blocks further container transfers");

        Character importedOwner = containerRegistry.create("Legacy Chest Owner", "", "north", "m", 242);
        java.util.List<CharacterInventory.Item> legacyContents = java.util.List.of(
                new CharacterInventory.Item("minecraft:torch", 32, "{CustomModelData:1}"));
        ok(CharacterInventory.importContainerItems(importedOwner, "legacy_chest", "world-dim-10-64-20", legacyContents)
                        && CharacterInventory.importContainerItems(importedOwner, "legacy_chest", "world-dim-10-64-20",
                        java.util.List.of(new CharacterInventory.Item("minecraft:torch", 99, "{CustomModelData:1}")))
                        && CharacterInventory.containerItems(importedOwner, "legacy_chest").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 32
                        && CharacterInventory.hasContainerImportSource(importedOwner, "legacy_chest", "world-dim-10-64-20"),
                "physical chest import marker makes legacy contents import exactly once");

        CharacterRegistry estateRegistry = new CharacterRegistry();
        Character deceased = estateRegistry.create("Borin", "", "hill", "m", 230);
        Character heir = estateRegistry.create("Elin", "", "hill", "f", 255);
        deceased.spouse = heir.id;
        heir.spouse = deceased.id;
        deceased.props.put("population.home", "settlement:stoneford");
        CharacterInventory.add(deceased, "minecraft:emerald", 37);
        CharacterInventory.add(deceased, "minecraft:iron_sword", 1, "{Damage:5}");
        CharacterInventory.equip(deceased, "mainhand", "minecraft:iron_sword");
        CharacterInventory.createContainer(deceased, "family_chest", java.util.List.of());
        ok(CharacterInventory.deposit(deceased, "family_chest", deceased, "minecraft:emerald", 10),
                "deceased Character can place items in their owned family container");
        estateRegistry.kill(deceased.id);
        ok(!deceased.alive && CharacterInventory.count(heir, "minecraft:emerald") == 27
                        && CharacterInventory.count(deceased, "minecraft:emerald") == 0
                        && CharacterInventory.containerItems(heir, "family_chest").stream().mapToInt(CharacterInventory.Item::count).sum() == 10
                        && CharacterInventory.equipment(heir).get("mainhand").id().equals("minecraft:iron_sword")
                        && CharacterInventory.equipment(heir).get("mainhand").data().equals("{Damage:5}"),
                "death transfers personal items, equipped gear and owned shared containers to a living spouse");
        ok(CharacterInventory.withdraw(heir, "family_chest", heir, "minecraft:emerald", 10)
                        && CharacterInventory.count(heir, "minecraft:emerald") == 37,
                "inherited shared container ownership is rebound to the heir");
        estateRegistry.kill(deceased.id);
        ok(CharacterInventory.count(heir, "minecraft:emerald") == 37,
                "repeated death processing cannot duplicate inherited items");

        CharacterRegistry pendingRegistry = new CharacterRegistry();
        Character dead = pendingRegistry.create("Tomas", "", "vale", "m", 200);
        dead.props.put("population.location", "settlement:oakwatch");
        CharacterInventory.add(dead, "minecraft:gold_ingot", 9);
        pendingRegistry.kill(dead.id);
        ok("awaiting_kin_or_home_claim".equals(dead.props.get("estate.status"))
                        && "settlement:oakwatch".equals(dead.props.get("estate.home"))
                        && CharacterInventory.count(dead, "minecraft:gold_ingot") == 9,
                "without an heir, estate remains recoverable on the deceased Character and home");

        Character claimant = pendingRegistry.create("Nell", "", "vale", "f", 225);
        dead.children.add(claimant.id);
        ok(CharacterInventory.claimEstate(dead, claimant)
                        && CharacterInventory.count(claimant, "minecraft:gold_ingot") == 9
                        && CharacterInventory.count(dead, "minecraft:gold_ingot") == 0,
                "later-discovered living kin can claim the pending estate");
        ok(!CharacterInventory.claimEstate(dead, claimant),
                "settled estate cannot be claimed twice");

        CharacterAuthority worldAuthority = new CharacterAuthority();
        worldAuthority.beginWorld(new WorldId(UUID.fromString("22222222-2222-4222-8222-222222222222")));
        CharacterRegistry settlementPeople = new CharacterRegistry();
        CharacterRegistry playerPeople = new CharacterRegistry();
        Character crossMapDeceased = settlementPeople.create("Lord Edric", "", "north", "m", 220);
        Character playerHeir = playerPeople.create("Player Heir", "", "north", "f", 245);
        crossMapDeceased.props.put("family.spouse_global_id", playerHeir.globalId);
        playerHeir.props.put("family.spouse_global_id", crossMapDeceased.globalId);
        CharacterInventory.add(crossMapDeceased, "minecraft:diamond", 14);
        worldAuthority.registerMap("settlement:north", settlementPeople);
        worldAuthority.registerMap("__players__", playerPeople);
        settlementPeople.kill(crossMapDeceased.id);
        ok(CharacterInventory.count(playerHeir, "minecraft:diamond") == 14
                        && CharacterInventory.count(crossMapDeceased, "minecraft:diamond") == 0
                        && "inherited".equals(crossMapDeceased.props.get("estate.status"))
                        && playerHeir.globalId.equals(crossMapDeceased.props.get("estate.recipient_global_id")),
                "death estate resolves a spouse in the separate global player registry");

        Character parent = settlementPeople.create("Parent", "", "north", "m", 180);
        CharacterRegistry secondHousehold = new CharacterRegistry();
        Character child = secondHousehold.create("Child", "", "north", "f", 210);
        parent.props.put("family.children_global_ids", java.util.List.of(child.globalId));
        CharacterInventory.add(parent, "minecraft:gold_ingot", 6);
        worldAuthority.registerMap("settlement:south", secondHousehold);
        settlementPeople.kill(parent.id);
        ok(CharacterInventory.count(child, "minecraft:gold_ingot") == 6
                        && CharacterInventory.count(parent, "minecraft:gold_ingot") == 0,
                "death estate resolves descendants through global family IDs across map partitions");
        Character pendingAcrossMaps = settlementPeople.create("Duke Oren", "", "north", "m", 190);
        CharacterInventory.add(pendingAcrossMaps, "minecraft:emerald", 4);
        settlementPeople.kill(pendingAcrossMaps.id);
        Character claimantAcrossMaps = playerPeople.create("Niece", "", "north", "f", 215);
        pendingAcrossMaps.props.put("family.children_global_ids", java.util.List.of(claimantAcrossMaps.globalId));
        ok(CharacterInventory.claimEstate(worldAuthority, pendingAcrossMaps, claimantAcrossMaps)
                        && CharacterInventory.count(claimantAcrossMaps, "minecraft:emerald") == 4
                        && CharacterInventory.count(pendingAcrossMaps, "minecraft:emerald") == 0,
                "pending home estate can be claimed by a later-discovered descendant in another registry");
        worldAuthority.clear();

        CharacterRegistry crashSource = new CharacterRegistry();
        CharacterRegistry crashRecipient = new CharacterRegistry();
        Character crashDeceased = crashSource.create("Crash Recovery Lord", "", "north", "m", 190);
        Character crashHeir = crashRecipient.create("Crash Recovery Heir", "", "north", "f", 220);
        crashDeceased.props.put("family.spouse_global_id", crashHeir.globalId);
        CharacterInventory.add(crashDeceased, "minecraft:diamond", 11);
        String sourceBeforeTransfer = crashSource.toJson();
        CharacterAuthority liveAuthority = new CharacterAuthority();
        liveAuthority.beginWorld(new WorldId(UUID.fromString("33333333-3333-4333-8333-333333333333")));
        liveAuthority.registerMap("estate-source", crashSource);
        liveAuthority.registerMap("estate-recipient", crashRecipient);
        crashSource.kill(crashDeceased.id);
        String recipientAfterTransfer = crashRecipient.toJson();

        CharacterRegistry recoveredSource = new CharacterRegistry();
        recoveredSource.fromJson(sourceBeforeTransfer, "world|estate-source");
        CharacterRegistry recoveredRecipient = new CharacterRegistry();
        recoveredRecipient.fromJson(recipientAfterTransfer, "world|estate-recipient");
        CharacterAuthority recoveredAuthority = new CharacterAuthority();
        recoveredAuthority.beginWorld(new WorldId(UUID.fromString("33333333-3333-4333-8333-333333333333")));
        recoveredAuthority.registerMap("estate-source", recoveredSource);
        recoveredAuthority.registerMap("estate-recipient", recoveredRecipient);
        CharacterInventory.reconcileEstates(recoveredAuthority);
        Character recoveredDeceased = recoveredSource.get(crashDeceased.id);
        Character recoveredHeir = recoveredRecipient.get(crashHeir.id);
        ok(!recoveredDeceased.alive && CharacterInventory.count(recoveredDeceased, "minecraft:diamond") == 0
                        && CharacterInventory.count(recoveredHeir, "minecraft:diamond") == 11
                        && Boolean.TRUE.equals(recoveredDeceased.props.get("death.lifecycle.completed")),
                "recipient-first estate commit recovers a crash between separate registry file saves without loss or duplication");
        liveAuthority.clear();
        recoveredAuthority.clear();

        Character actionContainerOwner = registry.create("Container Owner", "", "north", "m", 260);
        Character actionContainerGuest = registry.create("Container Guest", "", "north", "f", 261);
        CharacterInventory.createContainer(actionContainerOwner, "shared_store", java.util.List.of(actionContainerGuest.globalId));
        CharacterInventory.add(actionContainerGuest, "minecraft:emerald", 3);
        ActionExecutionService inventoryJournal = new ActionExecutionService();
        ActionExecutionService.Request depositRequest = new ActionExecutionService.Request(
                "inventory-deposit-once", "inventory.container.deposit",
                "character:" + actionContainerGuest.globalId, actionContainerOwner.globalId,
                java.util.List.of("shared_store", "minecraft:emerald", "2"), 75);
        ActionExecutionService.Task depositTask = CharacterInventoryActions.deposit(
                inventoryJournal, depositRequest, actionContainerOwner, actionContainerGuest,
                "shared_store", "minecraft:emerald", 2, 75, null);
        ActionExecutionService.Task duplicateDepositTask = CharacterInventoryActions.deposit(
                inventoryJournal, depositRequest, actionContainerOwner, actionContainerGuest,
                "shared_store", "minecraft:emerald", 2, 75, null);
        ok(depositTask.status() == ActionExecutionService.Status.SUCCEEDED
                        && duplicateDepositTask.receiptId().equals(depositTask.receiptId())
                        && CharacterInventory.count(actionContainerGuest, "minecraft:emerald") == 1
                        && CharacterInventory.containerItems(actionContainerOwner, "shared_store").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 2,
                "shared-container deposits execute through the common action journal exactly once");

        CharacterAuthority transactionAuthority = new CharacterAuthority();
        transactionAuthority.beginWorld(new WorldId(UUID.fromString("44444444-4444-4444-8444-444444444444")));
        CharacterRegistry transactionPlayers = new CharacterRegistry();
        CharacterRegistry transactionKeep = new CharacterRegistry();
        Character transactionPlayer = transactionPlayers.create("Cross-Registry Player", "", "north", "m", 250);
        Character transactionOwner = transactionKeep.create("Cross-Registry Keeper", "", "north", "f", 260);
        CharacterInventory.createContainer(transactionOwner, "cross_registry_store",
                java.util.List.of(transactionPlayer.globalId));
        CharacterInventory.replaceSnapshot(transactionPlayer,
                java.util.List.of(new CharacterInventory.Item("minecraft:diamond", 5, "{Damage:0}", 0)),
                java.util.Map.of());
        transactionAuthority.registerMap("__players__", transactionPlayers);
        transactionAuthority.registerMap("settlement:keeper", transactionKeep);
        boolean crossTransfer = CharacterInventoryTransactions.executeExact(
                "inventory-cross-registry-success", transactionAuthority, transactionOwner,
                transactionPlayer, "cross_registry_store", "minecraft:diamond", "{Damage:0}",
                0, 2, true, character -> true);
        ok(crossTransfer
                        && CharacterInventory.items(transactionPlayer).stream().mapToInt(CharacterInventory.Item::count).sum() == 3
                        && CharacterInventory.containerItems(transactionOwner, "cross_registry_store").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 2
                        && !CharacterInventory.hasPendingTransfer(transactionPlayer)
                        && !CharacterInventory.hasPendingTransfer(transactionOwner),
                "cross-registry shared-container transfer commits once and clears its write-ahead markers");

        CharacterRegistry duplicatePlayerRegistry = new CharacterRegistry();
        duplicatePlayerRegistry.fromJson(transactionPlayers.toJson(), "world|__players__");
        Character stalePlayerReference = duplicatePlayerRegistry.get(transactionPlayer.id);
        int containerBeforeStaleAttempt = CharacterInventory.containerItems(
                transactionOwner, "cross_registry_store").stream().mapToInt(CharacterInventory.Item::count).sum();
        boolean staleActorRejected = !CharacterInventoryTransactions.executeExact(
                "inventory-stale-actor-reference", transactionAuthority, transactionOwner,
                stalePlayerReference, "cross_registry_store", "minecraft:diamond", "{Damage:0}",
                0, 1, true, character -> true);
        ok(staleActorRejected
                        && CharacterInventory.items(transactionPlayer).stream().mapToInt(CharacterInventory.Item::count).sum() == 3
                        && CharacterInventory.containerItems(transactionOwner, "cross_registry_store").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == containerBeforeStaleAttempt,
                "cross-registry transfer rejects a stale Character object even when its GlobalId matches the canonical player");

        CharacterRegistry duplicateOwnerRegistry = new CharacterRegistry();
        duplicateOwnerRegistry.fromJson(transactionKeep.toJson(), "world|settlement:keeper");
        Character staleOwnerReference = duplicateOwnerRegistry.get(transactionOwner.id);
        boolean staleOwnerRejected = !CharacterInventoryTransactions.executeExact(
                "inventory-stale-owner-reference", transactionAuthority, staleOwnerReference,
                transactionPlayer, "cross_registry_store", "minecraft:diamond", "{Damage:0}",
                0, 1, true, character -> true);
        ok(staleOwnerRejected
                        && CharacterInventory.items(transactionPlayer).stream().mapToInt(CharacterInventory.Item::count).sum() == 3
                        && CharacterInventory.containerItems(transactionOwner, "cross_registry_store").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == containerBeforeStaleAttempt,
                "cross-registry transfer rejects a stale Character container owner instead of persisting a detached ledger");

        CharacterAuthority crashTransferAuthority = new CharacterAuthority();
        crashTransferAuthority.beginWorld(new WorldId(UUID.fromString("55555555-5555-4555-8555-555555555555")));
        CharacterRegistry crashTransferSource = new CharacterRegistry();
        CharacterRegistry crashTransferDestination = new CharacterRegistry();
        Character crashTransferPlayer = crashTransferSource.create("Transfer Recovery Player", "", "north", "m", 250);
        Character crashTransferOwner = crashTransferDestination.create("Transfer Recovery Keeper", "", "north", "f", 260);
        CharacterInventory.createContainer(crashTransferOwner, "recovery_store",
                java.util.List.of(crashTransferPlayer.globalId));
        CharacterInventory.replaceSnapshot(crashTransferPlayer,
                java.util.List.of(new CharacterInventory.Item("minecraft:emerald", 7, "{Damage:2}", 4)),
                java.util.Map.of());
        crashTransferAuthority.registerMap("__players__", crashTransferSource);
        crashTransferAuthority.registerMap("settlement:recovery", crashTransferDestination);
        String[] sourceDisk = {""}, destinationDisk = {""};
        int[] persistenceCalls = {0};
        boolean interruptedTransfer = CharacterInventoryTransactions.executeExact(
                "inventory-cross-registry-interrupted", crashTransferAuthority, crashTransferOwner,
                crashTransferPlayer, "recovery_store", "minecraft:emerald", "{Damage:2}",
                4, 3, true, character -> {
                    persistenceCalls[0]++;
                    if (persistenceCalls[0] == 1) {
                        sourceDisk[0] = crashTransferSource.toJson();       // durable PREPARED source
                        destinationDisk[0] = crashTransferDestination.toJson(); // durable PREPARED destination
                        return true;
                    }
                    if (persistenceCalls[0] == 2) {
                        destinationDisk[0] = crashTransferDestination.toJson(); // destination COMMITTED
                        return false; // simulate process loss before the source file is replaced
                    }
                    return true;
                });
        CharacterRegistry recoveredTransferSource = new CharacterRegistry();
        recoveredTransferSource.fromJson(sourceDisk[0], "world|__players__");
        CharacterRegistry recoveredTransferDestination = new CharacterRegistry();
        recoveredTransferDestination.fromJson(destinationDisk[0], "world|settlement:recovery");
        CharacterAuthority recoveredTransferAuthority = new CharacterAuthority();
        recoveredTransferAuthority.beginWorld(new WorldId(UUID.fromString("55555555-5555-4555-8555-555555555555")));
        recoveredTransferAuthority.registerMap("__players__", recoveredTransferSource);
        recoveredTransferAuthority.registerMap("settlement:recovery", recoveredTransferDestination);
        boolean transferRecovered = CharacterInventoryTransactions.reconcile(
                recoveredTransferAuthority, character -> true);
        Character recoveredTransferPlayer = recoveredTransferAuthority.getGlobal(crashTransferPlayer.globalId);
        Character recoveredTransferOwner = recoveredTransferAuthority.getGlobal(crashTransferOwner.globalId);
        ok(!interruptedTransfer && transferRecovered
                        && CharacterInventory.items(recoveredTransferPlayer).stream().mapToInt(CharacterInventory.Item::count).sum() == 4
                        && CharacterInventory.containerItems(recoveredTransferOwner, "recovery_store").stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 3
                        && !CharacterInventory.hasPendingTransfer(recoveredTransferPlayer)
                        && !CharacterInventory.hasPendingTransfer(recoveredTransferOwner),
                "restart reconciliation completes a destination-committed transfer whose source file was not yet replaced");
        CharacterAuthority corruptAuthority = new CharacterAuthority();
        corruptAuthority.beginWorld(new WorldId(UUID.fromString("66666666-6666-4666-8666-666666666666")));
        CharacterRegistry corruptPlayers = new CharacterRegistry();
        CharacterRegistry corruptKeep = new CharacterRegistry();
        Character corruptPlayer = corruptPlayers.create("Corrupt Marker Player", "", "north", "m", 245);
        Character corruptOwner = corruptKeep.create("Corrupt Marker Owner", "", "north", "f", 250);
        CharacterInventory.createContainer(corruptOwner, "corrupt_store",
                java.util.List.of(corruptPlayer.globalId));
        CharacterInventory.replaceSnapshot(corruptPlayer,
                java.util.List.of(new CharacterInventory.Item("minecraft:diamond", 4, "{Damage:0}", 2)),
                java.util.Map.of());
        corruptAuthority.registerMap("__players__", corruptPlayers);
        corruptAuthority.registerMap("settlement:corrupt", corruptKeep);
        String corruptTransactionId = "inventory-corrupt-marker";
        String corruptMarkerKey = CharacterInventory.TRANSFER_MARKER_PREFIX
                + UUID.nameUUIDFromBytes(corruptTransactionId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.Map<String, Object> corruptSourceMarker = new java.util.LinkedHashMap<>();
        corruptSourceMarker.put("transaction_id", corruptTransactionId);
        corruptSourceMarker.put("operation", "deposit");
        corruptSourceMarker.put("source_global_id", corruptPlayer.globalId);
        corruptSourceMarker.put("destination_global_id", corruptOwner.globalId);
        corruptSourceMarker.put("owner_global_id", corruptOwner.globalId);
        corruptSourceMarker.put("container_id", "corrupt_store");
        corruptSourceMarker.put("item_id", "minecraft:diamond");
        corruptSourceMarker.put("item_data", "{Damage:0}");
        corruptSourceMarker.put("source_slot", 2);
        corruptSourceMarker.put("amount", 1);
        corruptSourceMarker.put("role", "source");
        corruptSourceMarker.put("phase", "PREPARED");
        java.util.Map<String, Object> corruptDestinationMarker =
                new java.util.LinkedHashMap<>(corruptSourceMarker);
        corruptDestinationMarker.put("source_global_id", UUID.randomUUID().toString());
        corruptDestinationMarker.put("role", "destination");
        corruptPlayer.props.put(corruptMarkerKey, corruptSourceMarker);
        corruptOwner.props.put(corruptMarkerKey, corruptDestinationMarker);
        boolean corruptMarkerAccepted = CharacterInventoryTransactions.reconcile(
                corruptAuthority, character -> true);
        ok(!corruptMarkerAccepted
                        && CharacterInventory.hasPendingTransfer(corruptPlayer)
                        && CharacterInventory.hasPendingTransfer(corruptOwner)
                        && CharacterInventory.items(corruptPlayer).stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 4
                        && CharacterInventory.containerItems(corruptOwner, "corrupt_store").isEmpty(),
                "mismatched WAL endpoint identities fail closed without clearing markers or mutating either inventory");
        corruptPlayer.props.remove(corruptMarkerKey);
        corruptOwner.props.remove(corruptMarkerKey);
        String malformedMarkerKey = CharacterInventory.TRANSFER_MARKER_PREFIX + "malformed";
        corruptPlayer.props.put(malformedMarkerKey, "not-a-marker-map");
        boolean malformedMarkerAccepted = CharacterInventoryTransactions.reconcile(
                corruptAuthority, character -> true);
        ok(!malformedMarkerAccepted && CharacterInventory.hasPendingTransfer(corruptPlayer)
                        && CharacterInventory.items(corruptPlayer).stream()
                        .mapToInt(CharacterInventory.Item::count).sum() == 4,
                "malformed non-map WAL marker also blocks recovery instead of being silently ignored");
        corruptAuthority.clear();

        transactionAuthority.clear();
        crashTransferAuthority.clear();
        recoveredTransferAuthority.clear();

        CharacterRegistry overflowRegistry = new CharacterRegistry();
        Character overflowDead = overflowRegistry.create("Rurik", "", "clan", "m", 210);
        Character overflowHeir = overflowRegistry.create("Sella", "", "clan", "f", 230);
        overflowDead.spouse = overflowHeir.id;
        overflowHeir.spouse = overflowDead.id;
        CharacterInventory.add(overflowDead, "minecraft:emerald", 1);
        CharacterInventory.add(overflowHeir, "minecraft:emerald", 1_000_000);
        int[] deathEvents = {0}, dismissEvents = {0};
        overflowRegistry.appoint(overflowDead.id, "marshal", "realm:test");
        overflowRegistry.addListener(new CharacterRegistry.Listener() {
            @Override public void died(Character character) { deathEvents[0]++; }
            @Override public void dismissed(Character character, String role, String place) { dismissEvents[0]++; }
        });
        boolean overflowRejected = false;
        try { overflowRegistry.kill(overflowDead.id); }
        catch (ArithmeticException | IllegalArgumentException expected) { overflowRejected = true; }
        ok(overflowRejected && CharacterInventory.count(overflowDead, "minecraft:emerald") == 1
                        && CharacterInventory.count(overflowHeir, "minecraft:emerald") == 1_000_000,
                "estate overflow rejects the complete transfer without losing either inventory");
        ok(CharacterInventory.remove(overflowHeir, "minecraft:emerald", 1)
                        && overflowRegistry.kill(overflowDead.id).size() == 1
                        && CharacterInventory.count(overflowDead, "minecraft:emerald") == 0
                        && CharacterInventory.count(overflowHeir, "minecraft:emerald") == 1_000_000,
                "failed estate settlement can retry after capacity is available");
        ok(deathEvents[0] == 1 && dismissEvents[0] == 1 && overflowDead.posts.isEmpty(),
                "estate retry completes service dismissal and death lifecycle exactly once");

        String containerJson = containerRegistry.toJson();
        CharacterRegistry restoredContainerRegistry = new CharacterRegistry();
        restoredContainerRegistry.fromJson(containerJson, "test-world|containers");
        Character restoredOwner = restoredContainerRegistry.get(containerOwner.id);
        Character restoredAuthorized = restoredContainerRegistry.get(authorized.id);
        CharacterInventory.add(restoredAuthorized, "minecraft:iron_sword", 1, "{Damage:3}");
        ok(CharacterInventory.deposit(restoredOwner, "keep_armory", restoredAuthorized, "minecraft:iron_sword", 1)
                        && CharacterInventory.containerItems(restoredOwner, "keep_armory").get(0).data().equals("{Damage:3}"),
                "shared container access and item payload survive CharacterRegistry save/load");

        String json = pendingRegistry.toJson();
        CharacterRegistry restored = new CharacterRegistry();
        restored.fromJson(json, "test-world|estate");
        Character restoredDead = restored.get(dead.id);
        Character restoredClaimant = restored.get(claimant.id);
        ok(restoredDead != null && restoredClaimant != null
                        && "inherited".equals(restoredDead.props.get("estate.status"))
                        && CharacterInventory.count(restoredClaimant, "minecraft:gold_ingot") == 9,
                "inventory ownership and estate settlement survive registry save/load");

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
