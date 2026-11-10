package dev.worldforge.people;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Write-ahead transaction for one Character-to-container item movement.
 * The destination commit is persisted before the source debit; recovery completes
 * a missing debit or discards an uncommitted prepare marker without guessing.
 */
public final class CharacterInventoryTransactions {
    @FunctionalInterface public interface Persister { boolean persist(Character character); }

    private static final String PREPARED = "PREPARED";
    private static final String DESTINATION_COMMITTED = "DESTINATION_COMMITTED";
    private static final String SOURCE_COMMITTED = "SOURCE_COMMITTED";
    private static final String BOTH_COMMITTED = "BOTH_COMMITTED";

    private CharacterInventoryTransactions() { }

    public static boolean executeExact(String transactionId, CharacterAuthority authority,
                                       Character owner, Character actor, String containerId,
                                       String itemId, String data, int sourceSlot, int amount,
                                       boolean deposit, Persister persister) {
        Objects.requireNonNull(authority); Objects.requireNonNull(owner);
        Objects.requireNonNull(actor); Objects.requireNonNull(persister);
        if (transactionId == null || transactionId.isBlank() || amount < 1 || !actor.alive
                || authority.getGlobal(owner.globalId) != owner
                || authority.getGlobal(actor.globalId) != actor
                || !CharacterInventory.canAccessContainer(owner, containerId, actor)
                || CharacterInventory.hasPendingTransfer(owner)
                || CharacterInventory.hasPendingTransfer(actor)) return false;

        Character source = deposit ? actor : owner;
        Character destination = deposit ? owner : actor;
        CharacterRegistry sourceRegistry = authority.registryForGlobal(source.globalId);
        CharacterRegistry destinationRegistry = authority.registryForGlobal(destination.globalId);
        if (sourceRegistry == null || destinationRegistry == null) return false;

        String markerKey = CharacterInventory.transferMarkerKey(transactionId);
        if (CharacterInventory.transferMarker(source, markerKey) != null
                || (destination != source && CharacterInventory.transferMarker(destination, markerKey) != null))
            return false;

        Map<String, Object> common = new LinkedHashMap<>();
        common.put("transaction_id", transactionId);
        common.put("operation", deposit ? "deposit" : "withdraw");
        common.put("source_global_id", source.globalId);
        common.put("destination_global_id", destination.globalId);
        common.put("owner_global_id", owner.globalId);
        common.put("container_id", containerId);
        common.put("item_id", itemId);
        common.put("item_data", data == null ? "" : data);
        common.put("source_slot", deposit ? sourceSlot : -1);
        common.put("amount", amount);

        if (source == destination) {
            Map<String, Object> before = new LinkedHashMap<>(source.props);
            CharacterInventory.putTransferMarker(source, markerKey, marker(common, "both", PREPARED));
            if (!persister.persist(source)) return false;
            if (!mutate(owner, actor, containerId, itemId, data, sourceSlot, amount, deposit)) {
                restore(source, before);
                persister.persist(source);
                return false;
            }
            CharacterInventory.putTransferMarker(source, markerKey, marker(common, "both", BOTH_COMMITTED));
            if (!persister.persist(source)) return false;
            CharacterInventory.removeTransferMarker(source, markerKey);
            persister.persist(source); // Cleanup-only failure is recovered by startup.
            return true;
        }

        Map<String, Object> sourceBefore = new LinkedHashMap<>(source.props);
        Map<String, Object> destinationBefore = new LinkedHashMap<>(destination.props);
        CharacterInventory.putTransferMarker(source, markerKey, marker(common, "source", PREPARED));
        CharacterInventory.putTransferMarker(destination, markerKey, marker(common, "destination", PREPARED));
        // PREPARED is durable before either inventory changes. A partial prepare is safe to discard.
        if (!persister.persist(source)) return false;

        if (!mutate(owner, actor, containerId, itemId, data, sourceSlot, amount, deposit)) {
            restore(source, sourceBefore);
            restore(destination, destinationBefore);
            persister.persist(source);
            return false;
        }

        // Both phases are changed in memory before the destination-first registry save.
        // If the source file is not reached, its disk copy still says PREPARED and recovery
        // will debit it exactly once using the already committed destination marker.
        CharacterInventory.putTransferMarker(destination, markerKey,
                marker(common, "destination", DESTINATION_COMMITTED));
        CharacterInventory.putTransferMarker(source, markerKey,
                marker(common, "source", SOURCE_COMMITTED));
        if (!persister.persist(source)) return false;

        CharacterInventory.removeTransferMarker(source, markerKey);
        CharacterInventory.removeTransferMarker(destination, markerKey);
        persister.persist(source); // The item movement itself is already durably committed.
        return true;
    }

    private static boolean mutate(Character owner, Character actor, String containerId,
                                  String itemId, String data, int slot, int amount, boolean deposit) {
        return deposit
                ? CharacterInventory.depositExact(owner, containerId, actor, itemId, data, slot, amount)
                : CharacterInventory.withdrawExact(owner, containerId, actor, itemId, data, amount);
    }

