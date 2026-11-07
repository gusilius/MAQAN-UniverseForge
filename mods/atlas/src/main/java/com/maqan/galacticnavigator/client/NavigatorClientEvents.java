package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.MaqanGalacticNavigator;
import com.maqan.galacticnavigator.data.CosmicHorizonsScanner;
import com.maqan.galacticnavigator.navigation.NavigatorExplorationStore;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPosition;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerPositionTracker;
import com.maqan.galacticnavigator.navigation.NavigatorWaypoint;
import com.maqan.galacticnavigator.navigation.NavigatorWaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MaqanGalacticNavigator.MODID, value = Dist.CLIENT)
public final class NavigatorClientEvents {
    private static String lastExploredDimension="";
    private static int lastExploredChunkX=Integer.MIN_VALUE;
    private static int lastExploredChunkZ=Integer.MIN_VALUE;
    private static int lastExploredSectionY=Integer.MIN_VALUE;
    private static boolean deathWaypointRecorded;
    private static int deathWaypointSequence;

    private NavigatorClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            CosmicHorizonsScanner.refreshFromCosmicHorizons(minecraft.level);
            updatePlayerPosition(minecraft);
            CosmicPlayerSpaceAdapter.tick(minecraft);
            updateDeathWaypoint(minecraft);
        } else {
            NavigatorPlayerPositionTracker.clear();
            CosmicPlayerSpaceAdapter.clearForWorldExit();
            lastExploredDimension="";
            lastExploredChunkX=lastExploredChunkZ=lastExploredSectionY=Integer.MIN_VALUE;
        }

        if (NavigatorScreen.OPEN_KEY.consumeClick() && minecraft.screen == null)
            openMaqanWorkspace(minecraft);

        if (NavigatorScreen.OPEN_SURFACE_MAP_KEY.consumeClick() && minecraft.screen == null
                && NavigatorPlayerPositionTracker.current()!=null) {
            NavigatorScreen parent = new NavigatorScreen();
            parent.setEmbeddedInMaqan(true);
            openMaqanWorkspace(minecraft, new NavigatorSurfaceMapScreen(
                    NavigatorPlayerPositionTracker.current().dimensionId(), parent));
        }

        if (NavigatorScreen.CREATE_WAYPOINT_KEY.consumeClick() && minecraft.screen == null
                && NavigatorPlayerPositionTracker.current()!=null) {
            NavigatorPlayerPosition p=NavigatorPlayerPositionTracker.current();
            NavigatorWaypointStore.add(new NavigatorWaypoint(
                    "Waypoint",p.dimensionId(),"",NavigatorWaypoint.CoordinateFrame.DIMENSION,
                    NavigatorWaypoint.Scope.LOCAL,NavigatorWaypoint.Category.PERSONAL,
                    "Personal","◆",0xFF62D9F2,p.x(),p.y(),p.z(),true,false,""));
        }
    }

    /**
     * The optional Platform owns the only global UI when installed.
     * Atlas keeps its standalone entry point only for installations without Platform.
     */
    private static void openMaqanWorkspace(Minecraft minecraft) {
        openMaqanWorkspace(minecraft, null);
    }

    private static void openMaqanWorkspace(Minecraft minecraft,
            net.minecraft.client.gui.screens.Screen initialView) {
        if (net.minecraftforge.fml.ModList.get().isLoaded("maqan_platform")) {
            try {
                Class<?> type = Class.forName("dev.maqan.platform.client.PlatformShellScreen");
                Object instance = type.getDeclaredConstructor().newInstance();
                if (instance instanceof net.minecraft.client.gui.screens.Screen screen) {
                    minecraft.setScreen(screen);
                    if (initialView != null)
                        type.getMethod("showEmbeddedView", net.minecraft.client.gui.screens.Screen.class)
                                .invoke(instance, initialView);
                    return;
                }
            } catch (ReflectiveOperationException | LinkageError error) {
                com.mojang.logging.LogUtils.getLogger().error("Unable to open MAQAN global workspace", error);
            }
        }
        minecraft.setScreen(initialView == null ? new NavigatorScreen() : initialView);
    }

    private static void updateDeathWaypoint(Minecraft minecraft) {
        if(minecraft.player==null||minecraft.level==null)return;
        var player=minecraft.player;
        if(player.isDeadOrDying()&&!deathWaypointRecorded){
            String dimension=minecraft.level.dimension().location().toString();
            NavigatorWaypointStore.add(new NavigatorWaypoint(
                    "Death "+(++deathWaypointSequence),dimension,"",
                    NavigatorWaypoint.CoordinateFrame.DIMENSION,NavigatorWaypoint.Scope.LOCAL,
                    NavigatorWaypoint.Category.DEATH,"Deaths","✕",0xFFFF626D,
                    player.getX(),player.getY(),player.getZ(),true,false,"Last known death position"));
            deathWaypointRecorded=true;
        }else if(player.getHealth()>0.0F){
            deathWaypointRecorded=false;
        }
    }

    private static void updatePlayerPosition(Minecraft minecraft) {
        if(minecraft.player==null||minecraft.level==null)return;
        var player=minecraft.player;
        Level level=minecraft.level;
        String dimension=level.dimension().location().toString();
        double x=player.getX(),y=player.getY(),z=player.getZ();
        var velocity=player.getDeltaMovement();
        NavigatorPlayerPositionTracker.publish(new NavigatorPlayerPosition(
                dimension,x,y,z,velocity.x,velocity.y,velocity.z,
                player.getYRot(),player.getXRot(),System.currentTimeMillis()));

        int chunkX=player.chunkPosition().x,chunkZ=player.chunkPosition().z;
        int sectionY=Math.floorDiv(player.blockPosition().getY(),16);
        BlockPos position=player.blockPosition();
        boolean newChunk=!dimension.equals(lastExploredDimension)
                ||chunkX!=lastExploredChunkX||chunkZ!=lastExploredChunkZ;
        boolean newVerticalLayer=newChunk||sectionY!=lastExploredSectionY;
        if(newChunk||newVerticalLayer){
            var biomeHolder=level.getBiome(position);
            Biome biome=biomeHolder.value();
            String biomeId=biomeHolder.unwrapKey().map(key->key.location().toString()).orElse("unknown");
            if(newChunk){
                // Surface metadata is sampled only in the chunk the player currently occupies.
                int surfaceY=level.getHeight(Heightmap.Types.WORLD_SURFACE,position.getX(),position.getZ());
                // Capture only this loaded chunk while the player is inside it.
                // No neighboring chunk is queried, loaded, or revealed.
                int[] terrainColors=new int[256];
                int[] terrainHeights=new int[256];
                BlockPos.MutableBlockPos sample=new BlockPos.MutableBlockPos();
                for(int localZ=0;localZ<16;localZ++){
                    for(int localX=0;localX<16;localX++){
                        int index=localZ*16+localX;
                        int worldX=(chunkX<<4)+localX,worldZ=(chunkZ<<4)+localZ;
                        int topY=level.getHeight(Heightmap.Types.WORLD_SURFACE,worldX,worldZ);
                        terrainHeights[index]=topY;
                        sample.set(worldX,Math.max(level.getMinBuildHeight(),
                                Math.min(level.getMaxBuildHeight()-1,topY-1)),worldZ);
                        var state=level.getBlockState(sample);
                        var stateMapColor=state.getMapColor(level,sample);
                        terrainColors[index]=stateMapColor.col;
                    }
                }
                NavigatorExplorationStore.recordPlayerChunk(dimension,chunkX,chunkZ,
                        surfaceY,biomeId,biome.getWaterColor(),terrainColors,terrainHeights);
            }
            if(newVerticalLayer){
                NavigatorExplorationStore.recordPlayerLayer(dimension,chunkX,chunkZ,sectionY,
                        position.getY(),biomeId,biome.getWaterColor());
            }
            lastExploredDimension=dimension;
            lastExploredChunkX=chunkX;
            lastExploredChunkZ=chunkZ;
            lastExploredSectionY=sectionY;
        }
    }
}
