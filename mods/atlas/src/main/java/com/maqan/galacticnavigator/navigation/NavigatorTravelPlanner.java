package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, data-driven interstellar route estimator.
 *
 * This class calculates a journey only; it does not create dimensions or teleport
 * players. The caller supplies positions and discovered transit links from datapacks.
 */
public final class NavigatorTravelPlanner {
    private NavigatorTravelPlanner() {}

    public enum Drive {
        SUBLIGHT("Sublight", 0.025, 0.0, false),
        HYPERDRIVE("Hyperspace jump", 8.0, 18.0, false),
        NAVIGATOR("Calculated fold", 6.0, 35.0, false),
        WARP("Warp translation", 3.5, 24.0, false),
        WORMHOLE("Wormhole", 0.0, 12.0, true),
        MASS_RELAY("Mass relay", 0.0, 8.0, true),
        GATE("Jump gate", 0.0, 10.0, true);

        public final String label;
        public final double speedUnitsPerSecond;
        public final double activationSeconds;
        public final boolean requiresLink;

        Drive(String label, double speed, double activation, boolean link) {
            this.label = label;
            this.speedUnitsPerSecond = speed;
            this.activationSeconds = activation;
            this.requiresLink = link;
        }
    }

    public record Vector3(double x, double y, double z) {
        public Vector3 {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Coordinates must be finite");
        }
        public Vector3 add(Vector3 other) { return new Vector3(x + other.x, y + other.y, z + other.z); }
        public Vector3 subtract(Vector3 other) { return new Vector3(x - other.x, y - other.y, z - other.z); }
        public Vector3 scale(double factor) { return new Vector3(x * factor, y * factor, z * factor); }
        public double distanceTo(Vector3 other) { return Math.sqrt(
                square(x - other.x) + square(y - other.y) + square(z - other.z)); }
        private static double square(double value) { return value * value; }
        public String compact() { return String.format(Locale.ROOT, "%.2f / %.2f / %.2f", x, y, z); }
    }

    public record Leg(int index, Vector3 departure, Vector3 arrival, double distance,
                      double durationSeconds, String operation) {}

    public record Request(Vector3 origin, Vector3 destination, Drive drive,
                          double maxJumpRange, double navigationSkill,
                          boolean routeCharted, boolean transitLinkAvailable,
                          double hazardLevel, double arrivalSafetyRadius) {
        public Request {
            if (origin == null || destination == null || drive == null)
                throw new IllegalArgumentException("Origin, destination and drive are required");
            if (!Double.isFinite(maxJumpRange) || maxJumpRange <= 0)
                throw new IllegalArgumentException("Maximum jump range must be positive");
            if (!Double.isFinite(navigationSkill) || navigationSkill < 0 || navigationSkill > 1)
                throw new IllegalArgumentException("Navigation skill must be between 0 and 1");
            if (!Double.isFinite(hazardLevel) || hazardLevel < 0 || hazardLevel > 1)
                throw new IllegalArgumentException("Hazard level must be between 0 and 1");
            if (!Double.isFinite(arrivalSafetyRadius) || arrivalSafetyRadius < 0)
                throw new IllegalArgumentException("Arrival safety radius cannot be negative");
        }
    }

    public record Plan(boolean valid, String message, Drive drive, List<Leg> legs,
                       double totalDistance, double durationSeconds, double confidence,
                       double risk, Vector3 calculatedArrival, double arrivalErrorRadius) {
        public Plan { legs = List.copyOf(legs); }
        public String durationLabel() {
            if (!valid) return "Unavailable";
            if (durationSeconds < 60) return String.format(Locale.ROOT, "%.0f sec", durationSeconds);
            if (durationSeconds < 3600) return String.format(Locale.ROOT, "%.1f min", durationSeconds / 60);
            return String.format(Locale.ROOT, "%.2f h", durationSeconds / 3600);
        }
    }

