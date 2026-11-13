package dev.worldforge.settle.mc;

import dev.worldforge.settle.TradeNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.*;

/**
 * Physical LOD adapter for the canonical wf-settlements shipment registry.
 * It never owns cargo or progress: every visible caravan is a projection of a persisted Shipment.
 */
public final class SettlementCaravans {
    private static final String TAG = "wf_settlement_shipment_caravan";
    private static final double SHOW_RADIUS = 112, HIDE_RADIUS = 148;
    private static final Map<Long, List<UUID>> VISIBLE = new HashMap<>();

    public static void tick(MinecraftServer server, Collection<TradeNetwork.Shipment> shipments) {
        if (server == null || shipments == null) return;
        Set<Long> active = new HashSet<>();
        for (TradeNetwork.Shipment shipment : shipments) {
            if (!("в пути".equals(shipment.status) || "ожидает места".equals(shipment.status))
                    || !shipment.physical()) continue;
            ServerLevel level = level(server, shipment.fromDimension);
            if (level == null) continue;
            double[] head = routePositionForSnapshot(shipment.path(), shipment.progress(),
                    shipment.fromX, shipment.fromZ, shipment.toX, shipment.toZ);
            double x = head[0], z = head[1], yaw = head[2];
            ServerPlayer nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.serverLevel() != level) continue;
                double distance = Math.hypot(player.getX() - x, player.getZ() - z);
                if (distance < nearestDistance) { nearestDistance = distance; nearest = player; }
            }
            boolean visible = VISIBLE.containsKey(shipment.id);
            if (nearest == null || nearestDistance > (visible ? HIDE_RADIUS : SHOW_RADIUS)) continue;
            BlockPos probe = BlockPos.containing(x, level.getMinBuildHeight(), z);
            if (!level.hasChunkAt(probe)) continue; // never force-load a remote route
            active.add(shipment.id);
            List<UUID> ids = VISIBLE.computeIfAbsent(shipment.id, ignored -> new ArrayList<>());
            if (ids.isEmpty()) spawn(level, shipment, x, z, ids);
            int index = 0;
            for (UUID id : ids) {
                Entity entity = level.getEntity(id);
                if (entity == null) continue;
                double offset = index++ * 2.4;
                double px = x - Math.sin(yaw) * offset;
                double pz = z - Math.cos(yaw) * offset;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        (int)Math.floor(px), (int)Math.floor(pz));
                entity.moveTo(px, y, pz, (float)Math.toDegrees(yaw), 0);
            }
        }
        for (Iterator<Map.Entry<Long,List<UUID>>> it = VISIBLE.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Long,List<UUID>> entry = it.next();
            if (active.contains(entry.getKey())) continue;
            discard(server, entry.getValue());
            it.remove();
        }
    }

    /** Reuse wf-trade's CaravanPath interpolation when installed; deterministic polyline fallback otherwise. */
    public static double[] routePositionForSnapshot(double[][] path, double progress,
                                           double fromX, double fromZ, double toX, double toZ) {
        if (path == null || path.length < 2) path = new double[][]{{fromX,fromZ},{toX,toZ}};
        try {
            Class<?> worldLayer = Class.forName("dev.worldforge.trade.mc.WorldLayer");
            Object result = worldLayer.getMethod("settlementPosition", double[][].class, double.class)
                    .invoke(null, path, progress);
            if (result instanceof double[] position && position.length >= 3
                    && Double.isFinite(position[0]) && Double.isFinite(position[1]) && Double.isFinite(position[2]))
                return position;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional wf-trade not installed.
        }
        double total=0;
        for(int i=1;i<path.length;i++) total+=Math.hypot(path[i][0]-path[i-1][0],path[i][1]-path[i-1][1]);
        if(total<=1e-9)return new double[]{fromX,fromZ,0};
        double target=Math.max(0,Math.min(1,progress))*total, walked=0;
        for(int i=1;i<path.length;i++){
            double dx=path[i][0]-path[i-1][0], dz=path[i][1]-path[i-1][1], length=Math.hypot(dx,dz);
            if(walked+length>=target || i==path.length-1){
                double part=length<=1e-9?1:(target-walked)/length;
                return new double[]{path[i-1][0]+dx*part,path[i-1][1]+dz*part,Math.atan2(-dx,dz)};
            }
            walked+=length;
        }
        return new double[]{toX,toZ,0};
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels())
            if (level.dimension().location().toString().equals(dimension)) return level;
        return null;
    }

    private static void spawn(ServerLevel level, TradeNetwork.Shipment shipment,
                              double x, double z, List<UUID> ids) {
        for (int i = 0; i < 3; i++) {
            Mob mob = i == 0 ? EntityType.VILLAGER.create(level) : EntityType.TRADER_LLAMA.create(level);
            if (mob == null) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int)Math.floor(x), (int)Math.floor(z));
            mob.moveTo(x, y, z, 0, 0);
            mob.setNoAi(true);
            mob.setInvulnerable(true);
            mob.setSilent(true);
            mob.setPersistenceRequired();
            mob.addTag(TAG);
            mob.getPersistentData().putLong("wf_shipment_id", shipment.id);
            if (i == 0) {
                mob.setCustomName(Component.literal("Караван #" + shipment.id + " · " + shipment.quantity + " " + shipment.res));
                mob.setCustomNameVisible(true);
            }
            UUID id = mob.getUUID();
            ids.add(id);
            if (!level.addFreshEntity(mob)) ids.remove(id);
        }
    }

    private static void discard(MinecraftServer server, List<UUID> ids) {
        for (ServerLevel level : server.getAllLevels())
            for (UUID id : ids) {
                Entity entity = level.getEntity(id);
                if (entity != null) entity.discard();
            }
    }

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().getTags().contains(TAG)
                && !VISIBLE.values().stream().anyMatch(ids -> ids.contains(event.getEntity().getUUID())))
            event.setCanceled(true); // persisted visual remnants are rebuilt from the canonical shipment after restart
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        for (List<UUID> ids : VISIBLE.values()) discard(event.getServer(), ids);
        VISIBLE.clear();
    }
}
