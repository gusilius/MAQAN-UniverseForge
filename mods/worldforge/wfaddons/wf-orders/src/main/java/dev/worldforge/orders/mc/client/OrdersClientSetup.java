package dev.worldforge.orders.mc.client;

import dev.worldforge.mod.client.ClientViews;
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

/** Клиент wf_orders: окно организаций (клавиша O). */
@Mod.EventBusSubscriber(modid = "wf_orders", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class OrdersClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_orders.open", GLFW.GLFW_KEY_O, "key.categories.worldforge");

    private OrdersClientSetup() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register("wf_orders:orders", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof OrdersScreen s) s.setModel(payload); else mc.setScreen(new OrdersScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) {
                dev.worldforge.orders.mc.OrdersMod.LOGGER.error("wf_orders: не удалось открыть окно: {}", ex.toString());
            }
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wforders");
        });
    }
}
