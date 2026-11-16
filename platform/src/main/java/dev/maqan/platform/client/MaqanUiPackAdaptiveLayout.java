package dev.maqan.platform.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsive placement for the imported MAQAN skin.
 *
 * The concept coordinates describe visual proportions, not a fixed screen
 * canvas. Large viewports keep the familiar left/right composition; compact
 * viewports collapse the side windows into the shell's existing drawers and
 * place the minimap and command console in a non-overlapping bottom dock.
 */
public final class MaqanUiPackAdaptiveLayout {
    public static final int COMPACT_WIDTH = WorkspaceResponsiveLayout.COMPACT_WIDTH;
    public static final int COMPACT_HEIGHT = WorkspaceResponsiveLayout.COMPACT_HEIGHT;

    public record Rect(int x, int y, int width, int height) {
        public Rect {
            if (width < 0 || height < 0) throw new IllegalArgumentException("Negative rectangle size");
        }
        public int right() { return x + width; }
        public int bottom() { return y + height; }
    }

    public record Result(boolean compact, Map<String, Rect> rectangles) {
        public Result { rectangles = Map.copyOf(rectangles); }
        public Rect get(String layer) { return rectangles.get(layer); }
    }

    private MaqanUiPackAdaptiveLayout() {}

    public static boolean isCompact(int width, int height) {
        return width < COMPACT_WIDTH || height < COMPACT_HEIGHT;
    }

    public static Result calculate(int width, int height, Map<String, int[]> reference) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        boolean compact = isCompact(w, h);
        double baseScale = Math.min(w / 1368.0, h / 760.0);
        Map<String, Rect> out = new LinkedHashMap<>();

        int[] topRef = reference.get("top");
        int[] leftRailRef = reference.get("lcol");
        int[] rightRailRef = reference.get("rcol");
        int[] minimapRef = reference.get("mm");
        int[] consoleRef = reference.get("bot");
        int topH = Math.min(h, Math.max(28, (int)Math.round((topRef[3] - topRef[1]) * baseScale)));
        int leftRail = compact ? (w < 560 ? 0 : 30)
                : Math.min(w, Math.max(1, (int)Math.round((leftRailRef[2] - leftRailRef[0]) * baseScale)));
        int rightRail = compact ? (w < 720 ? 0 : 26)
                : Math.min(w, Math.max(1, (int)Math.round((1368 - rightRailRef[0]) * baseScale)));
        int gap = compact ? 6 : clamp((int)Math.round(12 * baseScale), 1, 18);

        out.put("top", new Rect(0, 0, w, topH));
        if (leftRail > 0) out.put("lcol", new Rect(0, topH, leftRail, Math.max(0, h - topH)));
        if (rightRail > 0) out.put("rcol", new Rect(w - rightRail, topH, rightRail, Math.max(0, h - topH)));

        double minimapAspect = (minimapRef[2] - minimapRef[0]) / (double)(minimapRef[3] - minimapRef[1]);
        int minimapRefW = minimapRef[2] - minimapRef[0];
        int minimapRefH = minimapRef[3] - minimapRef[1];
        int consoleRefW = consoleRef[2] - consoleRef[0];
        int consoleRefH = consoleRef[3] - consoleRef[1];

