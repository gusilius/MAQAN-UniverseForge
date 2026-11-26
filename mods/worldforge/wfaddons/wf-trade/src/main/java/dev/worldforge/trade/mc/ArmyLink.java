package dev.worldforge.trade.mc;

import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.mc.ArmyMod;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.WorldForgeMod;

/** Мягкая связь с wf_army: торговые договоры снижают пошлины. */
final class ArmyLink {
    private ArmyLink() {}

    private static ArmyEngine engineOf(String realm) {
        for (MapData m : WorldForgeMod.loadedMaps().values()) if (m.titles != null && m.titles.realms.containsKey(realm)) return ArmyMod.armies(m);
        return null;
    }

    static boolean pact(String a, String b) {
        ArmyEngine e = engineOf(a);
        return e != null && e.partners(a).contains(b);
    }
}
