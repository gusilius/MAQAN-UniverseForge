package dev.worldforge.trade.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно лавки: слева товары с ценой покупки и продажи, справа выбранный товар и кнопки купить/продать на 1, 10, 50. */
public class ShopScreen extends Screen {
    private static final int W = 640, H = 400;
    private Map<String, Object> m = new LinkedHashMap<>();
    private List<Map<String, Object>> goods = new ArrayList<>();
    private int sel, scroll, refresh;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public ShopScreen(String payload, Consumer<String> cmd) { super(Component.literal("Лавка")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        m = (Map<String, Object>) Json.parse(payload);
        goods = new ArrayList<>();
        if (m.get("goods") instanceof List<?> l) for (Object o : l) goods.add((Map<String, Object>) o);
        if (sel >= goods.size()) sel = 0;
    }

    private static String str(Map<String, Object> x, String k) { Object o = x.get(k); return o == null ? "" : String.valueOf(o); }
    private static double dbl(Map<String, Object> x, String k) { return x.get(k) instanceof Number n ? n.doubleValue() : 0; }
    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0 && m.get("id") instanceof Number n) cmd.accept("wftrade shop open " + n.intValue()); }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        int id = (int) dbl(m, "id");
        WFUi.panel(g, font, x, y, W, H, str(m, "name") + "  ·  " + str(m, "realm") + "  ·  наценка " + (int) dbl(m, "margin") + "%");
        int top = y + 24, bodyH = H - 34, lw = 330;
        WFUi.bevel(g, x + 10, top, lw, bodyH, true);
        g.drawString(font, "Товар", x + 18, top + 5, WFUi.GOLD_DIM); g.drawString(font, "Купить", x + 170, top + 5, WFUi.GOLD_DIM); g.drawString(font, "Продать", x + 230, top + 5, WFUi.GOLD_DIM); g.drawString(font, "Запас", x + 290, top + 5, WFUi.GOLD_DIM);
        int rows = (bodyH - 22) / 16;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, goods.size() - rows)));
        for (int i = 0; i < rows && scroll + i < goods.size(); i++) {
            Map<String, Object> gd = goods.get(scroll + i);
            int ry = top + 18 + i * 16;
            WFUi.rowBg(g, x + 12, ry, lw - 6, 15, i, scroll + i == sel, WFUi.over(mx, my, x + 12, ry, lw - 6, 15));
            g.drawString(font, font.plainSubstrByWidth(str(gd, "name"), 150), x + 18, ry + 4, WFUi.TX);
            g.drawString(font, fmt(dbl(gd, "buy")), x + 170, ry + 4, WFUi.GOLD);
            g.drawString(font, fmt(dbl(gd, "sell")), x + 230, ry + 4, WFUi.GOOD);
            g.drawString(font, String.valueOf((long) dbl(gd, "stock")), x + 290, ry + 4, WFUi.MUTED);
            final int k = scroll + i;
            hits.add(new Hit(x + 12, ry, lw - 6, 15, () -> sel = k, null));
        }
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH, true);
        g.drawString(font, "В кассе торговца: " + (long) dbl(m, "cash") + " зол.", rx + 10, top + 8, WFUi.TX);
        if (goods.isEmpty()) { super.render(g, mx, my, pt); return; }
        Map<String, Object> gd = goods.get(Math.min(sel, goods.size() - 1));
        String gid = str(gd, "id");
        g.drawString(font, str(gd, "name"), rx + 10, top + 28, WFUi.GOLD);
        g.drawString(font, "Купить 1: " + fmt(dbl(gd, "buy")) + " зол.", rx + 10, top + 42, WFUi.TX);
        g.drawString(font, "Продать 1: " + fmt(dbl(gd, "sell")) + " зол.", rx + 10, top + 54, WFUi.TX);
        g.drawString(font, "Предмет: " + str(gd, "item"), rx + 10, top + 68, WFUi.MUTED);
        int by = top + 92;
        g.drawString(font, "КУПИТЬ", rx + 10, by - 11, WFUi.GOLD_DIM);
        for (int i = 0, bx = rx + 10; i < 3; i++) { final int q = new int[]{1, 10, 50}[i]; button(g, bx, by, 50, "x" + q, true, mx, my, () -> cmd.accept("wftrade shop buy " + id + " " + gid + " " + q), "Купить " + q + " у лавки"); bx += 54; }
        g.drawString(font, "ПРОДАТЬ", rx + 10, by + 24, WFUi.GOLD_DIM);
        for (int i = 0, bx = rx + 10; i < 3; i++) { final int q = new int[]{1, 10, 50}[i]; button(g, bx, by + 35, 50, "x" + q, true, mx, my, () -> cmd.accept("wftrade shop sell " + id + " " + gid + " " + q), "Продать " + q + " лавке из сумки"); bx += 54; }
        g.drawString(font, "Золото - золотые самородки в сумке.", rx + 10, top + bodyH - 14, WFUi.MUTED);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; }
        }
        super.render(g, mx, my, pt);
    }

    @Override public boolean mouseScrolled(double mx, double my, double d) { scroll -= (int) Math.signum(d) * 2; return true; }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (h.run != null && WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h)) { h.run.run(); return true; } }
        return super.mouseClicked(mx, my, b);
    }
}
