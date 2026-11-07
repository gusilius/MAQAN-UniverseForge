package com.maqan.galacticnavigator.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure geometry helpers for clipping and rasterizing WorldForge map polygons. */
public final class WorldForgeMapGeometry {
    private WorldForgeMapGeometry() {}

    public record Point(double x, double y) {}
    public record Span(int y, int xStart, int xEndExclusive) {}

    /** Clips a polygon against an axis-aligned viewport using Sutherland-Hodgman. */
    public static List<Point> clipPolygon(List<Point> polygon,
                                          double left, double top, double right, double bottom) {
        if (polygon == null || polygon.size() < 3 || right <= left || bottom <= top) return List.of();
        List<Point> result = new ArrayList<>(polygon);
        result = clipEdge(result, 0, left);
        result = clipEdge(result, 1, right);
        result = clipEdge(result, 2, top);
        result = clipEdge(result, 3, bottom);
        return result.size() < 3 ? List.of() : List.copyOf(result);
    }

    private static List<Point> clipEdge(List<Point> input, int edge, double boundary) {
        if (input.isEmpty()) return List.of();
        List<Point> output = new ArrayList<>();
        Point previous = input.get(input.size() - 1);
        boolean previousInside = inside(previous, edge, boundary);
        for (Point current : input) {
            boolean currentInside = inside(current, edge, boundary);
            if (currentInside != previousInside) output.add(intersection(previous, current, edge, boundary));
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private static boolean inside(Point point, int edge, double boundary) {
        return switch (edge) {
            case 0 -> point.x >= boundary;
            case 1 -> point.x <= boundary;
            case 2 -> point.y >= boundary;
            default -> point.y <= boundary;
        };
    }

    private static Point intersection(Point a, Point b, int edge, double boundary) {
        if (edge <= 1) {
            double dx = b.x - a.x;
            double t = Math.abs(dx) < 1.0e-12 ? 0 : (boundary - a.x) / dx;
            return new Point(boundary, a.y + (b.y - a.y) * t);
        }
        double dy = b.y - a.y;
        double t = Math.abs(dy) < 1.0e-12 ? 0 : (boundary - a.y) / dy;
        return new Point(a.x + (b.x - a.x) * t, boundary);
    }

    /** Scanline fill spans for concave or convex polygons, clipped to the viewport. */
    public static List<Span> scanlineSpans(List<Point> polygon,
                                            int left, int top, int right, int bottom) {
        List<Point> clipped = clipPolygon(polygon, left, top, right, bottom);
        if (clipped.size() < 3) return List.of();
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point point : clipped) {
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        int firstY = Math.max(top, (int) Math.ceil(minY - 0.5));
        int lastY = Math.min(bottom - 1, (int) Math.floor(maxY - 0.5));
        List<Span> spans = new ArrayList<>();
        List<Double> intersections = new ArrayList<>(clipped.size());
        for (int y = firstY; y <= lastY; y++) {
            double sampleY = y + 0.5;
            intersections.clear();
            for (int i = 0; i < clipped.size(); i++) {
                Point a = clipped.get(i), b = clipped.get((i + 1) % clipped.size());
                if ((a.y <= sampleY && b.y > sampleY) || (b.y <= sampleY && a.y > sampleY)) {
                    intersections.add(a.x + (sampleY - a.y) * (b.x - a.x) / (b.y - a.y));
                }
            }
            intersections.sort(Comparator.naturalOrder());
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                int x1 = Math.max(left, (int) Math.ceil(intersections.get(i) - 0.5));
                int x2 = Math.min(right, (int) Math.ceil(intersections.get(i + 1) - 0.5));
                if (x2 > x1) spans.add(new Span(y, x1, x2));
            }
        }
        return List.copyOf(spans);
    }

    /** Area-weighted polygon centroid; falls back to the vertex mean for degenerate shapes. */
    public static Point centroid(List<Point> polygon) {
        if (polygon == null || polygon.isEmpty()) return new Point(0, 0);
        double twiceArea = 0, cx = 0, cy = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            double cross = a.x * b.y - b.x * a.y;
            twiceArea += cross;
            cx += (a.x + b.x) * cross;
            cy += (a.y + b.y) * cross;
        }
        if (Math.abs(twiceArea) > 1.0e-9)
            return new Point(cx / (3 * twiceArea), cy / (3 * twiceArea));
        double x = 0, y = 0;
        for (Point point : polygon) { x += point.x; y += point.y; }
        return new Point(x / polygon.size(), y / polygon.size());
    }
}
