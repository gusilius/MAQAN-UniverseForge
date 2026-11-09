package dev.maqan.platform.client;

/**
 * Canonical MAQAN Platform workspace design tokens.
 *
 * Keep these values synchronized with shared/contracts/ui-theme.json and the
 * independent Atlas fallback theme. This class owns presentation tokens only;
 * module screens retain their own behavior and domain data.
 */
public final class WorkspaceTheme {
    public static final int BACKGROUND = 0xF0060B12;
    public static final int SURFACE = 0xE8101924;
    public static final int SURFACE_RAISED = 0xF014202D;
    public static final int SURFACE_INSET = 0xE80B121A;
    public static final int SURFACE_HOVER = 0xE0253948;
    public static final int BORDER = 0x883B5265;
    public static final int BORDER_SUBTLE = 0x55334452;
    public static final int TEXT = 0xFFE8EEF5;
    public static final int TEXT_MUTED = 0xFF8292A5;
    public static final int TEXT_DISABLED = 0xFF556474;
    public static final int ACCENT = 0xFF69CDE2;
    public static final int ACCENT_SOFT = 0xFF477F91;
    public static final int SUCCESS = 0xFF67B85C;
    public static final int WARNING = 0xFFE5BD78;
    public static final int DANGER = 0xFFD9503A;
    public static final int INFORMATION = 0xFF5AA0D8;

    public static final int SPACE_UNIT = 4;
    public static final int PANEL_HEADER_HEIGHT = 26;
    public static final int RAIL_WIDTH = 44;
    public static final int TOP_BAR_HEIGHT = 35;
    public static final int FOCUS_WIDTH = 2;

    private WorkspaceTheme() {}

    public static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }
}
