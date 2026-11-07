package dev.worldforge.quests.mc;

import dev.worldforge.origins.Origins;
import dev.worldforge.origins.mc.OriginsMod;

/** Мягкая связь с wf_origins: флаг "quest:<флаг>" в требованиях классов проверяется по флагам выполненных заданий. */
final class OriginsHook {
    private OriginsHook() {}

    static void install() {
        final Origins.Hooks prev = OriginsMod.ORIGINS.hooks;
        OriginsMod.ORIGINS.hooks = new Origins.Hooks() {
            @Override public boolean flag(String owner, String flag) {
                if (!flag.startsWith("quest:")) return prev.flag(owner, flag);
                return QuestsMod.QUESTS.flags(owner.startsWith("p:") ? owner.substring(2) : owner).contains(flag.substring(6));
            }
            @Override public java.util.Map<String, Double> sheet(String owner) { return prev.sheet(owner); }
            @Override public void archetype(String owner, String a) { prev.archetype(owner, a); }
            @Override public String realmOf(String owner) { return prev.realmOf(owner); }
            @Override public Object realmProp(String realm, String key) { return prev.realmProp(realm, key); }
        };
    }
}
