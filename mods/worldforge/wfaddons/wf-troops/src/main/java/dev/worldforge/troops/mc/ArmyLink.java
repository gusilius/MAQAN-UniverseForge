package dev.worldforge.troops.mc;

import dev.worldforge.army.Army;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.mc.ArmyMod;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;

import java.util.Map;

/** Мягкая связь с wf_army: призыв солдат из абстрактной армии и возврат выживших. Загружается только если есть wf_army. */
final class ArmyLink {
    private ArmyLink() {}

    record Found(ArmyEngine engine, Army army) {}

    /** Resolve only when the scoped identity is unique; duplicate IDs across maps fail closed. */
    static Found find(int armyId, String realm) {
        if (armyId <= 0 || realm == null || realm.isBlank()) return null;
        Found match = null;
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            ArmyEngine e = ArmyMod.armies(m);
            Army a = e.armies.get(armyId);
            if (a == null || !realm.equals(a.realm)) continue;
            if (match != null && match.army() != a) return null;
            match = new Found(e, a);
        }
        return match;
    }

    /** Used by commands that only receive an integer ID. Ambiguity is rejected, never first-match wins. */
    static Found findUnique(int armyId) {
        if (armyId <= 0) return null;
        Found match = null;
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            ArmyEngine e = ArmyMod.armies(m);
            Army a = e.armies.get(armyId);
            if (a == null) continue;
            if (match != null && match.army() != a) return null;
            match = new Found(e, a);
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

    static boolean give(int armyId, String realm, Map<String, Integer> men) {
        Found f = find(armyId, realm);
        if (f == null || men == null) return false;
        for (var e : men.entrySet()) f.army().add(e.getKey(), e.getValue());
        return true;
    }
}
