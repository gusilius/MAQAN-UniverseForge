package dev.worldforge.people.mc;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import dev.worldforge.people.CharacterInventoryActions;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkHooks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real chest/barrel and command entry points for Character-owned shared containers. */
public final class CharacterInventoryEvents {
    private static final String OWNER_KEY = "worldforge_inventory_owner_global_id";
    private static final String CONTAINER_KEY = "worldforge_inventory_container_id";
    private static final String SOURCE_KEY = "worldforge_inventory_source_key";

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() != LogicalSide.SERVER || !(event.getEntity() instanceof ServerPlayer player)) return;
        var state = event.getLevel().getBlockState(event.getPos());
        boolean chestBlock = state.getBlock() instanceof ChestBlock;
        boolean barrelBlock = state.getBlock() instanceof BarrelBlock;
        if (!chestBlock && !barrelBlock) return;

        BlockPos clickedPos = event.getPos().immutable();
        BlockPos partnerPos = null;
        BlockEntity clickedEntity = event.getLevel().getBlockEntity(clickedPos);
        BlockEntity partnerEntity = null;
        Container physical;
        BlockPos anchorPos = clickedPos;

        if (chestBlock) {
            ChestBlock chest = (ChestBlock) state.getBlock();
            physical = ChestBlock.getContainer(chest, state, event.getLevel(), clickedPos, false);
            if (physical == null) return; // Preserve vanilla's blocked-chest rule.
            if (state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                partnerPos = clickedPos.relative(ChestBlock.getConnectedDirection(state)).immutable();
                partnerEntity = event.getLevel().getBlockEntity(partnerPos);
                if (!(clickedEntity instanceof Container) || !(partnerEntity instanceof Container)) {
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.FAIL);
                    return;
                }
                anchorPos = comparePosition(clickedPos, partnerPos) <= 0 ? clickedPos : partnerPos;
            }
        } else {
            if (!(clickedEntity instanceof Container container)) return;
            physical = container;
        }
        if (!(clickedEntity instanceof BlockEntity) || clickedEntity == null) return;

        Character actor = PeopleMod.playerCharacter(player.getUUID());
        if (actor == null) return; // Vanilla storage remains available before Genesis creates the canonical player Character.
        if (!PeopleMod.identityPartitionsReady()) {
            player.sendSystemMessage(Component.literal("Общие хранилища закрыты: не все реестры персонажей восстановлены."));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        BlockEntity anchorEntity = anchorPos.equals(clickedPos) ? clickedEntity : partnerEntity;
        BlockEntity otherEntity = partnerPos == null ? null
                : (anchorEntity == clickedEntity ? partnerEntity : clickedEntity);
        CompoundTag anchorData = anchorEntity.getPersistentData();
        CompoundTag otherData = otherEntity == null ? null : otherEntity.getPersistentData();

        String ownerId = compatibleMetadata(anchorData, otherData, OWNER_KEY);
        String containerId = compatibleMetadata(anchorData, otherData, CONTAINER_KEY);
        String sourceKey = compatibleMetadata(anchorData, otherData, SOURCE_KEY);
        if (ownerId == null || containerId == null || sourceKey == null) {
            player.sendSystemMessage(Component.literal("У половин сундука противоречивые метаданные; доступ заблокирован, чтобы не потерять предметы."));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        boolean anyMetadata = !ownerId.isBlank() || !containerId.isBlank() || !sourceKey.isBlank();
        boolean completeMetadata = !ownerId.isBlank() && !containerId.isBlank() && !sourceKey.isBlank();
        if (anyMetadata && !completeMetadata) {
            player.sendSystemMessage(Component.literal("У контейнера неполные метаданные владельца; доступ заблокирован до восстановления."));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (!anyMetadata) {
            sourceKey = sourceKey(event.getLevel().dimension().location().toString(), anchorPos);
            containerId = "block_" + UUID.nameUUIDFromBytes(sourceKey.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
            if (!claim(player, actor, physical, containerId, sourceKey)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                return;
            }
            ownerId = actor.globalId;
        } else {
            Character owner = PeopleMod.authority().getGlobal(ownerId);
            if (owner != null && !owner.alive) {
                String heirId = String.valueOf(owner.props.getOrDefault("estate.recipient_global_id", ""));
                Character heir = PeopleMod.authority().getGlobal(heirId);
                if (heir != null && CharacterInventory.hasContainer(heir, containerId)) {
                    owner = heir;
                    ownerId = heir.globalId;
                }
            }
            if (owner == null || !CharacterInventory.hasContainer(owner, containerId)
                    || !CharacterInventory.canAccessContainer(owner, containerId, actor)) {
                player.sendSystemMessage(Component.literal("Контейнер принадлежит другому персонажу или доступ не предоставлен."));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                return;
            }
        }

        Character owner = PeopleMod.authority().getGlobal(ownerId);
        if (owner == null || !CharacterInventory.canAccessContainer(owner, containerId, actor)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        try {
            // Validate the complete canonical contents before touching either physical chest half.
            CharacterInventory.containerItems(owner, containerId);
        } catch (RuntimeException corruptLedger) {
            player.sendSystemMessage(Component.literal(
                    "Хранилище заблокировано: данные предметов повреждены. Физические блоки не очищены."));
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        // Both halves carry the same durable owner/container/source identity. If a chunk save
        // persisted only one half, the next interaction repairs the missing half from the other.
        writeMetadata(anchorEntity, ownerId, containerId, sourceKey);
        if (otherEntity != null) writeMetadata(otherEntity, ownerId, containerId, sourceKey);
        clearPhysical(physical, clickedEntity, otherEntity);

        SimpleContainer view = new SimpleContainer(54);
        Character resolvedOwner = owner;
        String resolvedContainer = containerId;
        BlockPos menuPosition = anchorPos.immutable();
        BlockPos menuPartner = partnerPos == null ? null : partnerPos.immutable();
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (id, playerInventory, ignored) -> new SharedCharacterContainerMenu(
                        id, playerInventory, player, resolvedOwner, resolvedContainer, menuPosition, menuPartner, view),
                Component.literal("Character Storage")));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static String compatibleMetadata(CompoundTag primary, CompoundTag secondary, String key) {
        String first = primary.getString(key);
        String second = secondary == null ? "" : secondary.getString(key);
        if (!first.isBlank() && !second.isBlank() && !first.equals(second)) return null;
        return first.isBlank() ? second : first;
    }

    private static void writeMetadata(BlockEntity entity, String ownerId, String containerId, String sourceKey) {
        if (entity == null) return;
        CompoundTag data = entity.getPersistentData();
        data.putString(OWNER_KEY, ownerId);
        data.putString(CONTAINER_KEY, containerId);
        data.putString(SOURCE_KEY, sourceKey);
        entity.setChanged();
    }

    private static int comparePosition(BlockPos left, BlockPos right) {
        int x = Integer.compare(left.getX(), right.getX());
        if (x != 0) return x;
        int z = Integer.compare(left.getZ(), right.getZ());
        if (z != 0) return z;
        return Integer.compare(left.getY(), right.getY());
    }

    private static boolean claim(ServerPlayer player, Character owner, Container physical,
                                 String containerId, String sourceKey) {
        if (CharacterInventory.hasContainerImportSource(owner, containerId, sourceKey))
            return PeopleMod.persistCharacter(owner, player.getServer());
        List<CharacterInventory.Item> stacks = new ArrayList<>();
        for (int i = 0; i < physical.getContainerSize(); i++) {
            ItemStack stack = physical.getItem(i);
            if (stack.isEmpty()) continue;
            var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) return false;
            stacks.add(new CharacterInventory.Item(key.toString(), stack.getCount(),
                    stack.save(new CompoundTag()).toString()));
        }
        String requestId = "inventory-block-claim:" + UUID.nameUUIDFromBytes(
                sourceKey.getBytes(StandardCharsets.UTF_8));
        List<String> args = List.of(containerId, sourceKey, Integer.toString(stacks.size()));
        long minute = WorldForgeApi.clock().worldMinute();
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                requestId, "inventory.container.claim", "character:" + owner.globalId,
                owner.globalId, args, minute);
        ActionExecutionService.Task task = CharacterInventoryActions.executeDurable(
                WorldForgeApi.actionExecution(), request, "inventory.container.claim", owner, owner,
                args, minute, WorldForgeMod.actionExecutionFile(player.getServer()),
                () -> CharacterInventory.importContainerItems(owner, containerId, sourceKey, stacks),
                () -> PeopleMod.persistCharacter(owner, player.getServer()),
                "Physical container imported");
        if (task.status() != ActionExecutionService.Status.SUCCEEDED
                && !CharacterInventory.hasContainerImportSource(owner, containerId, sourceKey)) {
            player.sendSystemMessage(Component.literal("Не удалось принять содержимое: " + task.message()));
            return false;
        }
        return CharacterInventory.hasContainerImportSource(owner, containerId, sourceKey)
                && PeopleMod.persistCharacter(owner, player.getServer());
    }

    private static void clearPhysical(Container physical, BlockEntity first, BlockEntity second) {
        for (int i = 0; i < physical.getContainerSize(); i++) physical.setItem(i, ItemStack.EMPTY);
        if (first != null) first.setChanged();
        if (second != null) second.setChanged();
    }

    private static String sourceKey(String dimension, BlockPos pos) {
        String seed = WorldForgeMod.worldId() + "|" + dimension + "|"
                + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("wfinventory")
                        .then(Commands.literal("share")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> changeAccess(
                                                context.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(context, "player"), true))))
                        .then(Commands.literal("unshare")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> changeAccess(
                                                context.getSource().getPlayerOrException(),
                                                EntityArgument.getPlayer(context, "player"), false)))));
    }

    private static int changeAccess(ServerPlayer player, ServerPlayer target, boolean grant) {
        if (!(player.containerMenu instanceof SharedCharacterContainerMenu menu)) {
            player.sendSystemMessage(Component.literal("Сначала откройте управляемый общий контейнер."));
            return 0;
        }
        Character owner = PeopleMod.authority().getGlobal(menu.ownerGlobalId());
        Character actor = PeopleMod.playerCharacter(player.getUUID());
        Character grantee = PeopleMod.playerCharacter(target.getUUID());
        if (owner == null || actor == null || grantee == null || !owner.globalId.equals(actor.globalId)) {
            player.sendSystemMessage(Component.literal("Доступ может менять только персонаж-владелец; оба аккаунта должны иметь Genesis-персонажа."));
            return 0;
        }
        String actionId = grant ? "inventory.container.grant_access" : "inventory.container.revoke_access";
        List<String> args = List.of(menu.containerId(), grantee.globalId);
        long minute = WorldForgeApi.clock().worldMinute();
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                "inventory-access:" + UUID.randomUUID(), actionId, "character:" + actor.globalId,
                owner.globalId, args, minute);
        ActionExecutionService.Task task = CharacterInventoryActions.executeDurable(
                WorldForgeApi.actionExecution(), request, actionId, owner, actor, args, minute,
                WorldForgeMod.actionExecutionFile(player.getServer()),
                () -> grant
                        ? CharacterInventory.grantContainerAccess(owner, menu.containerId(), grantee.globalId)
                        : CharacterInventory.revokeContainerAccess(owner, menu.containerId(), grantee.globalId),
                () -> PeopleMod.persistCharacter(owner, player.getServer()),
                grant ? "Access granted" : "Access revoked");
        if (task.status() != ActionExecutionService.Status.SUCCEEDED) {
            player.sendSystemMessage(Component.literal("Не удалось изменить доступ: " + task.message()));
            return 0;
        }
        player.sendSystemMessage(Component.literal(grant ? "Доступ предоставлен." : "Доступ отозван."));
        return 1;
    }
}
