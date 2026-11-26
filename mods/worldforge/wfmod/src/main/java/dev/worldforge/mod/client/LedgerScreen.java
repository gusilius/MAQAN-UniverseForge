package dev.worldforge.mod.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.Ledger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Реестр государств: таблица по данным {@link Ledger}. Клик по заголовку - сортировка (стрелка), вкладки наборов колонок,
 * поиск по имени (печатайте), строка зрителя золотая, клик по строке шлёт {@code focusrealm <id>}, прокрутка колесом, итоги внизу.
 */
public class LedgerScreen extends Screen {
    private static final int ROW = 12, HEAD = 14, PAD = 6;
    private final Ledger ledger;
    private final Consumer<String> cmd;
    private int tab, scroll;
    private String sortKey = "area";
    private boolean asc;
    private String query = "";
    private final List<int[]> hits = new ArrayList<>();   // x,y,w,h,тип(1 заголовок,2 строка,3 вкладка,4 поиск),индекс
    private final Map<Integer, int[]> widths = new java.util.HashMap<>();

    public LedgerScreen(TitleData data, String viewerRealm, Consumer<String> cmd) {
        super(Component.literal("Реестр"));
        this.ledger = Ledger.build(data, viewerRealm);
        this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int[] widths(List<Ledger.Col> cols, int avail) {
        int[] w = widths.get(tab);
        if (w != null) return w;
        w = new int[cols.size()];
        List<Ledger.Row> all = ledger.rows;
        int sum = 0;
        for (int i = 0; i < w.length; i++) {
            Ledger.Col c = cols.get(i);
            int m = font.width(c.label) + 14;                       // место под стрелку
            for (Ledger.Row r : all) m = Math.max(m, font.width(ledger.cell(c, r)) + PAD * 2);
            Map<String, Double> t = ledger.totals(all, cols);
            if (t.containsKey(c.key)) m = Math.max(m, font.width(Ledger.fmtNum(c.kind, t.get(c.key))) + PAD * 2);
            w[i] = c.numeric() ? m : Math.min(m, 150);
            sum += w[i];
        }
        // не влезает: ужимаем текстовые колонки до 56 px
        for (int guard = 0; sum > avail && guard < 200; guard++) {
            int widest = -1;
            for (int i = 0; i < w.length; i++) if (!cols.get(i).numeric() && w[i] > 56 && (widest < 0 || w[i] > w[widest])) widest = i;
            if (widest < 0) break;
            w[widest]--; sum--;
        }
        // осталось место: раздаём первой текстовой колонке (имя)
        if (sum < avail && w.length > 0) w[0] += avail - sum;
        widths.put(tab, w);
        return w;
    }

    private String effectiveSort(List<Ledger.Col> cols) {
        for (Ledger.Col c : cols) if (c.key.equals(sortKey)) return sortKey;
        return "name";
    }

    private void arrow(GuiGraphics g, int x, int y, boolean up, int c) {
        for (int i = 0; i < 4; i++) {
            int half = up ? i : 3 - i;
            g.fill(x + 3 - half, y + i, x + 4 + half, y + i + 1, c);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int W = Math.min(width - 16, 900), H = Math.min(height - 16, 440);
        int x = (width - W) / 2, y = (height - H) / 2;
        List<Ledger.Col> cols = ledger.columns(tab);
        String sk = effectiveSort(cols);
        List<Ledger.Row> rows = ledger.view(sk, asc, query);
        WFUi.panel(g, font, x, y, W, H, "Реестр государств  ·  " + rows.size() + " из " + ledger.rows.size());

        // вкладки и поиск
        int ty = y + 25;
        WFUi.tabs(g, font, x + 10, ty, Ledger.TABS, tab, mx, my);
        int cx = x + 10;
        for (int i = 0; i < Ledger.TABS.length; i++) { int tw = font.width(Ledger.TABS[i]) + 16; hits.add(new int[]{cx, ty, tw, 15, 3, i}); cx += tw + 2; }
        int sw = Math.min(190, W / 3), sx = x + W - 10 - sw;
        WFUi.bevel(g, sx, ty, sw, 15, true);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (query.isEmpty()) g.drawString(font, "Поиск: начните печатать", sx + 6, ty + 4, WFUi.OFF);
        else g.drawString(font, font.plainSubstrByWidth(query, sw - 24) + (blink ? "_" : ""), sx + 6, ty + 4, WFUi.TX);
        if (!query.isEmpty()) { g.drawString(font, "x", sx + sw - 10, ty + 4, WFUi.MUTED); hits.add(new int[]{sx + sw - 14, ty, 14, 15, 4, 0}); }

        int tx = x + 10, tw = W - 20, hy = ty + 20;
        int bodyTop = hy + HEAD, totalH = ROW + 4, bodyH = y + H - 10 - totalH - bodyTop;
        int[] cw = widths(cols, tw - 8);
        WFUi.bevel(g, tx, hy, tw, y + H - 10 - hy, true);

        // заголовок
        g.fill(tx + 2, hy + 2, tx + tw - 2, hy + HEAD, 0xFF26262C);
        int colX = tx + 2;
        for (int i = 0; i < cols.size(); i++) {
            Ledger.Col c = cols.get(i);
            boolean on = c.key.equals(sk), hov = WFUi.over(mx, my, colX, hy + 2, cw[i], HEAD - 2);
            if (hov) g.fill(colX, hy + 2, colX + cw[i], hy + HEAD, WFUi.HOVER);
            String lab = font.plainSubstrByWidth(c.label, cw[i] - 14);
            int lx = c.numeric() ? colX + cw[i] - PAD - font.width(lab) - (on ? 8 : 0) : colX + PAD;
            g.drawString(font, lab, lx, hy + 4, on ? WFUi.GOLD : WFUi.GOLD_DIM);
            if (on) arrow(g, c.numeric() ? colX + cw[i] - PAD - 6 : lx + font.width(lab) + 3, hy + 5, asc, WFUi.GOLD);
            if (i > 0) g.fill(colX, hy + 3, colX + 1, hy + HEAD - 1, WFUi.LO);
            hits.add(new int[]{colX, hy + 2, cw[i], HEAD - 2, 1, i});
            colX += cw[i];
        }
        g.fill(tx + 2, hy + HEAD, tx + tw - 2, hy + HEAD + 1, WFUi.HI);

        // тело
        int content = rows.size() * ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - bodyH)));
        g.enableScissor(tx + 2, bodyTop + 1, tx + tw - 2, bodyTop + bodyH);
        int ry = bodyTop + 1 - scroll;
        for (int i = 0; i < rows.size(); i++, ry += ROW) {
            if (ry + ROW < bodyTop || ry > bodyTop + bodyH) continue;
            Ledger.Row r = rows.get(i);
            boolean hov = WFUi.over(mx, my, tx + 2, ry, tw - 8, ROW) && my >= bodyTop && my < bodyTop + bodyH;
            WFUi.rowBg(g, tx + 2, ry, tw - 4, ROW, i, r.viewer, hov);
            if (r.viewer) { g.fill(tx + 2, ry, tx + 4, ry + ROW, WFUi.GOLD); g.fill(tx + 2, ry, tx + tw - 2, ry + 1, 0x60F0C85A); g.fill(tx + 2, ry + ROW - 1, tx + tw - 2, ry + ROW, 0x60F0C85A); }
            int cxx = tx + 2;
            for (int k = 0; k < cols.size(); k++) {
                Ledger.Col c = cols.get(k);
                String s = ledger.cell(c, r);
                int color = r.viewer ? WFUi.GOLD : WFUi.TX;
                int sx2 = cxx + PAD;
                if (k == 0) { WFUi.swatch(g, cxx + 7, ry + 1, r.rgb); sx2 = cxx + 20; }
                if (c.numeric()) {
                    Double n = r.num(c.key);
                    if (n == null || n == 0) color = r.viewer ? WFUi.GOLD_DIM : WFUi.OFF;
                    if (c.key.equals("enemies") && n != null && n > 0) color = WFUi.BAD;
                    if (c.key.equals("allies") && n != null && n > 0) color = WFUi.GOOD;
                    String t = font.plainSubstrByWidth(s, cw[k] - PAD * 2);
                    g.drawString(font, t, cxx + cw[k] - PAD - font.width(t), ry + 2, color);
                } else {
                    if (c.key.equals("rel") && r.relRgb >= 0 && !r.viewer) color = 0xFF000000 | r.relRgb;
                    else if (s.equals("-")) color = WFUi.OFF;
                    else if (!r.viewer && !c.key.equals("name")) color = WFUi.MUTED;
                    g.drawString(font, font.plainSubstrByWidth(s, cw[k] - (sx2 - cxx) - 4), sx2, ry + 2, color);
                }
                cxx += cw[k];
            }
            hits.add(new int[]{tx + 2, Math.max(ry, bodyTop), tw - 8, Math.min(ry + ROW, bodyTop + bodyH) - Math.max(ry, bodyTop), 2, i});
        }
        g.disableScissor();
        if (rows.isEmpty()) g.drawString(font, "Ничего не найдено.", tx + 10, bodyTop + 8, WFUi.MUTED);
        WFUi.scrollbar(g, tx + tw - 5, bodyTop + 1, bodyH - 1, content, scroll);

