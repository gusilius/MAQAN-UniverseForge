package dev.worldforge.settle.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.SettlementMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Player-facing orders for the authoritative settlement map. Coordinates are local offsets from settlement centre. */
public final class SettleMapCommands {
    private SettleMapCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wfmap")
                .then(Commands.literal("refresh").executes(c -> run(c, col -> {
                    ServerLevel level = col.level(c.getSource().getServer());
                    if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable");
                    int x = (int) Math.floor(c.getSource().getPosition().x) - col.cx;
                    int z = (int) Math.floor(c.getSource().getPosition().z) - col.cz;
                    return "Terrain samples added: " + col.captureTerrain(level, x - 64, z - 64, x + 64, z + 64);
                })))
                .then(Commands.literal("zone")
                        .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("z", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("width", IntegerArgumentType.integer(8, 2048))
                        .then(Commands.argument("depth", IntegerArgumentType.integer(8, 2048)).executes(c -> run(c, col -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            int w = IntegerArgumentType.getInteger(c, "width"), h = IntegerArgumentType.getInteger(c, "depth");
                            ServerLevel level = col.level(c.getSource().getServer());
                            if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable");
                            col.captureTerrain(level, x - 8, z - 8, x + w + 8, z + h + 8);
                            SettlementMap.Zone zone;
                            try { zone = SettlementMap.Zone.valueOf(StringArgumentType.getString(c, "type").toUpperCase(java.util.Locale.ROOT)); }
                            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Zones: residential, agriculture, industry, commerce, civic, military, storage"); }
                            SettlementMap.Parcel p = col.map.zone(StringArgumentType.getString(c, "id"), x, z, w, h, zone);
                            return "Parcel " + p.id + ": " + p.zone + " (" + p.width + "x" + p.depth + ")";
                        }))))))))
                .then(Commands.literal("road")
                        .then(Commands.argument("x1", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("z1", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("x2", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("z2", IntegerArgumentType.integer(-100000, 100000)).executes(c -> run(c, col -> {
                            int x1 = IntegerArgumentType.getInteger(c, "x1"), z1 = IntegerArgumentType.getInteger(c, "z1");
                            int x2 = IntegerArgumentType.getInteger(c, "x2"), z2 = IntegerArgumentType.getInteger(c, "z2");
                            ServerLevel level = col.level(c.getSource().getServer());
                            if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable");
                            col.captureTerrain(level, Math.min(x1,x2)-16, Math.min(z1,z2)-16, Math.max(x1,x2)+16, Math.max(z1,z2)+16);
                            SettlementMap.Road road = col.map.addRoad(x1, z1, x2, z2);
                            return "Road #" + road.id + ": " + Math.round(road.length()) + " blocks";
                        }))))))
                .then(Commands.literal("warehouse")
                        .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("x", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("z", IntegerArgumentType.integer(-100000, 100000))
                        .then(Commands.argument("capacity", IntegerArgumentType.integer(1, 1000000)).executes(c -> run(c, col -> {
                            int x = IntegerArgumentType.getInteger(c, "x"), z = IntegerArgumentType.getInteger(c, "z");
                            ServerLevel level = col.level(c.getSource().getServer());
                            if (level == null) throw new IllegalArgumentException("Settlement dimension is unavailable");
                            col.captureTerrain(level, x-8, z-8, x+8, z+8);
                            SettlementMap.Warehouse w = col.map.addWarehouse(StringArgumentType.getString(c, "id"), x, z, IntegerArgumentType.getInteger(c, "capacity"));
                            return "Warehouse " + w.id + " created";
                        }))))))
                .then(Commands.literal("logistics")
                        .then(Commands.argument("from", StringArgumentType.word())
                        .then(Commands.argument("to", StringArgumentType.word())
                        .then(Commands.argument("resource", StringArgumentType.word())
                        .then(Commands.argument("rate", IntegerArgumentType.integer(1, 10000)).executes(c -> run(c, col -> {
                            SettlementMap.LogisticsJob job = col.map.addLogistics(StringArgumentType.getString(c, "from"),
                                    StringArgumentType.getString(c, "to"), StringArgumentType.getString(c, "resource"),
                                    IntegerArgumentType.getInteger(c, "rate"));
                            return "Logistics route #" + job.id + " created";
                        }))))))
                .then(Commands.literal("info").executes(c -> run(c, col -> "Cells " + col.map.cells().size()
                        + ", parcels " + col.map.parcels.size() + ", roads " + col.map.roads.size()
                        + ", warehouses " + col.map.warehouses.size() + ", logistics " + col.map.logistics.size()
                        + ", goods moved " + Math.round(col.map.totalGoodsMoved)))));
    }

    private interface MapOrder { String run(Colony colony); }

    private static int run(CommandContext<CommandSourceStack> context, MapOrder order) {
        CommandSourceStack source = context.getSource();
        Colony colony = SettleMod.nearest(source.getLevel(), source.getPosition().x, source.getPosition().z);
        if (colony == null) { source.sendFailure(Component.literal("No settlement nearby")); return 0; }
        if (!dev.worldforge.mod.WFPerm.trusted(source)
                && !(source.getEntity() instanceof ServerPlayer player && player.getUUID().toString().equals(colony.owner))) {
            source.sendFailure(Component.literal("Only the settlement ruler may change its map")); return 0;
        }
        try {
            String message = order.run(colony);
            source.sendSuccess(() -> Component.literal(message), true);
            return 1;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal(ex.getMessage() == null ? "Map order failed" : ex.getMessage()));
            return 0;
        }
    }
}
