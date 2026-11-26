package dev.worldforge.dialogue;

import java.util.Map;

/** Условие показа реплики или выбора. Регистрируется в {@link DialogueApi} под id и получает аргументы из JSON. */
@FunctionalInterface
public interface Condition {
    boolean test(Ctx ctx, Map<String, Object> args);
}
