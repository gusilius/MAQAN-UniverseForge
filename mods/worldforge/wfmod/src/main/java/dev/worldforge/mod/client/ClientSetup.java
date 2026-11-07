package dev.worldforge.mod.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/** Клавиши (M: карта, N: миникарта) и миникарта. Подключается только на клиенте. */
public final class ClientSetup {
    public static final KeyMapping OPEN = new KeyMapping("key.worldforge.map", GLFW.GLFW_KEY_M, "key.categories.worldforge");
    public static final KeyMapping MINI = new KeyMapping("key.worldforge.minimap", GLFW.GLFW_KEY_N, "key.categories.worldforge");

    private ClientSetup() {}

    public static void register(IEventBus modBus) {
        ClientViews.register("wf:clock", ClientClock::update);
        modBus.addListener(ClientSetup::keys);
        modBus.addListener(ClientSetup::overlays);
        MinecraftForge.EVENT_BUS.register(new ClientSetup());
    }

    private static void keys(RegisterKeyMappingsEvent e) {
        e.register(OPEN);
        e.register(MINI);
    }

    private static void overlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("minimap", new MiniMap());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (mc.player == null || mc.screen != null) continue;
            if (ClientState.state == null) {
                mc.player.displayClientMessage(Component.literal("На этой планете нет политической карты"), true);
            } else {
                mc.setScreen(new MapScreen());
            }
        }
        while (MINI.consumeClick()) {
            ClientState.minimap = !ClientState.minimap;
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(ClientState.minimap ? "Миникарта включена" : "Миникарта выключена"), true);
        }
    }
}
