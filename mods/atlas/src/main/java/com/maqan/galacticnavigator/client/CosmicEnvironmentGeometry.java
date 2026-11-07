package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog.EnvironmentObject;
import com.maqan.galacticnavigator.data.CosmicEnvironmentCatalog.Vector3;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, system-space geometry inputs for cosmic environment rendering.
 *
 * This class does not issue OpenGL calls and does not create world entities,
 * chunks, blocks or dimensions. A renderer can turn these snapshots into
 * batched meshes while retaining the Cosmic Horizons system coordinate frame.
 */
public final class CosmicEnvironmentGeometry {
    private static final int DEFAULT_BELT_PARTICLES = 512;
    private static final int MAX_BELT_PARTICLES = 8192;
    private static final int DEFAULT_NEBULA_SAMPLES = 384;
    private static final int MAX_NEBULA_SAMPLES = 4096;
    private static final int COMET_TAIL_SEGMENTS = 32;

    private CosmicEnvironmentGeometry() {}

    public record Particle(Vector3 position, float size, float brightness, int seed) {}
    public record NebulaSample(Vector3 position, float density, int seed) {}
    public record CometVertex(Vector3 position, float halfWidth, float opacity, int stream) {}
    public record CometGeometry(Vector3 nucleus, double comaRadius,
                                List<CometVertex> dustTail, List<CometVertex> ionTail) {}

