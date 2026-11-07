package dev.maqan.platform.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** First functional pass of the shared MAQAN desktop shell. Gameplay data stays in its owning mod. */
public final class PlatformShellScreen extends Screen {
    private static final int BG = 0xF0080E18, BAR = 0xF0101A28, PANEL = 0xE5122030;
    private static final int EDGE = 0xFF29445A, CYAN = 0xFF65D8F2, GOLD = 0xFFE0B66C;
    private String workspace = "ATLAS";
    private boolean leftOpen = true, rightOpen = true;
    private boolean windowOpen = true, dragging = false;
    private int windowX = 330, windowY = 142, windowW = 330, windowH = 210;
    private int dragX, dragY;
    private final List<Button> controls = new ArrayList<>();

    public PlatformShellScreen() { super(Component.literal("MAQAN Platform")); }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        controls.clear();
        int top = 8;
        addButton("ATLAS", 12, top, 82, () -> workspace = "ATLAS");
        addButton("WORLDFORGE", 98, top, 112, () -> workspace = "WORLDFORGE");
        addButton("WORKSPACE", 216, top, 94, () -> workspace = "WORKSPACE");
        addButton(leftOpen ? "‹" : "›", 8, 44, 24, () -> { leftOpen = !leftOpen; rebuildShellControls(); });
        addButton(rightOpen ? "›" : "‹", width - 32, 44, 24, () -> { rightOpen = !rightOpen; rebuildShellControls(); });
        String[] dock = {"NAV", "MAP", "ECON", "POLITICS", "ARMY", "JOURNAL"};
        int dw = Math.min(76, Math.max(48, (width - 40) / dock.length));
        int total = dw * dock.length;
        int start = (width - total) / 2;
        for (int i = 0; i < dock.length; i++) {
            final String item = dock[i];
            addButton(item, start + i * dw, height - 34, dw - 4, () -> { windowOpen = true; workspace = item.equals("NAV") || item.equals("MAP") ? "ATLAS" : "WORLDFORGE"; });
        }
        addButton("×", width - 32, 8, 24, this::onClose);
    }

    private void rebuildShellControls() { clearWidgets(); init(); }

    private void addButton(String label, int x, int y, int w, Runnable action) {
        Button b = Button.builder(Component.literal(label), button -> action.run())
                .bounds(x, y, w, 20).build();
        controls.add(b);
        addRenderableWidget(b);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
        drawGrid(g);
        g.fill(0, 0, width, 34, BAR);
        g.fill(0, 33, width, 35, EDGE);
        g.drawString(font, "MAQAN", 14, 18, 0xFFEAF4FC, false);
        g.drawString(font, "UNIVERSE PLATFORM  /  " + workspace, 82, 18, CYAN, false);
        g.drawString(font, "SESSION  •  LOCAL", width - 150, 18, 0xFF91A7BA, false);

        if (leftOpen) {
            g.fill(0, 36, 190, height - 42, PANEL);
            g.fill(188, 36, 190, height - 42, EDGE);
            g.drawString(font, "EXPLORER", 14, 52, GOLD, false);
            g.drawString(font, "WORKSPACES", 14, 76, 0xFF91A7BA, false);
            g.drawString(font, "Atlas", 22, 96, workspace.equals("ATLAS") ? CYAN : 0xFFE2EAF2, false);
            g.drawString(font, "WorldForge", 22, 114, workspace.equals("WORLDFORGE") ? CYAN : 0xFFE2EAF2, false);
            g.drawString(font, "Tools and maps", 22, 144, 0xFF91A7BA, false);
            g.drawString(font, "Favorites", 22, 162, 0xFF91A7BA, false);
        }
        if (rightOpen) {
            g.fill(width - 210, 36, width, height - 42, PANEL);
            g.fill(width - 212, 36, width - 210, height - 42, EDGE);
            g.drawString(font, "CONTEXT", width - 194, 52, GOLD, false);
            g.drawString(font, "No object selected", width - 194, 76, 0xFFB5C4D2, false);
            g.drawString(font, "Select an object in an", width - 194, 94, 0xFF8195A8, false);
            g.drawString(font, "Atlas or WorldForge view.", width - 194, 106, 0xFF8195A8, false);
            g.drawString(font, "MODULE STATUS", width - 194, 142, GOLD, false);
            statusLine(g, "Atlas", "maqan_galactic_navigator", width - 194, 162);
            statusLine(g, "WorldForge", "worldforge", width - 194, 180);
        }
        g.fill(0, height - 42, width, height, BAR);
        g.fill(0, height - 43, width, height - 41, EDGE);
        if (windowOpen) renderToolWindow(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void statusLine(GuiGraphics g, String label, String modId, int x, int y) {
        boolean present = ModList.get().isLoaded(modId);
        g.drawString(font, label, x, y, 0xFFDCE6EF, false);
        g.drawString(font, present ? "DETECTED" : "NOT INSTALLED", x + 86, y, present ? 0xFF7ED6AA : 0xFF8A98A8, false);
    }

    private void renderToolWindow(GuiGraphics g, int mouseX, int mouseY) {
        int x = Math.max(200, Math.min(windowX, width - windowW - 220));
        int y = Math.max(48, Math.min(windowY, height - windowH - 54));
        windowX = x; windowY = y;
        g.fill(x + 4, y + 5, x + windowW + 4, y + windowH + 5, 0x88000000);
        g.fill(x, y, x + windowW, y + windowH, PANEL);
        g.fill(x, y, x + windowW, y + 26, BAR);
        g.fill(x, y + 25, x + windowW, y + 27, CYAN);
        g.drawString(font, workspace.equals("ATLAS") ? "ATLAS  /  SPATIAL WORKSPACE" : "WORLDFORGE  /  WORLD MANAGEMENT", x + 12, y + 9, 0xFFEAF4FC, false);
        g.drawString(font, "×", x + windowW - 16, y + 9, 0xFFEAF4FC, false);
        g.drawString(font, "Integration surface", x + 14, y + 46, GOLD, false);
        boolean installed = ModList.get().isLoaded(workspace.equals("ATLAS") ? "maqan_galactic_navigator" : "worldforge");
        g.drawString(font, installed ? "Module detected — adapter connection is the next integration step." : "Install the corresponding gameplay mod to enable this workspace.", x + 14, y + 68, 0xFFB6C7D7, false);
        g.drawString(font, "The shell does not fabricate map or simulation data.", x + 14, y + 92, 0xFF8297AA, false);
        g.drawString(font, "Window controls and workspace state are local to this session.", x + 14, y + 110, 0xFF8297AA, false);
        g.fill(x + windowW - 12, y + windowH - 12, x + windowW - 4, y + windowH - 4, EDGE);
    }

    private void drawGrid(GuiGraphics g) {
        for (int x = 0; x < width; x += 32) g.fill(x, 35, x + 1, height - 42, 0x182A526B);
        for (int y = 35; y < height - 42; y += 32) g.fill(0, y, width, y + 1, 0x182A526B);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && windowOpen && mouseX >= windowX && mouseX <= windowX + windowW
                && mouseY >= windowY && mouseY <= windowY + 26) {
            if (mouseX >= windowX + windowW - 24) { windowOpen = false; return true; }
            dragging = true; dragX = (int) mouseX - windowX; dragY = (int) mouseY - windowY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            windowX = (int) mouseX - this.dragX;
            windowY = (int) mouseY - this.dragY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static void drawGridPlaceholder() { }
}
