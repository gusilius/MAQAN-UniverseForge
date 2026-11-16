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
    public static final int COMPACT_WIDTH = 980;
    public static final int COMPACT_HEIGHT = 620;

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
        double scale = Math.min(w / 1368.0, h / 760.0);
        Map<String, Rect> out = new LinkedHashMap<>();

        int topH = clamp((int)Math.round(62 * Math.max(0.72, h / 760.0)), 34, 78);
        int leftRail = compact ? (w < 560 ? 0 : 30) : clamp((int)Math.round(58 * scale), 42, 68);
        int rightRail = compact && w < 720 ? 0 : (compact ? 26 : clamp((int)Math.round(42 * scale), 32, 56));
        int gap = compact ? 6 : clamp((int)Math.round(12 * scale), 8, 18);

        out.put("top", new Rect(0, 0, w, topH));
        if (leftRail > 0) out.put("lcol", new Rect(0, topH, leftRail, Math.max(0, h - topH)));
        if (rightRail > 0) out.put("rcol", new Rect(w - rightRail, topH, rightRail, Math.max(0, h - topH)));

        if (!compact) {
            int[] leftRef = reference.get("lp");
            int[] rightRef = reference.get("rp");
            int leftW = clamp((int)Math.round((leftRef[2] - leftRef[0]) * scale), 220, Math.min(390, Math.max(220, w / 3)));
            int rightW = clamp((int)Math.round((rightRef[2] - rightRef[0]) * scale), 220, Math.min(390, Math.max(220, w / 3)));
            int panelH = clamp((int)Math.round(Math.max(leftRef[3] - leftRef[1], rightRef[3] - rightRef[1]) * scale),
                    260, Math.max(260, h - topH - 190));
            int panelY = topH + gap;
            out.put("lp", new Rect(leftRail + gap, panelY, leftW, panelH));
            out.put("rp", new Rect(Math.max(leftRail + gap + leftW + gap, w - rightRail - gap - rightW),
                    panelY, rightW, panelH));

            int minimapW = clamp((int)Math.round((reference.get("mm")[2] - reference.get("mm")[0]) * scale),
                    210, Math.min(380, Math.max(210, w / 3)));
            int minimapH = clamp((int)Math.round((reference.get("mm")[3] - reference.get("mm")[1]) * scale),
                    150, Math.min(270, Math.max(150, h / 3)));
            out.put("mm", new Rect(0, h - minimapH, minimapW, minimapH));

            int consoleW = clamp((int)Math.round((reference.get("bot")[2] - reference.get("bot")[0]) * scale),
                    500, Math.min(1120, Math.max(500, w - minimapW - rightRail - gap * 3)));
            int consoleH = clamp((int)Math.round((reference.get("bot")[3] - reference.get("bot")[1]) * scale),
                    112, Math.min(220, Math.max(112, h / 3)));
            int consoleX = clamp((w - consoleW) / 2, minimapW + gap, Math.max(minimapW + gap, w - rightRail - consoleW - gap));
            out.put("bot", new Rect(consoleX, h - consoleH, consoleW, consoleH));
        } else {
            // The side information windows are represented by the shell's
            // existing compact drawers; don't paint fixed-size frames over the map.
            int dockH = clamp((int)Math.round(h * 0.25), 76, 142);
            int minimapW = clamp((int)Math.round(w * 0.31), Math.min(150, w), Math.min(260, w));
            int bottomY = h - dockH;
            if (w >= 560) out.put("mm", new Rect(0, bottomY, minimapW, dockH));
            int consoleX = w >= 560 ? minimapW + gap : 0;
            int consoleW = Math.max(0, w - consoleX);
            out.put("bot", new Rect(consoleX, bottomY, consoleW, dockH));
        }

        int corner = compact ? 0 : clamp((int)Math.round(136 * scale), 72, 168);
        if (corner > 0) out.put("cor", new Rect(0, 0, corner, clamp((int)Math.round(94 * scale), 48, 116)));
        return new Result(compact, out);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