    private static void restore(Character person, Map<String, Object> snapshot) {
        person.props.clear();
        person.props.putAll(snapshot);
    }

    private static Map<String, Object> marker(Map<String, Object> common, String role, String phase) {
        Map<String, Object> result = new LinkedHashMap<>(common);
        result.put("role", role);
        result.put("phase", phase);
        return result;
    }

    private static final java.util.List<String> TRANSACTION_FIELDS = java.util.List.of(
            "transaction_id", "operation", "source_global_id", "destination_global_id",
            "owner_global_id", "container_id", "item_id", "item_data", "source_slot", "amount");

    private static Map<String, Object> transactionHeader(Map<String, Object> marker) {
        Map<String, Object> header = new LinkedHashMap<>();
        for (String field : TRANSACTION_FIELDS) header.put(field, marker.get(field));
        return header;
    }

    /** Validate marker provenance before recovery is allowed to mutate either endpoint. */
    private static boolean validMarker(CharacterAuthority authority, String propertyKey,
                                       Map<String, Object> marker, Character person, String role) {
        Object tx = marker.get("transaction_id");
        Object operation = marker.get("operation");
        Object sourceRaw = marker.get("source_global_id");
        Object destinationRaw = marker.get("destination_global_id");
        Object ownerRaw = marker.get("owner_global_id");
        Object containerRaw = marker.get("container_id");
        Object itemRaw = marker.get("item_id");
        Object dataRaw = marker.get("item_data");
        Object slotRaw = marker.get("source_slot");
        Object amountRaw = marker.get("amount");
        if (!(tx instanceof String transactionId) || transactionId.isBlank()
                || !propertyKey.equals(CharacterInventory.transferMarkerKey(transactionId))
                || !(operation instanceof String op)
                || !(sourceRaw instanceof String sourceId)
                || !(destinationRaw instanceof String destinationId)
                || !(ownerRaw instanceof String ownerId)
                || !(containerRaw instanceof String containerId)
                || !(itemRaw instanceof String itemId)
                || !(dataRaw instanceof String data)
                || !(slotRaw instanceof Number slotNumber)
                || !(amountRaw instanceof Number amountNumber)) return false;

        long rawSlot = slotNumber.longValue();
        long amount = amountNumber.longValue();
        if (slotNumber.doubleValue() != rawSlot || amountNumber.doubleValue() != amount
                || rawSlot < -1 || rawSlot > 35 || amount < 1 || amount > Integer.MAX_VALUE
                || !containerId.matches("[a-z0-9_.-]{1,96}")) return false;
        int sourceSlot = (int) rawSlot;
        try {
            if (!java.util.UUID.fromString(sourceId).toString().equals(sourceId)
                    || !java.util.UUID.fromString(destinationId).toString().equals(destinationId)
                    || !java.util.UUID.fromString(ownerId).toString().equals(ownerId)) return false;
            new CharacterInventory.Item(itemId, 1, data, "deposit".equals(op) ? sourceSlot : -1);
        } catch (RuntimeException invalid) {
            return false;
        }

        Character source = authority.getGlobal(sourceId);
        Character destination = authority.getGlobal(destinationId);
        Character owner = authority.getGlobal(ownerId);
        if (source == null || destination == null || owner == null
                || !source.globalId.equals(sourceId)
                || !destination.globalId.equals(destinationId)
                || !owner.globalId.equals(ownerId)) return false;

        if ("deposit".equals(op)) {
            if (!ownerId.equals(destinationId) || sourceSlot < 0 || sourceSlot > 35) return false;
        } else if ("withdraw".equals(op)) {
            if (!ownerId.equals(sourceId) || sourceSlot != -1) return false;
        } else return false;

        String phase = String.valueOf(marker.get("phase"));
        return switch (role) {
            case "source" -> person == source
                    && (PREPARED.equals(phase) || SOURCE_COMMITTED.equals(phase));
            case "destination" -> person == destination
                    && (PREPARED.equals(phase) || DESTINATION_COMMITTED.equals(phase));
            case "both" -> person == source && source == destination
                    && (PREPARED.equals(phase) || BOTH_COMMITTED.equals(phase));
            default -> false;
        };
    }

