package dev.maqan.platform.client;

/** Client-side camera rig math; contains no Minecraft or world-state dependencies. */
public final class MaqanCameraRig {
    public enum Preset { FREE, RTS }

    public static final double MIN_DISTANCE = 6.0;
    public static final double MAX_DISTANCE = 64.0;
    public static final double MIN_PITCH = 25.0;
    public static final double MAX_PITCH = 88.0;

    private double focusX;
    private double focusZ;
    private double yaw;
    private double pitch;
    private double distance;
    private double leash = 88.0;

    public void start(double x, double z, double yawDegrees, Preset preset) {
        focusX = x;
        focusZ = z;
        yaw = yawDegrees;
        setPreset(preset);
    }

    public void setPreset(Preset preset) {
        if (preset == Preset.FREE) {
            pitch = 42.0;
            distance = 20.0;
        } else {
            pitch = 68.0;
            distance = 38.0;
        }
    }

    public void pan(double forward, double right, double seconds, boolean fast) {
        double length = Math.hypot(forward, right);
        if (length < 1.0e-9 || seconds <= 0.0) return;
        forward /= Math.max(1.0, length);
        right /= Math.max(1.0, length);
        double speed = (3.5 + distance * 0.42) * (fast ? 2.0 : 1.0);
        double radians = Math.toRadians(yaw);
        double amount = speed * seconds;
        focusX += (-Math.sin(radians) * forward - Math.cos(radians) * right) * amount;
        focusZ += (Math.cos(radians) * forward - Math.sin(radians) * right) * amount;
    }

    public void rotate(double yawDelta, double pitchDelta) {
        yaw = (yaw + yawDelta) % 360.0;
        pitch = clamp(pitch + pitchDelta, MIN_PITCH, MAX_PITCH);
    }

    public void zoomBy(double wheelSteps) {
        if (!Double.isFinite(wheelSteps)) return;
        distance = clamp(distance * Math.pow(0.88, wheelSteps), MIN_DISTANCE, MAX_DISTANCE);
    }

    public void focusOn(double x, double z) {
        focusX = x;
        focusZ = z;
    }

    public void clampTo(double playerX, double playerZ) {
        double dx = focusX - playerX;
        double dz = focusZ - playerZ;
        double length = Math.hypot(dx, dz);
        if (length > leash) {
            focusX = playerX + dx / length * leash;
            focusZ = playerZ + dz / length * leash;
        }
    }

    /** Returns camera position [x,y,z] for a sampled ground height. */
    public double[] position(double groundY) {
        double pitchRadians = Math.toRadians(pitch);
        double yawRadians = Math.toRadians(yaw);
        double horizontal = Math.cos(pitchRadians) * distance;
        return new double[]{
                focusX + Math.sin(yawRadians) * horizontal,
                groundY + Math.sin(pitchRadians) * distance,
                focusZ - Math.cos(yawRadians) * horizontal
        };
    }

    public double focusX() { return focusX; }
    public double focusZ() { return focusZ; }
    public double yaw() { return yaw; }
    public double pitch() { return pitch; }
    public double distance() { return distance; }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
