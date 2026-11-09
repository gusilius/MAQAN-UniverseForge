package dev.worldforge.troops;

/**
 * Свободная камера командного режима: смотрит на точку фокуса на земле с заданного расстояния, наклона и курса.
 * Три вида: SKY (почти отвесно сверху, как в стратегии), TACTICS (под углом, видно бойцов), и сама камера перестаёт быть привязана к игроку.
 * Чистая математика: игровой слой только подставляет высоту земли и двигает камеру-сущность.
 */
public final class CameraRig {
    public enum View {
        FREE("Свободная", 18, 8), TACTICS("Изометрия", 42, 16), SKY("Стратегия", 72, 34);
        public final String title; public final double pitch, zoom;
        View(String t, double p, double z) { title = t; pitch = p; zoom = z; }
    }

    public static final double MIN_ZOOM = 5, MAX_ZOOM = 70, MIN_PITCH = 25, MAX_PITCH = 88, BASE_SPEED = 0.9, EDGE = 14;

    public View view = View.SKY;
    public double fx, fz, yaw, pitch = View.SKY.pitch, zoom = View.SKY.zoom;
    /** Радиус вокруг игрока, дальше которого камера не уходит: за ним клиент не видит мир (чанки не присланы). */
    public double leash = 90;

    public void start(double x, double z, double yawDeg, View v) { fx = x; fz = z; yaw = yawDeg; setView(v); }

    public void setView(View v) { view = v; pitch = v.pitch; zoom = v.zoom; }

    public void cycle() { setView(View.values()[(view.ordinal() + 1) % View.values().length]); }

    /** Сдвиг по экрану: forward/right в [-1,1]; скорость растёт с высотой, чтобы на большом приближении не ползти. */
    public void pan(double forward, double right, double dt, boolean fast) {
        double sp = BASE_SPEED * (0.6 + zoom) * (fast ? 2.2 : 1) * dt * 8, r = Math.toRadians(yaw);
        double len = Math.hypot(forward, right);
        if (len < 1e-9) return;
        forward /= Math.max(1, len); right /= Math.max(1, len);
        fx += (-Math.sin(r) * forward - Math.cos(r) * right) * sp;
        fz += (Math.cos(r) * forward - Math.sin(r) * right) * sp;
    }

    /** Скролл у края экрана: вектор (right, forward) от -1 до 1, 0 в середине. */
    public static double[] edgeScroll(double mx, double my, int w, int h) {
        double right = mx < EDGE ? -1 : mx > w - EDGE ? 1 : 0, fwd = my < EDGE ? 1 : my > h - EDGE ? -1 : 0;
        return new double[]{right, fwd};
    }

    public void rotate(double dYaw, double dPitch) {
        yaw = (yaw + dYaw) % 360;
        pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch + dPitch));
    }

    public void zoomBy(double steps) { zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * Math.pow(0.88, steps))); }

    public void focusOn(double x, double z) { fx = x; fz = z; }

    /** Не даёт уйти от игрока дальше leash. */
    public void clamp(double px, double pz) {
        double d = Math.hypot(fx - px, fz - pz);
        if (d > leash) { fx = px + (fx - px) / d * leash; fz = pz + (fz - pz) / d * leash; }
    }

    /** Позиция камеры {x, y, z} при высоте земли в фокусе groundY. */
    public double[] position(double groundY) {
        double p = Math.toRadians(pitch), r = Math.toRadians(yaw);
        double back = Math.cos(p) * zoom;
        return new double[]{fx + Math.sin(r) * back, groundY + Math.sin(p) * zoom, fz - Math.cos(r) * back};
    }
}
