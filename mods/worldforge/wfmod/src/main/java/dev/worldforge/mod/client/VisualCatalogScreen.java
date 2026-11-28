package dev.worldforge.mod.client;

import dev.worldforge.mod.Markers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** In-game picker for a marker's visual identity. The same profile IDs are consumed by map/RTS/Diorama adapters. */
public final class VisualCatalogScreen extends Screen {
    private final MapScreen parent;
    private final String markerId;
    private final String markerName;
    private final String markerType;
    private final List<WorldObjectVisualCatalog.Entry> choices;
    private int scroll;

    public VisualCatalogScreen(MapScreen parent, Markers.Marker marker) {
        super(Component.literal("Каталог визуальных моделей"));
        this.parent = parent;
        this.markerId = marker.id;
        this.markerName = marker.name;
        this.markerType = marker.type;
        this.choices = WorldObjectVisualCatalog.choicesFor(marker.type);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int panelW = Math.max(1, Math.min(650, width - 28));
        int panelH = Math.max(1, Math.min(500, height - 28));
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        g.fill(left, top, left + panelW, top + panelH, 0xF0201A16);
        g.renderOutline(left, top, panelW, panelH, 0xFFB58A4B);
        g.renderOutline(left + 3, top + 3, panelW - 6, panelH - 6, 0xFF6D4D2D);
        g.drawString(font, "КАТАЛОГ ВИЗУАЛОВ", left + 16, top + 13, 0xFFFFD98B, false);
        g.drawString(font, markerName + " · " + markerType, left + 16, top + 29, 0xFFE0D0B4, false);
        g.drawString(font, "ЛКМ — выбрать · ↑/↓ — прокрутка · Enter — первый профиль · Esc — назад",
                left + 16, top + 43, 0xFF9D927F, false);

        int autoY = top + 62;
        boolean autoHover = mouseX >= left + 12 && mouseX < left + panelW - 12
                && mouseY >= autoY && mouseY < autoY + 27;
        g.fill(left + 12, autoY, left + panelW - 12, autoY + 27, autoHover ? 0xFF55432D : 0xFF342A20);
        g.renderOutline(left + 12, autoY, panelW - 24, 27, 0xFFC59B5D);
        g.drawString(font, "АВТОМАТИЧЕСКИ · подобрать по типу объекта", left + 22, autoY + 9, 0xFFFFE2A0, false);

        int contentTop = autoY + 35;
        int contentBottom = top + panelH - 14;
        int rowH = 54, gap = 6;
        int cols = panelW >= 440 ? 2 : 1;
        int cardW = (panelW - 36 - (cols - 1) * gap) / cols;
        int rows = (choices.size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (contentBottom - contentTop) / (rowH + gap));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visibleRows)));
        g.enableScissor(left + 8, contentTop, left + panelW - 8, contentBottom);
        for (int i = 0; i < choices.size(); i++) {
            int row = i / cols, col = i % cols;
            if (row < scroll || row >= scroll + visibleRows) continue;
            int x = left + 12 + col * (cardW + gap);
            int y = contentTop + (row - scroll) * (rowH + gap);
            WorldObjectVisualCatalog.Entry entry = choices.get(i);
            boolean hovered = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + rowH;
            g.fill(x, y, x + cardW, y + rowH, hovered ? 0xFF59452D : 0xFF30271F);
            g.renderOutline(x, y, cardW, rowH, hovered ? 0xFFE3B86E : 0xFF806342);
            drawPreview(g, entry, x + 7, y + 8);
            int textX = x + 38;
            g.drawString(font, entry.displayName(), textX, y + 7, 0xFFFFE0A0, false);
            g.drawString(font, entry.id(), textX, y + 20, 0xFFB6A58B, false);
            String description = entry.description().isBlank()
                    ? "LOD " + entry.profile().iconLod() + " → " + entry.profile().modelLod()
                    : entry.description();
            g.drawString(font, font.plainSubstrByWidth(description, cardW - 48), textX, y + 34, 0xFF988D7B, false);
        }
        g.disableScissor();
        g.drawString(font, "Профилей: " + choices.size(), left + 14, top + panelH - 11, 0xFF9D927F, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawPreview(GuiGraphics g, WorldObjectVisualCatalog.Entry entry, int x, int y) {
        g.fill(x - 3, y - 3, x + 27, y + 27, 0xFF211A15);
        g.renderOutline(x - 3, y - 3, 30, 30, 0xFF927348);
        ResourceLocation preview = ResourceLocation.tryParse(entry.previewTexture());
        if (preview != null) {
            g.blit(preview, x, y, 0, 0, 24, 24, 24, 24);
            return;
        }
        String item = entry.profile().mapItem();
        ResourceLocation id = ResourceLocation.tryParse(item);
        if (id != null) {
            var value = BuiltInRegistries.ITEM.getOptional(id);
            if (value.isPresent() && !value.get().getDefaultInstance().isEmpty()) {
                g.renderItem(new ItemStack(value.get()), x + 4, y + 4);
                return;
            }
        }
        g.fill(x + 5, y + 5, x + 19, y + 19, 0xFFB58A4B);
        g.drawCenteredString(font, entry.profile().icon().substring(0, 1).toUpperCase(), x + 12, y + 8, 0xFF211A15);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int panelW = Math.max(1, Math.min(650, width - 28)), panelH = Math.max(1, Math.min(500, height - 28));
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        int autoY = top + 62;
        if (mouseX >= left + 12 && mouseX < left + panelW - 12
                && mouseY >= autoY && mouseY < autoY + 27) {
            apply("auto");
            return true;
        }
        int contentTop = autoY + 35, contentBottom = top + panelH - 14;
        if (mouseY < contentTop || mouseY >= contentBottom) return true;
        int cols = panelW >= 440 ? 2 : 1;
        int gap = 6, rowH = 54;
        int cardW = (panelW - 36 - (cols - 1) * gap) / cols;
        int col = (int) ((mouseX - (left + 12)) / (cardW + gap));
        int row = (int) ((mouseY - contentTop) / (rowH + gap)) + scroll;
        if (col < 0 || col >= cols || row < 0) return true;
        int index = row * cols + col;
        if (index < choices.size()) {
            int x = left + 12 + col * (cardW + gap);
            int y = contentTop + (row - scroll) * (rowH + gap);
            if (mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + rowH) {
                apply(choices.get(index).id());
                return true;
            }
        }
        return true;
    }

    private void apply(String profile) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(
                "worldforge marker visual " + markerId + " " + profile);
        mc.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelW = Math.max(1, Math.min(650, width - 28)), panelH = Math.max(1, Math.min(500, height - 28));
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        if (mouseX < left || mouseX >= left + panelW || mouseY < top || mouseY >= top + panelH)
            return super.mouseScrolled(mouseX, mouseY, delta);
        int contentTop = top + 97, contentBottom = top + panelH - 14;
        int cols = panelW >= 440 ? 2 : 1;
        int rowH = 54, gap = 6;
        int rows = (choices.size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (contentBottom - contentTop) / (rowH + gap));
        scroll = Math.max(0, Math.min(scroll + (delta < 0 ? 1 : -1), Math.max(0, rows - visibleRows)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        int panelW = Math.max(1, Math.min(650, width - 28));
        int panelH = Math.max(1, Math.min(500, height - 28));
        int cols = panelW >= 440 ? 2 : 1;
        int visibleRows = Math.max(1, (panelH - 111) / 60);
        int rows = (choices.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows - visibleRows);
        if (key == GLFW.GLFW_KEY_UP) { scroll = Math.max(0, scroll - 1); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll = Math.min(maxScroll, scroll + 1); return true; }
        if (key == GLFW.GLFW_KEY_ENTER && !choices.isEmpty()) {
            apply(choices.get(Math.min(choices.size() - 1, scroll * cols)).id());
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
