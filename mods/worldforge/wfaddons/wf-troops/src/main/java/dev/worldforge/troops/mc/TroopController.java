package dev.worldforge.troops.mc;

import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Morale;
import dev.worldforge.troops.Leadership;
import dev.worldforge.troops.Squads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Исполняет приказы отрядов: раз в полсекунды раздаёт каждому солдату точку в строю и режим боя. */
final class TroopController {
    private TroopController() {}

    static final double SPACING = 1.7;

    private static UUID safeUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    static void step(MinecraftServer server) {
        for (Squads.Squad s : new ArrayList<>(TroopsMod.SQUADS.all())) {
            List<TroopEntity> alive = new ArrayList<>();
            for (String id : s.members) { TroopEntity t = TroopsMod.LIVE.get(id); if (t != null && t.isAlive()) alive.add(t); }
            if (alive.isEmpty()) continue;                                         // отряд выгружен вместе с чанками: ждёт
            double cx = 0, cz = 0;
            for (TroopEntity t : alive) { cx += t.getX(); cz += t.getZ(); }
            cx /= alive.size(); cz /= alive.size();
            s.cx = cx; s.cz = cz;
            if (morale(server, s, alive, cx, cz)) continue;                          // отряд бежит: приказов не слушает
            Squads.Order o = s.current();
            TroopEntity.Engage defaultEngage = switch (s.stance) {
                case AGGRESSIVE -> TroopEntity.Engage.FULL;
                case DEFENSIVE -> TroopEntity.Engage.NEAR;
                case HOLD -> TroopEntity.Engage.RANGE;
                case PASSIVE -> TroopEntity.Engage.NONE;
            };
            if (o == null || o.kind() == Squads.Kind.HOLD) {
                for (TroopEntity t : alive) { t.hasDest = false; t.forced = null; t.engage = o == null ? defaultEngage : TroopEntity.Engage.RANGE; }
                continue;
            }
            switch (o.kind()) {
                case MOVE, ATTACK_MOVE, RETREAT, PATROL -> {
                    double yaw = Formations.yawTo(cx, cz, o.x(), o.z());
                    boolean far = Math.hypot(o.x() - cx, o.z() - cz) > 2;
                    if (!far) yaw = s.yaw; else s.yaw = yaw;
                    List<Formations.Slot> slots = Formations.slots(s.formation, alive.size(), SPACING);
                    List<double[]> world = Formations.place(slots, o.x(), o.z(), yaw);
                    List<String> roles = new ArrayList<>();
                    List<double[]> local = new ArrayList<>();
                    double rad = Math.toRadians(yaw), rx = Math.cos(rad), rz = Math.sin(rad);
                    for (TroopEntity t : alive) {
                        roles.add(t.def() == null ? "melee" : t.def().role());
                        local.add(new double[]{-(t.getX() - cx) * rx - (t.getZ() - cz) * rz, 0});            // боковое положение относительно курса
                    }
                    int[] as = Formations.assign(roles, slots, local);
                    double sum = 0;
                    TroopEntity.Engage eng = o.kind() == Squads.Kind.ATTACK_MOVE ? (s.stance == Squads.Stance.PASSIVE ? TroopEntity.Engage.NONE : TroopEntity.Engage.FULL) : TroopEntity.Engage.NONE;
                    for (int i = 0; i < alive.size(); i++) {
                        TroopEntity t = alive.get(i);
                        double[] w = world.get(as[i]);
                        t.hasDest = true; t.destX = w[0]; t.destZ = w[1]; t.destYaw = yaw; t.forced = null; t.engage = eng;
                        sum += Math.hypot(w[0] - t.getX(), w[1] - t.getZ());
                    }
                    if (sum / alive.size() < 3.0 && far == false || sum / alive.size() < 2.2) TroopsMod.SQUADS.completeCurrent(s.id);
                }
                case ATTACK -> {
                    UUID targetId = safeUuid(o.target());
                    Entity target = targetId == null ? null : TroopsMod.find(targetId);
                    if (!(target instanceof net.minecraft.world.entity.LivingEntity le) || !le.isAlive()) { TroopsMod.SQUADS.completeCurrent(s.id); break; }
                    for (TroopEntity t : alive) { t.hasDest = false; t.forced = target.getUUID(); t.engage = TroopEntity.Engage.FULL; }
                }
                case FOLLOW, GUARD -> {
                    UUID targetId = safeUuid(o.target().isEmpty() ? s.owner : o.target());
                    Entity target = targetId == null ? null : TroopsMod.find(targetId);
                    if (target == null) { TroopsMod.SQUADS.completeCurrent(s.id); break; }
                    List<Formations.Slot> slots = Formations.slots(s.formation, alive.size(), SPACING);
                    double yaw = target.getYRot() + 180;
                    List<double[]> world = Formations.place(slots, target.getX(), target.getZ() - 0, yaw);
                    for (int i = 0; i < alive.size(); i++) {
                        TroopEntity t = alive.get(i);
                        t.hasDest = true; t.destX = world.get(i)[0] + Math.sin(Math.toRadians(yaw)) * 3; t.destZ = world.get(i)[1] - Math.cos(Math.toRadians(yaw)) * 3;
                        t.destYaw = Double.NaN; t.forced = null; t.engage = defaultEngage;
                    }
                }
                default -> TroopsMod.SQUADS.completeCurrent(s.id);
            }
        }
    }

