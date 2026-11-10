package dev.worldforge.people;

import java.util.*;

/**
 * One persistent item ledger owned by the canonical Character.
 * Minecraft entities are projections of this inventory, never its owner.
 */
public final class CharacterInventory {
    private static final String KEY = "inventory.items";
    private CharacterInventory() {}

    /** Result of reconciling the durable Character ledger with the live Minecraft body snapshot. */
    public enum PlayerSyncDecision { UNCHANGED, IMPORT_PHYSICAL, PROJECT_LEDGER, CONFLICT }

    /**
     * The ledger wins only when the physical projection is unchanged; the physical body may be
     * imported only when the ledger has not independently changed. Never guess when both diverge.
     */
    public static PlayerSyncDecision playerSyncDecision(boolean externalLedgerChanged,
                                                        boolean physicalChanged,
                                                        boolean persistenceDirty) {
        if (externalLedgerChanged && physicalChanged) return PlayerSyncDecision.CONFLICT;
        if (externalLedgerChanged) return PlayerSyncDecision.PROJECT_LEDGER;
        if (physicalChanged || persistenceDirty) return PlayerSyncDecision.IMPORT_PHYSICAL;
        return PlayerSyncDecision.UNCHANGED;
    }

    public record Item(String id,int count,String data,int slot) {
        public Item(String id,int count,String data) { this(id,count,data,-1); }
        public Item {
            id=validId(id);
            if(count<1||count>1_000_000)throw new IllegalArgumentException("invalid item count");
            data=data==null?"":data;
            if(data.length()>32_768)throw new IllegalArgumentException("item data too large");
            if(slot < -1 || slot > 35)throw new IllegalArgumentException("invalid inventory slot");
        }
    }

