package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.data.GalaxyCatalog;
import com.maqan.galacticnavigator.navigation.MovingTargetInterception;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker;
import com.maqan.galacticnavigator.navigation.NavigatorPlayerMarkerTracker;
import com.maqan.galacticnavigator.navigation.NavigatorRoutePreview;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes the player in Cosmic Horizons system-space only when the active
 * Minecraft dimension is exactly the system dimension declared by the
 * Cosmic Horizons catalogue. Surface-dimension coordinates are never reused
 * as galactic coordinates.
 */
public final class CosmicPlayerSpaceAdapter {
    public enum Status { CONNECTED, SHARED_DIMENSION, NO_PLAYER, NO_SYSTEM_FOR_DIMENSION }
    private static volatile Status status=Status.NO_PLAYER;
    private static volatile String detail="Waiting for player";
    private CosmicPlayerSpaceAdapter(){}

    public static void tick(Minecraft minecraft) {
        if(minecraft==null||minecraft.player==null||minecraft.level==null){
            clear(Status.NO_PLAYER,"Player is not in a world");
            return;
        }
        Level level=minecraft.level;
        String dimension=level.dimension().location().toString();
        List<Map.Entry<String,com.google.gson.JsonObject>> matches=new ArrayList<>();
        for(String systemId:GalaxyCatalog.universes()){
            var system=GalaxyCatalog.universe(systemId);
            if(dimension.equals(GalaxyCatalog.text(system,"dimension",""))
                    &&system.has("planet_data")&&system.get("planet_data").isJsonObject()
                    &&system.has("planets")&&system.get("planets").isJsonObject()){
                matches.add(Map.entry(systemId,system));
            }
        }
        if(matches.isEmpty()){
            clear(Status.NO_SYSTEM_FOR_DIMENSION,
                    "No Cosmic Horizons system declares dimension "+dimension);
            return;
        }
        // Several systems may intentionally share one CH space dimension.
        // Their body coordinates and the player's block coordinates still use
        // that dimension's common world frame; leave systemId blank rather than
        // arbitrarily assigning the player to one of those systems.
        String systemId=matches.size()==1?matches.get(0).getKey():"";

        var player=minecraft.player;
        var velocity=player.getDeltaMovement();
        // Cosmic Horizons renders its native planet_data positions in the
        // declared system dimension. Only in that exact dimension do the
        // player's world coordinates share the system-space frame.
        MovingTargetInterception.Vector3 position=
                new MovingTargetInterception.Vector3(player.getX(),player.getY(),player.getZ());
        MovingTargetInterception.Vector3 velocityPerSecond=
                new MovingTargetInterception.Vector3(velocity.x*20.0,velocity.y*20.0,velocity.z*20.0);
        double gameTimeSeconds=(level.getGameTime()+minecraft.getFrameTime())/20.0;
        NavigatorPlayerMarker marker=new NavigatorPlayerMarker(
                dimension,systemId,position,velocityPerSecond,
                -Math.toRadians(player.getYRot()),gameTimeSeconds,
                player.getGameProfile().getName(),true);
        NavigatorPlayerMarkerTracker.publish(marker);
        NavigatorRoutePreview.refresh(minecraft,marker);
        status=systemId.isBlank()?Status.SHARED_DIMENSION:Status.CONNECTED;
        detail=systemId.isBlank()
                ?"Shared Cosmic Horizons coordinate frame · "+matches.size()+" systems · "+dimension
                :"System-space link: "+systemId+" · "+dimension;
    }

    public static Status status(){return status;}
    public static String detail(){return detail;}

    public static void clearForWorldExit(){
        clear(Status.NO_PLAYER,"Player is not in a world");
        NavigatorRoutePreview.clear();
    }
    private static void clear(Status next,String message){
        NavigatorPlayerMarkerTracker.clear();
        NavigatorRoutePreview.clear();
        status=next;
        detail=message;
    }
}