    /** Шаг морали, ауры командира и лечения. Возвращает true, если отряд бежит. */
    private static boolean morale(MinecraftServer server, Squads.Squad s, List<TroopEntity> alive, double cx, double cz) {
        int n = alive.size();
        double[] en = alive.get(0).enemiesAround(cx, cz, 28);
        boolean near = en[0] > 0;
        if (s.lastAlive < 0) s.lastAlive = n;
        double lost = Math.max(0, s.lastAlive - n) / (double) Math.max(1, s.initial);
        s.lastAlive = n;
        List<TroopEntity> leaders = new ArrayList<>();
        for (TroopEntity t : alive) {
            boolean appointed = !s.leaderCharacterId.isEmpty() && s.leaderCharacterId.equals(t.characterId);
            double aura = Leadership.aura(t.def() == null ? 0 : t.def().aura(),
                    appointed, appointed && s.leaderHero, appointed ? s.leadershipRank : 0);
            if (aura > 0) leaders.add(t);
        }
        s.morale = Morale.step(s.morale, n / (double) Math.max(1, s.initial), lost, near ? en[0] / n : 0, !leaders.isEmpty(), near, 0.5);
        boolean was = s.routed;
        s.routed = Morale.routed(was, s.morale, near);
        if (s.routed != was) {
            var p = server.getPlayerList().getPlayer(UUID.fromString(s.owner));
            if (p != null) p.displayClientMessage(net.minecraft.network.chat.Component.literal(s.routed ? "Отряд «" + s.name + "» бежит с поля боя!" : "Отряд «" + s.name + "» пришёл в себя"), false);
            if (s.routed) s.queue.clear();
        }
        for (TroopEntity t : alive) {
            boolean led = false;
            for (TroopEntity l : leaders) {
                boolean appointed = !s.leaderCharacterId.isEmpty() && s.leaderCharacterId.equals(l.characterId);
                double aura = Leadership.aura(l.def() == null ? 0 : l.def().aura(),
                        appointed, appointed && s.leaderHero, appointed ? s.leadershipRank : 0);
                if (t.distanceToSqr(l) <= aura * aura) { led = true; break; }
            }
            t.setDamageMult(Morale.damage(s.morale, led));
            t.setMorale((int) Math.round(s.morale * 100));
        }
        for (TroopEntity h : alive) {                                               // лекари лечат раненых своего отряда рядом
            if (h.def() == null || h.def().heal() <= 0) continue;
            List<TroopEntity> near2 = new ArrayList<>();
            for (TroopEntity t : alive) if (t.distanceToSqr(h) <= Morale.HEAL_RANGE * Morale.HEAL_RANGE) near2.add(t);
            double[] hp = new double[near2.size()], mx = new double[near2.size()];
            for (int i = 0; i < hp.length; i++) { hp[i] = near2.get(i).getHealth(); mx[i] = near2.get(i).getMaxHealth(); }
            double[] plan = Morale.healPlan(hp, mx, Morale.HEAL_TARGETS, h.def().heal() * 0.5);
            for (int i = 0; i < plan.length; i++) if (plan[i] > 0) near2.get(i).heal((float) plan[i]);
        }
        if (!s.routed) return false;
        double[] f = near ? Morale.flee(cx, cz, en[1], en[2], 30) : new double[]{cx, cz};
        for (TroopEntity t : alive) { t.hasDest = true; t.destX = f[0]; t.destZ = f[1]; t.destYaw = Double.NaN; t.forced = null; t.engage = TroopEntity.Engage.NONE; }
        return true;
    }
}