    public static Plan calculate(Request request) {
        Vector3 from=request.origin(),to=request.destination();
        Drive drive=request.drive();
        double distance=from.distanceTo(to);
        if(drive.requiresLink&&!request.transitLinkAvailable)
            return invalid(drive,"No matching discovered "+drive.label+" link exists between these systems.");
        if(distance<1.0E-9&&!drive.requiresLink)
            return new Plan(true,"Already at the requested coordinates.",drive,List.of(),
                    0,0,1,0,to,0);

        DriveTransitModel.Profile profile=DriveTransitModel.profile(drive,request.maxJumpRange());
        DriveTransitModel.Estimate estimate;
        try{
            estimate=DriveTransitModel.estimate(profile,distance,request.navigationSkill());
        }catch(IllegalArgumentException ex){
            return invalid(drive,"Drive transit model rejected the requested distance or parameters.");
        }

        List<Leg> legs=new ArrayList<>();
        Vector3 delta=to.subtract(from);
        int count=Math.max(1,estimate.legs());
        for(int i=0;i<count;i++){
            Vector3 a=from.add(delta.scale((double)i/count));
            Vector3 b=from.add(delta.scale((double)(i+1)/count));
            double legDistance=a.distanceTo(b);
            double legDuration;
            if(profile.exposure()==DriveTransitModel.Exposure.LINKED_ENDPOINTS){
                legDuration=estimate.durationSeconds();
            }else if(drive==Drive.HYPERDRIVE){
                var legEstimate=DriveTransitModel.estimate(profile,legDistance,request.navigationSkill());
                legDuration=legEstimate.durationSeconds();
            }else if(i==0){
                legDuration=estimate.durationSeconds();
            }else{
                legDuration=0;
            }
            legs.add(new Leg(i+1,a,b,legDistance,legDuration,estimate.operation()));
        }

        double duration=estimate.durationSeconds();
        double confidence;
        double risk=request.hazardLevel();
        double error;
        if(drive==Drive.HYPERDRIVE||drive==Drive.NAVIGATOR){
            confidence=clamp(0.55+request.navigationSkill()*0.42
                    +(request.routeCharted()?0.03:-0.12)-request.hazardLevel()*0.22,0.05,0.995);
            double uncertainty=(drive==Drive.NAVIGATOR?0.16:0.035)
                    *(1.0-request.navigationSkill())*(request.routeCharted()?0.55:1.0);
            error=Math.max(request.arrivalSafetyRadius(),distance*uncertainty);
            risk=clamp(risk+(drive==Drive.NAVIGATOR?0.18:0.07)
                    *(1.0-request.navigationSkill()),0,1);
        }else if(drive.requiresLink){
            confidence=clamp(0.99-request.hazardLevel()*0.35,0.1,0.999);
            error=request.arrivalSafetyRadius()*(drive==Drive.WORMHOLE?1.8:0.25);
            risk=clamp(risk+(drive==Drive.WORMHOLE?0.30:0.04),0,1);
        }else{
            confidence=clamp(0.995-request.hazardLevel()*0.2,0.2,0.999);
            error=request.arrivalSafetyRadius();
        }

        Vector3 arrival=safeArrival(to,error,from,request.routeCharted());
        String message=String.format(Locale.ROOT,
                "%d leg(s) · %.2f distance units · %.0f%% confidence · %s",
                legs.size(),distance,confidence*100.0,profile.exposure().name());
        return new Plan(true,message,drive,legs,distance,duration,confidence,risk,arrival,error);
    }

    private static Vector3 safeArrival(Vector3 target, double radius, Vector3 origin, boolean charted) {
        if (radius <= 0) return target;
        Vector3 direction = target.subtract(origin);
        double length = Math.max(1.0E-9, Math.sqrt(direction.x * direction.x + direction.z * direction.z));
        double offset = Math.min(radius, Math.max(0.5, radius * (charted ? 0.15 : 0.45)));
        return new Vector3(target.x - direction.x / length * offset, target.y,
                target.z - direction.z / length * offset);
    }

    private static Plan invalid(Drive drive, String message) {
        return new Plan(false, message, drive, List.of(), 0, 0, 0, 1, null, 0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
