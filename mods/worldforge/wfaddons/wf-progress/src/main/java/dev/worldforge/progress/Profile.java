package dev.worldforge.progress;

import java.util.*;

/** Прокачка одного владельца (игрока "p:<uuid>" или NPC "n:<id>"): уровень, опыт, очки, вложенные атрибуты, навыки и узлы. */
public final class Profile {
    public final String owner;
    public int level = 1, attrFree, skillFree, passiveFree;
    public long xp, totalXp;
    public String archetype = "";
    public final Map<String, Integer> attrs = new LinkedHashMap<>();        // вложенные очки
    public final Map<String, Integer> skills = new LinkedHashMap<>();
    public final Set<String> nodes = new LinkedHashSet<>();
    public int respecs;

    public Profile(String owner) { this.owner = owner; }

    public int attr(String id) { return attrs.getOrDefault(id, 0); }
    public int skill(String id) { return skills.getOrDefault(id, 0); }
    public int spentPassive() { int n = 0; for (String s : nodes) n++; return n; }
}
