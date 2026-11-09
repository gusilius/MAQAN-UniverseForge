package dev.maqan.platform.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Small shared drawing primitives for Platform, Atlas and WorldForge screens. */
public final class WorkspaceUi {
    private WorkspaceUi() {}

    public static void panel(GuiGraphics graphics, int x, int y, int width, int height,
                             String title, Font font) {
        graphics.fill(x, y, x + width, y + height, WorkspaceTheme.SURFACE);
        graphics.fill(x, y, x + width, y + WorkspaceTheme.PANEL_HEADER_HEIGHT,
                WorkspaceTheme.SURFACE_RAISED);
        graphics.fill(x, y + WorkspaceTheme.PANEL_HEADER_HEIGHT - 1,
                x + width, y + WorkspaceTheme.PANEL_HEADER_HEIGHT,
                WorkspaceTheme.BORDER);
        graphics.drawString(font, Component.literal(title == null ? "" : title),
                x + 10, y + 8, WorkspaceTheme.TEXT, false);
    }

    public static void section(GuiGraphics graphics, int x, int y, int width,
                               String title, Font font) {
        graphics.drawString(font, Component.literal(title == null ? "" : title.toUpperCase(java.util.Locale.ROOT)),
                x, y, WorkspaceTheme.ACCENT, false);
        graphics.fill(x, y + 11, x + Math.max(0, width), y + 12, WorkspaceTheme.BORDER_SUBTLE);
    }

    public static void status(GuiGraphics graphics, Font font, int x, int y,
                              String label, WorkspaceRuntime.Severity severity) {
        int color = switch (severity) {
            case SUCCESS -> WorkspaceTheme.SUCCESS;
            case WARNING -> WorkspaceTheme.WARNING;
            case ERROR -> WorkspaceTheme.DANGER;
            case INFO -> WorkspaceTheme.INFORMATION;
        };
        graphics.fill(x, y + 2, x + 5, y + 7, color);
        graphics.drawString(font, Component.literal(label == null ? "" : label), x + 9, y, color, false);
    }

    public static boolean contains(double mouseX, double mouseY,
                                   int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public static int scaled(int base, double uiScale) {
        if (!Double.isFinite(uiScale)) return base;
        return Math.max(1, Math.min(4096, (int) Math.round(base * Math.max(0.5, Math.min(2.0, uiScale)))));
    }
}
