package dev.worldforge.mod.client;

/**
 * WorldForge's compile-time copy of shared/contracts/ui-theme.json.
 * Keep token values synchronized with MAQAN Platform WorkspaceTheme and Atlas
 * MaqanUiTheme. Modules remain independently buildable and installable.
 */
public final class MaqanUiPalette {
    public static final int BACKGROUND = 0xFF080D14;
    public static final int SURFACE = 0xFF101923;
    public static final int SURFACE_RAISED = 0xFF172330;
    public static final int SURFACE_HOVER = 0xFF253948;
    public static final int SURFACE_INSET = 0xFF0B121A;
    public static final int BORDER = 0xFF34495B;
    public static final int BORDER_SUBTLE = 0xFF223442;
    public static final int TEXT = 0xFFE8EEF5;
    public static final int TEXT_MUTED = 0xFF8292A5;
    public static final int TEXT_DISABLED = 0xFF556474;
    public static final int ACCENT = 0xFF69CDE2;
    public static final int ACCENT_SOFT = 0xFF477F91;
    public static final int SUCCESS = 0xFF67B85C;
    public static final int WARNING = 0xFFE5BD78;
    public static final int DANGER = 0xFFD9503A;
    public static final int INFORMATION = 0xFF5AA0D8;

    private MaqanUiPalette() {}
}
