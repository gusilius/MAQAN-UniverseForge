package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Durable transaction journal for strategic army -> physical troop deployment.
 * The operation plan is immutable; only the phase and each spawned Character reference advance.
 */
public final class MilitaryMusterLedger {
    public enum Phase { PREPARED, ROSTER_DEBITED, STOCK_DEBITED, SQUADS_SAVED, ENTITIES_SPAWNED, COMMITTED }

    public record StockDebit(String colonyId, String receiptId, Map<String, Integer> resources) {
        public StockDebit {
            colonyId = required(colonyId, "colony ID", 256);
            receiptId = token(receiptId, "stock receipt");
            resources = integerMap(resources, true);
            if (resources.isEmpty()) throw new IllegalArgumentException("stock debit is empty");
        }
    }

    public record SquadPlan(String id, String name, String owner, String realm,
                            double centerX, double centerZ, List<String> members) {
        public SquadPlan {
            id = token(id, "squad ID");
            name = required(name, "squad name", 128);
            owner = required(owner, "squad owner", 128);
            realm = realm == null ? "" : realm.trim();
            finite(centerX, "squad center X");
            finite(centerZ, "squad center Z");
            if (members == null || members.isEmpty() || members.size() > 40)
                throw new IllegalArgumentException("squad must contain 1..40 physical soldiers");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String member : members) unique.add(uuid(member, "squad member UUID"));
            if (unique.size() != members.size()) throw new IllegalArgumentException("duplicate squad member UUID");
            members = List.copyOf(unique);
        }
    }

    public record Spawn(String entityId, String unitId, String squadId, String dimension,
                        double x, double y, double z, double yaw, String characterGlobalId) {
        public Spawn {
            entityId = uuid(entityId, "troop entity UUID");
            unitId = token(unitId, "unit ID");
            squadId = token(squadId, "squad ID");
            dimension = required(dimension, "dimension", 256);
            finite(x, "spawn X"); finite(y, "spawn Y"); finite(z, "spawn Z"); finite(yaw, "spawn yaw");
            characterGlobalId = characterGlobalId == null || characterGlobalId.isBlank()
                    ? "" : uuid(characterGlobalId, "Character UUID");
        }
        Spawn withCharacter(String characterId) {
            return new Spawn(entityId, unitId, squadId, dimension, x, y, z, yaw, characterId);
        }
    }

    public record Operation(String id, String worldId, String mapId, int armyId, String realm, String owner,
                            Map<String, Integer> manpowerDelta, List<StockDebit> stockDebits,
                            List<SquadPlan> squads, List<Spawn> spawns, Phase phase) {
        public Operation {
            id = uuid(id, "muster operation UUID");
            worldId = uuid(worldId, "WorldId");
            mapId = required(mapId, "map ID", 256);
            if (armyId <= 0) throw new IllegalArgumentException("army ID must be positive");
            realm = required(realm, "army realm", 128);
            owner = required(owner, "owner UUID", 128);
            manpowerDelta = integerMap(manpowerDelta, false);
            if (manpowerDelta.isEmpty() || manpowerDelta.values().stream().anyMatch(value -> value >= 0))
                throw new IllegalArgumentException("muster manpower delta must contain only negative values");
            if (stockDebits == null || stockDebits.isEmpty() || stockDebits.size() > 64)
                throw new IllegalArgumentException("muster requires 1..64 stock debits");
            stockDebits = List.copyOf(stockDebits);
            if (squads == null || squads.isEmpty() || squads.size() > 128)
                throw new IllegalArgumentException("muster requires 1..128 squad plans");
            squads = List.copyOf(squads);
            if (spawns == null || spawns.isEmpty() || spawns.size() > 5_000)
                throw new IllegalArgumentException("muster requires 1..5000 physical troop plans");
            spawns = List.copyOf(spawns);
            if (phase == null) throw new IllegalArgumentException("muster phase is required");

            Set<String> squadIds = new HashSet<>();
            Set<String> memberIds = new HashSet<>();
            for (SquadPlan squad : squads) {
                if (!squadIds.add(squad.id())) throw new IllegalArgumentException("duplicate muster squad ID");
                if (!squad.owner().equals(owner)) throw new IllegalArgumentException("muster squad owner mismatch");
                for (String member : squad.members())
                    if (!memberIds.add(member)) throw new IllegalArgumentException("physical troop belongs to multiple squads");
            }
            Set<String> spawnIds = new HashSet<>();
            for (Spawn spawn : spawns) {
                if (!spawnIds.add(spawn.entityId())) throw new IllegalArgumentException("duplicate muster entity UUID");
                if (!squadIds.contains(spawn.squadId()) || !memberIds.contains(spawn.entityId()))
                    throw new IllegalArgumentException("muster spawn is not assigned to its saved squad plan");
            }
            if (!memberIds.equals(spawnIds)) throw new IllegalArgumentException("squad membership and spawn plan differ");
            Set<String> stockRefs = new HashSet<>();
            for (StockDebit debit : stockDebits)
                if (!stockRefs.add(debit.receiptId())) throw new IllegalArgumentException("duplicate stock receipt ID");
        }

        Operation withPhase(Phase next) {
            return new Operation(id, worldId, mapId, armyId, realm, owner, manpowerDelta,
                    stockDebits, squads, spawns, next);
        }

        Operation withCharacter(String entityId, String characterId) {
            List<Spawn> updated = new ArrayList<>(spawns.size());
            boolean found = false;
            for (Spawn spawn : spawns) {
                if (spawn.entityId().equals(entityId)) {
                    updated.add(spawn.withCharacter(characterId));
                    found = true;
                } else updated.add(spawn);
            }
            if (!found) throw new IllegalArgumentException("Unknown muster troop entity " + entityId);
            return new Operation(id, worldId, mapId, armyId, realm, owner, manpowerDelta,
                    stockDebits, squads, updated, phase);
        }

        boolean samePlan(Operation other) {
            if (other == null) return false;
            Operation a = withPhase(Phase.PREPARED).withoutCharacterIds();
            Operation b = other.withPhase(Phase.PREPARED).withoutCharacterIds();
            return a.equals(b);
        }

        private Operation withoutCharacterIds() {
            List<Spawn> clean = spawns.stream().map(s -> s.withCharacter("")).toList();
            return new Operation(id, worldId, mapId, armyId, realm, owner, manpowerDelta,
                    stockDebits, squads, clean, Phase.PREPARED);
        }
    }

    public record Match(Operation operation, Spawn spawn) { }

    public static final int MAX_OPERATIONS = 50_000;
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    public synchronized boolean stage(Operation operation) {
        if (operation == null) throw new IllegalArgumentException("muster operation is required");
        Operation existing = operations.get(operation.id());
        if (existing != null) {
            if (!existing.samePlan(operation))
                throw new IllegalStateException("muster operation ID was reused with a different payload");
            return false;
        }
        if (operations.size() >= MAX_OPERATIONS)
            throw new IllegalStateException("muster receipt limit reached; reconcile/compact before another deployment");
        operations.put(operation.id(), operation);
        return true;
    }

    public synchronized Operation get(String id) { return operations.get(id); }
    public synchronized List<Operation> operations() { return List.copyOf(operations.values()); }
    public synchronized List<Operation> pending() {
        return operations.values().stream().filter(op -> op.phase() != Phase.COMMITTED).toList();
    }
    public synchronized boolean hasPendingArmy(String worldId, String mapId, int armyId) {
        return operations.values().stream().anyMatch(op -> op.phase() != Phase.COMMITTED
                && op.worldId().equals(worldId) && op.mapId().equals(mapId) && op.armyId() == armyId);
    }

    public synchronized Match findSpawn(String entityId) {
        if (entityId == null) return null;
        for (Operation operation : operations.values())
            for (Spawn spawn : operation.spawns())
                if (spawn.entityId().equals(entityId)) return new Match(operation, spawn);
        return null;
    }

    public synchronized boolean advance(String id, Phase expected, Phase next) {
        Operation current = operations.get(id);
        if (current == null || current.phase() != expected || expected.ordinal() + 1 != next.ordinal()) return false;
        operations.put(id, current.withPhase(next));
        return true;
    }

    public synchronized boolean recordCharacter(String id, String entityId, String characterGlobalId) {
        Operation current = operations.get(id);
        if (current == null) return false;
        String character = uuid(characterGlobalId, "Character UUID");
        Spawn target = current.spawns().stream().filter(s -> s.entityId().equals(entityId)).findFirst().orElse(null);
        if (target == null) return false;
        if (!target.characterGlobalId().isBlank())
            return target.characterGlobalId().equals(character);
        operations.put(id, current.withCharacter(entityId, character));
        return true;
    }

    public synchronized void clear() { operations.clear(); }

    public synchronized String toJson() {
        List<Object> rows = new ArrayList<>();
        for (Operation operation : operations.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", operation.id());
            row.put("worldId", operation.worldId());
            row.put("mapId", operation.mapId());
            row.put("armyId", operation.armyId());
            row.put("realm", operation.realm());
            row.put("owner", operation.owner());
            row.put("manpowerDelta", operation.manpowerDelta());
            row.put("phase", operation.phase().name());

            List<Object> stock = new ArrayList<>();
            for (StockDebit debit : operation.stockDebits())
                stock.add(Map.of("colony", debit.colonyId(), "receipt", debit.receiptId(), "resources", debit.resources()));
            row.put("stockDebits", stock);

            List<Object> squads = new ArrayList<>();
            for (SquadPlan squad : operation.squads())
                squads.add(Map.of("id", squad.id(), "name", squad.name(), "owner", squad.owner(),
                        "realm", squad.realm(), "x", squad.centerX(), "z", squad.centerZ(), "members", squad.members()));
            row.put("squads", squads);

            List<Object> spawns = new ArrayList<>();
            for (Spawn spawn : operation.spawns())
                spawns.add(Map.of("entity", spawn.entityId(), "unit", spawn.unitId(), "squad", spawn.squadId(),
                        "dimension", spawn.dimension(), "x", spawn.x(), "y", spawn.y(), "z", spawn.z(),
                        "yaw", spawn.yaw(), "character", spawn.characterGlobalId()));
            row.put("spawns", spawns);
            rows.add(row);
        }
        return Json.write(Map.of("format", "wf-military-muster/1", "operations", rows));
    }

    public synchronized void fromJson(String json) {
        Object parsed = Json.parse(json == null ? "" : json);
        if (!(parsed instanceof Map<?, ?> root)
                || !"wf-military-muster/1".equals(root.get("format"))
                || !(root.get("operations") instanceof List<?> rows))
            throw new IllegalArgumentException("Invalid military muster ledger format");
        if (rows.size() > MAX_OPERATIONS) throw new IllegalArgumentException("Too many military muster operations");
        Map<String, Operation> restored = new LinkedHashMap<>();
        Set<String> allEntities = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid muster operation row");
            String id = requiredValue(row.get("id"), "operation ID");
            String worldId = requiredValue(row.get("worldId"), "WorldId");
            String mapId = requiredValue(row.get("mapId"), "map ID");
            int armyId = integer(row.get("armyId"), "army ID");
            String realm = requiredValue(row.get("realm"), "realm");
            String owner = requiredValue(row.get("owner"), "owner");
            Phase phase;
            try { phase = Phase.valueOf(requiredValue(row.get("phase"), "phase")); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid muster phase", ex); }

            Map<String, Integer> delta = parseIntegerMap(row.get("manpowerDelta"));
            List<StockDebit> stock = new ArrayList<>();
            for (Object value : list(row.get("stockDebits"), "stock debits")) {
                if (!(value instanceof Map<?, ?> debit)) throw new IllegalArgumentException("Invalid muster stock debit");
                stock.add(new StockDebit(requiredValue(debit.get("colony"), "colony"),
                        requiredValue(debit.get("receipt"), "receipt"), parseIntegerMap(debit.get("resources"))));
            }

            List<SquadPlan> squads = new ArrayList<>();
            for (Object value : list(row.get("squads"), "squads")) {
                if (!(value instanceof Map<?, ?> squad)) throw new IllegalArgumentException("Invalid muster squad");
                List<String> members = strings(squad.get("members"), "squad members");
                squads.add(new SquadPlan(requiredValue(squad.get("id"), "squad ID"),
                        requiredValue(squad.get("name"), "squad name"),
                        requiredValue(squad.get("owner"), "squad owner"),
                        squad.get("realm") instanceof String valueRealm ? valueRealm : "",
                        number(squad.get("x"), "squad X"), number(squad.get("z"), "squad Z"), members));
            }

            List<Spawn> spawns = new ArrayList<>();
            for (Object value : list(row.get("spawns"), "spawns")) {
                if (!(value instanceof Map<?, ?> spawn)) throw new IllegalArgumentException("Invalid muster spawn");
                spawns.add(new Spawn(requiredValue(spawn.get("entity"), "entity UUID"),
                        requiredValue(spawn.get("unit"), "unit ID"), requiredValue(spawn.get("squad"), "squad ID"),
                        requiredValue(spawn.get("dimension"), "dimension"),
                        number(spawn.get("x"), "spawn X"), number(spawn.get("y"), "spawn Y"),
                        number(spawn.get("z"), "spawn Z"), number(spawn.get("yaw"), "spawn yaw"),
                        spawn.get("character") instanceof String character ? character : ""));
            }

            Operation operation = new Operation(id, worldId, mapId, armyId, realm, owner,
                    delta, stock, squads, spawns, phase);
            if (restored.putIfAbsent(operation.id(), operation) != null)
                throw new IllegalArgumentException("Duplicate military muster operation: " + id);
            for (Spawn spawn : spawns)
                if (!allEntities.add(spawn.entityId()))
                    throw new IllegalArgumentException("Troop entity appears in multiple muster operations: " + spawn.entityId());
        }
        operations.clear();
        operations.putAll(restored);
    }

    private static Map<String, Integer> integerMap(Map<String, Integer> value, boolean positive) {
        if (value == null || value.size() > 64) throw new IllegalArgumentException("invalid integer map");
        TreeMap<String, Integer> result = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : value.entrySet()) {
            String key = token(entry.getKey(), "integer map key");
            Integer amount = entry.getValue();
            if (amount == null || (positive ? amount <= 0 : amount == 0)
                    || Math.abs((long) amount) > 1_000_000_000L)
                throw new IllegalArgumentException("invalid quantity for " + key);
            result.put(key, amount);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Integer> parseIntegerMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Expected integer map");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Number number))
                throw new IllegalArgumentException("Invalid integer map entry");
            double d = number.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid integer quantity");
            result.put(key, (int) d);
        }
        return result;
    }

    private static List<?> list(Object value, String field) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException(field + " must be an array");
        return result;
    }

    private static List<String> strings(Object value, String field) {
        List<?> values = list(value, field);
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text)) throw new IllegalArgumentException("Invalid " + field);
            result.add(text);
        }
        return result;
    }

    private static int integer(Object value, String field) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() <= 0
                || n.doubleValue() > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid " + field);
        return n.intValue();
    }

    private static double number(Object value, String field) {
        if (!(value instanceof Number n)) throw new IllegalArgumentException("Invalid " + field);
        return finite(n.doubleValue(), field);
    }

    private static double finite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    private static String requiredValue(Object value, String field) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " is required");
        return required(text, field, 256);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max)
            throw new IllegalArgumentException(field + " is required and must be at most " + max + " characters");
        return value.trim();
    }

    private static String token(String value, String field) {
        String clean = required(value, field, 128);
        if (!clean.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid " + field);
        return clean;
    }

    private static String uuid(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        UUID parsed;
        try { parsed = UUID.fromString(value); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid " + field, ex); }
        String result = parsed.toString();
        if (!result.equalsIgnoreCase(value.trim())) throw new IllegalArgumentException("Non-canonical " + field);
        return result;
    }
}
