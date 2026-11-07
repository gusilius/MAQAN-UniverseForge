package com.maqan.galacticnavigator.network;

import com.maqan.galacticnavigator.MaqanGalacticNavigator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.network.FriendlyByteBuf;

import java.util.function.Supplier;

/** Server-authoritative dimension teleport requests. */
public final class NavigatorNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(MaqanGalacticNavigator.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(PROTOCOL::equals)
            .serverAcceptedVersions(PROTOCOL::equals)
            .simpleChannel();
    private static boolean registered;

    private NavigatorNetwork() {}

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.messageBuilder(TeleportRequest.class, 0)
                .encoder(TeleportRequest::encode)
                .decoder(TeleportRequest::decode)
                .consumerMainThread(TeleportRequest::handle)
                .add();
        registered = true;
    }

    public static void teleport(String dimension, double x, double y, double z) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new TeleportRequest(dimension, x, y, z));
    }

    public record TeleportRequest(String dimension, double x, double y, double z) {
        static void encode(TeleportRequest msg, FriendlyByteBuf buf) {
            buf.writeUtf(msg.dimension, 256);
            buf.writeDouble(msg.x); buf.writeDouble(msg.y); buf.writeDouble(msg.z);
        }
        static TeleportRequest decode(FriendlyByteBuf buf) {
            return new TeleportRequest(buf.readUtf(256), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        static void handle(TeleportRequest msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                ResourceLocation id = ResourceLocation.tryParse(msg.dimension);
                if (id == null || !Double.isFinite(msg.x) || !Double.isFinite(msg.y) || !Double.isFinite(msg.z)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Invalid dimension or coordinates."));
                } else if (!player.server.isSingleplayer() && !player.hasPermissions(2)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Teleport requires permission level 2."));
                } else {
                    ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                    ServerLevel target = player.server.getLevel(key);
                    if (target == null) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Dimension is not loaded/registered: " + id));
                    } else if (msg.y < target.getMinBuildHeight() || msg.y >= target.getMaxBuildHeight()) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Y is outside this dimension's build height."));
                    } else {
                        BlockPos pos = BlockPos.containing(msg.x, msg.y, msg.z);
                        if (!target.getWorldBorder().isWithinBounds(pos)) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Coordinates are outside the world border."));
                        } else {
                            target.getChunkAt(pos);
                            player.teleportTo(target, msg.x + 0.5, msg.y, msg.z + 0.5, player.getYRot(), player.getXRot());
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[MAQAN] Teleported to " + id + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()));
                        }
                    }
                }
            }
            context.setPacketHandled(true);
        }
    }
}