    /** Reconcile after the player registry and every currently loaded map registry have been indexed. */
    public static boolean reconcile(CharacterAuthority authority, Persister persister) {
        Objects.requireNonNull(authority); Objects.requireNonNull(persister);
        Map<String, Character> sourceCharacters = new LinkedHashMap<>();
        Map<String, Character> destinationCharacters = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sourceMarkers = new LinkedHashMap<>();
        Map<String, Map<String, Object>> destinationMarkers = new LinkedHashMap<>();
        Map<String, Map<String, Object>> bothMarkers = new LinkedHashMap<>();
        Map<String, Map<String, Object>> transactionHeaders = new LinkedHashMap<>();

        for (CharacterIdentity identity : authority.identities()) {
            Character person = authority.get(identity);
            if (person == null) continue;
            for (Map.Entry<String, Object> property : new java.util.ArrayList<>(person.props.entrySet())) {
                if (!property.getKey().startsWith(CharacterInventory.TRANSFER_MARKER_PREFIX)) continue;
                if (!(property.getValue() instanceof Map<?, ?> raw)) return false;
                Map<String, Object> record = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : raw.entrySet())
                    if (field.getKey() instanceof String name) record.put(name, field.getValue());
                Object transactionRaw = record.get("transaction_id");
                if (!(transactionRaw instanceof String transactionId) || transactionId.isBlank()) return false;
                String role = String.valueOf(record.get("role"));
                if (!validMarker(authority, property.getKey(), record, person, role)) return false;
                Map<String, Object> header = transactionHeader(record);
                Map<String, Object> previousHeader = transactionHeaders.putIfAbsent(transactionId, header);
                if (previousHeader != null && !previousHeader.equals(header)) return false;
                switch (role) {
                    case "source" -> {
                        if (sourceCharacters.putIfAbsent(transactionId, person) != null) return false;
                        sourceMarkers.put(transactionId, record);
                    }
                    case "destination" -> {
                        if (destinationCharacters.putIfAbsent(transactionId, person) != null) return false;
                        destinationMarkers.put(transactionId, record);
                    }
                    case "both" -> {
                        if (bothMarkers.putIfAbsent(transactionId, record) != null) return false;
                        sourceCharacters.put(transactionId, person);
                    }
                    default -> { return false; }
                }
            }
        }

        java.util.Set<String> transactions = new java.util.LinkedHashSet<>();
        transactions.addAll(sourceMarkers.keySet());
        transactions.addAll(destinationMarkers.keySet());
        transactions.addAll(bothMarkers.keySet());
        for (String transactionId : transactions) {
            Character source = sourceCharacters.get(transactionId);
            Character destination = destinationCharacters.get(transactionId);
            Map<String, Object> sourceMarker = sourceMarkers.get(transactionId);
            Map<String, Object> destinationMarker = destinationMarkers.get(transactionId);
            String key = CharacterInventory.transferMarkerKey(transactionId);

            if (bothMarkers.containsKey(transactionId)) {
                Map<String, Object> marker = bothMarkers.get(transactionId);
                String phase = String.valueOf(marker.get("phase"));
                if (source == null || !(PREPARED.equals(phase) || BOTH_COMMITTED.equals(phase))) return false;
                CharacterInventory.removeTransferMarker(source, key);
                if (!persister.persist(source)) return false;
                continue;
            }

            String destinationPhase = destinationMarker == null ? "" : String.valueOf(destinationMarker.get("phase"));
            String sourcePhase = sourceMarker == null ? "" : String.valueOf(sourceMarker.get("phase"));
            if (DESTINATION_COMMITTED.equals(destinationPhase)) {
                if (sourceMarker != null && PREPARED.equals(sourcePhase)) {
                    if (source == null || !CharacterInventory.completeTransferSourceDebit(source, sourceMarker))
                        return false;
                    sourceMarker.put("phase", SOURCE_COMMITTED);
                    CharacterInventory.putTransferMarker(source, key, sourceMarker);
                    if (!persister.persist(source)) return false;
                } else if (sourceMarker != null && !SOURCE_COMMITTED.equals(sourcePhase)) {
                    return false;
                }
                // A missing source marker means its debit was committed before cleanup was interrupted.
                if (source != null && CharacterInventory.transferMarker(source, key) != null) {
                    CharacterInventory.removeTransferMarker(source, key);
                    if (!persister.persist(source)) return false;
                }
                if (destination != null) {
                    CharacterInventory.removeTransferMarker(destination, key);
                    if (!persister.persist(destination)) return false;
                }
                continue;
            }

            if (SOURCE_COMMITTED.equals(sourcePhase) && destinationMarker == null) {
                // Destination cleanup was saved first after the source debit was already durable.
                CharacterInventory.removeTransferMarker(source, key);
                if (!persister.persist(source)) return false;
                continue;
            }

            if ((sourceMarker == null || PREPARED.equals(sourcePhase))
                    && (destinationMarker == null || PREPARED.equals(destinationPhase))) {
                // No destination commit exists. PREPARED records are only write-ahead locks;
                // the item transfer never became durable and is discarded.
                if (source != null && sourceMarker != null) {
                    CharacterInventory.removeTransferMarker(source, key);
                    if (!persister.persist(source)) return false;
                }
                if (destination != null && destinationMarker != null) {
                    CharacterInventory.removeTransferMarker(destination, key);
                    if (!persister.persist(destination)) return false;
                }
                continue;
            }
            return false; // Unknown marker combination: fail closed, never guess where items belong.
        }
        return true;
    }
}
