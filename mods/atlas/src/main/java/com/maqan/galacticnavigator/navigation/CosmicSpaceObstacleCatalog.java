package com.maqan.galacticnavigator.navigation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.maqan.galacticnavigator.client.NativeOrbitalSimulation;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog;
import com.maqan.galacticnavigator.data.GalaxyCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Builds time-dependent collision envelopes from explicit Cosmic Horizons data.
 * Unknown body sizes are omitted, never guessed from GUI scale or artwork.
 */
public final class CosmicSpaceObstacleCatalog {
    public record Snapshot(String systemId,double sampledAtSeconds,
                           List<DynamicSpaceRoutePlanner.Obstacle> obstacles,
                           int bodiesWithoutPhysicalRadius) {
        public Snapshot { obstacles=List.copyOf(obstacles); }
    }
    private CosmicSpaceObstacleCatalog(){}

    public static Snapshot snapshot(String systemId,double sampledAtSeconds){
        if(systemId==null||systemId.isBlank()||!Double.isFinite(sampledAtSeconds))
            return new Snapshot("",sampledAtSeconds,List.of(),0);
        JsonObject system=GalaxyCatalog.universe(systemId);
        if(system.size()==0||!system.has("planets")||!system.get("planets").isJsonObject())
            return new Snapshot(systemId,sampledAtSeconds,List.of(),0);

        JsonObject bodies=system.getAsJsonObject("planets");
        NativeOrbitalSimulation.ForecastSession forecast=
                NativeOrbitalSimulation.forecastSession(systemId,system,sampledAtSeconds*20.0);
        if(forecast==null)return new Snapshot(systemId,sampledAtSeconds,List.of(),bodies.size());
        List<DynamicSpaceRoutePlanner.Obstacle> result=new ArrayList<>();
        int missingRadius=0;
        for(Map.Entry<String,JsonElement> entry:bodies.entrySet()){
            if(!entry.getValue().isJsonObject())continue;
            JsonObject body=entry.getValue().getAsJsonObject();
            double radius=positive(body,"collision_radius","physical_radius","radius_blocks","radius");
            if(!Double.isFinite(radius)){
                if(!hasExplicitHazardRadius(body))missingRadius++;
                else radius=positive(body,"navigation_hazard_radius");
            }
            if(Double.isFinite(radius)){
                double margin=nonNegative(body,"navigation_clearance","collision_margin");
                String id=entry.getKey();
                String classification=firstString(body,"kind","classification","display_type","type");
                result.add(new DynamicSpaceRoutePlanner.Obstacle(
                        id,time->positionAt(forecast,body,time),radius,margin,classification));
            }
        }

        // Extended hazards are included only when the datapack explicitly marks
        // them as navigation hazards. Presentation geometry alone is not danger.
        for(CosmicEnvironmentCatalog.EnvironmentObject object:
                CosmicEnvironmentCatalog.snapshot(systemId,sampledAtSeconds*20.0)){
            JsonElement element=bodies.get(object.bodyId());
            if(element==null||!element.isJsonObject())continue;
            JsonObject body=element.getAsJsonObject();
            if(!explicitHazard(body)&&object.kind()!=CosmicEnvironmentCatalog.Kind.COMET)continue;
            double margin=nonNegative(body,"navigation_clearance","collision_margin");
            if(object.kind()==CosmicEnvironmentCatalog.Kind.COMET
                    &&Double.isFinite(object.comaRadius())&&object.comaRadius()>0){
                result.add(new DynamicSpaceRoutePlanner.Obstacle(
                        object.bodyId()+":coma",time->positionAt(forecast,body,time),
                        object.comaRadius(),margin,"comet coma"));
            }else if(object.kind()==CosmicEnvironmentCatalog.Kind.ASTEROID_BELT
                    &&object.hasBeltGeometry()&&Double.isFinite(object.beltThickness())
                    &&object.beltThickness()>0){
                double outer=object.outerBeltRadius();
                double halfThickness=object.beltThickness();
                var geometry=new DynamicSpaceRoutePlanner.AnnularBeltGeometry(
                        object.innerBeltRadius(),outer,halfThickness,
                        Math.toRadians(finiteOr(object.beltInclinationDegrees(),0)),
                        Math.toRadians(finiteOr(object.beltNodeDegrees(),0)));
                result.add(new DynamicSpaceRoutePlanner.Obstacle(
                        object.bodyId()+":belt",time->positionAt(forecast,body,time),
                        outer,margin,"asteroid belt",geometry));
            }else if(object.kind()==CosmicEnvironmentCatalog.Kind.NEBULA&&object.hasVolume()){
                var extent=object.volumeExtent();
                double rx,ry,rz;
                if(extent!=null&&extent.isPositiveExtent()){
                    rx=extent.x()*0.5;ry=extent.y()*0.5;rz=extent.z()*0.5;
                }else{
                    rx=ry=rz=object.volumeRadius();
                }
                double rotationX=Math.toRadians(number(body,"volume_rotation_x_degrees",0));
                double rotationY=Math.toRadians(number(body,"volume_rotation_y_degrees",0));
                double rotationZ=Math.toRadians(number(body,"volume_rotation_z_degrees",0));
                var geometry=new DynamicSpaceRoutePlanner.EllipsoidGeometry(
                        rx,ry,rz,rotationX,rotationY,rotationZ);
                if(object.position()==null||!object.position().isFinite()
                        ||object.volumeCenter()==null||!object.volumeCenter().isFinite())continue;
                var offset=new DynamicSpaceRoutePlanner.Vec3(
                        object.volumeCenter().x()-object.position().x(),
                        object.volumeCenter().y()-object.position().y(),
                        object.volumeCenter().z()-object.position().z());
                double broadRadius=Math.max(rx,Math.max(ry,rz));
                result.add(new DynamicSpaceRoutePlanner.Obstacle(
                        object.bodyId()+":nebula",
                        time->positionAt(forecast,body,time).add(
                                offset),
                        broadRadius,margin,"nebula hazard",geometry));
            }
        }
        return new Snapshot(systemId,sampledAtSeconds,result,missingRadius);
    }

