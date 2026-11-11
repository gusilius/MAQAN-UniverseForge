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

    static Found find(int armyId) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            ArmyEngine e = ArmyMod.armies(m);
            Army a = e.armies.get(armyId);
            if (a != null) return new Found(e, a);
        }
        return null;
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

    /** Снимает людей с армии. Вызывать только после успешного плана. */
    static void take(Army a, Map<String, Integer> men) {
        for (var e : men.entrySet()) {
            a.units.merge(e.getKey(), -e.getValue(), Integer::sum);
            if (a.units.getOrDefault(e.getKey(), 0) <= 0) a.units.remove(e.getKey());
        }
    }

    static boolean give(int armyId, Map<String, Integer> men) {
        Found f = find(armyId);
        if (f == null) return false;
        for (var e : men.entrySet()) f.army().add(e.getKey(), e.getValue());
        return true;
    }

    /**
     * Stages one idempotent manpower credit per physical soldier, then synchronously writes
     * the strategic army snapshot. The troop entities must not be released before this returns true.
     */
    static boolean returnToArmy(Found found, Collection<TroopEntity> troops,
                                Map<String, String> armyTypes, MinecraftServer server) {
        if (found == null || found.army() == null || troops == null || troops.isEmpty()
                || armyTypes == null || server == null) return false;

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
            Map<String, Integer> delta = Muster.back(Map.of(troop.unitId(), 1), armyTypes);
            if (delta.isEmpty() || delta.values().stream().anyMatch(value -> value == null || value <= 0))
                throw new IllegalStateException("No strategic roster mapping for physical troop " + troop.unitId());
            deltas.put(entityId, delta);
        }

        for (Map.Entry<String, Map<String, Integer>> entry : deltas.entrySet())
            found.engine().applyRosterDeltaOnce("troop-return:" + entry.getKey(), found.army().id, entry.getValue());

        // Atomic per-map snapshot: a failure leaves every physical body in the world.
        return ArmyMod.persistNow(server);
    }
}
