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
    static Colony found(ServerLevel lv, BlockPos pos, String name, GenesisDefs.Tier tier, GenesisDefs.Focus focus,
                        ServerPlayer owner, Draft d, String operationId) {
        Colony c = SettleMod.found(lv, name, pos, operationId);
        if (c.genesisProvisioned) return c;
        if (c.genesisProvisioningRuntimeBlocked) {
            throw new IllegalStateException("Genesis provisioning had an uncommitted in-memory mutation; restart the server to reload the last durable checkpoint before reconciliation");
        }
        String spec = provisioningSpec(name, tier, focus, owner, d);
        if (!c.genesisProvisioningSpec.isEmpty() && !c.genesisProvisioningSpec.equals(spec)) {
            throw new IllegalStateException("Genesis operation ID was reused with a different settlement configuration");
        }
        if (!c.genesisProvisioningStarted) {
            c.genesisProvisioningStarted = true;
            c.genesisProvisioningSpec = spec;
            checkpoint(lv, c, "operation accepted");
        } else if (c.genesisProvisioningSpec.isEmpty()) {
            throw new IllegalStateException("Legacy interrupted Genesis settlement has no operation fingerprint and requires manual reconciliation");
        }

        Settlement s = c.s;
        try {
            // Each phase and its cursor are written in the same atomic colony snapshot.
            // If the process dies before the snapshot is committed, neither the phase's
            // model changes nor its cursor are durable, so restart can safely repeat it.
            if (c.genesisProvisioningStep < 1) {
                for (int i = 4; i < tier.workers(); i++) s.addWorker(null);
                c.genesisProvisioningStep = 1;
                checkpoint(lv, c, "worker tier");
            }
            if (c.genesisProvisioningStep < 2) {
                for (Map.Entry<String, Integer> e : tier.stock().entrySet()) {
                    int base = e.getKey().equals("wood") ? 60 : e.getKey().equals("food") ? 40 : e.getKey().equals("stone") ? 10 : 0;
                    int extra = e.getValue() - base;
                    if (extra > 0) s.add(e.getKey(), extra);
                }
                c.genesisProvisioningStep = 2;
                checkpoint(lv, c, "tier stock");
            }
            if (c.genesisProvisioningStep < 3) {
                if (focus != null) {
                    for (Map.Entry<String, Integer> e : focus.stock().entrySet()) if (e.getValue() > 0) s.add(e.getKey(), e.getValue());
                    for (int i = 0; i < focus.workers(); i++) s.addWorker(null);
                }
                c.genesisProvisioningStep = 3;
                checkpoint(lv, c, "settlement focus");
            }
            if (c.genesisProvisioningStep < 4) {
                int n = 0;
                for (String b : tier.buildings()) {
                    if (!s.defs.buildings.containsKey(b)) continue;
                    boolean placed = false;
                    for (int attempt = 0; attempt < 12; attempt++) {
                        double ang = (n + attempt * 0.37) * 2.399963, radius = 9 + 5 * Math.sqrt(n + 1) + attempt * 3;
                        int x = (int) Math.round(Math.cos(ang) * radius), z = (int) Math.round(Math.sin(ang) * radius);
                        try { s.place(b, x, z); placed = true; break; }
                        catch (Settlement.OrderException ex) { /* occupied: try another deterministic position */ }
                    }
                    if (!placed) throw new IllegalStateException("Could not place required Genesis building: " + b);
                    n++;
                }
                c.genesisProvisioningStep = 4;
                checkpoint(lv, c, "initial buildings");
            }
            if (c.genesisProvisioningStep < 5) {
                if (owner != null) {
                    c.owner = owner.getUUID().toString();
                    s.setTax(d.tax);
                    s.raids = d.raids;
                    s.auto = d.auto;
                }
                c.genesisProvisioningStep = 5;
                checkpoint(lv, c, "settlement ownership");
            }
            c.genesisProvisioned = true;
            checkpoint(lv, c, "completed");
            return c;
        } catch (RuntimeException failure) {
            c.genesisProvisioningRuntimeBlocked = true;
            throw failure;
        }
    }

    private static void checkpoint(ServerLevel level, Colony colony, String phase) {
        if (!SettleMod.saveDurably(level.getServer())) {
            colony.genesisProvisioningRuntimeBlocked = true;
            throw new IllegalStateException("Could not persist Genesis settlement checkpoint: " + phase);
        }
    }

    private static String provisioningSpec(String name, GenesisDefs.Tier tier, GenesisDefs.Focus focus,
                                           ServerPlayer owner, Draft draft) {
        StringBuilder value = new StringBuilder();
        value.append(name).append('|').append(tier.id()).append('|').append(tier.workers());
        new java.util.TreeMap<>(tier.stock()).forEach((key, amount) -> value.append("|tier:").append(key).append('=').append(amount));
        tier.buildings().forEach(building -> value.append("|building:").append(building));
        if (focus != null) {
            value.append("|focus:").append(focus.id()).append('|').append(focus.workers());
            new java.util.TreeMap<>(focus.stock()).forEach((key, amount) -> value.append("|focus-stock:").append(key).append('=').append(amount));
        }
        value.append("|owner:").append(owner == null ? "" : owner.getUUID())
                .append("|tax:").append(draft.tax).append("|raids:").append(draft.raids).append("|auto:").append(draft.auto);
        return value.toString();
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
