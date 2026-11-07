package dev.worldforge.troops.mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Клиент -> сервер: приказ выделенным. action: 0 приказ, 1 строй, 2 поза, 3 рабочих в здание (x,z), 4 освободить рабочих, 5 собрать в отряд, 6 запросить меню стройки (x,z - где камера), 7 заложить здание (target - id, x,z - центр; ids - рабочие-строители). */
public record OrderPacket(int action, List<String> ids, int kind, double x, double z, String target, boolean queue, int arg) {
    public static final int ORDER = 0, FORMATION = 1, STANCE = 2, WORK = 3, RELEASE = 4, GROUP = 5, BUILD_LIST = 6, BUILD = 7;

    public static void encode(OrderPacket p, FriendlyByteBuf b) {
        b.writeVarInt(p.action);
        b.writeVarInt(Math.min(p.ids.size(), 200));
        for (int i = 0; i < p.ids.size() && i < 200; i++) b.writeUtf(p.ids.get(i), 40);
        b.writeVarInt(p.kind); b.writeDouble(p.x); b.writeDouble(p.z); b.writeUtf(p.target, 40); b.writeBoolean(p.queue); b.writeVarInt(p.arg);
    }

    public static OrderPacket decode(FriendlyByteBuf b) {
        int a = b.readVarInt(), n = Math.min(b.readVarInt(), 200);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(b.readUtf(40));
        return new OrderPacket(a, ids, b.readVarInt(), b.readDouble(), b.readDouble(), b.readUtf(40), b.readBoolean(), b.readVarInt());
    }

    public static void handle(OrderPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        ServerPlayer sp = c.getSender();
        if (sp != null) c.enqueueWork(() -> TroopsMod.handleOrder(sp, p));
        c.setPacketHandled(true);
    }
}
