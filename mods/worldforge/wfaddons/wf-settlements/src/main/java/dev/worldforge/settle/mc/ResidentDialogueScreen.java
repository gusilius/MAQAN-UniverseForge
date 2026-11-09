package dev.worldforge.settle.mc;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Dedicated, keyboard/mouse accessible resident conversation workspace. */
public final class ResidentDialogueScreen extends AbstractContainerScreen<ResidentDialogueMenu> {
    private static final int PANEL_W = 430, PANEL_H = 380;

    public ResidentDialogueScreen(ResidentDialogueMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = PANEL_W;
        imageHeight = PANEL_H;
        inventoryLabelY = 10000;
        titleLabelY = 10000;
    }

    @Override protected void init() {
        super.init();
        int left = leftPos + 18;
        int top = topPos + 248;
        int w = 94, h = 22, gap = 8;
        addRenderableWidget(action("Поговорить", "talk", left, top, w, h));
        addRenderableWidget(action("Следовать", "follow", left + w + gap, top, w, h));
        addRenderableWidget(action("Остановиться", "stop", left + (w + gap) * 2, top, w, h));
        addRenderableWidget(action("На работу", "work", left + (w + gap) * 3, top, w, h));
        addRenderableWidget(action("Подарок", "gift", left, top + 30, w, h));
        addRenderableWidget(action("Поручение", "quest", left + w + gap, top + 30, w, h));
        String questAction = menu.quest.startsWith("ACCEPTED") ? "deliver"
                : menu.quest.startsWith("OFFERED") ? "accept" : "status";
        String questLabel = menu.quest.startsWith("ACCEPTED") ? "Передать" : menu.quest.startsWith("OFFERED") ? "Принять" : "Состояние";
        addRenderableWidget(action(questLabel, questAction, left + (w + gap) * 2, top + 30, w, h));
        addRenderableWidget(action("Профиль", "status", left + (w + gap) * 3, top + 30, w, h));
        addRenderableWidget(action("О семье", "talk_family", left, top + 60, w, h));
        addRenderableWidget(action("О работе", "talk_work", left + w + gap, top + 60, w, h));
        addRenderableWidget(action("О мире", "talk_world", left + (w + gap) * 2, top + 60, w, h));
        addRenderableWidget(action("Ваше мнение", "talk_opinion", left + (w + gap) * 3, top + 60, w, h));
        addRenderableWidget(action("Строить", "work_builder", left, top + 90, w, h));
        addRenderableWidget(action("Лесоруб", "work_lumberjack", left + w + gap, top + 90, w, h));
        addRenderableWidget(action("Добыча", "work_miner", left + (w + gap) * 2, top + 90, w, h));
        addRenderableWidget(action("Перевозка", "work_hauler", left + (w + gap) * 3, top + 90, w, h));
    }

    private Button action(String label, String command, int x, int y, int width, int height) {
        return Button.builder(Component.literal(label), button -> {
            if (minecraft != null && minecraft.player != null)
                minecraft.player.connection.sendCommand("wfresident " + command + " " + menu.residentId);
        }).bounds(x, y, width, height).build();
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xF00B1019);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + 3, 0xFF69C7D5);
        graphics.fill(x + 12, y + 12, x + 146, y + 150, 0xFF151F2B);
        graphics.fill(x + 156, y + 12, x + imageWidth - 12, y + 150, 0xFF111A25);
        graphics.fill(x + 12, y + 158, x + imageWidth - 12, y + 368, 0xFF101925);
        graphics.fill(x + 12, y + 240, x + imageWidth - 12, y + imageHeight - 12, 0xFF172431);
        graphics.fill(x + 12, y + 12, x + 146, y + 13, 0xFF355568);
        graphics.fill(x + 156, y + 12, x + imageWidth - 12, y + 13, 0xFF355568);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.literal(menu.residentName), 160, 22, 0xFFE8F3F6, false);
        graphics.drawString(font, Component.literal(menu.profession + " · " + menu.age + " лет"), 160, 42, 0xFF91AAB7, false);
        graphics.drawString(font, Component.literal("СОСТОЯНИЕ"), 160, 68, 0xFF69C7D5, false);
        graphics.drawString(font, Component.literal(menu.status), 160, 84, 0xFFD6E2E8, false);
        graphics.drawString(font, Component.literal("ОТНОШЕНИЕ К ВАМ"), 160, 108, 0xFF69C7D5, false);
        graphics.drawString(font, Component.literal(menu.relationship), 160, 124, 0xFFD6E2E8, false);
        graphics.drawString(font, Component.literal("СЕМЬЯ · ХАРАКТЕР · НАВЫКИ"), 24, 166, 0xFF69C7D5, false);
        int familyY = 182;
        for (var line : font.split(Component.literal(menu.family), 380).stream().limit(2).toList()) {
            graphics.drawString(font, line, 24, familyY, 0xFFD6E2E8, false);
            familyY += 10;
        }
        graphics.drawString(font, Component.literal("ПОРУЧЕНИЕ"), 24, 206, 0xFF69C7D5, false);
        int questY = 222;
        for (var line : font.split(Component.literal(menu.quest), 380).stream().limit(2).toList()) {
            graphics.drawString(font, line, 24, questY, 0xFFD6E2E8, false);
            questY += 10;
        }
        graphics.drawString(font, Component.literal("МАQAN · ЖИВОЙ МИР"), 24, 24, 0xFF8DA7B6, false);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override public boolean isPauseScreen() { return false; }
}
