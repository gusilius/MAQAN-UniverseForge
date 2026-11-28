package dev.worldforge.mod.client;

import dev.worldforge.mod.Markers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Map-first creation flow: choose object type, visual identity or automatic fallback, then name it. */
public final class MarkerCreateScreen extends Screen {
    private final MapScreen parent;
    private final int blockX, blockZ;
    private final String[] types = Markers.TYPES.toArray(String[]::new);
    private int typeIndex;
    private int scroll;
    private String selectedProfile = "auto";
    private EditBox nameBox;
    private List<WorldObjectVisualCatalog.Entry> choices = List.of();

    public MarkerCreateScreen(MapScreen parent, int blockX, int blockZ) {
        super(Component.literal("Создать объект карты"));
        this.parent = parent;
        this.blockX = blockX;
        this.blockZ = blockZ;
        refreshChoices();
    }

    @Override
    protected void init() {
        int panelH = Math.max(1, Math.min(470, height - 24));
        int top = (height - panelH) / 2;
        nameBox = new EditBox(font, width / 2 - 150, top + panelH - 56, 300, 20, Component.literal("Название объекта"));
        nameBox.setMaxLength(64);
        nameBox.setValue("Новый объект");
        nameBox.setHint(Component.literal("Название города, крепости, армии..."));
        addRenderableWidget(nameBox);
    }

    private String type() { return types[typeIndex]; }