    /** Deterministic asteroid point cloud; missing radial geometry yields no batch. */
    public static List<Particle> asteroidBelt(EnvironmentObject belt, double gameTime) {
        if (belt == null || belt.kind() != CosmicEnvironmentCatalog.Kind.ASTEROID_BELT
                || belt.position() == null || !belt.position().isFinite()
                || !Double.isFinite(gameTime)) return List.of();

        double inner = belt.innerBeltRadius();
        double outer = belt.outerBeltRadius();
        if (!(Double.isFinite(inner) && Double.isFinite(outer) && inner >= 0.0 && outer > inner)) {
            double center = belt.beltCenterRadius();
            double width = belt.beltWidth();
            if (!Double.isFinite(center) || !Double.isFinite(width)
                    || center <= 0.0 || width <= 0.0) return List.of();
            inner = Math.max(0.0, center - width * 0.5);
            outer = center + width * 0.5;
        }
        if (!(outer > inner) || outer > 1.0E12) return List.of();

        int count = belt.particleCount() > 0
                ? Math.min(MAX_BELT_PARTICLES, belt.particleCount()) : DEFAULT_BELT_PARTICLES;
        double thickness = Double.isFinite(belt.beltThickness())
                ? Math.max(0.0, Math.min(belt.beltThickness(), (outer - inner) * 0.5))
                : Math.max(0.001, (outer - inner) * 0.025);
        double inclination = Math.toRadians(finiteOr(belt.beltInclinationDegrees(), 0.0));
        double node = Math.toRadians(finiteOr(belt.beltNodeDegrees(), 0.0));
        double phase = finiteOr(belt.beltPhaseRadians(), 0.0);
        double period = belt.beltRotationPeriodTicks();
        double rotation = phase + (Double.isFinite(period) && period > 0.0
                ? Math.PI * 2.0 * gameTime / period : 0.0);
        long seedBase = stableSeed(belt.systemId(), belt.bodyId());

        List<Particle> particles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seed = mix64(seedBase + i * 0x9E3779B97F4A7C15L);
            double radius = Math.sqrt(inner * inner
                    + unit(seed) * (outer * outer - inner * inner));
            double angle = rotation + unit(mix64(seed)) * Math.PI * 2.0;
            double localX = Math.cos(angle) * radius;
            double localZ = Math.sin(angle) * radius;
            double localY = (unit(mix64(seed ^ 0xD1B54A32D192ED03L)) * 2.0 - 1.0) * thickness;

            double inclinedY = localY * Math.cos(inclination) - localZ * Math.sin(inclination);
            double inclinedZ = localY * Math.sin(inclination) + localZ * Math.cos(inclination);
            double worldX = localX * Math.cos(node) - inclinedZ * Math.sin(node);
            double worldZ = localX * Math.sin(node) + inclinedZ * Math.cos(node);
            Vector3 center = belt.position();
            Vector3 position = new Vector3(center.x() + worldX,
                    center.y() + inclinedY, center.z() + worldZ);
            float size = (float)(0.35 + unit(mix64(seed ^ 0x94D049BB133111EBL)) * 0.9);
            float brightness = (float)(0.45
                    + unit(mix64(seed ^ 0xBF58476D1CE4E5B9L)) * 0.55);
            particles.add(new Particle(position, size, brightness, (int)seed));
        }
        return List.copyOf(particles);
    }

    /**
     * Creates a deterministic 3D sample cloud inside an authored ellipsoid.
     * Volume extents/radius must be explicit; render scale only affects
     * presentation and is never reused as physical collision or orbit size.
     */
    public static List<NebulaSample> nebulaVolume(EnvironmentObject nebula) {
        if (nebula == null || nebula.kind() != CosmicEnvironmentCatalog.Kind.NEBULA
                || nebula.volumeCenter() == null || !nebula.volumeCenter().isFinite())
            return List.of();

        Vector3 extent = nebula.volumeExtent();
        double rx, ry, rz;
        if (extent != null && extent.isPositiveExtent()) {
            rx = extent.x() * 0.5;
            ry = extent.y() * 0.5;
            rz = extent.z() * 0.5;
        } else if (Double.isFinite(nebula.volumeRadius()) && nebula.volumeRadius() > 0.0) {
            rx = ry = rz = nebula.volumeRadius();
        } else {
            return List.of();
        }

        double renderScale = Double.isFinite(nebula.volumeRenderScale())
                ? clamp(nebula.volumeRenderScale(), 0.1, 8.0) : 1.0;
        rx *= renderScale;
        ry *= renderScale;
        rz *= renderScale;
        if (!finitePositive(rx) || !finitePositive(ry) || !finitePositive(rz)
                || Math.max(rx, Math.max(ry, rz)) > 1.0E12) return List.of();

        int count = nebula.particleCount() > 0
                ? Math.min(MAX_NEBULA_SAMPLES, nebula.particleCount()) : DEFAULT_NEBULA_SAMPLES;
        double opacity = clamp(finiteOr(nebula.opacity(), 1.0), 0.0, 1.0);
        long seedBase = stableSeed(nebula.systemId(), nebula.bodyId());
        Vector3 center = nebula.volumeCenter();
        double[] rotation = parseEulerDegrees(nebula.volumeOrientation());
        double sx = Math.sin(rotation[0]), cx = Math.cos(rotation[0]);
        double sy = Math.sin(rotation[1]), cy = Math.cos(rotation[1]);
        double sz = Math.sin(rotation[2]), cz = Math.cos(rotation[2]);
        List<NebulaSample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seed = mix64(seedBase + i * 0x9E3779B97F4A7C15L);
            // Uniform volume distribution: radius is cube-root distributed.
            double radial = Math.cbrt(unit(seed));
            double z = unit(mix64(seed ^ 0xD1B54A32D192ED03L)) * 2.0 - 1.0;
            double azimuth = unit(mix64(seed ^ 0x94D049BB133111EBL)) * Math.PI * 2.0;
            double planar = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double nx = radial * planar * Math.cos(azimuth);
            double ny = radial * z;
            double nz = radial * planar * Math.sin(azimuth);
            // Authored Euler rotation is applied X, then Y, then Z in the system frame.
            double px = nx * rx;
            double py = ny * ry;
            double pz = nz * rz;
            double x1 = px;
            double y1 = py * cx - pz * sx;
            double z1 = py * sx + pz * cx;
            double x2 = x1 * cy + z1 * sy;
            double y2 = y1;
            double z2 = -x1 * sy + z1 * cy;
            double x3 = x2 * cz - y2 * sz;
            double y3 = x2 * sz + y2 * cz;
            double z3 = z2;
            Vector3 position = new Vector3(center.x() + x3,
                    center.y() + y3, center.z() + z3);
            double edgeFalloff = 1.0 - radial;
            float density = (float)(opacity * (0.12 + 0.88 * edgeFalloff * edgeFalloff));
            samples.add(new NebulaSample(position, density, (int)seed));
        }
        return List.copyOf(samples);
    }

    /**
     * Creates two tapered comet-tail ribbons in system coordinates. A valid
     * authored/parent-derived tail direction and positive tail length are
     * required; no physical length or direction is invented.
     */
    public static CometGeometry comet(EnvironmentObject comet, double gameTime) {
        if (comet == null || comet.kind() != CosmicEnvironmentCatalog.Kind.COMET
                || comet.position() == null || !comet.position().isFinite()
                || !comet.hasTailDirection() || !Double.isFinite(comet.tailLength())
                || comet.tailLength() <= 0.0 || !Double.isFinite(gameTime)) return null;

        Vector3 direction = comet.tailDirection().normalized();
        if (direction == null) return null;
        Vector3 reference = Math.abs(direction.y()) < 0.9
                ? new Vector3(0.0, 1.0, 0.0) : new Vector3(1.0, 0.0, 0.0);
        Vector3 side = cross(direction, reference).normalized();
        if (side == null) return null;
        Vector3 secondSide = cross(direction, side).normalized();
        if (secondSide == null) return null;

        long seed = stableSeed(comet.systemId(), comet.bodyId());
        double phase = unit(mix64(seed)) * Math.PI * 2.0 + gameTime * 0.025;
        double length = Math.min(comet.tailLength(), 1.0E12);
        double coma = Double.isFinite(comet.comaRadius())
                ? Math.max(0.0, Math.min(comet.comaRadius(), length * 0.25)) : 0.0;
        List<CometVertex> dust = new ArrayList<>(COMET_TAIL_SEGMENTS * 2);
        List<CometVertex> ion = new ArrayList<>(COMET_TAIL_SEGMENTS * 2);

        for (int i = 0; i <= COMET_TAIL_SEGMENTS; i++) {
            double t = i / (double)COMET_TAIL_SEGMENTS;
            double distance = length * t;
            double taper = Math.pow(1.0 - t, 1.35);
            double wave = Math.sin(phase + t * 5.0) * length * 0.012 * t;
            Vector3 center = add(comet.position(),
                    add(scale(direction, distance), scale(side, wave)));

            double dustWidth = Math.max(0.0001, (coma * 0.35 + length * 0.035) * taper);
            double ionWidth = Math.max(0.00005, (coma * 0.12 + length * 0.009) * taper);
            addRibbonPair(dust, center, side, dustWidth, 1.0 - t * 0.92, 0);
            addRibbonPair(ion, center, secondSide, ionWidth, 1.0 - t * 0.97, 1);
        }
        return new CometGeometry(comet.position(), coma, List.copyOf(dust), List.copyOf(ion));
    }

    private static void addRibbonPair(List<CometVertex> output, Vector3 center,
                                      Vector3 side, double width, double opacity, int stream) {
        output.add(new CometVertex(add(center, scale(side, width)),
                (float)width, (float)clamp(opacity, 0.0, 1.0), stream));
        output.add(new CometVertex(add(center, scale(side, -width)),
                (float)width, (float)clamp(opacity, 0.0, 1.0), stream));
    }

    private static Vector3 add(Vector3 a, Vector3 b) {
        return new Vector3(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }
    private static Vector3 scale(Vector3 a, double value) {
        return new Vector3(a.x() * value, a.y() * value, a.z() * value);
    }
    private static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(a.y() * b.z() - a.z() * b.y(),
                a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }
    /**
     * Accepts a JSON object with explicit x/y/z or pitch/yaw/roll degree
     * members, or a three-number [x,y,z] Euler array. Unsupported encodings
     * intentionally fall back to an unrotated volume.
     */
    private static double[] parseEulerDegrees(String encoded) {
        double[] result = new double[3];
        if (encoded == null || encoded.isBlank()) return result;
        try {
            JsonElement element = JsonParser.parseString(encoded);
            if (element.isJsonArray()) {
                JsonArray values = element.getAsJsonArray();
                if (values.size() >= 3) {
                    result[0] = Math.toRadians(values.get(0).getAsDouble());
                    result[1] = Math.toRadians(values.get(1).getAsDouble());
                    result[2] = Math.toRadians(values.get(2).getAsDouble());
                }
            } else if (element.isJsonObject()) {
                JsonObject values = element.getAsJsonObject();
                result[0] = Math.toRadians(readNumber(values, "x", "pitch"));
                result[1] = Math.toRadians(readNumber(values, "y", "yaw"));
                result[2] = Math.toRadians(readNumber(values, "z", "roll"));
            }
        } catch (RuntimeException ignored) {
            return new double[3];
        }
        for (int i = 0; i < result.length; i++)
            if (!Double.isFinite(result[i])) result[i] = 0.0;
        return result;
    }
    private static double readNumber(JsonObject object, String primary, String alternate) {
        try {
            if (object.has(primary)) return object.get(primary).getAsDouble();
            if (object.has(alternate)) return object.get(alternate).getAsDouble();
        } catch (RuntimeException ignored) { }
        return 0.0;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
    private static long stableSeed(String systemId, String bodyId) {
        long hash = 0xcbf29ce484222325L;
        String key = String.valueOf(systemId) + "/" + String.valueOf(bodyId);
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return mix64(hash);
    }
    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }
}
