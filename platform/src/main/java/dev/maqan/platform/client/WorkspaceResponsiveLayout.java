package dev.maqan.platform.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolution-aware fallback layout for the shared MAQAN workspace.
 *
 * <p>At desktop sizes the user's dock tree remains authoritative. On compact
 * viewports the map becomes the full canvas and one selected panel is shown
 * as a drawer, preventing minimum-size constraints from crushing the map or
 * allowing panels to overlap one another.</p>
 */
public final class WorkspaceResponsiveLayout {
    public static final int COMPACT_WIDTH = 840;
    public static final int COMPACT_HEIGHT = 560;

    public record Result(boolean compact, Map<String, WorkspaceDockTree.Rect> rectangles) {
        public Result {
            rectangles = Map.copyOf(rectangles);
        }
    }

    private WorkspaceResponsiveLayout() {}

    public static boolean isCompact(int width, int height) {
        return width < COMPACT_WIDTH || height < COMPACT_HEIGHT;
    }

    public static Result calculate(int width, int height, String activeDrawer) {
        return calculate(width, height, activeDrawer, 0);
    }

    /**
     * @param reservedBottom height reserved for persistent shell controls such
     *                       as the concept minimap and command dock
     */
    public static Result calculate(int width, int height, String activeDrawer, int reservedBottom) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int usableBottom = Math.max(35, h - Math.max(0, Math.min(reservedBottom, h - 36)));
        boolean compact = isCompact(w, h);
        Map<String, WorkspaceDockTree.Rect> result = new LinkedHashMap<>();
        if (!compact) return new Result(false, result);

        int left = Math.min(44, Math.max(0, w - 1));
        int top = Math.min(35, Math.max(0, h - 1));
        int canvasWidth = Math.max(1, w - left);
        int canvasHeight = Math.max(1, usableBottom - top);
        result.put("content", new WorkspaceDockTree.Rect(left, top, canvasWidth, canvasHeight));

        if (activeDrawer == null || activeDrawer.isBlank()
                || activeDrawer.equals("content")) return new Result(true, result);

        int margin = Math.min(8, Math.max(2, w / 32));
        int drawerWidth = Math.max(1, Math.min(272, w - left - margin * 2));
        int availableHeight = Math.max(1, usableBottom - top - margin * 2);
        int drawerHeight;
        int x;
        int y;
        switch (activeDrawer) {
            case "timeline" -> {
                drawerHeight = Math.max(1, Math.min(164, availableHeight));
                drawerWidth = Math.max(1, Math.min(w, w - left - margin * 2));
                x = clamp(left + margin, 0, Math.max(0, w - drawerWidth));
                y = clamp(usableBottom - margin - drawerHeight, 0, Math.max(0, usableBottom - drawerHeight));
            }
            case "inspector", "tools" -> {
                drawerHeight = Math.max(1, Math.min(availableHeight, Math.max(210, (int) (availableHeight * 0.72))));
                x = clamp(Math.max(left + margin, w - margin - drawerWidth), 0, Math.max(0, w - drawerWidth));
                y = clamp(top + margin, 0, Math.max(0, usableBottom - drawerHeight));
            }
            default -> {
                drawerHeight = Math.max(1, Math.min(availableHeight, Math.max(210, (int) (availableHeight * 0.72))));
                x = clamp(left + margin, 0, Math.max(0, w - drawerWidth));
                y = clamp(top + margin, 0, Math.max(0, usableBottom - drawerHeight));
            }
        }
        result.put(activeDrawer, new WorkspaceDockTree.Rect(x, y, drawerWidth, drawerHeight));
        return new Result(true, result);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
