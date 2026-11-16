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

        // The top bar keeps the concept texture's original aspect ratio and
        // stretches horizontally without forcing the rest of the skin to scale.
        int topH = Math.min(h, Math.max(1, (int)Math.round(w * 62.0 / 1368.0)));
        int leftRail = compact ? (w < 560 ? 0 : 30) : clamp((int)Math.round(58 * baseScale), 42, 68);
        int rightRail = compact ? (w < 720 ? 0 : 26) : clamp((int)Math.round(42 * baseScale), 32, 56);
        int gap = compact ? 6 : clamp((int)Math.round(12 * baseScale), 8, 18);

        out.put("top", new Rect(0, 0, w, topH));
        if (leftRail > 0) out.put("lcol", new Rect(0, topH, leftRail, Math.max(0, h - topH)));
        if (rightRail > 0) out.put("rcol", new Rect(w - rightRail, topH, rightRail, Math.max(0, h - topH)));

        double minimapAspect = 292.0 / 226.0;
        double consoleAspect = 846.0 / 176.0;
        if (!compact) {
            int[] leftRef = reference.get("lp");
            int[] rightRef = reference.get("rp");
            int leftRefW = leftRef[2] - leftRef[0], leftRefH = leftRef[3] - leftRef[1];
            int rightRefW = rightRef[2] - rightRef[0], rightRefH = rightRef[3] - rightRef[1];
            int minimapMaxW = Math.min(380, Math.max(210, w / 3));
            int minimapMaxH = Math.min(270, Math.max(150, h / 3));
            double minimapScale = Math.min(baseScale,
                    Math.min(minimapMaxW / 292.0, minimapMaxH / 226.0));
            int minimapW = Math.max(1, (int)Math.round(292 * minimapScale));
            int minimapH = Math.max(1, (int)Math.round(minimapW / minimapAspect));
            int panelY = topH;
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
            out.put("lp", new Rect(leftRail + gap, panelY, leftW, leftH));
            out.put("rp", new Rect(Math.max(leftRail + gap + leftW + gap, w - rightRail - gap - rightW),
                    panelY, rightW, rightH));

            int minimapY = h - minimapH;
            out.put("mm", new Rect(0, minimapY, minimapW, minimapH));

            int consoleMaxW = Math.max(1, Math.min(1120, w - minimapW - rightRail - gap * 3));
            int consoleMaxH = Math.max(1, Math.min(220, h / 3));
            double consoleScale = Math.min(baseScale,
                    Math.min(consoleMaxW / 846.0, consoleMaxH / 176.0));
            int consoleW = Math.max(1, (int)Math.round(846 * consoleScale));
            int consoleH = Math.max(1, (int)Math.round(consoleW / consoleAspect));
            int consoleX = clamp((w - consoleW) / 2, minimapW + gap,
                    Math.max(minimapW + gap, w - rightRail - consoleW - gap));
            out.put("bot", new Rect(consoleX, h - consoleH, consoleW, consoleH));
        } else {
            // Keep both map and command console visible in a compact bottom
            // dock, preserving each artwork's aspect ratio independently.
            double miniWLimit = Math.min(260.0, Math.min(w * 0.36, 142.0 * minimapAspect));
            int minimapW = Math.max(1, (int)Math.round(miniWLimit));
            int minimapH = Math.max(1, (int)Math.round(minimapW / minimapAspect));
            int consoleX = Math.min(w, minimapW + gap);
            int consoleW = Math.max(0, w - consoleX);
            int consoleH = Math.max(0, Math.min(142, (int)Math.round(consoleW / consoleAspect)));
            out.put("mm", new Rect(0, h - minimapH, minimapW, minimapH));
            out.put("bot", new Rect(consoleX, h - consoleH, consoleW, consoleH));
        }

        int corner = compact ? 0 : clamp((int)Math.round(136 * baseScale), 72, 168);
        if (corner > 0) out.put("cor", new Rect(0, 0, corner,
                Math.max(1, (int)Math.round(corner * 94.0 / 136.0))));
        return new Result(compact, out);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