    public static List<Item> items(Character person) {
        Objects.requireNonNull(person);
        Object raw = person.props.get(KEY);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list))
            throw new IllegalStateException("Character inventory is not a list: " + person.globalId);
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            result.add(decodeItemRecord(list.get(i), "Character inventory entry " + i, true));
        return Collections.unmodifiableList(result);
    }

    public static int count(Character person,String itemId) {
        String id=validId(itemId);
        int total=0;
        for(Item item:items(person))if(item.id().equals(id))total=Math.addExact(total,item.count());
        return total;
    }

    public static void add(Character person,String itemId,int amount) {
        add(person,itemId,amount,"");
    }

    /**
     * Replaces the canonical inventory/equipment snapshot after validating every stack.
     * Used by persistence bridges; callers must serialize one authoritative body at a time.
     */
    public static void replaceSnapshot(Character person, Collection<Item> inventory, Map<String, Item> equipment) {
        Objects.requireNonNull(person); Objects.requireNonNull(inventory); Objects.requireNonNull(equipment);
        List<Item> nextItems = new ArrayList<>();
        for (Item item : inventory) nextItems.add(new Item(item.id(), item.count(), item.data(), item.slot()));
        nextItems.sort(Comparator.comparingInt((Item item) -> item.slot() < 0 ? Integer.MAX_VALUE : item.slot())
                .thenComparing(Item::id).thenComparing(Item::data));
        Map<String, Item> nextEquipment = new TreeMap<>();
        for (Map.Entry<String, Item> entry : equipment.entrySet()) {
            if (!EQUIPMENT_SLOTS.contains(entry.getKey())) throw new IllegalArgumentException("invalid equipment slot");
            Item item = new Item(entry.getValue().id(), entry.getValue().count(), entry.getValue().data());
            if (item.count() != 1) throw new IllegalArgumentException("equipment stack must contain exactly one item");
            nextEquipment.put(entry.getKey(), item);
        }
        write(person, nextItems);
        writeEquipment(person, nextEquipment);
    }

    /**
     * Reconciles the physical player inventory without deleting items granted to the Character
     * by other domain systems. Slotless entries are externally owned additions waiting to be
     * projected into the live player inventory; slotted entries are the previous body snapshot.
     */
    public static void replacePlayerSnapshot(Character person, Collection<Item> inventory,
                                              Map<String, Item> equipment) {
        Objects.requireNonNull(person); Objects.requireNonNull(inventory); Objects.requireNonNull(equipment);
        List<Item> next = new ArrayList<>();
        for (Item item : items(person)) if (item.slot() < 0) next.add(item);
        Set<Integer> usedSlots = new HashSet<>();
        for (Item item : inventory) {
            if (item.slot() < 0 || !usedSlots.add(item.slot()))
                throw new IllegalArgumentException("player snapshot contains an invalid or duplicate slot");
            next.add(new Item(item.id(), item.count(), item.data(), item.slot()));
        }
        next.sort(Comparator.comparingInt((Item item) -> item.slot() < 0 ? Integer.MAX_VALUE : item.slot())
                .thenComparing(Item::id).thenComparing(Item::data));
        Map<String, Item> slots = new TreeMap<>(equipment(person));
        for (String slot : Set.of("head", "chest", "legs", "feet", "offhand")) slots.remove(slot);
        for (Map.Entry<String, Item> entry : equipment.entrySet()) {
            if (!Set.of("head", "chest", "legs", "feet", "offhand").contains(entry.getKey()))
                throw new IllegalArgumentException("invalid player equipment slot");
            slots.put(entry.getKey(), new Item(entry.getValue().id(), 1, entry.getValue().data()));
        }
        write(person, next);
        writeEquipment(person, slots);
    }

    /** Items with different NBT payloads remain separate stacks. */
    public static void add(Character person,String itemId,int amount,String data) {
        Objects.requireNonNull(person);
        Item added=new Item(itemId,amount,data);
        List<Item> next=new ArrayList<>(items(person));
        for(int i=0;i<next.size();i++) {
            Item old=next.get(i);
            if(old.slot() == -1 && added.slot() == -1 && old.id().equals(added.id())&&old.data().equals(added.data())) {
                next.set(i,new Item(old.id(),Math.addExact(old.count(),amount),old.data()));
                write(person,next);return;
            }
        }
        next.add(added);
        next.sort(Comparator.comparing(Item::id).thenComparing(Item::data));
        write(person,next);
    }

    /** Removes exactly amount or leaves the inventory untouched. */
    public static boolean remove(Character person,String itemId,int amount) {
        Objects.requireNonNull(person);
        String id=validId(itemId);
        if(amount<1)return false;
        if(count(person,id)<amount)return false;
        int remaining=amount;
        List<Item> next=new ArrayList<>();
        for(Item item:items(person)) {
            if(!item.id().equals(id)){next.add(item);continue;}
            int take=Math.min(remaining,item.count());
            remaining-=take;
            if(item.count()>take)next.add(new Item(item.id(),item.count()-take,item.data(),item.slot()));
        }
        write(person,next);
        return true;
    }

    /** Atomic logical transfer: source is changed only after destination accepts the item. */
    public static boolean transfer(Character from,Character to,String itemId,int amount) {
        Objects.requireNonNull(from);Objects.requireNonNull(to);
        String id=validId(itemId);
        if(from==to||amount<1||count(from,id)<amount)return false;
        int remaining=amount;
        List<Item> sourceNext=new ArrayList<>();
        List<Item> payload=new ArrayList<>();
        for(Item item:items(from)) {
            if(!item.id().equals(id)||remaining==0){sourceNext.add(item);continue;}
            int take=Math.min(remaining,item.count());
            payload.add(new Item(item.id(),take,item.data()));
            if(item.count()>take)sourceNext.add(new Item(item.id(),item.count()-take,item.data(),item.slot()));
            remaining-=take;
        }
        if(remaining!=0)return false;
        List<Item> destinationNext=new ArrayList<>(items(to));
        for(Item moved:payload) {
            boolean merged=false;
            for(int i=0;i<destinationNext.size();i++) {
                Item existing=destinationNext.get(i);
                if(existing.slot() == -1 && existing.id().equals(moved.id())&&existing.data().equals(moved.data())) {
                    destinationNext.set(i,new Item(existing.id(),Math.addExact(existing.count(),moved.count()),existing.data()));
                    merged=true;break;
                }
            }
            if(!merged)destinationNext.add(moved);
        }
        destinationNext.sort(Comparator.comparing(Item::id).thenComparing(Item::data));
        // Commit both sides only after every validation and overflow check has succeeded.
        write(to,destinationNext);
        write(from,sourceNext);
        return true;
    }

    private static final String EQUIPMENT_KEY = "equipment.slots";
    private static final Set<String> EQUIPMENT_SLOTS = Set.of("head", "chest", "legs", "feet", "mainhand", "offhand");

    /** Read-only equipment snapshot; item NBT remains owned by the Character. */
    public static Map<String, Item> equipment(Character person) {
        Objects.requireNonNull(person);
        Object raw = person.props.get(EQUIPMENT_KEY);
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalStateException("Character equipment is not a map: " + person.globalId);
        Map<String, Item> result = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String slot) || !EQUIPMENT_SLOTS.contains(slot))
                throw new IllegalStateException("Character equipment contains an invalid slot: " + person.globalId);
            Item item = decodeItemRecord(entry.getValue(), "Character equipment slot " + slot, false);
            if (item.count() != 1)
                throw new IllegalStateException("Character equipment slot must contain exactly one item: " + slot);
            result.put(slot, item);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Updates an equipment projection from a physical item stack. Callers choose whether a replaced
     * item is returned to inventory; durability/NBT updates pass false, crafting swaps pass true.
     */
    public static void setEquipment(Character person, String slot, Item item, boolean returnPrevious) {
        Objects.requireNonNull(person); Objects.requireNonNull(item);
        if (!EQUIPMENT_SLOTS.contains(slot)) throw new IllegalArgumentException("invalid equipment slot");
        if (item.count() != 1) throw new IllegalArgumentException("equipment stack must contain exactly one item");
        Map<String, Item> slots = new TreeMap<>(equipment(person));
        Item previous = slots.get(slot);
        List<Item> inventory = new ArrayList<>(items(person));
        if (previous != null && returnPrevious && !previous.equals(item))
            inventory = mergeStacks(inventory, List.of(previous));
        slots.put(slot, item);
        write(person, inventory);
        writeEquipment(person, slots);
    }

    /** Equips one owned item and returns any previous equipment to the same Character inventory. */
    public static boolean equip(Character person, String slot, String itemId) {
        Objects.requireNonNull(person);
        if (!EQUIPMENT_SLOTS.contains(slot)) throw new IllegalArgumentException("invalid equipment slot");
        String id = validId(itemId);
        List<Item> inventory = new ArrayList<>(items(person));
        int found = -1;
        for (int i = 0; i < inventory.size(); i++) if (inventory.get(i).id().equals(id)) { found = i; break; }
        if (found < 0) return false;
        Item selected = inventory.get(found);
        List<Item> remaining = new ArrayList<>(inventory);
        if (selected.count() == 1) remaining.remove(found);
        else remaining.set(found, new Item(selected.id(), selected.count() - 1, selected.data(), selected.slot()));
        Map<String, Item> slots = new TreeMap<>(equipment(person));
        Item previous = slots.put(slot, new Item(selected.id(), 1, selected.data()));
        if (previous != null) remaining = mergeStacks(remaining, List.of(previous));
        write(person, remaining);
        writeEquipment(person, slots);
        return true;
    }

    /** Removes a physically broken or consumed equipment item without returning it to inventory. */
    public static boolean discardEquipment(Character person, String slot) {
        Objects.requireNonNull(person);
        if (!EQUIPMENT_SLOTS.contains(slot)) throw new IllegalArgumentException("invalid equipment slot");
        Map<String, Item> slots = new TreeMap<>(equipment(person));
        if (slots.remove(slot) == null) return false;
        writeEquipment(person, slots);
        return true;
    }

    /** Unequips a slot into inventory without discarding item NBT. */
    public static boolean unequip(Character person, String slot) {
        Objects.requireNonNull(person);
        if (!EQUIPMENT_SLOTS.contains(slot)) throw new IllegalArgumentException("invalid equipment slot");
        Map<String, Item> slots = new TreeMap<>(equipment(person));
        Item previous = slots.remove(slot);
        if (previous == null) return false;
        List<Item> inventory = mergeStacks(items(person), List.of(previous));
        write(person, inventory);
        writeEquipment(person, slots);
        return true;
    }

    private static void writeEquipment(Character person, Map<String, Item> equipment) {
        Map<String, Object> encoded = new TreeMap<>();
        for (Map.Entry<String, Item> entry : equipment.entrySet()) {
            Item item = entry.getValue();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.id()); value.put("count", item.count()); value.put("data", item.data());
            encoded.put(entry.getKey(), value);
        }
        if (encoded.isEmpty()) person.props.remove(EQUIPMENT_KEY);
        else person.props.put(EQUIPMENT_KEY, encoded);
    }

    /**
     * Creates a durable shared container on its canonical Character owner. Access is granted
     * by stable global Character UUIDs; the owner always retains access.
     */
    public static boolean createContainer(Character owner, String containerId, Collection<String> accessGlobalIds) {
        Objects.requireNonNull(owner);
        String id = validContainerId(containerId);
        Map<String, Object> containers = containers(owner);
        if (containers.containsKey(id)) return false;
        List<String> access = new ArrayList<>();
        if (accessGlobalIds != null) for (String globalId : accessGlobalIds) {
            String canonical = UUID.fromString(Objects.requireNonNull(globalId)).toString();
            if (!canonical.equals(owner.globalId) && !access.contains(canonical)) access.add(canonical);
        }
        Collections.sort(access);
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("owner_global_id", owner.globalId);
        container.put("access_global_ids", access);
        container.put("items", List.of());
        containers.put(id, container);
        owner.props.put(CONTAINERS_KEY, containers);
        return true;
    }

    private static final String CONTAINERS_KEY = "inventory.containers";

    /** Read-only item snapshot from a shared container. */
    public static List<Item> containerItems(Character owner, String containerId) {
        Map<String, Object> container = container(owner, containerId);
        return container == null ? List.of() : decodeItems(container.get("items"));
    }

    public static boolean hasContainer(Character owner, String containerId) {
        return owner != null && container(owner, containerId) != null;
    }

    public static boolean hasContainerImportSource(Character owner, String containerId, String sourceKey) {
        Map<String, Object> entry = container(owner, containerId);
        if (entry == null) return false;
        Object raw = entry.get("legacy_import_sources");
        return raw instanceof List<?> sources && sources.contains(sourceKey);
    }

    /** Imports a physical container exactly once; the source marker is committed with its items. */
    public static boolean importContainerItems(Character owner, String containerId, String sourceKey,
                                               Collection<Item> sourceItems) {
        Objects.requireNonNull(owner);
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        Objects.requireNonNull(sourceItems, "sourceItems");
        if (!hasContainer(owner, containerId) && !createContainer(owner, containerId, List.of())) return false;
        Map<String, Object> entry = container(owner, containerId);
        if (entry == null) return false;
        Object raw = entry.get("legacy_import_sources");
        List<String> sources = new ArrayList<>();
        if (raw instanceof List<?> values)
            for (Object value : values) if (value instanceof String key && !key.isBlank()) sources.add(key);
        if (sources.contains(sourceKey)) return true;
        List<Item> imported = new ArrayList<>();
        for (Item item : sourceItems) imported.add(new Item(item.id(), item.count(), item.data()));
        entry.put("items", encodeItems(mergeStacks(decodeItems(entry.get("items")), imported)));
        sources.add(sourceKey);
        entry.put("legacy_import_sources", sources);
        Map<String, Object> all = containers(owner);
        all.put(validContainerId(containerId), entry);
        owner.props.put(CONTAINERS_KEY, all);
        return true;
    }

    public static boolean grantContainerAccess(Character owner, String containerId, String globalId) {
        Objects.requireNonNull(owner);
        String canonical = UUID.fromString(Objects.requireNonNull(globalId)).toString();
        Map<String, Object> entry = container(owner, containerId);
        if (entry == null || !owner.globalId.equals(entry.get("owner_global_id"))) return false;
        if (canonical.equals(owner.globalId)) return true;
        List<String> access = new ArrayList<>();
        Object raw = entry.get("access_global_ids");
        if (raw instanceof List<?> values)
            for (Object value : values) if (value instanceof String id && !access.contains(id)) access.add(id);
        if (!access.contains(canonical)) access.add(canonical);
        Collections.sort(access);
        entry.put("access_global_ids", access);
        Map<String, Object> all = containers(owner);
        all.put(validContainerId(containerId), entry);
        owner.props.put(CONTAINERS_KEY, all);
        return true;
    }

    public static boolean revokeContainerAccess(Character owner, String containerId, String globalId) {
        Objects.requireNonNull(owner);
        String canonical = UUID.fromString(Objects.requireNonNull(globalId)).toString();
        Map<String, Object> entry = container(owner, containerId);
        if (entry == null || !owner.globalId.equals(entry.get("owner_global_id"))) return false;
        List<String> access = new ArrayList<>();
        Object raw = entry.get("access_global_ids");
        if (raw instanceof List<?> values)
            for (Object value : values) if (value instanceof String id && !id.equals(canonical)) access.add(id);
        entry.put("access_global_ids", access);
        Map<String, Object> all = containers(owner);
        all.put(validContainerId(containerId), entry);
        owner.props.put(CONTAINERS_KEY, all);
        return true;
    }

    public static boolean canAccessContainer(Character owner, String containerId, Character actor) {
        return containerAccess(owner, containerId, actor);
    }

    /** Exact NBT-aware deposit from one physical player inventory slot. */
    public static boolean depositExact(Character owner, String containerId, Character actor,
                                       String itemId, String data, int playerSlot, int amount) {
        return moveContainerItemExact(owner, containerId, actor, itemId, data, playerSlot, amount, true);
    }

    /** Exact NBT-aware withdrawal; a stale menu cannot take another variant of the same item. */
    public static boolean withdrawExact(Character owner, String containerId, Character actor,
                                        String itemId, String data, int amount) {
        return moveContainerItemExact(owner, containerId, actor, itemId, data, -1, amount, false);
    }

    private static boolean moveContainerItemExact(Character owner, String containerId, Character actor,
                                                   String itemId, String data, int requiredSlot,
                                                   int amount, boolean deposit) {
        Objects.requireNonNull(owner); Objects.requireNonNull(actor);
        String item = validId(itemId);
        String payloadData = data == null ? "" : data;
        if (amount < 1 || !containerAccess(owner, containerId, actor)) return false;
        Map<String, Object> stored = container(owner, containerId);
        if (stored == null) return false;
        List<Item> actorItems = new ArrayList<>(items(actor));
        List<Item> storedItems = new ArrayList<>(decodeItems(stored.get("items")));
        List<Item> source = deposit ? actorItems : storedItems;
        List<Item> destination = deposit ? storedItems : actorItems;
        int available = 0;
        for (Item stack : source) {
            if (stack.id().equals(item) && stack.data().equals(payloadData)
                    && (!deposit || stack.slot() == requiredSlot)) available = Math.addExact(available, stack.count());
        }
        if (available < amount) return false;
        int left = amount;
        List<Item> remaining = new ArrayList<>(), moved = new ArrayList<>();
        for (Item stack : source) {
            boolean match = stack.id().equals(item) && stack.data().equals(payloadData)
                    && (!deposit || stack.slot() == requiredSlot);
            if (!match || left == 0) { remaining.add(stack); continue; }
            int take = Math.min(left, stack.count());
            moved.add(new Item(stack.id(), take, stack.data()));
            if (stack.count() > take)
                remaining.add(new Item(stack.id(), stack.count() - take, stack.data(), stack.slot()));
            left -= take;
        }
        if (left != 0) return false;
        List<Item> nextDestination = mergeStacks(destination, moved);
        if (deposit) {
            write(actor, remaining);
            stored.put("items", encodeItems(nextDestination));
        } else {
            write(actor, nextDestination);
            stored.put("items", encodeItems(remaining));
        }
        Map<String, Object> all = containers(owner);
        all.put(validContainerId(containerId), stored);
        owner.props.put(CONTAINERS_KEY, all);
        return true;
    }

    /** Deposit from a Character inventory into a shared container after checking access. */
    public static boolean deposit(Character owner, String containerId, Character actor, String itemId, int amount) {
        return moveContainerItem(owner, containerId, actor, itemId, amount, true);
    }

    /** Withdraw from a shared container into the authorized Character inventory. */
    public static boolean withdraw(Character owner, String containerId, Character actor, String itemId, int amount) {
        return moveContainerItem(owner, containerId, actor, itemId, amount, false);
    }

    private static boolean moveContainerItem(Character owner, String containerId, Character actor,
                                             String itemId, int amount, boolean deposit) {
        Objects.requireNonNull(owner); Objects.requireNonNull(actor);
        String item = validId(itemId);
        if (amount < 1 || !containerAccess(owner, containerId, actor)) return false;
        Map<String, Object> container = container(owner, containerId);
        if (container == null) return false;
        List<Item> characterItems = new ArrayList<>(items(actor));
        List<Item> storedItems = new ArrayList<>(decodeItems(container.get("items")));
        List<Item> source = deposit ? characterItems : storedItems;
        List<Item> destination = deposit ? storedItems : characterItems;
        if (source.stream().filter(stack -> stack.id().equals(item)).mapToInt(Item::count).sum() < amount) return false;
        List<Item> moved = new ArrayList<>();
        List<Item> remaining = new ArrayList<>();
        int left = amount;
        for (Item stack : source) {
            if (!stack.id().equals(item) || left == 0) { remaining.add(stack); continue; }
            int take = Math.min(left, stack.count());
            moved.add(new Item(stack.id(), take, stack.data()));
            if (stack.count() > take) remaining.add(new Item(stack.id(), stack.count() - take, stack.data(), stack.slot()));
            left -= take;
        }
        List<Item> nextDestination = mergeStacks(destination, moved);
        if (deposit) {
            write(actor, remaining);
            container.put("items", encodeItems(nextDestination));
        } else {
            write(actor, nextDestination);
            container.put("items", encodeItems(remaining));
        }
        Map<String, Object> allContainers = containers(owner);
        allContainers.put(validContainerId(containerId), container);
        owner.props.put(CONTAINERS_KEY, allContainers);
        return true;
    }

    /** Marker namespace for write-ahead inventory transfers across Character registry files. */
    public static final String TRANSFER_MARKER_PREFIX = "inventory.transfer.pending.";

    static String transferMarkerKey(String transactionId) {
        return TRANSFER_MARKER_PREFIX + UUID.nameUUIDFromBytes(
                Objects.requireNonNull(transactionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void putTransferMarker(Character person, String key, Map<String, Object> marker) {
        person.props.put(key, new LinkedHashMap<>(marker));
    }

    static Map<String, Object> transferMarker(Character person, String key) {
        Object raw = person.props.get(key);
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (entry.getKey() instanceof String name) copy.put(name, entry.getValue());
        return copy;
    }

    static void removeTransferMarker(Character person, String key) {
        person.props.remove(key);
    }

    public static boolean hasPendingTransfer(Character person) {
        return person != null && person.props.keySet().stream().anyMatch(key ->
                key.startsWith(TRANSFER_MARKER_PREFIX));
    }

    /** Replays only the source debit after a durable destination-commit marker is found. */
    static boolean completeTransferSourceDebit(Character source, Map<String, Object> marker) {
        Object operation = marker.get("operation");
        Object rawItem = marker.get("item_id");
        Object rawData = marker.get("item_data");
        Object rawAmount = marker.get("amount");
        Object rawContainer = marker.get("container_id");
        Object rawSlot = marker.get("source_slot");
        if (!(operation instanceof String op) || !(rawItem instanceof String itemId)
                || !(rawData instanceof String data) || !(rawAmount instanceof Number amountValue)
                || !(rawContainer instanceof String containerId) || !(rawSlot instanceof Number slotValue))
            return false;
        int amount = amountValue.intValue();
        if (amount < 1) return false;
        return switch (op) {
            case "deposit" -> removeExactInventory(source, itemId, data, slotValue.intValue(), amount);
            case "withdraw" -> removeExactContainer(source, containerId, itemId, data, amount);
            default -> false;
        };
    }

    private static boolean removeExactInventory(Character person, String itemId, String data,
                                                 int slot, int amount) {
        List<Item> current = items(person);
        int available = 0;
        for (Item stack : current)
            if (stack.id().equals(itemId) && stack.data().equals(data) && stack.slot() == slot)
                available = Math.addExact(available, stack.count());
        if (available < amount) return false;
        int left = amount;
        List<Item> next = new ArrayList<>();
        for (Item stack : current) {
            if (!stack.id().equals(itemId) || !stack.data().equals(data)
                    || stack.slot() != slot || left == 0) {
                next.add(stack);
                continue;
            }
            int take = Math.min(left, stack.count());
            left -= take;
            if (stack.count() > take)
                next.add(new Item(stack.id(), stack.count() - take, stack.data(), stack.slot()));
        }
        if (left != 0) return false;
        write(person, next);
        return true;
    }

    private static boolean removeExactContainer(Character owner, String containerId,
                                                String itemId, String data, int amount) {
        Map<String, Object> stored = container(owner, containerId);
        if (stored == null) return false;
        List<Item> current = new ArrayList<>(decodeItems(stored.get("items")));
        int available = 0;
        for (Item stack : current)
            if (stack.id().equals(itemId) && stack.data().equals(data))
                available = Math.addExact(available, stack.count());
        if (available < amount) return false;
        int left = amount;
        List<Item> next = new ArrayList<>();
        for (Item stack : current) {
            if (!stack.id().equals(itemId) || !stack.data().equals(data) || left == 0) {
                next.add(stack);
                continue;
            }
            int take = Math.min(left, stack.count());
            left -= take;
            if (stack.count() > take)
                next.add(new Item(stack.id(), stack.count() - take, stack.data()));
        }
        if (left != 0) return false;
        stored.put("items", encodeItems(next));
        Map<String, Object> all = containers(owner);
        all.put(validContainerId(containerId), stored);
        owner.props.put(CONTAINERS_KEY, all);
        return true;
    }

    private static boolean containerAccess(Character owner, String containerId, Character actor) {
        if (owner == null || actor == null) return false;
        Map<String, Object> container = container(owner, containerId);
        if (container == null || !owner.globalId.equals(container.get("owner_global_id"))) return false;
        if (owner.globalId.equals(actor.globalId)) return true;
        Object raw = container.get("access_global_ids");
        return raw instanceof List<?> list && list.contains(actor.globalId);
    }

    private static Map<String, Object> containers(Character owner) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object raw = owner.props.get(CONTAINERS_KEY);
        if (raw == null) return result;
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalStateException("Character containers are not a map: " + owner.globalId);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !key.matches("[a-z0-9_.-]{1,96}")
                    || !(entry.getValue() instanceof Map<?, ?> value))
                throw new IllegalStateException("Character contains a malformed shared-container record: " + owner.globalId);
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : value.entrySet()) {
                if (!(field.getKey() instanceof String name))
                    throw new IllegalStateException("Shared container contains a non-string field: " + key);
                copy.put(name, field.getValue());
            }
            result.put(key, copy);
        }
        return result;
    }

    private static Map<String, Object> container(Character owner, String containerId) {
        if (owner == null) return null;
        Object raw = containers(owner).get(validContainerId(containerId));
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet())
            if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
        return result;
    }

    private static List<Item> mergeStacks(List<Item> destination, List<Item> payload) {
        List<Item> result = new ArrayList<>(destination);
        for (Item moved : payload) {
            int match = -1;
            for (int i = 0; i < result.size(); i++) {
                Item existing = result.get(i);
                if (existing.slot() == moved.slot() && existing.id().equals(moved.id()) && existing.data().equals(moved.data())) { match = i; break; }
            }
            if (match >= 0) {
                Item existing = result.get(match);
                result.set(match, new Item(existing.id(), Math.addExact(existing.count(), moved.count()), existing.data()));
            } else result.add(moved);
        }
        result.sort(Comparator.comparing(Item::id).thenComparing(Item::data));
        return result;
    }

    private static List<Item> decodeItems(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list))
            throw new IllegalStateException("Shared-container item ledger is not a list");
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            result.add(decodeItemRecord(list.get(i), "Shared-container item entry " + i, true));
        return result;
    }

    private static Item decodeItemRecord(Object raw, String source, boolean includeSlot) {
        if (!(raw instanceof Map<?, ?> map))
            throw new IllegalStateException(source + " is not an object");
        Object rawId = map.get("id");
        if (!(rawId instanceof String id))
            throw new IllegalStateException(source + " has no string item ID");
        int count = exactInteger(map.get("count"), source + " count");
        Object rawData = map.get("data");
        if (rawData != null && !(rawData instanceof String))
            throw new IllegalStateException(source + " has invalid item NBT");
        String data = rawData instanceof String value ? value : "";
        int slot = -1;
        if (includeSlot && map.get("slot") != null)
            slot = exactInteger(map.get("slot"), source + " slot");
        try {
            return new Item(id, count, data, slot);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(source + " failed item validation", invalid);
        }
    }

    private static int exactInteger(Object raw, String field) {
        if (!(raw instanceof Number number))
            throw new IllegalStateException(field + " is not numeric");
        double decimal = number.doubleValue();
        long integer = number.longValue();
        if (!Double.isFinite(decimal) || decimal != (double) integer
                || integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE)
            throw new IllegalStateException(field + " is not an exact 32-bit integer");
        return (int) integer;
    }

    private static List<Map<String, Object>> encodeItems(List<Item> items) {
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (Item item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.id()); entry.put("count", item.count()); entry.put("data", item.data()); entry.put("slot", item.slot());
            encoded.add(entry);
        }
        return encoded;
    }

    private static String validContainerId(String id) {
        if (id == null || !id.matches("[a-z0-9_.-]{1,96}")) throw new IllegalArgumentException("invalid container id");
        return id;
    }

    /**
     * Settles a deceased person's estate. Close family receives the inventory; if no eligible
     * heir exists, the estate remains durably attached to the deceased Character and its home.
     */
    public static String settleEstate(CharacterRegistry registry, Character deceased) {
        return settleEstate(null, registry, deceased);
    }

    /** Estate succession resolves spouse and descendants across every registered world partition. */
    public static String settleEstate(CharacterAuthority authority, CharacterRegistry registry, Character deceased) {
        Objects.requireNonNull(registry);Objects.requireNonNull(deceased);
        // A recipient registry may have reached disk just before the deceased's registry.
        // Its durable incoming marker is the commit record: finish clearing the source on recovery.
        Object pendingRaw = deceased.props.get("estate.transfer_pending");
        Object recipientRaw = deceased.props.get("estate.recipient_global_id");
        if (authority != null && pendingRaw instanceof String pending && recipientRaw instanceof String recipientId) {
            Character committedHeir = authority.getGlobal(recipientId);
            if (committedHeir != null
                    && deceased.globalId.equals(committedHeir.props.get("estate.incoming." + pending))) {
                clearTransferredEstate(deceased, committedHeir);
                return committedHeir.id;
            }
        }
        if(Boolean.TRUE.equals(deceased.props.get("estate.settled")))return
                String.valueOf(deceased.props.getOrDefault("estate.recipient",""));
        Character heir=findHeir(authority, registry, deceased);
        String home=String.valueOf(deceased.props.getOrDefault("population.location",
                deceased.props.getOrDefault("population.home","")));
        if(heir!=null) {
            transferAll(deceased,heir);
            deceased.props.put("estate.recipient",heir.id);
            deceased.props.put("estate.recipient_global_id", heir.globalId);
            deceased.props.put("estate.status","inherited");
        } else {
            // Keep every item on the persistent dead Character; it is a recoverable home estate,
            // not a dropped entity stack that can despawn or be destroyed by chunk unload.
            deceased.props.put("estate.status","awaiting_kin_or_home_claim");
            deceased.props.put("estate.home",home);
            deceased.props.put("estate.recipient","");
        }
        deceased.props.put("estate.settled",true);
        return heir==null?"":heir.id;
    }

    /** A later-discovered descendant or household member can claim a pending estate exactly once. */
    public static boolean claimEstate(Character deceased, Character claimant) {
        return claimEstate(null, deceased, claimant);
    }

    /** Claims a pending estate using global family links, including relatives in another registry. */
    public static boolean claimEstate(CharacterAuthority authority, Character deceased, Character claimant) {
        Objects.requireNonNull(deceased);Objects.requireNonNull(claimant);
        if(deceased.alive||!claimant.alive||!isKin(deceased,claimant)
                ||!Boolean.TRUE.equals(deceased.props.get("estate.settled"))
                ||!"awaiting_kin_or_home_claim".equals(deceased.props.get("estate.status")))return false;
        transferAll(deceased,claimant);
        deceased.props.put("estate.recipient",claimant.id);
        deceased.props.put("estate.recipient_global_id",claimant.globalId);
        deceased.props.put("estate.status","inherited");
        return true;
    }

    private static void transferAll(Character from,Character to) {
        if (from == to) throw new IllegalArgumentException("estate recipient must differ from deceased");
        List<Item> source = items(from);
        List<Item> destination = new ArrayList<>(items(to));
        Map<String, Item> sourceEquipment = equipment(from);
        Map<String, Item> destinationEquipment = new TreeMap<>(equipment(to));
        List<Item> displacedEquipment = new ArrayList<>();
        // Preserve worn gear in the same slot when free; otherwise it becomes ordinary owned
        // inventory for the heir rather than overwriting the heir's equipment.
        for (Map.Entry<String, Item> entry : sourceEquipment.entrySet()) {
            if (destinationEquipment.containsKey(entry.getKey())) displacedEquipment.add(entry.getValue());
            else destinationEquipment.put(entry.getKey(), entry.getValue());
        }
        // Prepare and validate the complete estate before mutating either Character. This avoids
        // partial inheritance if a later stack would overflow or contain an invalid payload.
        for (Item moved : source) {
            // An heir receives ownership, not the deceased player's hotbar slot assignment.
            Item inherited = new Item(moved.id(), moved.count(), moved.data());
            int match = -1;
            for (int i = 0; i < destination.size(); i++) {
                Item existing = destination.get(i);
                if (existing.slot() == -1 && existing.id().equals(inherited.id()) && existing.data().equals(inherited.data())) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                Item existing = destination.get(match);
                destination.set(match, new Item(existing.id(),
                        Math.addExact(existing.count(), inherited.count()), existing.data()));
            } else destination.add(inherited);
        }
        destination = mergeStacks(destination, displacedEquipment);
        destination.sort(Comparator.comparing(Item::id).thenComparing(Item::data));
        Map<String, Object> sourceContainers = containers(from);
        Map<String, Object> destinationContainers = containers(to);
        String transferId = UUID.nameUUIDFromBytes((from.globalId + "|" + to.globalId + "|estate")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        for (Map.Entry<String, Object> entry : sourceContainers.entrySet()) {
            if (destinationContainers.containsKey(entry.getKey()))
                throw new IllegalStateException("estate container ID conflict: " + entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> raw))
                throw new IllegalStateException("invalid estate container: " + entry.getKey());
            Map<String, Object> moved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : raw.entrySet())
                if (field.getKey() instanceof String key) moved.put(key, field.getValue());
            if (!from.globalId.equals(moved.get("owner_global_id")))
                throw new IllegalStateException("estate container owner mismatch: " + entry.getKey());
            moved.put("owner_global_id", to.globalId);
            destinationContainers.put(entry.getKey(), moved);
        }
        write(to, destination);
        writeEquipment(to, destinationEquipment);
        to.props.put(CONTAINERS_KEY, destinationContainers);
        // Publish the recipient commit marker only after its complete estate has been applied.
        from.props.put("estate.transfer_pending", transferId);
        from.props.put("estate.recipient_global_id", to.globalId);
        to.props.put("estate.incoming." + transferId, from.globalId);
        write(from, List.of());
        writeEquipment(from, Map.of());
        from.props.remove(CONTAINERS_KEY);
    }

    private static void clearTransferredEstate(Character deceased, Character heir) {
        write(deceased, List.of());
        writeEquipment(deceased, Map.of());
        deceased.props.remove(CONTAINERS_KEY);
        deceased.props.put("estate.recipient", heir.id);
        deceased.props.put("estate.recipient_global_id", heir.globalId);
        deceased.props.put("estate.status", "inherited");
        deceased.props.put("estate.settled", true);
    }

    /** Reconcile recipient-first cross-registry estate commits after every partition has loaded. */
    public static void reconcileEstates(CharacterAuthority authority) {
        Objects.requireNonNull(authority);
        for (CharacterIdentity identity : authority.identities()) {
            Character heir = authority.get(identity);
            if (heir == null) continue;
            for (Map.Entry<String, Object> entry : new ArrayList<>(heir.props.entrySet())) {
                if (!entry.getKey().startsWith("estate.incoming.") || !(entry.getValue() instanceof String deceasedGlobalId))
                    continue;
                Character deceased = authority.getGlobal(deceasedGlobalId);
                if (deceased == null) continue;
                String transferId = entry.getKey().substring("estate.incoming.".length());
                deceased.props.put("estate.transfer_pending", transferId);
                deceased.props.put("estate.recipient_global_id", heir.globalId);
                CharacterRegistry registry = authority.registryForGlobal(deceasedGlobalId);
                if (registry != null && (deceased.alive || !Boolean.TRUE.equals(deceased.props.get("estate.settled"))
                        || !items(deceased).isEmpty() || !equipment(deceased).isEmpty()
                        || !containers(deceased).isEmpty()))
                    registry.kill(deceased.id);
            }
        }
    }

    private static Character findHeir(CharacterAuthority authority, CharacterRegistry registry, Character deceased) {
        Character spouse = related(authority, registry, deceased.props.get("family.spouse_global_id"), deceased.spouse);
        if (spouse != null && spouse.alive) return spouse;

        // Prefer living descendants, oldest first. Global links cross map/player registry boundaries.
        List<Character> descendants = new ArrayList<>();
        Object globalChildren = deceased.props.get("family.children_global_ids");
        if (globalChildren instanceof List<?> ids) for (Object id : ids) {
            Character child = related(authority, registry, id, "");
            if (child != null && child.alive) descendants.add(child);
        }
        for (String id : deceased.children) {
            Character child = registry.get(id);
            if (child != null && child.alive && !descendants.contains(child)) descendants.add(child);
        }
        descendants.sort(Comparator.comparingInt((Character c) -> c.birthYear).thenComparing(c -> c.globalId));
        if (!descendants.isEmpty()) return descendants.get(0);

        Character father = related(authority, registry, deceased.props.get("family.father_global_id"), deceased.father);
        if (father != null && father.alive) return father;
        Character mother = related(authority, registry, deceased.props.get("family.mother_global_id"), deceased.mother);
        if (mother != null && mother.alive) return mother;

        for (Character candidate : registry.all())
            if (candidate.alive && candidate != deceased && isKin(deceased, candidate)) return candidate;
        if (authority != null) for (CharacterIdentity identity : authority.identities()) {
            Character candidate = authority.get(identity);
            if (candidate != null && candidate.alive && candidate != deceased && isKin(deceased, candidate)) return candidate;
        }
        return null;
    }

    private static Character related(CharacterAuthority authority, CharacterRegistry local, Object globalId, String legacyId) {
        if (globalId instanceof String id && !id.isBlank() && authority != null) {
            Character found = authority.getGlobal(id);
            if (found != null) return found;
        }
        if (legacyId != null && !legacyId.isBlank()) {
            Character found = local.get(legacyId);
            if (found != null) return found;
            if (authority != null) return authority.getLegacy(legacyId);
        }
        return null;
    }

    private static boolean isKin(Character a,Character b) {
        if(a.id.equals(b.spouse)||b.id.equals(a.spouse)
                ||a.globalId.equals(b.props.get("family.spouse_global_id"))
                ||b.globalId.equals(a.props.get("family.spouse_global_id")))return true;
        if(a.children.contains(b.id)||b.children.contains(a.id))return true;
        if(listContains(a.props.get("family.children_global_ids"), b.globalId)
                ||listContains(b.props.get("family.children_global_ids"), a.globalId))return true;
        if(a.globalId.equals(b.props.get("family.father_global_id"))
                ||a.globalId.equals(b.props.get("family.mother_global_id"))
                ||b.globalId.equals(a.props.get("family.father_global_id"))
                ||b.globalId.equals(a.props.get("family.mother_global_id")))return true;
        if(!a.father.isBlank()&&(a.father.equals(b.id)||a.father.equals(b.father)||a.father.equals(b.mother)))return true;
        if(!a.mother.isBlank()&&(a.mother.equals(b.id)||a.mother.equals(b.father)||a.mother.equals(b.mother)))return true;
        return !b.father.isBlank()&&(b.father.equals(a.id)||b.children.contains(a.id))
                ||!b.mother.isBlank()&&(b.mother.equals(a.id)||b.children.contains(a.id));
    }

    private static boolean listContains(Object raw, String globalId) {
        return raw instanceof List<?> values && values.contains(globalId);
    }

    private static void write(Character person,List<Item> items) {
        List<Map<String,Object>> encoded=new ArrayList<>();
        for(Item item:items) {
            Map<String,Object> entry=new LinkedHashMap<>();
            entry.put("id",item.id());entry.put("count",item.count());entry.put("data",item.data());entry.put("slot",item.slot());
            encoded.add(entry);
        }
        person.props.put(KEY,encoded);
    }

    private static String validId(String id) {
        if(id==null||id.isBlank()||id.length()>160||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid item id");
        return id;
    }
}
