package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Durable outbox for non-lethal troop-to-civilian transitions (desertion and demobilization).
 *
 * Completed rows are retained as terminal receipts. Minecraft may persist an entity removal
 * later than the Character/settlement snapshots; the receipt prevents a stale chunk from
 * resurrecting the old physical soldier after a restart.
 */
public final class MilitaryTransitionLedger {
    public enum Kind { DESERTION, DEMOBILIZATION, STRATEGIC_RETURN }

    private enum State { PENDING, COMPLETED }
    private record Entry(Pending value, State state) { }

    public record Pending(String characterGlobalId, String troopEntityId, Kind kind,
                          int year, String destinationColonyId) {
        public Pending {
            characterGlobalId = uuid(characterGlobalId, "Character UUID");
            troopEntityId = uuid(troopEntityId, "troop entity UUID");
            if (kind == null) throw new IllegalArgumentException("transition kind is required");
            if (year < 1) throw new IllegalArgumentException("year must be positive");
            destinationColonyId = destinationColonyId == null ? "" : destinationColonyId.trim();
            if (destinationColonyId.length() > 256)
                throw new IllegalArgumentException("destination colony ID is too long");
        }
    }

    /** A safe hard stop is preferable to pruning receipts while stale chunk entities may exist. */
    public static final int MAX_TRANSITIONS = 100_000;
    private final Map<String, Entry> byEntity = new LinkedHashMap<>();
    private final Map<String, String> activeByCharacter = new HashMap<>();

    public synchronized boolean stage(Pending value) {
        if (value == null) throw new IllegalArgumentException("pending transition is required");
        Entry existing = byEntity.get(value.troopEntityId());
        if (existing != null) {
            if (!existing.value().equals(value))
                throw new IllegalStateException("military transition idempotency collision for troop "
                        + value.troopEntityId());
            return false;
        }
        if (activeByCharacter.containsKey(value.characterGlobalId()))
            throw new IllegalStateException("Character already has an active military transition: "
                    + value.characterGlobalId());
        if (byEntity.size() >= MAX_TRANSITIONS)
            throw new IllegalStateException("Military transition receipt limit reached; reconcile/compact before another transition");

        byEntity.put(value.troopEntityId(), new Entry(value, State.PENDING));
        activeByCharacter.put(value.characterGlobalId(), value.troopEntityId());
        return true;
    }

    /** Returns the currently pending transition for a Character, never an old terminal receipt. */
    public synchronized Pending get(String characterGlobalId) {
        String entityId = activeByCharacter.get(characterGlobalId);
        Entry entry = entityId == null ? null : byEntity.get(entityId);
        return entry == null ? null : entry.value();
    }

    public synchronized List<Pending> pending() {
        List<Pending> result = new ArrayList<>(activeByCharacter.size());
        for (Entry entry : byEntity.values())
            if (entry.state() == State.PENDING) result.add(entry.value());
        return List.copyOf(result);
    }

    public synchronized int size() { return activeByCharacter.size(); }

    /** True only for the exact physical entity covered by a committed terminal receipt. */
    public synchronized boolean isCompleted(String characterGlobalId, String troopEntityId) {
        Entry entry = byEntity.get(troopEntityId);
        return entry != null && entry.state() == State.COMPLETED
                && entry.value().characterGlobalId().equals(characterGlobalId);
    }

    /** Changes the pending intent into a durable terminal receipt; it does not erase history. */
    public synchronized boolean complete(String characterGlobalId) {
        String entityId = activeByCharacter.remove(characterGlobalId);
        if (entityId == null) return false;
        Entry entry = byEntity.get(entityId);
        if (entry == null || entry.state() != State.PENDING) {
            activeByCharacter.remove(characterGlobalId);
            return false;
        }
        byEntity.put(entityId, new Entry(entry.value(), State.COMPLETED));
        return true;
    }

