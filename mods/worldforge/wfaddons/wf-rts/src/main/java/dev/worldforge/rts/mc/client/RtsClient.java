package dev.worldforge.rts.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.ClientViews;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/**
 * Клиент wf_rts: RTS-панель управления поселением (клавиша H). Сервер wf_settle шлёт то же состояние поселения под id "wf_settle:hud",
 * а этот аддон превращает его в RtsHud: выбор, карточка команд, группы управления, приоритеты работ.
 */
@Mod.EventBusSubscriber(modid = "wf_rts", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RtsClient {
    public static final String HUD = "wf_settle:hud";
    public static final KeyMapping KEY = new KeyMapping("key.wf_rts.hud", GLFW.GLFW_KEY_H, "key.categories.worldforge");

    private RtsClient() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register(HUD, RtsClient::open));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wfsettle view hud");
        });
    }

    @SuppressWarnings("unchecked")
    private static void open(String payload) {
        try {
            Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
            Defs d = new Defs();
            for (Object o : (List<Object>) m.get("defs")) d.add((String) o);
            Settlement s = Settlement.fromJson((String) m.get("s"), d);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RtsHud h) h.setModel(s); else mc.setScreen(new RtsHud(s, ClientViews::command));
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger("wf_rts").error("wf_rts: не удалось открыть панель: {}", ex.toString());
        }
    }
}
