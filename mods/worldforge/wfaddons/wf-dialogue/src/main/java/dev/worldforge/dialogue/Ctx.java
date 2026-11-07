package dev.worldforge.dialogue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Контекст разговора: переменные для подстановки в текст ({player}, {npc}, {realm}...), флаги игрока (их хранит и сохраняет хозяин:
 * мод людей) и сервисы, которые нужны условиям и эффектам других аддонов (репутация, задания) - по классу.
 */
public final class Ctx {
    public final Map<String, String> vars = new HashMap<>();
    public final Set<String> flags;
    private final Map<Class<?>, Object> services = new HashMap<>();

    public Ctx(Set<String> flags) { this.flags = flags == null ? new HashSet<>() : flags; }

    public Ctx var(String k, String v) { vars.put(k, v); return this; }

    public <T> Ctx service(Class<T> type, T impl) { services.put(type, impl); return this; }

    public <T> T service(Class<T> type) {
        Object o = services.get(type);
        return o == null ? null : type.cast(o);
    }
}