    /** Reverts an in-memory acknowledgement when the terminal snapshot could not be saved. */
    public synchronized boolean reopen(String characterGlobalId, String troopEntityId) {
        Entry entry = byEntity.get(troopEntityId);
        if (entry == null || entry.state() != State.COMPLETED
                || !entry.value().characterGlobalId().equals(characterGlobalId)
                || activeByCharacter.containsKey(characterGlobalId)) return false;
        byEntity.put(troopEntityId, new Entry(entry.value(), State.PENDING));
        activeByCharacter.put(characterGlobalId, troopEntityId);
        return true;
    }

    public synchronized void clear() {
        byEntity.clear();
        activeByCharacter.clear();
    }

    public synchronized String toJson() {
        List<Object> pendingRows = new ArrayList<>();
        List<Object> completedRows = new ArrayList<>();
        for (Entry entry : byEntity.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Pending value = entry.value();
            row.put("character", value.characterGlobalId());
            row.put("entity", value.troopEntityId());
            row.put("kind", value.kind().name());
            row.put("year", value.year());
            row.put("colony", value.destinationColonyId());
            (entry.state() == State.PENDING ? pendingRows : completedRows).add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-military-transition-outbox/2");
        root.put("pending", pendingRows);
        root.put("completed", completedRows);
        return Json.write(root);
    }

    /** Reads v1 pending-only saves and v2 saves with durable terminal receipts. */
    public synchronized void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root))
            throw new IllegalArgumentException("Invalid military transition outbox format");
        String format = root.get("format") instanceof String value ? value : "";
        boolean legacy = "wf-military-transition-outbox/1".equals(format);
        if (!legacy && !"wf-military-transition-outbox/2".equals(format))
            throw new IllegalArgumentException("Invalid military transition outbox format");
        if (!(root.get("pending") instanceof List<?> pendingRows))
            throw new IllegalArgumentException("Military transition pending rows are missing");
        List<?> completedRows;
        if (legacy) completedRows = List.of();
        else if (root.get("completed") instanceof List<?> rows) completedRows = rows;
        else throw new IllegalArgumentException("Military transition completed receipts are missing");

        Map<String, Entry> restored = new LinkedHashMap<>();
        Map<String, String> active = new HashMap<>();
        readRows(pendingRows, State.PENDING, restored, active);
        readRows(completedRows, State.COMPLETED, restored, active);
        byEntity.clear();
        byEntity.putAll(restored);
        activeByCharacter.clear();
        activeByCharacter.putAll(active);
    }

    private static void readRows(List<?> rows, State state, Map<String, Entry> restored,
                                 Map<String, String> active) {
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row))
                throw new IllegalArgumentException("Invalid military transition receipt row");
            String character = requiredString(row.get("character"), "Character UUID");
            String entity = requiredString(row.get("entity"), "troop entity UUID");
            String kind = requiredString(row.get("kind"), "transition kind");
            if (!(row.get("year") instanceof Number year))
                throw new IllegalArgumentException("Military transition year is not numeric");
            String colony = row.get("colony") instanceof String value ? value : "";
            Pending value;
            try {
                value = new Pending(character, entity, Kind.valueOf(kind), year.intValue(), colony);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Invalid military transition receipt", error);
            }
            if (restored.size() >= MAX_TRANSITIONS)
                throw new IllegalArgumentException("Too many military transition receipts");
            if (restored.putIfAbsent(value.troopEntityId(), new Entry(value, state)) != null)
                throw new IllegalArgumentException("Duplicate military transition entity: " + entity);
            if (state == State.PENDING
                    && active.putIfAbsent(value.characterGlobalId(), value.troopEntityId()) != null)
                throw new IllegalArgumentException("Duplicate pending Character transition: " + character);
        }
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(field + " is required");
        return text;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        UUID parsed;
        try { parsed = UUID.fromString(value); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid " + field, error); }
        String canonical = parsed.toString();
        if (!canonical.equalsIgnoreCase(value.trim()))
            throw new IllegalArgumentException("Non-canonical " + field);
        return canonical;
    }
}
