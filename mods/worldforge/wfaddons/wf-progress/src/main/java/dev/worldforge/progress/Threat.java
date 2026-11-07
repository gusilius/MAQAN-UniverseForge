package dev.worldforge.progress;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Угрозы, зависящие от уровня: бандиты, звери, нежить. Уровень зоны растёт с расстоянием от точки отсчёта и опасностью местности и
 * подтягивается к уровню игрока в пределах окна (слабых земель не бывает совсем, непосильных тоже). Встреча - набор бойцов со своими
 * уровнями, множителями здоровья и урона. Определения - JSON (wf_progress/wf_threats), всё можно менять и добавлять.
 */
public final class Threat {
    public record Member(String role, String entity, int min, int max, double hp, double dmg, int armor, List<String> names) {}
    public record Def(String id, String name, String kind, int minLevel, int maxLevel, int weight, List<Member> members, int goldMin, int goldMax, double xp, String biome) {}

    public static final class Fighter {
        public String role, entity, name;
        public int level, armor;
        public double hpMult, dmgMult;
    }

    public static final class Encounter {
        public Def def;
        public int level, gold;
        public double xp;
        public final List<Fighter> fighters = new ArrayList<>();
    }

    public double hpPerLevel = 0.12, dmgPerLevel = 0.07, blocksPerLevel = 120;
    public int overPlayer = 5, underPlayer = 3, baseLevel = 1;
    public final Map<String, Def> defs = new LinkedHashMap<>();

    private static double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
    private static int num(Object o, int d) { return o instanceof Number n ? n.intValue() : d; }
    private static String str(Object o, String d) { return o == null ? d : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    public void load(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Нужен объект JSON");
        Map<String, Object> m = (Map<String, Object>) root;
        if (m.get("scaling") instanceof Map<?, ?> s) {
            hpPerLevel = dbl(s.get("hp_per_level"), hpPerLevel); dmgPerLevel = dbl(s.get("dmg_per_level"), dmgPerLevel); blocksPerLevel = dbl(s.get("blocks_per_level"), blocksPerLevel);
            overPlayer = num(s.get("over_player"), overPlayer); underPlayer = num(s.get("under_player"), underPlayer); baseLevel = num(s.get("base_level"), baseLevel);
        }
        if (m.get("threats") instanceof List<?> l) for (Object o : l) {
            Map<String, Object> t = (Map<String, Object>) o;
            String id = str(t.get("id"), "");
            if (id.isEmpty()) throw new IllegalArgumentException("У угрозы нет id");
            List<Member> ms = new ArrayList<>();
            if (t.get("members") instanceof List<?> ml) for (Object mo : ml) {
                Map<String, Object> mm = (Map<String, Object>) mo;
                List<String> names = new ArrayList<>();
                if (mm.get("names") instanceof List<?> nl) for (Object n : nl) names.add(String.valueOf(n));
                int min = 1, max = 1;
                if (mm.get("count") instanceof List<?> c && c.size() >= 2) { min = num(c.get(0), 1); max = num(c.get(1), min); } else if (mm.get("count") != null) min = max = num(mm.get("count"), 1);
                ms.add(new Member(str(mm.get("role"), "grunt"), str(mm.get("entity"), "minecraft:pillager"), min, max, dbl(mm.get("hp_mult"), 1), dbl(mm.get("dmg_mult"), 1), num(mm.get("armor"), 0), names));
            }
            if (ms.isEmpty()) throw new IllegalArgumentException(id + ": нет бойцов");
            int gmin = 0, gmax = 0;
            if (t.get("gold") instanceof List<?> g && g.size() >= 2) { gmin = num(g.get(0), 0); gmax = num(g.get(1), gmin); }
            defs.put(id, new Def(id, str(t.get("name"), id), str(t.get("kind"), "bandits"), num(t.get("min_level"), 1), num(t.get("max_level"), 99), num(t.get("weight"), 10), ms, gmin, gmax, dbl(t.get("xp"), 10), str(t.get("biome"), "")));
        }
    }

    /** Уровень зоны: расстояние от точки отсчёта + опасность (0..20) в окне вокруг уровня игрока. */
    public int zoneLevel(double distance, int playerLevel, double danger) {
        double raw = baseLevel + distance / blocksPerLevel + danger;
        double lo = Math.max(1, playerLevel - underPlayer), hi = playerLevel + overPlayer;
        return (int) Math.max(1, Math.round(Math.max(lo, Math.min(hi, raw))));
    }

    public double hpMult(int level) { return 1 + hpPerLevel * (level - 1); }

    public double dmgMult(int level) { return 1 + dmgPerLevel * (level - 1); }

    /** Подходящие угрозы данного вида для уровня. */
    public List<Def> candidates(String kind, int level) {
        List<Def> out = new ArrayList<>();
        for (Def d : defs.values()) if ((kind == null || d.kind().equals(kind)) && level >= d.minLevel() && level <= d.maxLevel()) out.add(d);
        return out;
    }

    public Encounter roll(String kind, int level, Random rng) {
        List<Def> c = candidates(kind, level);
        if (c.isEmpty()) return null;
        int total = 0;
        for (Def d : c) total += Math.max(1, d.weight());
        int pick = rng.nextInt(total);
        Def chosen = c.get(0);
        for (Def d : c) { pick -= Math.max(1, d.weight()); if (pick < 0) { chosen = d; break; } }
        return build(chosen, level, rng);
    }

    public Encounter build(Def d, int level, Random rng) {
        Encounter e = new Encounter();
        e.def = d; e.level = level;
        e.gold = d.goldMax() <= 0 ? 0 : (int) Math.round((d.goldMin() + rng.nextInt(d.goldMax() - d.goldMin() + 1)) * (1 + (level - 1) * 0.1));
        e.xp = d.xp() * (1 + (level - 1) * 0.15);
        for (Member m : d.members()) {
            int n = m.min() + (m.max() > m.min() ? rng.nextInt(m.max() - m.min() + 1) : 0);
            n += level / 15 > 0 && !m.role().equals("boss") && !m.role().equals("captain") ? rng.nextInt(level / 15 + 1) : 0;
            for (int i = 0; i < n; i++) {
                Fighter f = new Fighter();
                f.role = m.role(); f.entity = m.entity();
                f.level = Math.max(1, level + (m.role().equals("captain") ? 2 : m.role().equals("boss") ? 4 : rng.nextInt(3) - 1));
                f.hpMult = hpMult(f.level) * m.hp(); f.dmgMult = dmgMult(f.level) * m.dmg();
                f.armor = m.armor() + f.level / 6;
                f.name = m.names().isEmpty() ? m.role() : m.names().get(rng.nextInt(m.names().size()));
                e.fighters.add(f);
            }
        }
        return e;
    }

    /** Подпись над головой: [уровень] имя. */
    public static String label(Fighter f) { return "[" + f.level + "] " + f.name; }
}
