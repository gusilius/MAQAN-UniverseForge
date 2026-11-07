package dev.worldforge.rts;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.ArrayList;
import java.util.List;

/**
 * Преобразование «блоки поселения - пиксели миникарты» (вид сверху, север вверх, ось z вниз по экрану). Охват подгоняется под
 * здания и рабочих с запасом; масштаб одинаков по осям. Из этого же вида строятся прямоугольники для выбора рамкой на миникарте.
 */
public final class MapView {
    public final int px, py, pw, ph;
    public final double minX, minZ, scale;

    private MapView(int px, int py, int pw, int ph, double minX, double minZ, double scale) { this.px = px; this.py = py; this.pw = pw; this.ph = ph; this.minX = minX; this.minZ = minZ; this.scale = scale; }

    /** Вписать поселение в прямоугольник миникарты (px,py,pw,ph). Охват не меньше 48 блоков, чтобы пустое поселение не раздувалось. */
    public static MapView fit(Settlement s, int px, int py, int pw, int ph) {
        double x0 = -8, x1 = 8, z0 = -8, z1 = 8;
        if (s != null) {
            for (Building b : s.buildings) {
                if (b == null || b.def == null || !validWorldPoint(b.x, b.z)
                        || !Double.isFinite(b.def.sizeX) || !Double.isFinite(b.def.sizeZ)
                        || b.def.sizeX <= 0 || b.def.sizeZ <= 0) continue;
                double halfX = Math.min(1_000_000, b.def.sizeX) / 2.0;
                double halfZ = Math.min(1_000_000, b.def.sizeZ) / 2.0;
                x0 = Math.min(x0, b.x - halfX); x1 = Math.max(x1, b.x + halfX);
                z0 = Math.min(z0, b.z - halfZ); z1 = Math.max(z1, b.z + halfZ);
            }
            for (Worker w : s.workers) {
                if (w == null || !validWorldPoint(w.x, w.z)) continue;
                x0 = Math.min(x0, w.x); x1 = Math.max(x1, w.x);
                z0 = Math.min(z0, w.z); z1 = Math.max(z1, w.z);
            }
        }
        x0 -= 6; x1 += 6; z0 -= 6; z1 += 6;
        double spanX = Math.max(48, x1 - x0), spanZ = Math.max(48, z1 - z0);
        // A minimized/resized GUI can briefly report a zero-sized viewport. Keep
        // the world-to-screen transform finite until the next valid layout pass.
        int safePw = Math.max(1, pw), safePh = Math.max(1, ph);
        double sc = Math.min(safePw / spanX, safePh / spanZ);
        if (!Double.isFinite(sc) || sc <= 0) sc = 1.0 / 48.0;
        double cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        return new MapView(px, py, pw, ph, cx - safePw / sc / 2, cz - safePh / sc / 2, sc);
    }

    private static boolean validWorldPoint(double x, double z) {
        return Double.isFinite(x) && Double.isFinite(z)
                && Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000;
    }

    public int sx(double x) { return px + (int) Math.round((x - minX) * scale); }

    public int sy(double z) { return py + (int) Math.round((z - minZ) * scale); }

    public double wx(int sx) { return minX + (sx - px) / scale; }

    public double wz(int sy) { return minZ + (sy - py) / scale; }

    public boolean contains(int sx, int sy) { return sx >= px && sx < px + pw && sy >= py && sy < py + ph; }

    /** Прямоугольники рабочих (5x5 вокруг точки) и зданий (по размеру, не меньше 4x4) в пикселях экрана. */
    public List<Selection.Rect> rects(Settlement s) {
        List<Selection.Rect> l = new ArrayList<>();
        if (s == null) return l;
        for (Building b : s.buildings) {
            if (b == null || b.def == null || !validWorldPoint(b.x, b.z)
                    || !Double.isFinite(b.def.sizeX) || !Double.isFinite(b.def.sizeZ)) continue;
            int w = Math.max(4, (int) Math.min(1_000_000, Math.round(b.def.sizeX * scale)));
            int h = Math.max(4, (int) Math.min(1_000_000, Math.round(b.def.sizeZ * scale)));
            l.add(new Selection.Rect(Selection.Rect.BUILDING, b.uid, sx(b.x) - w / 2, sy(b.z) - h / 2, w, h));
        }
        for (Worker w : s.workers) if (w != null && validWorldPoint(w.x, w.z))
            l.add(new Selection.Rect(Selection.Rect.WORKER, w.id, sx(w.x) - 2, sy(w.z) - 2, 5, 5));
        return l;
    }

    /** Что под точкой миникарты: рабочие (радиус 4 px) важнее зданий, иначе земля. Координаты земли - целые блоки. */
    public Target pick(Settlement s, int sx, int sy) {
        Worker best = null;
        double bd = 4.5 * 4.5;
        if (s != null) for (Worker w : s.workers) {
            if (w == null || !validWorldPoint(w.x, w.z)) continue;
            double dx = sx(w.x) - sx, dy = sy(w.z) - sy, d = dx * dx + dy * dy;
            if (Double.isFinite(d) && d <= bd) { bd = d; best = w; }
        }
        int gx = (int) Math.round(wx(sx)), gz = (int) Math.round(wz(sy));
        if (best != null) return Target.worker(best.id, gx, gz);
        // Do not index rects(s) by the original building index: rects() deliberately
        // filters malformed entries, so a null/invalid building shifts every later hit target.
        for (int i = s.buildings.size() - 1; i >= 0; i--) {
            Building building = s.buildings.get(i);
            if (building == null || building.def == null || !validWorldPoint(building.x, building.z)
                    || !Double.isFinite(building.def.sizeX) || !Double.isFinite(building.def.sizeZ)
                    || building.def.sizeX <= 0 || building.def.sizeZ <= 0) continue;
            int w = Math.max(4, (int) Math.min(1_000_000, Math.round(building.def.sizeX * scale)));
            int h = Math.max(4, (int) Math.min(1_000_000, Math.round(building.def.sizeZ * scale)));
            int left = sx(building.x) - w / 2, top = sy(building.z) - h / 2;
            if (sx >= left && sx <= left + w && sy >= top && sy <= top + h)
                return Target.building(building.uid, gx, gz);
        }
        return Target.ground(gx, gz);
    }
}
