package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.MaqanGalacticNavigator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MaqanGalacticNavigator.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class NavigatorClientModEvents {
    private NavigatorClientModEvents() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(NavigatorScreen.OPEN_KEY);
        event.register(NavigatorScreen.OPEN_SURFACE_MAP_KEY);
        event.register(NavigatorScreen.CREATE_WAYPOINT_KEY);
    }
}
