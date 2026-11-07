package com.maqan.galacticnavigator.navigation;

import net.minecraft.client.Minecraft;

/**
 * Read-only hand-off of the latest calculated system-space corridor to the
 * Navigator atlas. The corridor is periodically recalculated as the player
 * and authored obstacle ephemerides move; it never executes travel.
 */
public final class NavigatorRoutePreview {
    public record Preview(String systemId,DynamicSpaceRoutePlanner.Result route,
                          double calculatedAtSeconds,int obstacleCount,int unknownBodyCount,
                          DynamicSpaceRoutePlanner.Vec3 destination,double speedUnitsPerSecond,
                          double clearance,int samplesPerSegment,int maxDetours,int maxReplans) {}
    private static volatile Preview current;
    private static long lastRefreshTick=Long.MIN_VALUE;
    private NavigatorRoutePreview(){}

    public static synchronized void publish(String systemId,DynamicSpaceRoutePlanner.Result route,
            double calculatedAtSeconds,CosmicSpaceObstacleCatalog.Snapshot snapshot,
            DynamicSpaceRoutePlanner.Request request){
        if(systemId==null||systemId.isBlank()||route==null||!route.valid()
                ||snapshot==null||request==null){clear();return;}
        current=new Preview(systemId,route,calculatedAtSeconds,snapshot.obstacles().size(),
                snapshot.bodiesWithoutPhysicalRadius(),request.destination(),
                request.speedUnitsPerSecond(),request.clearance(),request.samplesPerSegment(),
                request.maxDetours(),request.maxReplans());
        lastRefreshTick=Long.MIN_VALUE;
    }

    /** Called from the client player-space tick; throttled to one replan per 10 game ticks. */
    public static synchronized void refresh(Minecraft minecraft,
            com.maqan.galacticnavigator.navigation.NavigatorPlayerMarker marker){
        Preview previous=current;
        if(previous==null||minecraft==null||minecraft.level==null||marker==null
                ||!marker.positionKnown())return;
        long tick=minecraft.level.getGameTime();
        if(lastRefreshTick!=Long.MIN_VALUE&&tick-lastRefreshTick<10)return;
        lastRefreshTick=tick;
        var snapshot=CosmicSpaceObstacleCatalog.snapshot(previous.systemId(),marker.sampledAtSeconds());
        var request=CosmicSpaceObstacleCatalog.requestFromPlayer(previous.systemId(),minecraft.level,
                previous.destination(),previous.speedUnitsPerSecond(),previous.clearance(),
                previous.samplesPerSegment(),previous.maxDetours(),previous.maxReplans(),snapshot);
        if(request.isEmpty()){current=null;return;}
        var route=DynamicSpaceRoutePlanner.plan(request.get());
        if(!route.valid()){current=null;return;}
        current=new Preview(previous.systemId(),route,marker.sampledAtSeconds(),
                snapshot.obstacles().size(),snapshot.bodiesWithoutPhysicalRadius(),
                previous.destination(),previous.speedUnitsPerSecond(),previous.clearance(),
                previous.samplesPerSegment(),previous.maxDetours(),previous.maxReplans());
    }

    public static Preview current(){return current;}
    public static synchronized void clear(){current=null;lastRefreshTick=Long.MIN_VALUE;}
}
