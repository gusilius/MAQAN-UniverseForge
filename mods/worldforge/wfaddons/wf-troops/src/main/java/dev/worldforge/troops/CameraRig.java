package dev.worldforge.troops;

/**
 * Shared tactical camera rig. The focus point is on the terrain; the game adapter
 * is responsible for sampling terrain and resolving the final camera position
 * against loaded collision geometry.
 */
public final class CameraRig {
    public enum View {
        FREE("Total War", 28, 28),
        TACTICS("Изометрия", 55, 24),
        SKY("RTS", 68, 34),
        TOP_DOWN("Сверху", 88, 36),
        STRATEGIC("Стратегия", 74, 48);

        public final String title;
        public final double pitch, zoom;
        View(String title, double pitch, double zoom) {
            this.title = title;
            this.pitch = pitch;
            this.zoom = zoom;
        }
    }

    public static final double MIN_ZOOM = 6, MAX_ZOOM = 96;
    public static final double MIN_PITCH = 16, MAX_PITCH = 88;
    public static final double BASE_SPEED = 0.9, EDGE = 14;
    public static final double TERRAIN_CLEARANCE = 2.25;

    /** Serializable client camera state; contains no Minecraft objects or chunk references. */
    public record Snapshot(View view, double fx, double fz, double yaw,
                           double pitch, double zoom, double leash) {}

    public View view = View.SKY;
    public double fx, fz, yaw, pitch = View.SKY.pitch, zoom = View.SKY.zoom;
    private double targetPitch = View.SKY.pitch, targetZoom = View.SKY.zoom;
    private boolean transitioning;
    /** Client-side loaded-area leash; never force-load chunks for camera movement. */
    public double leash = 90;

    public void start(double x, double z, double yawDeg, View v) {
        fx = x;
        fz = z;
        yaw = yawDeg;
        setView(v);
    }

    public void setView(View v) {
        if (v == null) return;
        view = v;
        pitch = v.pitch;
        setZoom(v.zoom);
        targetPitch = pitch;
        targetZoom = zoom;
        transitioning = false;
    }

