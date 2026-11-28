package dev.worldforge.troops;

/**
 * Shared tactical camera rig. Horizontal focus and camera height are independent;
 * vertical placement is always expressed relative to the player's feet.
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
    public static final double MIN_HEIGHT = 8, MAX_HEIGHT = 256, HEIGHT_STEP = 4;
    private final java.util.EnumMap<View, Double> heights = new java.util.EnumMap<>(View.class);
    private final java.util.EnumMap<View, Boolean> heightLocks = new java.util.EnumMap<>(View.class);
    /** Current view's vertical offset from the player's feet, in blocks. */
    public double heightAbovePlayer = 68;

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
        heightAbovePlayer = heightFor(v);
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
        heightAbovePlayer = heightFor(v);
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
        heightAbovePlayer = heightFor(view);
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

    private static double defaultHeight(View v) {
        return switch (v) {
            case FREE -> 28;
            case TACTICS -> 36;
            case SKY -> 52;
            case TOP_DOWN -> 88;
            case STRATEGIC -> 68;
        };
    }

    public double heightFor(View v) {
        return heights.computeIfAbsent(v, CameraRig::defaultHeight);
    }

    public boolean heightLockedFor(View v) {
        return heightLocks.getOrDefault(v, false);
    }

    public void setHeightLocked(boolean locked) {
        heightLocks.put(view, locked);
    }

    public void setHeightFromUser(double value) {
        if (!Double.isFinite(value) || heightLockedFor(view)) return;
        heightAbovePlayer = Math.max(MIN_HEIGHT, Math.min(MAX_HEIGHT, value));
        heights.put(view, heightAbovePlayer);
    }

    public void adjustHeight(double delta) {
        if (!Double.isFinite(delta) || heightLockedFor(view)) return;
        setHeightFromUser(heightAbovePlayer + delta);
    }

    public void restoreHeight(View v, double value, boolean locked) {
        if (v == null) return;
        if (Double.isFinite(value) && value >= MIN_HEIGHT && value <= MAX_HEIGHT)
            heights.put(v, value);
        heightLocks.put(v, locked);
        if (view == v) heightAbovePlayer = heightFor(v);
    }

    /** Camera height is a player-relative offset, never a terrain or world-origin coordinate. */
    public double[] positionRelativeToPlayer(double playerY) {
        double p = Math.toRadians(pitch), r = Math.toRadians(yaw);
        double horizontal = Math.cos(p) * zoom;
        return new double[] {
                fx + Math.sin(r) * horizontal,
                playerY + heightAbovePlayer,
                fz - Math.cos(r) * horizontal
        };
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

    /** Pure safety floor used by the Minecraft adapter after sampling terrain below the camera. */
    public static double keepAboveTerrain(double cameraY, double terrainY) {
        if (!Double.isFinite(cameraY)) cameraY = terrainY + TERRAIN_CLEARANCE;
        if (!Double.isFinite(terrainY)) return cameraY;
        return Math.max(cameraY, terrainY + TERRAIN_CLEARANCE);
    }
}