        // итоги
        int fy = bodyTop + bodyH;
        g.fill(tx + 2, fy, tx + tw - 2, fy + 1, WFUi.HI);
        g.fill(tx + 2, fy + 1, tx + tw - 2, fy + totalH, 0xFF1A1A1F);
        Map<String, Double> tot = ledger.totals(rows, cols);
        int cxx = tx + 2;
        for (int k = 0; k < cols.size(); k++) {
            Ledger.Col c = cols.get(k);
            if (k == 0) g.drawString(font, "ИТОГО  " + rows.size(), cxx + 7, fy + 4, WFUi.GOLD);
            else if (tot.containsKey(c.key)) {
                String t = font.plainSubstrByWidth(Ledger.fmtNum(c.kind, tot.get(c.key)), cw[k] - PAD * 2);
                g.drawString(font, t, cxx + cw[k] - PAD - font.width(t), fy + 4, WFUi.GOLD);
            }
            cxx += cw[k];
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        List<Ledger.Col> cols = ledger.columns(tab);
        List<Ledger.Row> rows = ledger.view(effectiveSort(cols), asc, query);
        for (int[] h : hits) {
            if (!WFUi.over((int) mx, (int) my, h[0], h[1], h[2], h[3])) continue;
            switch (h[4]) {
                case 1: {
                    Ledger.Col c = cols.get(h[5]);
                    if (c.key.equals(effectiveSort(cols))) asc = !asc;
                    else { sortKey = c.key; asc = !c.numeric(); }
                    scroll = 0;
                    break;
                }
                case 2: if (h[5] < rows.size()) cmd.accept("focusrealm " + rows.get(h[5]).id); break;
                case 3: tab = h[5]; scroll = 0; break;
                case 4: query = ""; scroll = 0; break;
                default: break;
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

    /** Ввод символов в поиск (без @Override: работает и с заглушкой, и с настоящим Screen). */
    public boolean charTyped(char c, int mods) {
        if (c < 32 || c == 127 || query.length() >= 32) return false;
        query += c;
        scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 259 && !query.isEmpty()) { query = query.substring(0, query.length() - 1); scroll = 0; return true; }   // Backspace
        if (key == 256 && !query.isEmpty()) { query = ""; scroll = 0; return true; }                                       // Esc очищает поиск, второй закрывает
        return super.keyPressed(key, scan, mods);
    }
}
