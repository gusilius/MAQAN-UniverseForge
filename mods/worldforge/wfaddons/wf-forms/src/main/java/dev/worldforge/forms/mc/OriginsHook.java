package dev.worldforge.forms.mc;

import dev.worldforge.origins.Origins;
import dev.worldforge.origins.mc.OriginsMod;

/** Мягкая связь с wf_origins: флаги "form:<id>" и "form:<id>:active" в требованиях классов. Старый обработчик флагов сохраняется (цепочка). */
final class OriginsHook {
    private OriginsHook() {}

    static void install() {
        final Origins.Hooks prev = OriginsMod.ORIGINS.hooks;
        OriginsMod.ORIGINS.hooks = new Origins.Hooks() {
            @Override public boolean flag(String owner, String flag) { return flag.startsWith("form:") ? FormsMod.FORMS.flag(owner, flag) : prev.flag(owner, flag); }
            @Override public java.util.Map<String, Double> sheet(String owner) { return prev.sheet(owner); }
            @Override public void archetype(String owner, String a) { prev.archetype(owner, a); }
            @Override public String realmOf(String owner) { return prev.realmOf(owner); }
            @Override public Object realmProp(String realm, String key) { return prev.realmProp(realm, key); }
        };
    }
}
