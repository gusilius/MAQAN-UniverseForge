package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/**
 * Iterative interception of a moving point in system space.
 * The target ephemeris must use the same absolute simulation clock as the
 * orbital catalogue. No dimension/world-coordinate conversion is performed.
 */
public final class MovingTargetInterceptor {
    public record Request(DynamicSpaceRoutePlanner.Vec3 origin,
                          DynamicSpaceRoutePlanner.Ephemeris target,
                          DynamicSpaceRoutePlanner.Vec3 targetOffsetSystemSpace,
                          double departureTimeSeconds,double speedUnitsPerSecond,
                          double activationSeconds,int maxIterations,
                          double convergenceTolerance){
        public Request{
            Objects.requireNonNull(origin);Objects.requireNonNull(target);
            targetOffsetSystemSpace=targetOffsetSystemSpace==null
                    ?new DynamicSpaceRoutePlanner.Vec3(0,0,0):targetOffsetSystemSpace;
            if(!Double.isFinite(departureTimeSeconds)||!positive(speedUnitsPerSecond)
                    ||!nonNegative(activationSeconds)||maxIterations<1||maxIterations>64
                    ||!positive(convergenceTolerance))
                throw new IllegalArgumentException("Invalid interception request");
        }
    }
    public record Result(boolean converged,DynamicSpaceRoutePlanner.Vec3 interceptPosition,
                         double arrivalTimeSeconds,double travelDurationSeconds,
                         double missDistance,int iterations,String status){}
    private MovingTargetInterceptor(){}

    public static Result solve(Request request){
        Objects.requireNonNull(request);
        double arrival=request.departureTimeSeconds()+request.activationSeconds();
        DynamicSpaceRoutePlanner.Vec3 target=request.target().positionAt(arrival)
                .add(request.targetOffsetSystemSpace());
        double miss=Double.POSITIVE_INFINITY;
        for(int i=1;i<=request.maxIterations();i++){
            double travel=request.origin().distance(target)/request.speedUnitsPerSecond();
            double nextArrival=request.departureTimeSeconds()+request.activationSeconds()+travel;
            DynamicSpaceRoutePlanner.Vec3 next=request.target().positionAt(nextArrival)
                    .add(request.targetOffsetSystemSpace());
            miss=next.distance(target);
            if(miss<=request.convergenceTolerance()){
                return new Result(true,next,nextArrival,nextArrival-request.departureTimeSeconds(),
                        miss,i,"Moving target interception converged.");
            }
            target=next;arrival=nextArrival;
        }
        return new Result(false,target,arrival,arrival-request.departureTimeSeconds(),miss,
                request.maxIterations(),"No stable intercept found within the iteration limit.");
    }
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private static boolean nonNegative(double v){return Double.isFinite(v)&&v>=0;}
}
