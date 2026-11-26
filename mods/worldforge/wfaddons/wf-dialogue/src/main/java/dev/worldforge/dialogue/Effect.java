package dev.worldforge.dialogue;

import java.util.Map;

/** Действие при входе в реплику или выборе ответа. */
@FunctionalInterface
public interface Effect {
    void run(Ctx ctx, Map<String, Object> args);
}
