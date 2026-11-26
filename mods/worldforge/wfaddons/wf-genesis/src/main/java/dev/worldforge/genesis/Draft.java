package dev.worldforge.genesis;

import java.util.*;

/** Черновик персонажа одного игрока: всё, что выбрано в мастере, до подтверждения. */
public final class Draft {
    public final String owner;
    public String race = "", start = "", cls = "", settleName = "", focus = "";
    public int tax = 2;
    public boolean raids = true, auto = true;
    public final Map<String, String> look = new LinkedHashMap<>();
    public final Set<String> features = new LinkedHashSet<>();
    public final Set<String> profs = new LinkedHashSet<>();
    /** Очки, вложенные в атрибуты сверх базового значения (не меньше нуля). */
    public final Map<String, Integer> attrs = new LinkedHashMap<>();
    public final Map<String, Integer> skills = new LinkedHashMap<>();
    public boolean done;

    public Draft(String owner) { this.owner = owner; }

    public int attr(String id) { return attrs.getOrDefault(id, 0); }
}