    /**
     * Creates a dynamic route request only when the live player marker and the
     * selected system are in the same verified Cosmic Horizons dimension frame.
     */
    public static Optional<DynamicSpaceRoutePlanner.Request> requestFromPlayer(
            String systemId,Level level,DynamicSpaceRoutePlanner.Vec3 destination,
            double speedUnitsPerSecond,double clearance,int samplesPerSegment,
            int maxDetours,int maxReplans,Snapshot snapshot){
        if(level==null||destination==null)return Optional.empty();
        var marker=NavigatorPlayerMarkerTracker.current();
        if(marker==null||!marker.positionKnown()
                ||!level.dimension().location().toString().equals(marker.dimensionId()))return Optional.empty();
        JsonObject system=GalaxyCatalog.universe(systemId);
        String dimension=GalaxyCatalog.text(system,"dimension","");
        if(!dimension.equals(marker.dimensionId()))return Optional.empty();
        if(!marker.systemId().isBlank()&&!marker.systemId().equals(systemId))return Optional.empty();
        if(snapshot==null||!systemId.equals(snapshot.systemId()))return Optional.empty();
        DynamicSpaceRoutePlanner.Vec3 origin=new DynamicSpaceRoutePlanner.Vec3(
                marker.systemPosition().x(),marker.systemPosition().y(),marker.systemPosition().z());
        return Optional.of(new DynamicSpaceRoutePlanner.Request(origin,destination,
                marker.sampledAtSeconds(),speedUnitsPerSecond,clearance,snapshot.obstacles(),
                samplesPerSegment,maxDetours,maxReplans));
    }

    private static DynamicSpaceRoutePlanner.Vec3 positionAt(
            NativeOrbitalSimulation.ForecastSession forecast,JsonObject body,double absoluteTimeSeconds){
        double[] position=forecast.positionAt(body,absoluteTimeSeconds*20.0);
        return new DynamicSpaceRoutePlanner.Vec3(position[0],position[1],position[2]);
    }
    private static boolean explicitHazard(JsonObject body){
        return bool(body,"navigation_hazard")||bool(body,"navigation_obstacle")
                ||bool(body,"collision_enabled");
    }
    private static boolean hasExplicitHazardRadius(JsonObject body){
        return explicitHazard(body)&&Double.isFinite(positive(body,"navigation_hazard_radius"));
    }
    private static boolean bool(JsonObject body,String key){
        try{return body.has(key)&&!body.get(key).isJsonNull()&&body.get(key).getAsBoolean();}
        catch(RuntimeException ignored){return false;}
    }
    private static double finiteOr(double value,double fallback){
        return Double.isFinite(value)?value:fallback;
    }
    private static double positive(JsonObject object,String...keys){
        for(String key:keys){
            double value=number(object,key,Double.NaN);
            if(Double.isFinite(value)&&value>0)return value;
        }
        return Double.NaN;
    }
    private static double nonNegative(JsonObject object,String...keys){
        for(String key:keys){
            double value=number(object,key,Double.NaN);
            if(Double.isFinite(value)&&value>=0)return value;
        }
        return 0.0;
    }
    private static double number(JsonObject object,String key,double fallback){
        try{
            JsonElement value=object.get(key);
            if(value!=null&&!value.isJsonNull()){
                double result=value.getAsDouble();
                return Double.isFinite(result)?result:fallback;
            }
        }catch(RuntimeException ignored){}
        return fallback;
    }
    private static String firstString(JsonObject object,String...keys){
        for(String key:keys){
            try{
                if(object.has(key)&&!object.get(key).isJsonNull()){
                    String value=object.get(key).getAsString();
                    if(!value.isBlank())return value;
                }
            }catch(RuntimeException ignored){}
        }
        return "unknown";
    }
}
