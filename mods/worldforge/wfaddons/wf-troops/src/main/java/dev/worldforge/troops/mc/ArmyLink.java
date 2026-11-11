package dev.worldforge.troops.mc;

import dev.worldforge.army.Army;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.mc.ArmyMod;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;

import dev.worldforge.troops.Muster;
import net.minecraft.server.MinecraftServer;

import java.util.*;


/** Мягкая связь с wf_army: призыв солдат из абстрактной армии и возврат выживших. Загружается только если есть wf_army. */
final class ArmyLink {
    private ArmyLink() {}

    record Found(ArmyEngine engine, Army army) {}

    /**
     * Resolves a legacy numeric army reference only when it identifies exactly one loaded map.
     * Army IDs are map-local; returning the first match could silently mutate another world's army.
     */
    static Found find(int armyId) {
        if (armyId <= 0) return null;
        Found match = null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            ArmyEngine engine = ArmyMod.armies(map);
            Army army = engine.armies.get(armyId);
            if (army == null) continue;
            if (match != null && match.engine() != engine) return null;
            match = new Found(engine, army);
        }
        return match;
    }

    /**
     * Resolves a map-local army only when its realm identifies exactly one loaded map.
     * Ambiguous realm/army pairs fail closed instead of crediting a similarly numbered army elsewhere.
     */
    static Found find(int armyId, String realm) {
        if (armyId <= 0 || realm == null || realm.isBlank()) return null;
        Found match = null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null || !map.titles.realms.containsKey(realm)) continue;
            ArmyEngine engine = ArmyMod.armies(map);
            Army army = engine.armies.get(armyId);
            if (army == null || !realm.equals(army.realm)) continue;
            if (match != null && match.engine() != engine) return null;
            match = new Found(engine, army);
        }
        return match;
    }

    /** Exact map-scoped lookup used by RTS command hierarchy and physical troop transitions. */
    static Found findOnMap(int armyId, String mapId) {
        if (armyId <= 0 || mapId == null || mapId.isBlank()) return null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (!map.name.equals(mapId) || map.titles == null) continue;
            ArmyEngine engine = ArmyMod.armies(map);
            Army army = engine.armies.get(armyId);
            return army == null ? null : new Found(engine, army);
        }
        return null;
    }

    static Found find(int armyId, String realm, String mapId) {
        Found found = findOnMap(armyId, mapId);
        return found != null && Objects.equals(found.army().realm, realm) ? found : null;
    }

    /** Applies the strategic side of a deployment exactly once and persists its receipt with the army snapshot. */
    static boolean debitMuster(Found found, String operationId, Map<String, Integer> manpowerDelta,
                               MinecraftServer server) {
        if (found == null || found.engine() == null || found.army() == null
                || operationId == null || operationId.isBlank() || manpowerDelta == null || server == null) return false;
        found.engine().applyRosterDeltaOnce("muster:" + operationId, found.army().id, manpowerDelta);
        return ArmyMod.persistNow(server);
    }

    /** Снимает людей с армии. Вызывать только после успешного плана. */
    static void take(Army a, Map<String, Integer> men) {
        for (var e : men.entrySet()) {
            a.units.merge(e.getKey(), -e.getValue(), Integer::sum);
            if (a.units.getOrDefault(e.getKey(), 0) <= 0) a.units.remove(e.getKey());
        }
    }

    static boolean give(int armyId, Map<String, Integer> men) {
        Found f = find(armyId);
        return give(f, men);
    }

    static boolean give(Found found, Map<String, Integer> men) {
        if (found == null || found.army() == null || men == null) return false;
        for (var e : men.entrySet()) found.army().add(e.getKey(), e.getValue());
        return true;
    }

    /**
     * Finds a terminal physical-return receipt independently of whether its source army still exists.
     * Army merges and disbanding must never erase the tombstone for a late-loaded troop chunk.
     */
    static boolean hasReturnReceipt(String troopEntityId) {
        if (troopEntityId == null || troopEntityId.isBlank()) return false;
        String operationId = "troop-return:" + troopEntityId;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map == null || map.titles == null) continue;
            if (ArmyMod.armies(map).hasRosterOperation(operationId)) return true;
        }
        return false;
    }

    /**
     * Stages one idempotent manpower credit per physical soldier, then synchronously writes
     * the strategic army snapshot. The troop entities must not be released before this returns true.
     */
    static void stageReturn(Found found, Collection<TroopEntity> troops, Map<String, String> armyTypes) {
        if (found == null || found.army() == null || troops == null || troops.isEmpty() || armyTypes == null)
            throw new IllegalArgumentException("Invalid strategic army return request");

        List<TroopEntity> ordered = new ArrayList<>(troops);
        ordered.sort(Comparator.comparing(t -> t.getUUID().toString()));
        Map<String, Map<String, Integer>> deltas = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (TroopEntity troop : ordered) {
            if (troop == null || troop.armyId != found.army().id
                    || !Objects.equals(troop.realm, found.army().realm)
                    || troop.unitId() == null || troop.unitId().isBlank())
                throw new IllegalArgumentException("Invalid physical troop in army return");
            String entityId = troop.getUUID().toString();
            if (!seen.add(entityId)) throw new IllegalArgumentException("Duplicate physical troop in army return");
            Map<String, Integer> delta = Muster.back(troop.unitId(), troop.strategicUnitId, armyTypes);
            if (delta.isEmpty() || delta.values().stream().anyMatch(value -> value == null || value <= 0))
                throw new IllegalStateException("No strategic roster mapping for physical troop " + troop.unitId());
            deltas.put(entityId, delta);
        }

        for (Map.Entry<String, Map<String, Integer>> entry : deltas.entrySet())
            found.engine().applyRosterDeltaOnce("troop-return:" + entry.getKey(), found.army().id, entry.getValue());
    }

    /** Flushes all staged returns before any physical troop body is removed. */
    static boolean persistStagedReturns(MinecraftServer server) {
        return ArmyMod.persistNow(server);
    }
}
