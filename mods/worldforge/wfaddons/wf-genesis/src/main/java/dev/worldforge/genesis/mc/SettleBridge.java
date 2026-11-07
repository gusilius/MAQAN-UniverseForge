package dev.worldforge.genesis.mc;

import dev.worldforge.genesis.Draft;
import dev.worldforge.genesis.GenesisDefs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.mc.Colony;
import dev.worldforge.settle.mc.SettleMod;
import dev.worldforge.settle.mc.SettleViews;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/** Мягкая связь с wf_settle: основание стартового поселения. Класс не трогается, пока wf_settle не загружен. */
final class SettleBridge {
    private SettleBridge() {}

    static boolean ready() { return SettleMod.DEFS != null; }

    /** Основывает поселение уровня tier в точке. Правитель-игрок (owner) получает управление без прав оператора. */
    static Colony found(ServerLevel lv, BlockPos pos, String name, GenesisDefs.Tier tier, GenesisDefs.Focus focus, ServerPlayer owner, Draft d) {
        Colony c = SettleMod.found(lv, name, pos);
        Settlement s = c.s;
        for (int i = 4; i < tier.workers(); i++) s.addWorker(null);
        for (Map.Entry<String, Integer> e : tier.stock().entrySet()) s.add(e.getKey(), e.getValue() - (e.getKey().equals("wood") ? 60 : e.getKey().equals("food") ? 40 : e.getKey().equals("stone") ? 10 : 0));
        if (focus != null) {
            for (Map.Entry<String, Integer> e : focus.stock().entrySet()) s.add(e.getKey(), e.getValue());
            for (int i = 0; i < focus.workers(); i++) s.addWorker(null);
        }
        // здания по кругу вокруг центра, чтобы не пересекались
        int n = 0;
        for (String b : tier.buildings()) {
            if (!s.defs.buildings.containsKey(b)) continue;
            for (int attempt = 0; attempt < 12; attempt++) {
                double ang = (n + attempt * 0.37) * 2.399963, r = 9 + 5 * Math.sqrt(n + 1) + attempt * 3;
                int x = (int) Math.round(Math.cos(ang) * r), z = (int) Math.round(Math.sin(ang) * r);
                try { s.place(b, x, z); break; } catch (Settlement.OrderException ex) { /* занято - пробуем дальше */ }
            }
            n++;
        }
        if (owner != null) {
            c.owner = owner.getUUID().toString();
            s.setTax(d.tax);
            s.raids = d.raids;
            s.auto = d.auto;
        }
        return c;
    }

    static Colony nearest(ServerLevel lv, double x, double z, double maxDist) {
        Colony c = SettleMod.nearest(lv, x, z);
        if (c == null) return null;
        double dx = c.cx - x, dz = c.cz - z;
        return dx * dx + dz * dz <= maxDist * maxDist ? c : null;
    }

    static void panel(ServerPlayer sp, Colony c) { SettleViews.send(sp, c, SettleViews.PANEL); }

    static int cx(Colony c) { return c.cx; }

    static int cz(Colony c) { return c.cz; }
}
