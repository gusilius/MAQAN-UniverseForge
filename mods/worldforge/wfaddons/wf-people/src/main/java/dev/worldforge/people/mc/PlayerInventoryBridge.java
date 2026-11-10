package dev.worldforge.people.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.*;

/**
 * Bridges the logged-in Minecraft player's physical inventory to the canonical Character ledger.
 * The Character ledger is the persistent owner; the Minecraft inventory is its live body projection.
 */
public final class PlayerInventoryBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String INITIALIZED = "inventory.minecraft_bridge_initialized";
    private static final String SYNC_DIRTY = "inventory.minecraft_sync_dirty";
    // Main hand is a selected hotbar slot in PlayerInventory, not a second equipment container.
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };
    private static final String[] EQUIPMENT_KEYS = {
            "head", "chest", "legs", "feet", "offhand"
    };
    private static final Set<UUID> SAFE_DEATH_SNAPSHOTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Character character = PeopleMod.playerCharacter(player.getUUID());
            if (character == null) return; // Genesis may create the Character later in this session.
            try {
                if (Boolean.TRUE.equals(character.props.get(INITIALIZED))) {
                    if (!sync(player))
                        throw new IllegalStateException("Player inventory reconciliation is blocked; both saved copies were retained");
                } else importLegacy(player, character);
            } catch (RuntimeException ex) {
                LOGGER.error("wf_people: player inventory bridge could not reconcile {}: {}",
                        player.getGameProfile().getName(), ex.toString(), ex);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Инвентарь персонажа не удалось синхронизировать. Предметы не удалены; сообщите администратору."));
            }
        }
    }

    /** Genesis calls this after creating the canonical player Character and granting starter items. */
    public static void initialize(ServerPlayer player, Character character) {
        if (player == null || character == null) return;
        if (!Boolean.TRUE.equals(character.props.get(INITIALIZED))) importLegacy(player, character);
    }

    /** Short checkpoint interval limits loss of recent player inventory changes on an abnormal stop. */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player
                && player.tickCount % 100 == 0) sync(player);
    }

    /** Snapshot the canonical Character before vanilla death-drop processing can detach its items. */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (sync(player)) SAFE_DEATH_SNAPSHOTS.add(player.getUUID());
            else SAFE_DEATH_SNAPSHOTS.remove(player.getUUID());
        }
    }

    /**
     * Player body death is not Character permadeath: the canonical inventory remains owned by
     * that same Character and is restored on respawn. NPC Character death uses estate succession.
     */
    @SubscribeEvent
    public void onDeathDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && SAFE_DEATH_SNAPSHOTS.contains(player.getUUID())) event.getDrops().clear();
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Character character = PeopleMod.playerCharacter(player.getUUID());
            if (character != null && Boolean.TRUE.equals(character.props.get(INITIALIZED))
                    && SAFE_DEATH_SNAPSHOTS.remove(player.getUUID())) try {
                restore(player, character);
                if (!rebaseAfterRestore(player, character))
                    throw new IllegalStateException("Respawn inventory projection could not be durably saved");
            } catch (RuntimeException ex) {
                LOGGER.error("wf_people: player inventory restore after respawn failed for {}: {}",
                        player.getGameProfile().getName(), ex.toString(), ex);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Не удалось восстановить инвентарь персонажа после смерти; имущество осталось в реестре персонажа."));
            }
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
            SAFE_DEATH_SNAPSHOTS.remove(player.getUUID());
        }
    }

    /** Called before the people registry is written, so online inventory is included in world saves. */
    public static void syncOnline(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sync(player);
    }

    /** Force a journalled reconciliation before a player-facing inventory operation. */
    public static boolean syncNow(ServerPlayer player) { return sync(player); }

    /** Re-project the canonical ledger into the live Minecraft player inventory. */
    public static boolean projectNow(ServerPlayer player) {
        Character character = PeopleMod.playerCharacter(player.getUUID());
        if (character == null) return false;
        try {
            restore(player, character);
            return rebaseAfterRestore(player, character);
        } catch (RuntimeException ex) {
            LOGGER.error("wf_people: could not project canonical inventory for {}: {}",
                    player.getGameProfile().getName(), ex.toString(), ex);
            return false;
        }
    }

    public static ItemStack decodeItem(CharacterInventory.Item item) { return decode(item); }

    private static boolean sync(ServerPlayer player) {
        Character character = PeopleMod.playerCharacter(player.getUUID());
        if (character == null || player.getServer() == null) return false;
        try {
            Snapshot snapshot = capture(player);
            String fingerprint = fingerprint(snapshot);
            boolean initialized = Boolean.TRUE.equals(character.props.get(INITIALIZED));
            String previousSnapshot = String.valueOf(character.props.getOrDefault(
                    "inventory.minecraft_snapshot_fingerprint", "uninitialized"));
            String previousLedger = ledgerFingerprint(character);
            boolean externalLedgerChanged = initialized
                    && !previousLedger.equals(character.props.get("inventory.minecraft_projection_fingerprint"));
            boolean physicalChanged = !initialized
                    || !fingerprint.equals(character.props.get("inventory.minecraft_snapshot_fingerprint"));
            boolean persistenceDirty = Boolean.TRUE.equals(character.props.get(SYNC_DIRTY));
            CharacterInventory.PlayerSyncDecision decision = CharacterInventory.playerSyncDecision(
                    externalLedgerChanged, physicalChanged, persistenceDirty);
            if (decision == CharacterInventory.PlayerSyncDecision.UNCHANGED) return true;
            if (decision == CharacterInventory.PlayerSyncDecision.CONFLICT) {
                // Both copies contain independent changes. Keep both durable snapshots untouched;
                // a deterministic winner cannot be inferred from fingerprints alone.
                character.props.put(SYNC_DIRTY, true);
                if (!PeopleMod.persistCharacter(character, player.getServer()))
                    LOGGER.error("wf_people: could not persist conflict marker for player Character {}", character.globalId);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Конфликт инвентаря: физический инвентарь и Character-ledger изменились независимо. Обе копии сохранены без перезаписи; требуется сверка."));
                return false;
            }

            // Failed journal actions are terminal. A retry therefore receives a fresh request ID;
            // snapshot replacement is idempotent and the dirty bit forces another durable write.
            String seed = character.globalId + "|" + previousSnapshot + "|" + previousLedger + "|" + fingerprint;
            String requestId = "inventory-player-sync:" + UUID.nameUUIDFromBytes(
                    seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ":" + UUID.randomUUID();
            long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
            var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                    requestId, "inventory.player.snapshot", "character:" + character.globalId,
                    character.globalId, List.of(previousSnapshot, previousLedger, fingerprint), minute);
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(
                    request,
                    req -> PeopleMod.playerCharacter(player.getUUID()) == character && !player.isRemoved()
                            ? null : "Player Character is no longer bound to this online player",
                    (req, attempt) -> {
                        character.props.put(SYNC_DIRTY, true);
                        character.props.put(INITIALIZED, true);
                        if (decision == CharacterInventory.PlayerSyncDecision.PROJECT_LEDGER) {
                            restore(player, character);
                            return rebaseAfterRestore(player, character)
                                    ? dev.worldforge.mod.api.ActionResult.ok("Canonical inventory projected")
                                    : dev.worldforge.mod.api.ActionResult.fail("Canonical inventory projection could not be saved");
                        }
                        // IMPORT_PHYSICAL is safe only because the canonical ledger did not diverge.
                        CharacterInventory.replacePlayerSnapshot(character, snapshot.items(), snapshot.equipment());
                        character.props.put("inventory.minecraft_snapshot_fingerprint", fingerprint);
                        character.props.put("inventory.minecraft_projection_fingerprint", ledgerFingerprint(character));
                        character.props.remove(SYNC_DIRTY);
                        boolean persisted = PeopleMod.persistCharacter(character, player.getServer());
                        if (!persisted) character.props.put(SYNC_DIRTY, true);
                        return persisted
                                ? dev.worldforge.mod.api.ActionResult.ok("Player inventory snapshot saved")
                                : dev.worldforge.mod.api.ActionResult.fail("Player inventory snapshot could not be saved");
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.getServer()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED;
        } catch (RuntimeException ex) {
            LOGGER.error("wf_people: player inventory snapshot failed for {}: {}",
                    player.getGameProfile().getName(), ex.toString(), ex);
            return false;
        }
    }

    private static void importLegacy(ServerPlayer player, Character character) {
        Snapshot physical = capture(player);
        // One-time migration: combine old Character-owned entries with the vanilla player save
        // using the maximum count for an identical stack, avoiding duplication of already-ledgered
        // Genesis/domain grants while preserving distinct NBT variants.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, CharacterInventory.Item> stacks = new LinkedHashMap<>();
        for (CharacterInventory.Item item : CharacterInventory.items(character)) mergeMax(stacks, counts, item);
        for (CharacterInventory.Item item : physical.items()) mergeMax(stacks, counts, item);
        List<CharacterInventory.Item> merged = new ArrayList<>();
        for (Map.Entry<String, CharacterInventory.Item> entry : stacks.entrySet()) {
            CharacterInventory.Item item = entry.getValue();
            merged.add(new CharacterInventory.Item(item.id(), counts.get(entry.getKey()), item.data(), item.slot()));
        }
        Map<String, CharacterInventory.Item> equipment = new TreeMap<>(CharacterInventory.equipment(character));
        for (Map.Entry<String, CharacterInventory.Item> entry : physical.equipment().entrySet())
            equipment.putIfAbsent(entry.getKey(), entry.getValue());
        CharacterInventory.replaceSnapshot(character, merged, equipment);
        character.props.put(INITIALIZED, true);
        restore(player, character);
        if (!rebaseAfterRestore(player, character))
            throw new IllegalStateException("Player Character inventory snapshot could not be durably saved");
    }

    private static boolean rebaseAfterRestore(ServerPlayer player, Character character) {
        character.props.put(SYNC_DIRTY, true);
        Snapshot projected = capture(player);
        CharacterInventory.replaceSnapshot(character, projected.items(), projected.equipment());
        String fingerprint = fingerprint(projected);
        character.props.put("inventory.minecraft_snapshot_fingerprint", fingerprint);
        character.props.put("inventory.minecraft_projection_fingerprint", ledgerFingerprint(character));
        character.props.remove(SYNC_DIRTY);
        boolean persisted = PeopleMod.persistCharacter(character, player.getServer());
        if (!persisted) character.props.put(SYNC_DIRTY, true);
        return persisted;
    }

    private static String ledgerFingerprint(Character character) {
        StringBuilder value = new StringBuilder();
        for (CharacterInventory.Item item : CharacterInventory.items(character)) {
            value.append(item.slot()).append('|').append(item.id()).append('|')
                    .append(item.count()).append('|').append(item.data()).append('\n');
        }
        for (Map.Entry<String, CharacterInventory.Item> entry : new TreeMap<>(CharacterInventory.equipment(character)).entrySet()) {
            CharacterInventory.Item item = entry.getValue();
            value.append(entry.getKey()).append('|').append(item.id()).append('|')
                    .append(item.count()).append('|').append(item.data()).append('\n');
        }
        return UUID.nameUUIDFromBytes(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String fingerprint(Snapshot snapshot) {
        StringBuilder value = new StringBuilder();
        for (CharacterInventory.Item item : snapshot.items()) {
            value.append(item.slot()).append('|').append(item.id()).append('|')
                    .append(item.count()).append('|').append(item.data()).append('\n');
        }
        for (Map.Entry<String, CharacterInventory.Item> entry : new TreeMap<>(snapshot.equipment()).entrySet()) {
            CharacterInventory.Item item = entry.getValue();
            value.append(entry.getKey()).append('|').append(item.id()).append('|')
                    .append(item.count()).append('|').append(item.data()).append('\n');
        }
        return UUID.nameUUIDFromBytes(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static void mergeMax(Map<String, CharacterInventory.Item> stacks,
                                 Map<String, Integer> counts, CharacterInventory.Item item) {
        String key = item.slot() + "\u0000" + item.id() + "\u0000" + item.data();
        stacks.putIfAbsent(key, item);
        counts.merge(key, item.count(), Math::max);
    }

    private static Snapshot capture(ServerPlayer player) {
        List<CharacterInventory.Item> items = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            if (stack.isEmpty()) continue;
            items.add(encode(stack, slot));
        }
        Map<String, CharacterInventory.Item> equipment = new TreeMap<>();
        for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
            ItemStack stack = player.getItemBySlot(EQUIPMENT_SLOTS[i]);
            if (!stack.isEmpty()) equipment.put(EQUIPMENT_KEYS[i], encode(stack));
        }
        return new Snapshot(items, equipment);
    }

    private static CharacterInventory.Item encode(ItemStack stack) { return encode(stack, -1); }

    private static CharacterInventory.Item encode(ItemStack stack, int slot) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) throw new IllegalStateException("Unregistered item in player inventory");
        return new CharacterInventory.Item(id.toString(), stack.getCount(), stack.save(new CompoundTag()).toString(), slot);
    }

    private static void restore(ServerPlayer player, Character character) {
        ItemStack[] main = new ItemStack[player.getInventory().items.size()];
        Arrays.fill(main, ItemStack.EMPTY);
        for (CharacterInventory.Item item : CharacterInventory.items(character)) {
            List<ItemStack> decoded = new ArrayList<>();
            appendDecoded(decoded, item);
            if (item.slot() >= 0) {
                if (decoded.size() != 1 || !main[item.slot()].isEmpty())
                    throw new IllegalStateException("Invalid or duplicate saved player inventory slot " + item.slot());
                main[item.slot()] = decoded.get(0);
            } else {
                for (ItemStack stack : decoded) {
                    int free = -1;
                    for (int i = 0; i < main.length; i++) if (main[i].isEmpty()) { free = i; break; }
                    if (free < 0) throw new IllegalStateException("Character inventory exceeds player capacity; refusing partial restore");
                    main[free] = stack;
                }
            }
        }

        Map<String, CharacterInventory.Item> equipment = CharacterInventory.equipment(character);
        Map<String, ItemStack> decodedEquipment = new HashMap<>();
        for (int i = 0; i < EQUIPMENT_KEYS.length; i++) {
            CharacterInventory.Item item = equipment.get(EQUIPMENT_KEYS[i]);
            if (item != null) decodedEquipment.put(EQUIPMENT_KEYS[i], decode(item));
        }

        // All stacks are decoded and capacity-checked before the live inventory is mutated.
        player.getInventory().clearContent();
        for (int i = 0; i < main.length; i++) player.getInventory().items.set(i, main[i]);
        for (int i = 0; i < EQUIPMENT_SLOTS.length; i++) {
            ItemStack stack = decodedEquipment.getOrDefault(EQUIPMENT_KEYS[i], ItemStack.EMPTY);
            player.setItemSlot(EQUIPMENT_SLOTS[i], stack);
        }
    }

    private static void appendDecoded(List<ItemStack> result, CharacterInventory.Item item) {
        ItemStack stack = decode(item);
        int remaining = stack.getCount();
        while (remaining > 0) {
            int amount = Math.min(remaining, stack.getMaxStackSize());
            ItemStack part = stack.copy();
            part.setCount(amount);
            result.add(part);
            remaining -= amount;
        }
    }

    private static ItemStack decode(CharacterInventory.Item item) {
        ResourceLocation id = ResourceLocation.tryParse(item.id());
        if (id == null) throw new IllegalStateException("Invalid item id " + item.id());
        Item registered = BuiltInRegistries.ITEM.get(id);
        if (registered == net.minecraft.world.item.Items.AIR)
            throw new IllegalStateException("Unknown item " + item.id());
        try {
            if (!item.data().isBlank()) {
                CompoundTag tag = TagParser.parseTag(item.data());
                if (tag.contains("id")) {
                    ItemStack decoded = ItemStack.of(tag);
                    if (decoded.isEmpty() || !id.equals(BuiltInRegistries.ITEM.getKey(decoded.getItem())))
                        throw new IllegalStateException("Stored item NBT ID does not match canonical item ID " + item.id());
                    decoded.setCount(item.count());
                    return decoded;
                }
                ItemStack decoded = new ItemStack(registered, item.count());
                decoded.setTag(tag);
                return decoded;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid item NBT for " + item.id(), ex);
        }
        return new ItemStack(registered, item.count());
    }

    private record Snapshot(List<CharacterInventory.Item> items,
                            Map<String, CharacterInventory.Item> equipment) { }
}
