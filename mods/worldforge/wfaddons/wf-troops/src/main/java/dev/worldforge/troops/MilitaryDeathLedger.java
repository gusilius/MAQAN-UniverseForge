package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable outbox for military Character deaths. A pending record is written before
 * the Character lifecycle is mutated; it is removed only after the owning registry
 * confirms a durable save. Replaying a pending record is safe because Character death
 * itself is idempotent.
 */
public final class MilitaryDeathLedger {
    public record Pending(String characterGlobalId, String troopEntityId, int year, String cause) {
        public Pending {
            characterGlobalId = uuid(characterGlobalId, "Character UUID");
            troopEntityId = uuid(troopEntityId, "troop entity UUID");
            if (year < 1) throw new IllegalArgumentException("death year must be positive");
            if (cause == null || cause.isBlank() || cause.length() > 96)
                throw new IllegalArgumentException("death cause is required and must be at most 96 characters");
            cause = cause.trim();
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public synchronized boolean stage(Pending value) {
        if (value == null) throw new IllegalArgumentException("pending death is required");
        Pending existing = pending.get(value.characterGlobalId());
        if (existing != null) {
            if (!existing.equals(value))
                throw new IllegalStateException("death idempotency collision for Character " + value.characterGlobalId());
            return false;
        }
        for (Pending entry : pending.values()) {
            if (entry.troopEntityId().equals(value.troopEntityId()))
                throw new IllegalStateException("troop entity already has a pending Character death");
        }
        pending.put(value.characterGlobalId(), value);
        return true;
    }

    public synchronized Pending get(String characterGlobalId) {
        return pending.get(characterGlobalId);
    }

    public synchronized boolean complete(String characterGlobalId) {
        return pending.remove(characterGlobalId) != null;
    }

    public synchronized List<Pending> pending() {
        return List.copyOf(pending.values());
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized void clear() {
        pending.clear();
    }

    public synchronized String toJson() {
        List<Object> records = new ArrayList<>();
        for (Pending value : pending.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("character", value.characterGlobalId());
            row.put("entity", value.troopEntityId());
            row.put("year", value.year());
            row.put("cause", value.cause());
            records.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "wf-military-death-outbox/1");
        root.put("pending", records);
        return Json.write(root);
    }

    public synchronized void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)
                || !"wf-military-death-outbox/1".equals(root.get("format"))
                || !(root.get("pending") instanceof List<?> rows))
            throw new IllegalArgumentException("Invalid military death outbox format");
        Map<String, Pending> restored = new LinkedHashMap<>();
        java.util.Set<String> entityIds = new java.util.HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row))
                throw new IllegalArgumentException("Invalid pending military death row");
            String character = string(row.get("character"), "Character UUID");
            String entity = string(row.get("entity"), "troop entity UUID");
            if (!(row.get("year") instanceof Number year))
                throw new IllegalArgumentException("Pending military death year is not numeric");
            String cause = string(row.get("cause"), "death cause");
            Pending value = new Pending(character, entity, year.intValue(), cause);
            if (restored.putIfAbsent(value.characterGlobalId(), value) != null)
                throw new IllegalArgumentException("Duplicate pending Character death: " + character);
            if (!entityIds.add(value.troopEntityId()))
                throw new IllegalArgumentException("Duplicate pending troop entity death: " + entity);
        }
        pending.clear();
        pending.putAll(restored);
    }

    private static String string(Object value, String field) {
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
