package dev.worldforge.troops;

import dev.worldforge.mod.Json;

import java.util.*;

/**
 * Укрепления: стены с прочностью, башни, гарнизон и захват. Чистая модель: игровой слой раз в секунду подаёт, сколько осадного урона нанесено,
 * сколько врагов в радиусе, сколько защитников живо, и применяет результат (урон башен, смена владельца).
 */
public final class Forts {
    public static final double TOWER_DPS = 2.0, TOWER_BLIND = 0.3, REPAIR_PER_SEC = 0.8, CAPTURE_SECONDS = 20, CAPTURE_DECAY_SECONDS = 10, REPAIR_IDLE_SECONDS = 10;
    public static final int MAX_TOWERS = 8;

    public static final class Fort {
        public int id;
        public String name = "", owner = "", realm = "", dim = "minecraft:overworld";
        public double x, z, radius = 16, wall, wallMax = 400, capture, sinceHit = 999;
        public int towers = 2, garrison;

        /**
         * Кто для крепости враг: безхозная (бандитская) - все; свой владелец и то же государство - нет; два частных владельца без государств
         * друг другу не враги (чтобы игроки не громили друг друга); во всех остальных случаях - враг.
         */
        public boolean hostileTo(String otherOwner, String otherRealm) {
            if (owner.isEmpty() && realm.isEmpty()) return true;
            if (!owner.isEmpty() && owner.equals(otherOwner)) return false;
            if (!realm.isEmpty() && realm.equals(otherRealm)) return false;
            return !(realm.isEmpty() && (otherRealm == null || otherRealm.isEmpty()));
        }

        public boolean breached() { return wall <= 0; }
        public double wallShare() { return wallMax <= 0 ? 0 : wall / wallMax; }
        public boolean inside(double px, double pz) { return Math.hypot(px - x, pz - z) <= radius; }
    }

    public record Tick(boolean captured, double towerDps, boolean wallFell) {}

    private final Map<Integer, Fort> forts = new LinkedHashMap<>();
    private int next = 1;

    public Collection<Fort> all() { return forts.values(); }
    public Fort get(int id) { return forts.get(id); }

    public Fort create(String name, String owner, String realm, String dim, double x, double z, double radius, int towers) {
        Fort f = new Fort();
        f.id = next++; f.name = name == null || name.isBlank() ? "Крепость " + f.id : name; f.owner = owner == null ? "" : owner; f.realm = realm == null ? "" : realm;
        f.dim = dim; f.x = x; f.z = z; f.radius = Math.max(6, Math.min(64, radius)); f.towers = Math.max(0, Math.min(MAX_TOWERS, towers));
        f.wallMax = 200 + f.radius * 10 + f.towers * 50;
        f.wall = f.wallMax;
        forts.put(f.id, f);
        return f;
    }

    public boolean remove(int id) { return forts.remove(id) != null; }

    /** Урон стене; возвращает true, если стена только что рухнула. */
    public boolean damageWall(Fort f, double dmg) {
        if (dmg <= 0 || f.wall <= 0) return false;
        f.wall = Math.max(0, f.wall - dmg);
        f.sinceHit = 0;
        return f.wall <= 0;
    }

    /**
     * Секундный шаг.
     * @param siegeDmg   осадный урон по стене за секунду от врагов
     * @param attackers  вражеских солдат внутри радиуса
     * @param defenders  живых защитников гарнизона
     * @param enemiesNearWalls есть ли враги рядом с укреплением (башни стреляют и пока стена цела)
     */
    public Tick tick(Fort f, double siegeDmg, int attackers, int defenders, boolean enemiesNearWalls, double dt) {
        f.garrison = defenders;
        boolean fell = damageWall(f, siegeDmg * dt);
        f.sinceHit = siegeDmg > 0 ? 0 : f.sinceHit + dt;
        if (f.sinceHit >= REPAIR_IDLE_SECONDS && f.wall < f.wallMax && defenders > 0) f.wall = Math.min(f.wallMax, f.wall + REPAIR_PER_SEC * defenders * dt);
        boolean captured = false;
        if (!f.breached() || defenders > 0 || attackers == 0) f.capture = Math.max(0, f.capture - dt / CAPTURE_DECAY_SECONDS);
        else { f.capture += dt / CAPTURE_SECONDS; if (f.capture >= 1) { f.capture = 0; captured = true; } }
        double dps = !enemiesNearWalls || f.towers == 0 ? 0 : f.towers * TOWER_DPS * (defenders > 0 ? 1 : TOWER_BLIND);
        return new Tick(captured, dps, fell);
    }

    /** Смена владельца: новая власть, стена наполовину разрушена, гарнизона нет. */
    public void capture(Fort f, String newOwner, String newRealm) {
        f.owner = newOwner; f.realm = newRealm; f.capture = 0; f.garrison = 0;
        f.wall = Math.max(f.wall, f.wallMax * 0.25);
    }

    // ---- сохранение ----
    public String toJson() {
        List<Object> l = new ArrayList<>();
        for (Fort f : forts.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id); m.put("name", f.name); m.put("owner", f.owner); m.put("realm", f.realm); m.put("dim", f.dim); m.put("x", f.x); m.put("z", f.z);
            m.put("radius", f.radius); m.put("wall", f.wall); m.put("wallMax", f.wallMax); m.put("towers", f.towers); m.put("capture", f.capture);
            l.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", "wf-forts/1"); root.put("next", next); root.put("forts", l);
        return Json.write(root);
    }

    @SuppressWarnings("unchecked")
    public void fromJson(String json) {
        forts.clear();
        Object o = Json.parse(json);
        if (!(o instanceof Map)) return;
        Map<String, Object> root = (Map<String, Object>) o;
        next = root.get("next") instanceof Number n ? n.intValue() : 1;
        if (root.get("forts") instanceof List<?> l) for (Object x : l) {
            Map<String, Object> m = (Map<String, Object>) x;
            Fort f = new Fort();
            f.id = ((Number) m.get("id")).intValue(); f.name = String.valueOf(m.get("name")); f.owner = String.valueOf(m.get("owner")); f.realm = String.valueOf(m.get("realm"));
            f.dim = String.valueOf(m.get("dim")); f.x = ((Number) m.get("x")).doubleValue(); f.z = ((Number) m.get("z")).doubleValue();
            f.radius = ((Number) m.get("radius")).doubleValue(); f.wall = ((Number) m.get("wall")).doubleValue(); f.wallMax = ((Number) m.get("wallMax")).doubleValue();
            f.towers = ((Number) m.get("towers")).intValue(); f.capture = ((Number) m.get("capture")).doubleValue();
            forts.put(f.id, f);
            next = Math.max(next, f.id + 1);
        }
    }
}