    /** Begin a damped camera-preset transition without teleporting the camera boom. */
    public void transitionTo(View v) {
        if (v == null) return;
        view = v;
        targetPitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, v.pitch));
        targetZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, v.zoom));
        transitioning = true;
    }

    /** Frame-rate independent easing for pitch and boom length. */
    public void updateTransition(double dt) {
        if (!transitioning || !Double.isFinite(dt) || dt <= 0) return;
        double blend = 1.0 - Math.exp(-Math.min(0.1, dt) * 7.5);
        pitch += (targetPitch - pitch) * blend;
        zoom += (targetZoom - zoom) * blend;
        if (Math.abs(targetPitch - pitch) < 0.025 && Math.abs(targetZoom - zoom) < 0.025) {
            pitch = targetPitch;
            zoom = targetZoom;
            transitioning = false;
        }
    }

    public void cycle() {
        transitionTo(switch (view) {
            case SKY -> View.TACTICS;
            case TACTICS -> View.FREE;
            case FREE -> View.TOP_DOWN;
            case TOP_DOWN -> View.STRATEGIC;
            case STRATEGIC -> View.SKY;
        });
    }

    /** Screen-relative movement, normalized to prevent diagonal speed boosts. */
    public void pan(double forward, double right, double dt, boolean fast) {
        if (!Double.isFinite(forward) || !Double.isFinite(right) || !Double.isFinite(dt)) return;
        double sp = BASE_SPEED * (0.6 + zoom * 0.55) * (fast ? 2.2 : 1)
                * Math.max(0, Math.min(0.1, dt)) * 8;
        double r = Math.toRadians(yaw);
        double len = Math.hypot(forward, right);
        if (len < 1e-9) return;
        forward /= Math.max(1, len);
        right /= Math.max(1, len);
        fx += (-Math.sin(r) * forward - Math.cos(r) * right) * sp;
        fz += (Math.cos(r) * forward - Math.sin(r) * right) * sp;
    }

    public static double[] edgeScroll(double mx, double my, int w, int h) {
        double edge = Math.max(6, Math.min(20, Math.min(w, h) * 0.018));
        double right = mx < edge ? -1 : mx > w - edge ? 1 : 0;
        double fwd = my < edge ? 1 : my > h - edge ? -1 : 0;
        return new double[]{right, fwd};
    }

    public void rotate(double dYaw, double dPitch) {
        if (!Double.isFinite(dYaw) || !Double.isFinite(dPitch)) return;
        yaw = (yaw + dYaw) % 360;
        if (yaw < 0) yaw += 360;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + dPitch));
        targetPitch = pitch;
        transitioning = false;
    }

    public void setZoom(double distance) {
        if (!Double.isFinite(distance)) return;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, distance));
        targetZoom = zoom;
        transitioning = false;
    }

    public void zoomBy(double steps) {
        if (Double.isFinite(steps)) setZoom(zoom * Math.pow(0.88, steps));
    }

    public void focusOn(double x, double z) {
        if (Double.isFinite(x) && Double.isFinite(z)) { fx = x; fz = z; }
    }

    public void clamp(double px, double pz) {
        double d = Math.hypot(fx - px, fz - pz);
        if (Double.isFinite(d) && d > leash && d > 1e-9) {
            fx = px + (fx - px) / d * leash;
            fz = pz + (fz - pz) / d * leash;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(view, fx, fz, yaw, pitch, zoom, leash);
    }

    /** Restore a validated camera state without starting an animated preset transition. */
    public boolean restore(Snapshot saved) {
        if (saved == null || saved.view() == null
                || !Double.isFinite(saved.fx()) || !Double.isFinite(saved.fz())
                || !Double.isFinite(saved.yaw()) || !Double.isFinite(saved.pitch())
                || !Double.isFinite(saved.zoom()) || !Double.isFinite(saved.leash())
                || saved.zoom() < MIN_ZOOM || saved.zoom() > MAX_ZOOM
                || saved.pitch() < MIN_PITCH || saved.pitch() > MAX_PITCH
                || saved.leash() < 8 || saved.leash() > 512) return false;
        view = saved.view();
        fx = saved.fx();
        fz = saved.fz();
        yaw = ((saved.yaw() % 360) + 360) % 360;
        pitch = saved.pitch();
        zoom = saved.zoom();
        leash = saved.leash();
        targetPitch = pitch;
        targetZoom = zoom;
        transitioning = false;
        return true;
    }

    /** Desired camera position above the focus terrain. */
    public double[] position(double groundY) {
        double p = Math.toRadians(pitch), r = Math.toRadians(yaw);
        double horizontal = Math.cos(p) * zoom;
        return new double[]{
                fx + Math.sin(r) * horizontal,
                groundY + Math.sin(p) * zoom,
                fz - Math.cos(r) * horizontal
        };
    }

    /**
     * Resolve the playable surface used by the tactical camera.
     * A void column must not pin the camera to the bottom bedrock layer; in a
     * ceiling dimension the roof heightmap must not send the camera outside the world.
     */
    public static double resolveTerrainAnchor(double sampledSurfaceY, double playerY,
                                              int minBuildHeight, boolean hasCeiling) {
        double safePlayerY = Double.isFinite(playerY) ? playerY : minBuildHeight + 16.0;
        if (!Double.isFinite(sampledSurfaceY)) return safePlayerY + 8.0;
        if (sampledSurfaceY <= minBuildHeight + 2.0)
            return Math.max(safePlayerY + 8.0, minBuildHeight + 8.0);
        if (hasCeiling && (sampledSurfaceY > safePlayerY + 16.0
                || safePlayerY >= sampledSurfaceY - 4.0))
            return safePlayerY + 12.0;
        return sampledSurfaceY;
    }

    /** Pure safety floor used by the Minecraft adapter after sampling terrain below the camera. */
    public static double keepAboveTerrain(double cameraY, double terrainY) {
        if (!Double.isFinite(cameraY)) cameraY = terrainY + TERRAIN_CLEARANCE;
        if (!Double.isFinite(terrainY)) return cameraY;
        return Math.max(cameraY, terrainY + TERRAIN_CLEARANCE);
    }
}
