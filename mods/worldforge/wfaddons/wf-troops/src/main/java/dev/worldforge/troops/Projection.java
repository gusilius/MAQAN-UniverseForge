package dev.worldforge.troops;

/** Проекция мира в экран и обратно по матрицам (столбцовый порядок double[16], как у JOML). Чистая математика. */
public final class Projection {
    private Projection() {}

    public static double[] mul(double[] a, double[] b) {
        double[] r = new double[16];
        for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) { double s = 0; for (int k = 0; k < 4; k++) s += a[k * 4 + rr] * b[c * 4 + k]; r[c * 4 + rr] = s; }
        return r;
    }

    /** Точка мира (относительно камеры) -> {sx, sy, depth>0 если перед камерой}; sy растёт вниз. */
    public static double[] project(double[] vp, double x, double y, double z, int w, int h) {
        double cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12], cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13],
                cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
        if (cw <= 1e-6) return new double[]{0, 0, -1};
        return new double[]{(cx / cw * 0.5 + 0.5) * w, (1 - (cy / cw * 0.5 + 0.5)) * h, cw};
    }

    public static double[] invert(double[] m) {
        double[] inv = new double[16], a = m.clone();
        double[][] t = new double[4][8];
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) { t[r][c] = a[c * 4 + r]; t[r][4 + c] = r == c ? 1 : 0; }
        for (int i = 0; i < 4; i++) {
            int p = i;
            for (int r = i + 1; r < 4; r++) if (Math.abs(t[r][i]) > Math.abs(t[p][i])) p = r;
            if (Math.abs(t[p][i]) < 1e-12) return null;
            double[] tmp = t[i]; t[i] = t[p]; t[p] = tmp;
            double d = t[i][i];
            for (int c = 0; c < 8; c++) t[i][c] /= d;
            for (int r = 0; r < 4; r++) if (r != i) { double f = t[r][i]; for (int c = 0; c < 8; c++) t[r][c] -= f * t[i][c]; }
        }
        for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) inv[c * 4 + r] = t[r][4 + c];
        return inv;
    }

    /** Луч из камеры через пиксель: {dx,dy,dz} (направление, нормированное) в системе "относительно камеры". */
    public static double[] ray(double[] invVp, double sx, double sy, int w, int h) {
        double nx = sx / w * 2 - 1, ny = 1 - sy / h * 2;
        double[] far = unproject(invVp, nx, ny, 1), near = unproject(invVp, nx, ny, -1);
        double dx = far[0] - near[0], dy = far[1] - near[1], dz = far[2] - near[2], l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / l, dy / l, dz / l};
    }

    private static double[] unproject(double[] inv, double x, double y, double z) {
        double ox = inv[0] * x + inv[4] * y + inv[8] * z + inv[12], oy = inv[1] * x + inv[5] * y + inv[9] * z + inv[13],
                oz = inv[2] * x + inv[6] * y + inv[10] * z + inv[14], ow = inv[3] * x + inv[7] * y + inv[11] * z + inv[15];
        return new double[]{ox / ow, oy / ow, oz / ow};
    }

    /** Пересечение луча (из камеры на высоте camY) с плоскостью y=planeY (в координатах относительно камеры planeY - camY). null, если смотрим вверх. */
    public static double[] hitPlane(double[] dir, double relPlaneY) {
        if (Math.abs(dir[1]) < 1e-9) return null;
        double t = relPlaneY / dir[1];
        if (t <= 0) return null;
        return new double[]{dir[0] * t, relPlaneY, dir[2] * t};
    }

    /** Перспективная матрица (fovY градусов), столбцовая. */
    public static double[] perspective(double fovY, double aspect, double near, double far) {
        double f = 1 / Math.tan(Math.toRadians(fovY) / 2);
        double[] m = new double[16];
        m[0] = f / aspect; m[5] = f; m[10] = (far + near) / (near - far); m[11] = -1; m[14] = 2 * far * near / (near - far);
        return m;
    }

    /** Матрица вида: камера в начале координат; yaw/pitch как в Майнкрафте (yaw 0 - на юг +Z, pitch>0 - вниз). */
    public static double[] view(double yawDeg, double pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        double[] f = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
        double[] r = {-Math.cos(y), 0, -Math.sin(y)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        // строки: right, up, -forward (столбцовое хранение)
        return new double[]{r[0], u[0], -f[0], 0, r[1], u[1], -f[1], 0, r[2], u[2], -f[2], 0, 0, 0, 0, 1};
    }
}
