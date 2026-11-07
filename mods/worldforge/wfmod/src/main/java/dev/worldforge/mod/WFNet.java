package dev.worldforge.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Сеть: сервер отдаёт клиентам карту кусками, клиент просит рельеф, если его нет в кэше. */
public final class WFNet {
    private static final String PROTO = "1";
    public static final int CHUNK = 28_000;
    public static final byte STATE = 1, DELTA = 2, TERRAIN = 3, MARKERS = 4, TERRAIN_INFO = 5, CLEAR = 6, ACTIONS = 7, VIEW = 8;
    public static SimpleChannel CHANNEL;
    private static final AtomicInteger TRANSFER = new AtomicInteger(1);

    private WFNet() {}

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(WorldForgeMod.MOD_ID, "main"), () -> PROTO,
                NetworkRegistry.acceptMissingOr(PROTO), NetworkRegistry.acceptMissingOr(PROTO));
        CHANNEL.registerMessage(0, Blob.class, Blob::encode, Blob::decode, Blob::handle);
        CHANNEL.registerMessage(1, NeedTerrain.class, (m, b) -> { }, b -> new NeedTerrain(), NeedTerrain::handle);
    }

    /** Кусок большой передачи (сервер -> клиент). */
    public static final class Blob {
        public final byte kind;
        public final int id;
        public final short index, total;
        public final byte[] data;

        public Blob(byte kind, int id, short index, short total, byte[] data) {
            this.kind = kind; this.id = id; this.index = index; this.total = total; this.data = data;
        }

        static void encode(Blob m, FriendlyByteBuf b) {
            b.writeByte(m.kind);
            b.writeInt(m.id);
            b.writeShort(m.index);
            b.writeShort(m.total);
            b.writeByteArray(m.data);
        }

        static Blob decode(FriendlyByteBuf b) {
            return new Blob(b.readByte(), b.readInt(), b.readShort(), b.readShort(), b.readByteArray(CHUNK + 16));
        }

        static void handle(Blob m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.worldforge.mod.client.ClientHooks.onBlob(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Клиент просит рельеф карты (нет в кэше). */
    public static final class NeedTerrain {
        public NeedTerrain() { }

        static void handle(NeedTerrain m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> { if (sp != null) SyncService.sendTerrain(sp); });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Открыть у игрока окно аддона: id окна и произвольный текст (обычно JSON); клиент отдаёт их тому, кто вызвал {@link dev.worldforge.mod.client.ClientViews#register}. */
    public static void sendView(ServerPlayer sp, String viewId, String payload) {
        send(sp, VIEW, (viewId + "\n" + payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void send(ServerPlayer sp, byte kind, byte[] data) {
        if (CHANNEL == null) return;
        List<byte[]> parts = MapSync.split(data, CHUNK);
        int id = TRANSFER.getAndIncrement();
        for (int i = 0; i < parts.size(); i++) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new Blob(kind, id, (short) i, (short) parts.size(), parts.get(i)));
        }
    }
}
