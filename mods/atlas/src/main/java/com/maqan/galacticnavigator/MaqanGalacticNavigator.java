package com.maqan.galacticnavigator;

import net.minecraftforge.fml.common.Mod;
import com.maqan.galacticnavigator.network.NavigatorNetwork;

/** Common-side entry point. Client-only classes are deliberately not referenced here. */
@Mod(MaqanGalacticNavigator.MODID)
public final class MaqanGalacticNavigator {
    public static final String MODID = "maqan_galactic_navigator";

    public MaqanGalacticNavigator() {
        NavigatorNetwork.register();
        // Client UI and key bindings are registered by Dist.CLIENT event subscribers.
        // Keeping this entry point side-neutral prevents dedicated-server classloading crashes.
    }
}
