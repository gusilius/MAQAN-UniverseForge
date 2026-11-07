package dev.worldforge.trade.mc.client;

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

/** Клиент wf_trade: окно «Торговля» (клавиша Y). */
@Mod.EventBusSubscriber(modid = "wf_trade", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TradeClientSetup {
    public static final KeyMapping KEY = new KeyMapping("key.wf_trade.open", GLFW.GLFW_KEY_Y, "key.categories.worldforge");

    private TradeClientSetup() {}

    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent e) { e.register(KEY); }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> ClientViews.register("wf_trade:market", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof TradeScreen s) s.setModel(payload); else mc.setScreen(new TradeScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) {
                dev.worldforge.trade.mc.TradeMod.LOGGER.error("wf_trade: не удалось открыть окно: {}", ex.toString());
            }
        }));
        e.enqueueWork(() -> ClientViews.register("wf_trade:shop", payload -> {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof ShopScreen s) s.setModel(payload); else mc.setScreen(new ShopScreen(payload, ClientViews::command));
            } catch (RuntimeException ex) {
                dev.worldforge.trade.mc.TradeMod.LOGGER.error("wf_trade: не удалось открыть лавку: {}", ex.toString());
            }
        }));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent t) -> {
            if (t.phase != TickEvent.Phase.END) return;
            var mc = Minecraft.getInstance();
            while (KEY.consumeClick()) if (mc.player != null && mc.screen == null) ClientViews.command("wftrade");
        });
    }
}
