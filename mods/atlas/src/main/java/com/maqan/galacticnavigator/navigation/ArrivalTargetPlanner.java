package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/**
 * Converts body-relative arrival intent into a predicted system-space waypoint.
 * Final Minecraft spawn resolution remains server-authoritative and must run
 * collision, terrain, dimension and chunk checks before teleporting a player.
 */
public final class ArrivalTargetPlanner {
    private ArrivalTargetPlanner() {}

    public enum Mode {
        ORBITAL_ARRIVAL, SPACE_ENTRY, ATMOSPHERIC_ENTRY, SURFACE_LANDING, DESIGNATED_POINT
    }

    public record BodyFrame(MovingTargetInterception.PositionAtTime centerAtTime,
                            MovingTargetInterception.PositionAtTime rotationRadiansAtTime,
                            double physicalRadius, double atmosphereHeight) {
        public BodyFrame {
            Objects.requireNonNull(centerAtTime, "centerAtTime");
            Objects.requireNonNull(rotationRadiansAtTime, "rotationRadiansAtTime");
            if (!Double.isFinite(physicalRadius) || physicalRadius <= 0
                    || !Double.isFinite(atmosphereHeight) || atmosphereHeight < 0)
                throw new IllegalArgumentException("Invalid body dimensions");
        }
    }

    public record Request(Mode mode, BodyFrame body, double arrivalTimeSeconds,
                          double clearance, double latitudeRadians, double longitudeRadians,
                          MovingTargetInterception.Vector3 designatedSystemPosition) {
        public Request {
            Objects.requireNonNull(mode, "mode");
            if (!Double.isFinite(arrivalTimeSeconds) || !Double.isFinite(clearance) || clearance <= 0)
                throw new IllegalArgumentException("Arrival time and positive clearance are required");
            if (!Double.isFinite(latitudeRadians) || !Double.isFinite(longitudeRadians))
                throw new IllegalArgumentException("Latitude and longitude must be finite");
            if (mode == Mode.DESIGNATED_POINT) {
                Objects.requireNonNull(designatedSystemPosition, "designatedSystemPosition");
            } else {
                Objects.requireNonNull(body, "body");
            }
        }
    }

    public record Target(Mode mode, MovingTargetInterception.Vector3 systemPosition,
                         double bodyRotationRadians, boolean requiresServerSpawnValidation,
                         String description) {}

    public static Target plan(Request request) {
        if (request.mode() == Mode.DESIGNATED_POINT) {
            return new Target(request.mode(), request.designatedSystemPosition(), 0,
                    true, "Designated transit endpoint; server validation required.");
        }

        MovingTargetInterception.Vector3 center = request.body().centerAtTime()
                .position(request.arrivalTimeSeconds());
        double rotation = request.body().rotationRadiansAtTime()
                .position(request.arrivalTimeSeconds()).x();

        double latitude = Math.max(-Math.PI / 2,
                Math.min(Math.PI / 2, request.latitudeRadians()));
        double longitude = request.longitudeRadians() + rotation;
        double horizontal = Math.cos(latitude);
        MovingTargetInterception.Vector3 outward =
                new MovingTargetInterception.Vector3(
                        horizontal * Math.cos(longitude),
                        Math.sin(latitude),
                        horizontal * Math.sin(longitude)).normalized();

        double radialDistance = switch (request.mode()) {
            case ORBITAL_ARRIVAL -> request.body().physicalRadius() + request.clearance();
            case SPACE_ENTRY -> request.body().physicalRadius() + Math.max(request.clearance(),
                    request.body().atmosphereHeight() + request.clearance());
            case ATMOSPHERIC_ENTRY -> request.body().physicalRadius()
                    + request.body().atmosphereHeight() * 0.75 + request.clearance();
            case SURFACE_LANDING -> request.body().physicalRadius() + request.clearance();
            case DESIGNATED_POINT -> throw new IllegalStateException("Handled above");
        };

        String description = switch (request.mode()) {
            case ORBITAL_ARRIVAL -> "Orbital holding waypoint outside the body envelope.";
            case SPACE_ENTRY -> "Space approach waypoint outside the atmosphere.";
            case ATMOSPHERIC_ENTRY -> "Atmospheric approach waypoint; controlled descent required.";
            case SURFACE_LANDING -> "Surface target estimate; safe terrain search is mandatory.";
            case DESIGNATED_POINT -> "Designated endpoint.";
        };
        return new Target(request.mode(), center.add(outward.scale(radialDistance)),
                rotation, true, description);
    }
}
