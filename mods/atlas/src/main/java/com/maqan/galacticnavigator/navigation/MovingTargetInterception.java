package com.maqan.galacticnavigator.navigation;

import java.util.Objects;

/**
 * Predicts rendezvous with a moving celestial body.
 *
 * Coordinates and time units are supplied by the caller. This class never
 * teleports a player and never treats a planet centre as a valid spawn point.
 */
public final class MovingTargetInterception {
    private MovingTargetInterception() {}

    public record Vector3(double x, double y, double z) {
        public Vector3 {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Vector components must be finite");
        }
        public Vector3 add(Vector3 v) { return new Vector3(x + v.x, y + v.y, z + v.z); }
        public Vector3 subtract(Vector3 v) { return new Vector3(x - v.x, y - v.y, z - v.z); }
        public Vector3 scale(double s) { return new Vector3(x * s, y * s, z * s); }
        public double distanceTo(Vector3 v) {
            double dx=x-v.x, dy=y-v.y, dz=z-v.z;
            return Math.sqrt(dx*dx + dy*dy + dz*dz);
        }
        public double length() { return Math.sqrt(x*x + y*y + z*z); }
        public Vector3 normalized() {
            double length = length();
            if (length < 1.0E-12) throw new IllegalArgumentException("Direction cannot be zero");
            return scale(1.0 / length);
        }
    }

    @FunctionalInterface
    public interface PositionAtTime {
        Vector3 position(double absoluteTimeSeconds);
    }

    @FunctionalInterface
    public interface TravelTimeModel {
        double durationSeconds(Vector3 origin, Vector3 predictedDestination);
    }

    public record Result(boolean valid, boolean converged, String message,
                         Vector3 departurePosition, Vector3 targetAtDeparture,
                         Vector3 predictedInterceptPosition, double departureTimeSeconds,
                         double travelTimeSeconds, double arrivalTimeSeconds,
                         double missDistance, int iterations) {}

    /**
     * Fixed-point intercept solver. The supplied ephemeris must use the same
     * absolute time base as departureTimeSeconds.
     */
    public static Result solve(Vector3 origin, double departureTimeSeconds,
                               PositionAtTime targetEphemeris, TravelTimeModel travelTime,
                               double tolerance, int maxIterations) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(targetEphemeris, "targetEphemeris");
        Objects.requireNonNull(travelTime, "travelTime");
        if (!Double.isFinite(departureTimeSeconds))
            throw new IllegalArgumentException("Departure time must be finite");
        if (!Double.isFinite(tolerance) || tolerance <= 0)
            throw new IllegalArgumentException("Tolerance must be positive");
        if (maxIterations < 1 || maxIterations > 100)
            throw new IllegalArgumentException("Iterations must be between 1 and 100");

        Vector3 atDeparture = Objects.requireNonNull(targetEphemeris.position(departureTimeSeconds));
        Vector3 predicted = atDeparture;
        double duration = 0.0;
        double miss = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= maxIterations; i++) {
            double nextDuration = travelTime.durationSeconds(origin, predicted);
            if (!Double.isFinite(nextDuration) || nextDuration < 0.0)
                return invalid(origin, atDeparture, departureTimeSeconds, i,
                        "Travel-time model returned an invalid duration.");
            double arrivalTime = departureTimeSeconds + nextDuration;
            Vector3 nextPosition = Objects.requireNonNull(targetEphemeris.position(arrivalTime));
            miss = nextPosition.distanceTo(predicted);
            duration = nextDuration;
            predicted = nextPosition;
            if (miss <= tolerance) {
                return new Result(true, true, "Moving-target intercept converged.",
                        origin, atDeparture, predicted, departureTimeSeconds,
                        duration, arrivalTime, miss, i);
            }
        }
        return new Result(true, false,
                "Intercept estimate did not converge within the iteration limit; do not execute automatically.",
                origin, atDeparture, predicted, departureTimeSeconds,
                duration, departureTimeSeconds + duration, miss, maxIterations);
    }

    /**
     * Computes a holding point outside a body's collision envelope. This is an
     * orbital-space waypoint only; surface landing requires a separate server
     * terrain/collision validation step.
     */
    public static Vector3 orbitalHoldingPoint(Vector3 bodyCenter, Vector3 outwardDirection,
                                               double physicalRadius, double clearance) {
        Objects.requireNonNull(bodyCenter, "bodyCenter");
        Objects.requireNonNull(outwardDirection, "outwardDirection");
        if (!Double.isFinite(physicalRadius) || physicalRadius < 0
                || !Double.isFinite(clearance) || clearance <= 0)
            throw new IllegalArgumentException("A non-negative radius and positive clearance are required");
        return bodyCenter.add(outwardDirection.normalized().scale(physicalRadius + clearance));
    }

    private static Result invalid(Vector3 origin, Vector3 target, double departure, int iterations,
                                  String message) {
        return new Result(false, false, message, origin, target, target, departure,
                0, departure, Double.POSITIVE_INFINITY, iterations);
    }
}
