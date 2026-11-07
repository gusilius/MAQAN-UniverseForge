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

    static Found find(int armyId) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            ArmyEngine e = ArmyMod.armies(m);
            Army a = e.armies.get(armyId);
            if (a != null) return new Found(e, a);
        }
        return null;
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
}