        if (!compact) {
            int[] leftRef = reference.get("lp");
            int[] rightRef = reference.get("rp");
            int leftRefW = leftRef[2] - leftRef[0], leftRefH = leftRef[3] - leftRef[1];
            int rightRefW = rightRef[2] - rightRef[0], rightRefH = rightRef[3] - rightRef[1];
            int minimapMaxW = Math.min(380, Math.max(210, w / 3));
            int minimapMaxH = Math.min(270, Math.max(150, h / 3));
            double minimapScale = Math.min(baseScale,
                    Math.min(minimapMaxW / (double)minimapRefW, minimapMaxH / (double)minimapRefH));
            int minimapW = Math.max(1, (int)Math.round(minimapRefW * minimapScale));
            int minimapH = Math.max(1, (int)Math.round(minimapRefH * minimapScale));
            int panelY = Math.max(0, (int)Math.round(leftRef[1] * baseScale));
            int panelMaxH = Math.max(1, h - panelY - minimapH - gap);
            int panelMaxW = Math.min(390, Math.max(220, w / 3));
            double leftScale = Math.min(baseScale,
                    Math.min(panelMaxW / (double)leftRefW, panelMaxH / (double)leftRefH));
            double rightScale = Math.min(baseScale,
                    Math.min(panelMaxW / (double)rightRefW, panelMaxH / (double)rightRefH));
            int leftW = Math.max(1, (int)Math.round(leftRefW * leftScale));
            int leftH = Math.max(1, (int)Math.round(leftRefH * leftScale));
            int rightW = Math.max(1, (int)Math.round(rightRefW * rightScale));
            int rightH = Math.max(1, (int)Math.round(rightRefH * rightScale));
            int leftX = Math.max(0, (int)Math.round(leftRef[0] * baseScale));
            int rightMargin = Math.max(0, (int)Math.round((1368 - rightRef[2]) * baseScale));
            int rightX = w - rightMargin - rightW;
            out.put("lp", new Rect(leftX, panelY, leftW, leftH));
            out.put("rp", new Rect(Math.max(leftX + leftW + gap, rightX), panelY, rightW, rightH));

            int minimapX = Math.max(0, (int)Math.round(minimapRef[0] * baseScale));
            out.put("mm", new Rect(minimapX, h - minimapH, minimapW, minimapH));

            int consoleMaxW = Math.max(1, Math.min(1120, w - minimapW - rightRail - gap * 3));
            int consoleMaxH = Math.max(1, Math.min(220, h / 3));
            double consoleScale = Math.min(baseScale,
                    Math.min(consoleMaxW / (double)consoleRefW, consoleMaxH / (double)consoleRefH));
            int consoleW = Math.max(1, (int)Math.round(consoleRefW * consoleScale));
            int consoleH = Math.max(1, (int)Math.round(consoleRefH * consoleScale));
            int preferredConsoleX = (int)Math.round(consoleRef[0] * baseScale);
            int consoleX = clamp(preferredConsoleX, minimapX + minimapW + gap,
                    Math.max(minimapX + minimapW + gap, w - rightRail - consoleW - gap));
            out.put("bot", new Rect(consoleX, h - consoleH, consoleW, consoleH));
        } else {
            // Keep both map and command console visible in a compact bottom
            // dock, preserving each theme's own artwork proportions.
            double dockHeightLimit = Math.max(1.0, Math.min(142.0, h * 0.42));
            double miniWidthLimit = Math.min(260.0,
                    Math.min(w * 0.36, dockHeightLimit * minimapAspect));
            double miniScale = Math.min(miniWidthLimit / minimapRefW,
                    dockHeightLimit / minimapRefH);
            int minimapW = Math.max(1, (int)Math.round(minimapRefW * miniScale));
            int minimapH = Math.max(1, (int)Math.round(minimapRefH * miniScale));
            int consoleX = Math.min(w, minimapW + gap);
            int consoleAvailableW = Math.max(0, w - consoleX);
            double consoleScale = Math.min(consoleAvailableW / (double)consoleRefW,
                    dockHeightLimit / consoleRefH);
            int consoleW = Math.max(0, (int)Math.round(consoleRefW * consoleScale));
            int consoleH = Math.max(0, (int)Math.round(consoleRefH * consoleScale));
            out.put("mm", new Rect(0, h - minimapH, minimapW, minimapH));
            out.put("bot", new Rect(consoleX, h - consoleH, consoleW, consoleH));
        }

        int corner = compact ? 0 : clamp((int)Math.round(136 * baseScale), 72, 168);
        if (corner > 0) out.put("cor", new Rect(0, 0, corner,
                Math.max(1, (int)Math.round(corner * 94.0 / 136.0))));
        return new Result(compact, out);
    }

    /**
     * Central playable/map viewport between the desktop side windows and above
     * the persistent minimap/command dock. Null means the viewport is too small
     * or the compact drawer layout is active.
     */
    public static Rect contentViewport(Result layout, int margin) {
        if (layout == null || layout.compact()) return null;
        Rect left = layout.get("lp");
        Rect right = layout.get("rp");
        Rect top = layout.get("top");
        Rect minimap = layout.get("mm");
        Rect console = layout.get("bot");
        if (left == null || right == null || top == null || minimap == null || console == null) return null;
        int inset = Math.max(0, margin);
        int x = left.right() + inset;
        int y = top.bottom() + inset;
        int rightEdge = right.x() - inset;
        int bottomEdge = Math.min(minimap.y(), console.y()) - inset;
        return rightEdge > x && bottomEdge > y
                ? new Rect(x, y, rightEdge - x, bottomEdge - y) : null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
