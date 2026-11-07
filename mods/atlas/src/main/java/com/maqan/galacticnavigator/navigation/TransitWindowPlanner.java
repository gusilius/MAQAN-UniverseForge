package com.maqan.galacticnavigator.navigation;

import java.util.List;
import java.util.Objects;

/**
 * Entry/exit window validation for non-continuous drives.
 * It deliberately does not pretend a hyperspace/fold path is a sublight
 * segment through every point between the endpoints.
 */
public final class TransitWindowPlanner {
    public enum Mode { HYPERDRIVE, CALCULATED_FOLD, WORMHOLE, MASS_RELAY, JUMP_GATE }
    public record AvailabilityWindow(double periodSeconds,double openDurationSeconds,
                                     double phaseOffsetSeconds){
        public AvailabilityWindow{
            if(!positive(periodSeconds)||!positive(openDurationSeconds)
                    ||openDurationSeconds>periodSeconds||!Double.isFinite(phaseOffsetSeconds))
                throw new IllegalArgumentException("Invalid transit availability window");
        }
        public boolean isOpen(double absoluteTimeSeconds){
            if(!Double.isFinite(absoluteTimeSeconds))return false;
            double phase=(absoluteTimeSeconds-phaseOffsetSeconds)%periodSeconds;
            if(phase<0)phase+=periodSeconds;
            return phase<openDurationSeconds;
        }
        public double secondsUntilOpen(double absoluteTimeSeconds){
            if(isOpen(absoluteTimeSeconds))return 0;
            double phase=(absoluteTimeSeconds-phaseOffsetSeconds)%periodSeconds;
            if(phase<0)phase+=periodSeconds;
            return periodSeconds-phase;
        }
    }
    public record Link(String id,Mode mode,DynamicSpaceRoutePlanner.Vec3 entry,
                       DynamicSpaceRoutePlanner.Vec3 exit,double snapRadius,
                       AvailabilityWindow window,boolean discovered,boolean enabled){
        public Link{
            if(id==null||id.isBlank()||mode==null||entry==null||exit==null
                    ||!positive(snapRadius))throw new IllegalArgumentException("Invalid transit link");
        }
    }
    public record Request(Mode mode,DynamicSpaceRoutePlanner.Vec3 origin,
                          DynamicSpaceRoutePlanner.Vec3 destination,double departureTimeSeconds,
                          double travelDurationSeconds,double endpointClearance,
                          List<DynamicSpaceRoutePlanner.Obstacle> obstacles,Link link){
        public Request{
            Objects.requireNonNull(mode);Objects.requireNonNull(origin);Objects.requireNonNull(destination);
            obstacles=List.copyOf(obstacles==null?List.of():obstacles);
            if(!Double.isFinite(departureTimeSeconds)||!nonNegative(travelDurationSeconds)
                    ||!nonNegative(endpointClearance))throw new IllegalArgumentException("Invalid transit timing");
        }
    }
    public record Result(boolean valid,boolean ready,double departureTimeSeconds,
                         double arrivalTimeSeconds,double waitSeconds,String status,
                         String linkId){}
    private TransitWindowPlanner(){}

    public static Result evaluate(Request request){
        Objects.requireNonNull(request);
        double departure=request.departureTimeSeconds();
        double arrival=departure+request.travelDurationSeconds();
        if(request.mode()==Mode.WORMHOLE||request.mode()==Mode.MASS_RELAY
                ||request.mode()==Mode.JUMP_GATE){
            Link link=request.link();
            if(link==null||link.mode()!=request.mode()||!link.discovered()||!link.enabled())
                return invalid("No discovered, enabled link of the selected type connects these endpoints.");
            if(request.origin().distance(link.entry())>link.snapRadius()
                    ||request.destination().distance(link.exit())>link.snapRadius())
                return invalid("Requested coordinates do not match the authored link entry and exit.");
            if(link.window()!=null&&!link.window().isOpen(departure)){
                double wait=link.window().secondsUntilOpen(departure);
                return new Result(true,false,departure+wait,arrival+wait,wait,
                        "Transit link is closed; wait for its next availability window.",link.id());
            }
        }else if(request.link()!=null){
            return invalid("A direct drive cannot silently consume an unrelated transit link.");
        }
        for(DynamicSpaceRoutePlanner.Obstacle obstacle:request.obstacles()){
            if(obstacle.clearanceAt(request.origin(),departure)<=request.endpointClearance())
                return invalid("Unsafe transit entry: "+obstacle.classification()+" ["+obstacle.id()+"].");
            if(obstacle.clearanceAt(request.destination(),arrival)<=request.endpointClearance())
                return invalid("Unsafe transit exit at predicted arrival: "+obstacle.classification()
                        +" ["+obstacle.id()+"].");
        }
        return new Result(true,true,departure,arrival,0,
                "Entry and predicted exit windows are clear against known endpoint hazards.",
                request.link()==null?"":request.link().id());
    }
    private static Result invalid(String status){
        return new Result(false,false,0,0,0,status,"");
    }
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private static boolean nonNegative(double v){return Double.isFinite(v)&&v>=0;}
}
