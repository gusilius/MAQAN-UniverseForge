package dev.worldforge.troops.mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Клиент -> сервер: приказ выделенным. action: 0 приказ, 1 строй, 2 поза, 3 рабочих в здание (x,z), 4 освободить рабочих, 5 собрать в отряд, 6 запросить меню стройки (x,z - где камера), 7 заложить здание (target - id, x,z - центр; ids - рабочие-строители). */
public record OrderPacket(int action, List<String> ids, int kind, double x, double z, String target, boolean queue, int arg) {
    public static final int ORDER=0,FORMATION=1,STANCE=2,WORK=3,RELEASE=4,GROUP=5,BUILD_LIST=6,BUILD=7,WORK_STOP=8,WORK_HOLD=9,APPOINT=10,WORK_FOLLOW=11,WORK_AREA=12;

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

    public boolean valid() {
        if(ids==null||ids.size()>200||target==null||target.length()>40)return false;
        java.util.HashSet<String> unique=new java.util.HashSet<>();
        for(String id:ids)if(id==null||id.isBlank()||id.length()>40||!unique.add(id))return false;
        if(action<ORDER||action>WORK_AREA)return false;
        if(action==APPOINT&&(arg< -1||arg>10))return false;
        if(action==ORDER){
            if(kind<0||kind>=dev.worldforge.troops.Squads.Kind.values().length)return false;
            if(kind!=dev.worldforge.troops.Squads.Kind.ATTACK.ordinal()
                    &&(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000))return false;
            if(kind==dev.worldforge.troops.Squads.Kind.ATTACK.ordinal()){
                try{java.util.UUID.fromString(target);}catch(RuntimeException ex){return false;}
            }
        }
        if(action==BUILD&&(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000))return false;
        if(action==WORK_AREA&&(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000
                ||kind<0||kind>4||arg<0||arg>64))return false;
        if((action==WORK||action==WORK_STOP||action==WORK_HOLD||action==BUILD_LIST)
                &&(!Double.isFinite(x)||!Double.isFinite(z)||Math.abs(x)>30_000_000||Math.abs(z)>30_000_000))return false;
        return true;
    }

    public static void handle(OrderPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        ServerPlayer sp = c.getSender();
        if (sp != null && p != null && p.valid()) c.enqueueWork(() -> TroopsMod.handleOrder(sp, p));
        c.setPacketHandled(true);
    }
}
