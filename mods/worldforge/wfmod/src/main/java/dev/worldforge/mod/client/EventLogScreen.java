package dev.worldforge.mod.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.EventLog;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Окно журнала событий: чипы категорий, чип «Мои» (по государству зрителя), прокручиваемый список новые сверху. Клик по записи с государством: {@code focusrealm <id>}. */
public class EventLogScreen extends Screen {
    private static final int W = 560, H = 340, ROW = 14;
    private final EventLog log;
    private final TitleData data;
    private final String viewer;
    private final Consumer<String> cmd;
    private final Set<EventLog.Category> cats = EnumSet.noneOf(EventLog.Category.class);
    private boolean mine;
    private int scroll;
    private final List<int[]> hits = new ArrayList<>();   // x,y,w,h,тип(1 чип категории,2 мои,3 все,4 запись),индекс

    public EventLogScreen(EventLog log, TitleData data, String viewerRealm, Consumer<String> cmd) {
        super(Component.literal("Журнал"));
        this.log = log; this.data = data; this.viewer = viewerRealm; this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private String name(String id) { TitleData.Realm r = data == null ? null : data.realms.get(id); return r == null ? id : r.name; }

    private int rgb(String id) { TitleData.Realm r = data == null ? null : data.realms.get(id); return r == null ? 0x888888 : r.rgb; }

    private List<EventLog.Event> events() { return log.filter(cats, mine ? viewer : null, 0); }

    private int chip(GuiGraphics g, int x, int y, String label, int color, boolean on, boolean hover) {
        int w = font.width(label) + 14;
        WFUi.bevel(g, x, y, w, 15, on);
        if (hover && !on) g.fill(x + 2, y + 2, x + w - 2, y + 13, WFUi.HOVER);
        g.fill(x + 5, y + 5, x + 8, y + 10, color);
        g.drawString(font, label, x + 11, y + 4, on ? WFUi.GOLD : WFUi.TX);
        return w;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        List<EventLog.Event> evs = events();
        WFUi.panel(g, font, x, y, W, H, "Журнал событий  ·  " + evs.size() + " из " + log.size());
        int cx = x + 10, cy = y + 26;
        boolean all = cats.isEmpty();
        int w = chip(g, cx, cy, "Все", WFUi.TX, all, WFUi.over(mx, my, cx, cy, font.width("Все") + 14, 15));
        hits.add(new int[]{cx, cy, w, 15, 3, 0});
        cx += w + 3;
        for (EventLog.Category c : EventLog.Category.values()) {
            int cw = font.width(c.label) + 14;
            chip(g, cx, cy, c.label, EventFeed.color(c), cats.contains(c), WFUi.over(mx, my, cx, cy, cw, 15));
            hits.add(new int[]{cx, cy, cw, 15, 1, c.ordinal()});
            cx += cw + 3;
        }
        if (viewer != null) {
            String l = "Только " + name(viewer);
            int cw = font.width(l) + 14, rx = x + W - 10 - cw;
            chip(g, rx, cy, l, rgb(viewer), mine, WFUi.over(mx, my, rx, cy, cw, 15));
            hits.add(new int[]{rx, cy, cw, 15, 2, 0});
        }
        int top = y + 48, lh = H - 58, lx = x + 10, lw = W - 20;
        WFUi.bevel(g, lx, top, lw, lh, true);
        int content = evs.size() * ROW + 6, view = lh - 6;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - view)));
        if (evs.isEmpty()) g.drawString(font, "Событий нет.", lx + 8, top + 8, WFUi.MUTED);
        g.enableScissor(lx + 2, top + 2, lx + lw - 2, top + lh - 2);
        int ry = top + 3 - scroll;
        int timeW = 0;
        for (EventLog.Event e : evs) timeW = Math.max(timeW, font.width(EventFeed.time(e.time)));
        for (int i = 0; i < evs.size(); i++, ry += ROW) {
            if (ry + ROW < top || ry > top + lh) continue;
            EventLog.Event e = evs.get(i);
            boolean hov = WFUi.over(mx, my, lx + 2, ry, lw - 8, ROW) && my >= top && my < top + lh;
            WFUi.rowBg(g, lx + 2, ry, lw - 6, ROW, i, false, hov && !e.realms.isEmpty());
            int c = EventFeed.color(e.category);
            g.fill(lx + 3, ry + 1, lx + 5, ry + ROW - 1, c);
            g.drawString(font, EventFeed.time(e.time), lx + 10, ry + 3, WFUi.MUTED);
            int tx = lx + 10 + timeW + 8;
            int bw = WFUi.badge(g, font, tx, ry + 1, e.category.label, c);
            tx += bw + 6;
            // справа: цвета государств (до 3), остальное под текст
            int rr = lx + lw - 12;
            for (int k = Math.min(3, e.realms.size()) - 1; k >= 0; k--) { rr -= 11; WFUi.swatch(g, rr, ry + 2, rgb(e.realms.get(k))); }
            g.drawString(font, font.plainSubstrByWidth(e.text, rr - 6 - tx), tx, ry + 3, WFUi.TX);
            if (!e.realms.isEmpty()) hits.add(new int[]{lx + 2, Math.max(ry, top), lw - 8, Math.min(ROW, top + lh - Math.max(ry, top)), 4, i});
        }
        g.disableScissor();
        WFUi.scrollbar(g, lx + lw - 4, top + 2, lh - 4, content, scroll);
        for (int[] h : hits) {
            if (h[4] == 4 && WFUi.over(mx, my, h[0], h[1], h[2], h[3])) {
                EventLog.Event e = evs.get(h[5]);
                List<String> tip = new ArrayList<>();
                tip.add(e.category.label + "  ·  " + EventFeed.time(e.time));
                for (String r : e.realms) tip.add(name(r));
                tip.add("Клик: показать на карте");
                WFUi.tooltip(g, font, width, height, mx, my, tip);
                break;
            }
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        for (int[] h : hits) {
            if (!WFUi.over((int) mx, (int) my, h[0], h[1], h[2], h[3])) continue;
            if (h[4] == 1) { EventLog.Category c = EventLog.Category.values()[h[5]]; if (!cats.remove(c)) cats.add(c); scroll = 0; }
            else if (h[4] == 2) { mine = !mine; scroll = 0; }
            else if (h[4] == 3) { cats.clear(); scroll = 0; }
            else {
                List<EventLog.Event> evs = events();
                if (h[5] < evs.size() && !evs.get(h[5]).realms.isEmpty()) cmd.accept("focusrealm " + evs.get(h[5]).realms.get(0));
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta) * ROW * 3);
        return true;
    }
}
