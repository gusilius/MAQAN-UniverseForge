package dev.worldforge.dialogue;

import java.util.Map;

/** Вызов условия или эффекта из JSON: id и остальные поля как аргументы. */
public final class Call {
    public final String id;
    public final Map<String, Object> args;

    Call(String id, Map<String, Object> args) { this.id = id; this.args = args; }
}
