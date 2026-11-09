package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.NavigatorNativeBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * MAQAN Atlas visual language.
 *
 * Rendering only: Minecraft remains authoritative for widgets, focus, input,
 * hit-testing and screen lifecycle. Rust may compose the same surfaces when its
 * optional native library is available; every method has an equivalent Java fallback.
 */
public final class MaqanUiTheme {
    // Canonical tokens mirrored from shared/contracts/ui-theme.json.
    public static final int TEXT = 0xFFE8EEF5;
    public static final int MUTED = 0xFF8292A5;
    public static final int CYAN = 0xFF69CDE2;
    public static final int GOLD = 0xFFE5BD78;
    public static final int BACKGROUND = 0xFF080D14;
    private static final int PANEL = 0xD9101923;
    private static final int PANEL_EDGE = 0x6634495B;
    private static final int CONTROL = 0xCC172330;
    private static final int CONTROL_HOVER = 0xE0253948;

    private MaqanUiTheme() {}

    /** Quiet near-black canvas. The actual celestial scene remains untouched. */
    public static void backdrop(GuiGraphics g, int width, int height) {
        if (NavigatorNativeBridge.renderChrome(g, Minecraft.getInstance().font, width, height, 0.0f, 0)) return;
        g.fill(0, 0, width, height, BACKGROUND);
        // A single low-contrast horizon line gives the workspace depth without
        // competing with planet models, orbit paths or map annotations.
        int horizon = Math.max(1, Math.min(height - 1, (int) (height * 0.115f)));
        g.fill(0, horizon, width, horizon + 1, 0x183B6175);
    }

    /** Flat translucent surface; no bevels, corner cuts or decorative brackets. */
    public static void panel(GuiGraphics g, int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) return;
        if (NavigatorNativeBridge.renderPanel(g, left, top, right, bottom, CYAN)) return;
        g.fill(left, top, right, bottom, PANEL);
        g.fill(left, top, right, top + 1, PANEL_EDGE);
        g.fill(left, bottom - 1, right, bottom, 0x333E5265);
    }

    /** Minimal rectangular button with a precise active signal. */
    public static void button(GuiGraphics g, Font font, Button button) {
        if (button == null || !button.visible) return;
        if (NavigatorNativeBridge.renderButton(g, button)) return;
        int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
        boolean hover = button.isHoveredOrFocused();
        boolean enabled = button.active;
        int fill = !enabled ? 0x99101822 : hover ? CONTROL_HOVER : CONTROL;
        g.fill(x, y, x + w, y + h, fill);
        if (hover && enabled) g.fill(x, y, x + 2, y + h, CYAN);
        else g.fill(x, y, x + w, y + 1, 0x66566B80);
        g.drawCenteredString(font, button.getMessage(), x + w / 2, y + (h - 8) / 2,
                !enabled ? MUTED : hover ? 0xFFFFFFFF : TEXT);
    }

    /** Applies the shared skin after Screen.render; it does not alter widget behavior. */
    public static void skinWidgets(Screen screen, GuiGraphics graphics, Font font) {
        if (screen == null) return;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button) button(graphics, font, button);
            else if (child instanceof EditBox editBox) inputFrame(graphics, editBox);
        }
    }

    public static void inputFrame(GuiGraphics g, EditBox input) {
        if (input == null || !input.visible) return;
        int x = input.getX(), y = input.getY(), w = input.getWidth(), h = input.getHeight();
        int edge = input.isFocused() ? 0xCC69CDE2 : 0x66566B80;
        g.fill(x, y, x + w, y + 1, edge);
        g.fill(x, y + h - 1, x + w, y + h, 0x33465A6D);
    }

    public static void sectionHeader(GuiGraphics g, Font font, String label,
                                     int x, int y, int width, int height) {
        if (label == null || width <= 0 || height <= 0) return;
        if (NavigatorNativeBridge.renderSectionHeader(g, font, label, x, y, width, height)) return;
        g.drawString(font, label, x, y, CYAN, false);
        int lineY = y + Math.max(13, height - 2);
        g.fill(x, lineY, x + width, lineY + 1, 0x443D5265);
        g.fill(x, lineY, Math.min(x + width, x + 8 + font.width(label)), lineY + 1, 0xCC69CDE2);
    }

    public static void titleRight(GuiGraphics g, Font font, String title, String subtitle, int width) {
        if (NavigatorNativeBridge.renderTitleRight(g, font, title, subtitle, width)) return;
        g.drawString(font, title, 18, 13, TEXT, false);
        if (subtitle != null && !subtitle.isBlank())
            g.drawString(font, subtitle, Math.max(18, width - 18 - font.width(subtitle)), 27, MUTED, false);
        g.fill(12, 42, width - 12, 43, 0x443D5265);
        g.fill(12, 42, Math.min(width - 12, 42), 43, CYAN);
    }

    public static void title(GuiGraphics g, Font font, String title, String subtitle, int width) {
        if (NavigatorNativeBridge.renderTitle(g, font, title, subtitle, width)) return;
        g.drawString(font, title, 18, 13, TEXT, false);
        if (subtitle != null && !subtitle.isBlank()) g.drawString(font, subtitle, 18, 27, MUTED, false);
        g.fill(12, 42, width - 12, 43, 0x443D5265);
        g.fill(12, 42, Math.min(width - 12, 42), 43, CYAN);
    }
}