    private void refreshChoices() {
        choices = WorldObjectVisualCatalog.choicesFor(type());
        selectedProfile = "auto";
        scroll = 0;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int panelW = Math.max(1, Math.min(620, width - 24)), panelH = Math.min(470, height - 24);
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        g.fill(left, top, left + panelW, top + panelH, 0xF0201A16);
        g.renderOutline(left, top, panelW, panelH, 0xFFB58A4B);
        g.renderOutline(left + 3, top + 3, panelW - 6, panelH - 6, 0xFF6D4D2D);
        g.drawString(font, "СОЗДАНИЕ ОБЪЕКТА МИРА", left + 16, top + 13, 0xFFFFD98B, false);
        g.drawString(font, "Координаты: " + blockX + " / " + blockZ, left + 16, top + 29, 0xFFB7A78D, false);

        int typeY = top + 60;
        g.drawString(font, "ТИП ОБЪЕКТА", left + 16, typeY, 0xFFBBA98B, false);
        drawChoice(g, left + 16, typeY + 15, 30, 25, "‹", mouseX, mouseY);
        drawChoice(g, left + 52, typeY + 15, 240, 25, type(), mouseX, mouseY);
        drawChoice(g, left + 298, typeY + 15, 30, 25, "›", mouseX, mouseY);

        int profileY = typeY + 54;
        g.drawString(font, "ВИЗУАЛЬНЫЙ ПРОФИЛЬ", left + 16, profileY, 0xFFBBA98B, false);
        int autoY = profileY + 15;
        drawChoice(g, left + 16, autoY, panelW - 32, 26,
                "auto".equals(selectedProfile) ? "✓  АВТОМАТИЧЕСКИ · по типу объекта" : "АВТОМАТИЧЕСКИ · по типу объекта",
                mouseX, mouseY);
        int listY = autoY + 34, listBottom = top + panelH - 87;
        int cols = panelW >= 450 ? 2 : 1, gap = 6, rowH = 44;
        int cardW = (panelW - 42 - (cols - 1) * gap) / cols;
        int rows = (choices.size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (listBottom - listY) / (rowH + gap));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visibleRows)));
        g.enableScissor(left + 10, listY, left + panelW - 10, listBottom);
        for (int i = 0; i < choices.size(); i++) {
            int row = i / cols, col = i % cols;
            if (row < scroll || row >= scroll + visibleRows) continue;
            WorldObjectVisualCatalog.Entry entry = choices.get(i);
            int x = left + 16 + col * (cardW + gap);
            int y = listY + (row - scroll) * (rowH + gap);
            boolean selected = entry.id().equals(selectedProfile);
            boolean hover = mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + rowH;
            g.fill(x, y, x + cardW, y + rowH, selected ? 0xFF66502F : hover ? 0xFF51402B : 0xFF30271F);
            g.renderOutline(x, y, cardW, rowH, selected ? 0xFFE5BD76 : 0xFF806342);
            ResourceLocation preview = ResourceLocation.tryParse(entry.previewTexture());
            if (preview != null) {
                g.blit(preview, x + 7, y + 9, 0, 0, 24, 24, 24, 24);
            } else {
                ResourceLocation itemId = ResourceLocation.tryParse(entry.profile().mapItem());
                if (itemId != null) {
                    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId);
                    if (item.isPresent() && !item.get().getDefaultInstance().isEmpty())
                        g.renderItem(item.get().getDefaultInstance(), x + 11, y + 12);
                }
            }
            g.drawString(font, entry.displayName(), x + 38, y + 7, 0xFFFFE0A0, false);
            g.drawString(font, entry.id(), x + 38, y + 22, 0xFFB6A58B, false);
        }
        g.disableScissor();

        int nameY = top + panelH - 72;
        g.drawString(font, "НАЗВАНИЕ", left + 16, nameY - 13, 0xFFBBA98B, false);
        g.drawString(font, "Создать", left + panelW - 76, top + panelH - 19, 0xFFFFD98B, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawChoice(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF59452D : 0xFF342A20);
        g.renderOutline(x, y, w, h, 0xFF927348);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, 0xFFFFE0A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        int panelW = Math.max(1, Math.min(620, width - 24)), panelH = Math.min(470, height - 24);
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        int typeY = top + 75;
        if (mouseY >= typeY && mouseY < typeY + 25) {
            if (mouseX >= left + 16 && mouseX < left + 46) {
                typeIndex = Math.floorMod(typeIndex - 1, types.length); refreshChoices(); return true;
            }
            if (mouseX >= left + 298 && mouseX < left + 328) {
                typeIndex = Math.floorMod(typeIndex + 1, types.length); refreshChoices(); return true;
            }
        }
        int profileY = top + 129, autoY = profileY + 15;
        if (mouseY >= autoY && mouseY < autoY + 26 && mouseX >= left + 16 && mouseX < left + panelW - 16) {
            selectedProfile = "auto"; return true;
        }
        int listY = autoY + 34, listBottom = top + panelH - 87;
        int cols = panelW >= 450 ? 2 : 1, gap = 6, rowH = 44;
        int cardW = (panelW - 42 - (cols - 1) * gap) / cols;
        if (mouseY >= listY && mouseY < listBottom) {
            int col = (int) ((mouseX - (left + 16)) / (cardW + gap));
            int row = (int) ((mouseY - listY) / (rowH + gap)) + scroll;
            int index = row * cols + col;
            if (col >= 0 && col < cols && row >= 0 && index < choices.size()) {
                int x = left + 16 + col * (cardW + gap);
                int y = listY + (row - scroll) * (rowH + gap);
                if (mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + rowH) {
                    selectedProfile = choices.get(index).id(); return true;
                }
            }
        }
        if (mouseY >= top + panelH - 30 && mouseX >= left + panelW - 105) {
            createMarker(); return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void createMarker() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.isBlank()) {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.displayClientMessage(Component.literal("Введите название объекта"), true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(
                "worldforge marker addat " + type() + " " + selectedProfile + " " + blockX + " " + blockZ + " " + name);
        mc.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelW = Math.max(1, Math.min(620, width - 24)), panelH = Math.min(470, height - 24);
        int left = (width - panelW) / 2, top = (height - panelH) / 2;
        int listY = top + 163, listBottom = top + panelH - 87;
        if (mouseX < left + 10 || mouseX >= left + panelW - 10
                || mouseY < listY || mouseY >= listBottom)
            return super.mouseScrolled(mouseX, mouseY, delta);
        int cols = panelW >= 450 ? 2 : 1, rowH = 44, gap = 6;
        int rows = (choices.size() + cols - 1) / cols;
        int visibleRows = Math.max(1, (listBottom - listY) / (rowH + gap));
        scroll = Math.max(0, Math.min(scroll + (delta < 0 ? 1 : -1), Math.max(0, rows - visibleRows)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) { Minecraft.getInstance().setScreen(parent); return true; }
        if (key == GLFW.GLFW_KEY_LEFT) { typeIndex = Math.floorMod(typeIndex - 1, types.length); refreshChoices(); return true; }
        if (key == GLFW.GLFW_KEY_RIGHT) { typeIndex = Math.floorMod(typeIndex + 1, types.length); refreshChoices(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER && nameBox != null && nameBox.isFocused()) { createMarker(); return true; }
        int panelW = Math.max(1, Math.min(620, width - 24));
        int panelH = Math.max(1, Math.min(470, height - 24));
        int cols = panelW >= 450 ? 2 : 1;
        int visibleRows = Math.max(1, (panelH - 250) / 50);
        int rows = (choices.size() + cols - 1) / cols;
        int maxScroll = Math.max(0, rows - visibleRows);
        if (key == GLFW.GLFW_KEY_UP) { scroll = Math.max(0, scroll - 1); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll = Math.min(maxScroll, scroll + 1); return true; }
        return super.keyPressed(key, scanCode, modifiers);
    }
}
