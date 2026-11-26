package dev.worldforge.dialogue.mc.client;

import dev.worldforge.dialogue.mc.DialogueNet;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Экран диалога: тёмная панель внизу, имя говорящего, текст с переносом, нумерованные ответы. Рисуется как MapScreen, без виджетов. Esc закрывает. */
public class DialogueScreen extends Screen {
    private static final int PAD = 10, LINE = 10, MARGIN = 20;
    private long sessionId;
    private int revision;
    private String speaker, text;
    private byte mode;
    private List<String> choices;
    private final List<int[]> hits = new ArrayList<>();      // x1, y1, x2, y2, индекс ответа

    public DialogueScreen(DialogueNet.OpenView v) {
        super(Component.literal("Диалог"));
        update(v);
    }

    public long sessionId() { return sessionId; }

    public void update(DialogueNet.OpenView v) {
        sessionId = v.sessionId; revision = v.revision; speaker = v.speaker; text = v.text; mode = v.mode; choices = v.choices;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (DialogueNet.CHANNEL != null) DialogueNet.CHANNEL.sendToServer(new DialogueNet.CloseReq(sessionId));
        super.onClose();
    }

    private List<String> labels() {
        if (mode == DialogueNet.MODE_CONTINUE) return List.of("Далее");
        if (mode == DialogueNet.MODE_END) return List.of("Закрыть");
        return choices;
    }

    private void pick(int i) {
        if (mode == DialogueNet.MODE_END) { onClose(); return; }
        if (DialogueNet.CHANNEL != null) DialogueNet.CHANNEL.sendToServer(new DialogueNet.Choose(sessionId, revision, i));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int w = width - MARGIN * 2, inner = w - PAD * 2;
        List<FormattedCharSequence> body = font.split(Component.literal(text), inner);
        List<String> labels = labels();
        List<List<FormattedCharSequence>> opts = new ArrayList<>();
        int optH = 0;
        for (int i = 0; i < labels.size(); i++) {
            List<FormattedCharSequence> l = font.split(Component.literal(labels.get(i)), inner - 28);
            opts.add(l);
            optH += l.size() * LINE + 4;
        }
        int keyW = 18;
        int h = PAD + LINE + 8 + body.size() * LINE + 8 + optH + PAD;
        int x = MARGIN, y = Math.max(4, height - h - MARGIN);
        WFUi.overlay(g, x, y, w, h);
        int cy = y + PAD;
        g.drawString(font, speaker, x + PAD, cy, WFUi.GOLD);
        g.fill(x + PAD, cy + LINE, x + w - PAD, cy + LINE + 1, WFUi.LO);
        g.fill(x + PAD, cy + LINE + 1, x + w - PAD, cy + LINE + 2, WFUi.HI);
        cy += LINE + 8;
        for (FormattedCharSequence l : body) {
            g.drawString(font, l, x + PAD, cy, WFUi.TX);
            cy += LINE;
        }
        cy += 8;
        for (int i = 0; i < opts.size(); i++) {
            int oh = opts.get(i).size() * LINE + 4;
            boolean hov = WFUi.over(mx, my, x + PAD, cy - 2, w - PAD * 2, oh);
            if (hov) g.fill(x + PAD, cy - 2, x + w - PAD, cy - 2 + oh, WFUi.HOVER);
            WFUi.key(g, font, x + PAD + 3, cy - 1, labels().size() == 1 ? "\u21b5" : String.valueOf(i + 1));
            int ty = cy;
            for (FormattedCharSequence l : opts.get(i)) {
                g.drawString(font, l, x + PAD + keyW + 4, ty, hov ? WFUi.TX : WFUi.MUTED);
                ty += LINE;
            }
            hits.add(new int[]{x + PAD, cy - 2, x + w - PAD, cy - 2 + oh, i});
            cy += oh;
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int[] h : hits) {
                if (mx >= h[0] && mx < h[2] && my >= h[1] && my < h[3]) { pick(h[4]); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= 49 && key <= 57) {                       // GLFW_KEY_1..9
            int i = key - 49;
            if (i < labels().size()) { pick(i); return true; }
        }
        if ((key == 257 || key == 335 || key == 32) && labels().size() == 1) { pick(0); return true; }   // Enter, KP Enter, Space
        return super.keyPressed(key, scan, mods);
    }
}
