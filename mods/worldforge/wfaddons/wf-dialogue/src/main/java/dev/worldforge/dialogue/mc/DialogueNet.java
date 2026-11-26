package dev.worldforge.dialogue.mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Сеть диалогов: канал wf_dialogue:main. S2C: View, Close. C2S: Choose, CloseReq. Классы клиента грузятся только через DistExecutor. */
public final class DialogueNet {
    private static final String PROTO = "2";
    public static final int MAX_CHOICES = 16;
    public static final byte MODE_CHOICES = 0, MODE_CONTINUE = 1, MODE_END = 2;
    public static SimpleChannel CHANNEL;

    private DialogueNet() {}

    public static void init() {
        CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation("wf_dialogue", "main"), () -> PROTO,
                PROTO::equals, PROTO::equals);
        CHANNEL.registerMessage(0, OpenView.class, OpenView::encode, OpenView::decode, OpenView::handle);
        CHANNEL.registerMessage(1, Close.class, Close::encode, Close::decode, Close::handle);
        CHANNEL.registerMessage(2, Choose.class, Choose::encode, Choose::decode, Choose::handle);
        CHANNEL.registerMessage(3, CloseReq.class, CloseReq::encode, CloseReq::decode, CloseReq::handle);
    }

    public static void sendTo(ServerPlayer sp, Object msg) {
        if (CHANNEL != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), msg);
    }

    /** S2C: реплика и список ответов. */
    public static final class OpenView {
        public final long sessionId;
        public final int revision;
        public final String speaker, text;
        public final byte mode;
        public final List<String> choices;

        public OpenView(long sessionId, int revision, String speaker, String text, byte mode, List<String> choices) {
            this.sessionId = sessionId; this.revision = revision; this.speaker = speaker; this.text = text; this.mode = mode; this.choices = choices;
        }

        static void encode(OpenView m, FriendlyByteBuf b) {
            b.writeLong(m.sessionId);
            b.writeVarInt(m.revision);
            b.writeUtf(m.speaker);
            b.writeUtf(m.text);
            b.writeByte(m.mode);
            int n = Math.min(MAX_CHOICES, m.choices.size());
            b.writeVarInt(n);
            for (int i = 0; i < n; i++) b.writeUtf(m.choices.get(i));
        }

        static OpenView decode(FriendlyByteBuf b) {
            long id = b.readLong();
            int revision = b.readVarInt();
            String sp = b.readUtf(), tx = b.readUtf();
            byte mode = b.readByte();
            int n = Math.min(MAX_CHOICES, b.readVarInt());
            List<String> l = new ArrayList<>();
            for (int i = 0; i < n; i++) l.add(b.readUtf());
            return new OpenView(id, revision, sp, tx, mode, l);
        }

        static void handle(OpenView m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.worldforge.dialogue.mc.client.DialogueClient.onView(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: разговор закончен, закрыть экран. */
    public static final class Close {
        public final long sessionId;

        public Close(long sessionId) { this.sessionId = sessionId; }

        static void encode(Close m, FriendlyByteBuf b) { b.writeLong(m.sessionId); }

        static Close decode(FriendlyByteBuf b) { return new Close(b.readLong()); }

        static void handle(Close m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.worldforge.dialogue.mc.client.DialogueClient.onClose(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: выбранный ответ (для «Далее» и «Закрыть» индекс 0). */
    public static final class Choose {
        public final long sessionId;
        public final int revision, index;

        public Choose(long sessionId, int revision, int index) {
            this.sessionId = sessionId; this.revision = revision; this.index = index;
        }

        static void encode(Choose m, FriendlyByteBuf b) {
            b.writeLong(m.sessionId); b.writeVarInt(m.revision); b.writeVarInt(m.index);
        }

        static Choose decode(FriendlyByteBuf b) {
            return new Choose(b.readLong(), b.readVarInt(), b.readVarInt());
        }

        static void handle(Choose m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> { if (sp != null) DialogueMc.onChoose(sp, m.sessionId, m.revision, m.index); });
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: игрок закрыл экран (Esc). */
    public static final class CloseReq {
        public final long sessionId;

        public CloseReq(long sessionId) { this.sessionId = sessionId; }

        static void encode(CloseReq m, FriendlyByteBuf b) { b.writeLong(m.sessionId); }

        static CloseReq decode(FriendlyByteBuf b) { return new CloseReq(b.readLong()); }

        static void handle(CloseReq m, Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> { if (sp != null) DialogueMc.onCloseRequest(sp, m.sessionId); });
            ctx.get().setPacketHandled(true);
        }
    }
}
