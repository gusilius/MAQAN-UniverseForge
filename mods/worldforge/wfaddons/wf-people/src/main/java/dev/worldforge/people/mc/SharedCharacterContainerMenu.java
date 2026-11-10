package dev.worldforge.people.mc;

import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import dev.worldforge.people.CharacterInventoryActions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative vanilla chest screen backed by a Character-owned inventory container. */
public final class SharedCharacterContainerMenu extends ChestMenu {
    private final ServerPlayer opener;
    private final Character owner;
    private final String containerId;
    private final BlockPos position;
    private final BlockPos partnerPosition;
    private final SimpleContainer view;
    private final String sessionId = UUID.randomUUID().toString();

    public SharedCharacterContainerMenu(int id, Inventory playerInventory, ServerPlayer opener,
                                        Character owner, String containerId, BlockPos position,
                                        BlockPos partnerPosition, SimpleContainer view) {
        super(MenuType.GENERIC_9x6, id, playerInventory, view, 6);
        this.opener = opener;
        this.owner = owner;
        this.containerId = containerId;
        this.position = position.immutable();
        this.partnerPosition = partnerPosition == null ? null : partnerPosition.immutable();
        this.view = view;
        refreshView();
    }

    public String ownerGlobalId() { return owner.globalId; }
    public String containerId() { return containerId; }

    @Override
    public boolean stillValid(Player player) {
        Character actor = PeopleMod.playerCharacter(opener.getUUID());
        if (!PeopleMod.identityPartitionsReady()
                || player != opener || !player.level().hasChunkAt(position)
                || player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5,
                        position.getZ() + 0.5) > 64.0
                || actor == null || !owner.alive
                || !CharacterInventory.canAccessContainer(owner, containerId, actor)
                || !hasMatchingBlockMetadata(player, position)) return false;
        return partnerPosition == null || player.level().hasChunkAt(partnerPosition)
                && hasMatchingBlockMetadata(player, partnerPosition);
    }

    private boolean hasMatchingBlockMetadata(Player player, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (blockEntity == null) return false;
        CompoundTag data = blockEntity.getPersistentData();
        return owner.globalId.equals(data.getString("worldforge_inventory_owner_global_id"))
                && containerId.equals(data.getString("worldforge_inventory_container_id"));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player != opener || !(player instanceof ServerPlayer serverPlayer)) return;
        if (clickType == ClickType.QUICK_MOVE) {
            quickMoveStack(player, slotId);
            return;
        }
        if (clickType != ClickType.PICKUP || (button != 0 && button != 1)) return;
        if (slotId < 0 || slotId >= this.slots.size() || !getCarried().isEmpty()) return;
        performSlotAction(serverPlayer, slotId, button == 1);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (player != opener || !(player instanceof ServerPlayer serverPlayer)
                || index < 0 || index >= this.slots.size()) return ItemStack.EMPTY;
        performSlotAction(serverPlayer, index, false);
        return ItemStack.EMPTY;
    }

    private void performSlotAction(ServerPlayer player, int menuSlot, boolean oneItem) {
        Character actor = PeopleMod.playerCharacter(player.getUUID());
        if (!PeopleMod.identityPartitionsReady()) {
            player.sendSystemMessage(Component.literal("Общее хранилище заблокировано: восстановление реестров персонажей не завершено."));
            return;
        }
        if (actor == null || !owner.alive
                || !CharacterInventory.canAccessContainer(owner, containerId, actor)) return;
        if (menuSlot < 54) withdraw(player, actor, menuSlot, oneItem);
        else deposit(player, actor, menuSlot, oneItem);
        refreshView();
        broadcastChanges();
    }

    private void withdraw(ServerPlayer player, Character actor, int index, boolean oneItem) {
        List<CharacterInventory.Item> contents = CharacterInventory.containerItems(owner, containerId);
        if (index < 0 || index >= contents.size()) return;
        CharacterInventory.Item item = contents.get(index);
        int amount = oneItem ? 1 : item.count();
        String fingerprint = fingerprint(item.data());
        List<String> args = List.of(containerId, item.id(), fingerprint, Integer.toString(amount));
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = requestId("withdraw", getStateId(), index, oneItem ? 1 : 0, fingerprint);
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                requestId, "inventory.container.withdraw_exact", "character:" + actor.globalId,
                owner.globalId, args, minute);
        ActionExecutionService.Task task = CharacterInventoryActions.withdrawExact(
                WorldForgeApi.actionExecution(), request, PeopleMod.authority(), owner, actor,
                containerId, item.id(), item.data(), amount, minute,
                dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.getServer()),
                character -> PeopleMod.persistCharacter(character, player.getServer()));
        report(player, task);
    }

    private void deposit(ServerPlayer player, Character actor, int menuSlot, boolean oneItem) {
        Slot slot = this.slots.get(menuSlot);
        int playerSlot = slot.getContainerSlot();
        if (playerSlot < 0 || playerSlot >= player.getInventory().items.size()) return;
        if (!PlayerInventoryBridge.syncNow(player)) {
            player.sendSystemMessage(Component.literal("Инвентарь не удалось сохранить; перемещение отменено."));
            return;
        }
        CharacterInventory.Item selected = null;
        for (CharacterInventory.Item item : CharacterInventory.items(actor))
            if (item.slot() == playerSlot) { selected = item; break; }
        if (selected == null) return;
        int amount = oneItem ? 1 : selected.count();
        String fingerprint = fingerprint(selected.data());
        List<String> args = List.of(containerId, Integer.toString(playerSlot),
                selected.id(), fingerprint, Integer.toString(amount));
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = requestId("deposit", getStateId(), menuSlot, oneItem ? 1 : 0, fingerprint);
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                requestId, "inventory.container.deposit_exact", "character:" + actor.globalId,
                owner.globalId, args, minute);
        CharacterInventory.Item selectedStack = selected;
        ActionExecutionService.Task task = CharacterInventoryActions.depositExact(
                WorldForgeApi.actionExecution(), request, PeopleMod.authority(), owner, actor,
                containerId, selectedStack.id(), selectedStack.data(), playerSlot, amount, minute,
                dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.getServer()),
                character -> PeopleMod.persistCharacter(character, player.getServer()));
        report(player, task);
    }

    private void report(ServerPlayer player, ActionExecutionService.Task task) {
        if (task.status() != ActionExecutionService.Status.SUCCEEDED) {
            player.sendSystemMessage(Component.literal("Операция с инвентарём не подтверждена: " + task.message()));
            return;
        }
        if (!PlayerInventoryBridge.projectNow(player))
            player.sendSystemMessage(Component.literal(
                    "Имущество сохранено в Character-ledger; физическое отображение будет восстановлено при следующей синхронизации."));
    }

    private void refreshView() {
        view.clearContent();
        List<CharacterInventory.Item> contents = CharacterInventory.containerItems(owner, containerId);
        for (int i = 0; i < Math.min(view.getContainerSize(), contents.size()); i++)
            view.setItem(i, PlayerInventoryBridge.decodeItem(contents.get(i)));
    }

    private String requestId(String action, int state, int slot, int button, String stackFingerprint) {
        String seed = owner.globalId + "|" + containerId + "|" + opener.getUUID() + "|"
                + sessionId + "|" + state + "|" + action + "|" + slot + "|" + button + "|" + stackFingerprint;
        return "inventory-menu:" + UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String fingerprint(String data) {
        return UUID.nameUUIDFromBytes((data == null ? "" : data).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
