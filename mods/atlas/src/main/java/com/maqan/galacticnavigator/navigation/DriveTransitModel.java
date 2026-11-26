package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/**
 * Drive-specific travel-time and exposure model.
 *
 * Values are expressed in the same system-space units used by the catalogue.
 * This estimates transit; it does not steer a ship or authorize teleportation.
 */
public final class DriveTransitModel {
    public enum Exposure {
        CONTINUOUS_SPACE,       // body collision corridor must be checked along the path
        DISCRETE_TRANSIT,       // jump has entry/exit windows, not a traversed sublight corridor
        LINKED_ENDPOINTS        // only the authored gate/relay/wormhole endpoints are valid
    }

    public record Profile(NavigatorTravelPlanner.Drive drive, Exposure exposure,
                          double cruiseSpeed, double acceleration,
                          double brakingAcceleration, double activationSeconds,
                          double cooldownSeconds, double jumpRange,
                          boolean requiresDiscoveredLink) {
        public Profile {
            Objects.requireNonNull(drive);
            Objects.requireNonNull(exposure);
            if(!positive(cruiseSpeed)||!positive(acceleration)||!positive(brakingAcceleration)
                    ||!nonNegative(activationSeconds)||!nonNegative(cooldownSeconds)
                    ||!positive(jumpRange))
                throw new IllegalArgumentException("Invalid drive transit profile");
        }
    }

    public record Estimate(double durationSeconds,int legs,double accelerationSeconds,
                           double cruiseSeconds,double brakingSeconds,double activationSeconds,
                           Exposure exposure,String operation) {}

    private DriveTransitModel(){}

    public static Profile profile(NavigatorTravelPlanner.Drive drive,double configuredJumpRange){
        Objects.requireNonNull(drive);
        double range=positive(configuredJumpRange)?configuredJumpRange:1.0;
        return switch(drive){
            case SUBLIGHT -> new Profile(drive,Exposure.CONTINUOUS_SPACE,
                    drive.speedUnitsPerSecond,drive.speedUnitsPerSecond/20.0,
                    drive.speedUnitsPerSecond/20.0,drive.activationSeconds,0,range,false);
            case WARP -> new Profile(drive,Exposure.CONTINUOUS_SPACE,
                    drive.speedUnitsPerSecond,drive.speedUnitsPerSecond/8.0,
                    drive.speedUnitsPerSecond/8.0,drive.activationSeconds,2.0,range,false);
            case HYPERDRIVE -> new Profile(drive,Exposure.DISCRETE_TRANSIT,
                    drive.speedUnitsPerSecond,drive.speedUnitsPerSecond,
                    drive.speedUnitsPerSecond,drive.activationSeconds,4.0,range,false);
            case NAVIGATOR -> new Profile(drive,Exposure.DISCRETE_TRANSIT,
                    drive.speedUnitsPerSecond,drive.speedUnitsPerSecond,
                    drive.speedUnitsPerSecond,drive.activationSeconds,0,range,false);
            case WORMHOLE,MASS_RELAY,GATE -> new Profile(drive,Exposure.LINKED_ENDPOINTS,
                    drive.speedUnitsPerSecond>0?drive.speedUnitsPerSecond:1.0,1.0,1.0,
                    drive.activationSeconds,0,range,true);
        };
    }

    public static Estimate estimate(Profile profile,double distance,double navigationSkill){
        Objects.requireNonNull(profile);
        if(!Double.isFinite(distance)||distance<0||!Double.isFinite(navigationSkill)
                ||navigationSkill<0||navigationSkill>1)
            throw new IllegalArgumentException("Invalid distance or navigation skill");
        if(distance==0)return new Estimate(0,0,0,0,0,0,profile.exposure(),"Already at destination");
        return switch(profile.drive()){
            case SUBLIGHT -> continuous(profile,distance,"Sublight accelerate / cruise / brake");
            case WARP -> continuous(profile,distance,"Warp spool / translation / deceleration");
            case HYPERDRIVE -> jumps(profile,distance,"Hyperspace spool / jump / cooldown");
            case NAVIGATOR -> fold(profile,distance,navigationSkill);
            case WORMHOLE,MASS_RELAY,GATE -> linked(profile,distance);
        };
    }

    private static Estimate continuous(Profile p,double distance,String operation){
        double accelerationDistance=p.cruiseSpeed()*p.cruiseSpeed()
                /(2.0*p.acceleration());
        double brakingDistance=p.cruiseSpeed()*p.cruiseSpeed()
                /(2.0*p.brakingAcceleration());
        double accelerationTime=p.cruiseSpeed()/p.acceleration();
        double brakingTime=p.cruiseSpeed()/p.brakingAcceleration();
        double cruiseDistance=Math.max(0,distance-accelerationDistance-brakingDistance);
        if(cruiseDistance==0){
            double peak=Math.sqrt(2.0*distance/
                    (1.0/p.acceleration()+1.0/p.brakingAcceleration()));
            accelerationTime=peak/p.acceleration();
            brakingTime=peak/p.brakingAcceleration();
        }
        double cruiseTime=cruiseDistance/p.cruiseSpeed();
        double duration=p.activationSeconds()+accelerationTime+cruiseTime+brakingTime
                +p.cooldownSeconds();
        return new Estimate(duration,1,accelerationTime,cruiseTime,brakingTime,
                p.activationSeconds()+p.cooldownSeconds(),p.exposure(),operation);
    }

    private static Estimate jumps(Profile p,double distance,String operation){
        int count=Math.max(1,(int)Math.ceil(distance/p.jumpRange()));
        double legDistance=distance/count;
        double transit=legDistance/p.cruiseSpeed();
        double duration=count*(p.activationSeconds()+transit+p.cooldownSeconds());
        return new Estimate(duration,count,0,transit*count,0,
                count*(p.activationSeconds()+p.cooldownSeconds()),p.exposure(),operation);
    }

    private static Estimate fold(Profile p,double distance,double skill){
        double plot=25.0+distance*(0.12-0.08*skill);
        double transit=distance/p.cruiseSpeed();
        return new Estimate(p.activationSeconds()+plot+transit,1,0,transit,0,
                p.activationSeconds()+plot,p.exposure(),"Calculate and traverse fold corridor");
    }

    private static Estimate linked(Profile p,double distance){
        double transit=Math.max(1.0,distance*0.001);
        return new Estimate(p.activationSeconds()+transit,1,0,transit,0,
                p.activationSeconds(),p.exposure(),"Traverse authenticated linked endpoints");
    }

    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
    private static boolean nonNegative(double v){return Double.isFinite(v)&&v>=0;}
}
