package dev.worldforge.settle.mc.client;

import dev.worldforge.mod.client.WFArt;
import dev.worldforge.mod.client.WFUi;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.SettlementMap;
import dev.worldforge.settle.Worker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Окно поселения (WFUi), вкладки: «Обзор» (настроение с разбором, тревоги, склад с приростом), «Здания» (таблица: штат, КПД, узкое
 * место, приоритет, пауза, снос), «Люди» (профессии и назначение) и «Строить» (карточки с ценой и причиной отказа).
 * Все приказы уходят командами /wfsettle через {@code cmd}.
 */
public class SettleScreen extends Screen {
    private static final int W = 640, H = 400;
    private static final String[] TABS = {"Обзор", "Здания", "Люди", "Строить", "Торговля", "Бюджет", "Города", "Журнал", "Карта"};
    private Settlement s;
    private SettlementMap map = new SettlementMap();
    private final Consumer<String> cmd;
    private int tab, selWorker, selBuilding, scroll, cat;
    private final List<Object[]> hits = new ArrayList<>();   // {x,y,w,h,Runnable,title,tipLines}

    public SettleScreen(Settlement s, Consumer<String> cmd) {
        super(Component.literal(s.name));
        this.s = s;
        this.cmd = cmd;
    }

    public void setModel(Settlement n) { this.s = n; mapZoom = 1.0; mapPanX = 0; mapPanY = 0; }
    public void setMap(SettlementMap map) { this.map = map == null ? new SettlementMap() : map; }

    private java.util.List<dev.worldforge.settle.CityRow> cities = new ArrayList<>();
    private String citySort = "pop";
    private dev.worldforge.settle.TradeNetwork net = new dev.worldforge.settle.TradeNetwork();
    public void setCities(java.util.List<dev.worldforge.settle.CityRow> c, dev.worldforge.settle.TradeNetwork n) { this.cities = c; this.net = n; }
    public void setCities(java.util.List<dev.worldforge.settle.CityRow> c) { this.cities = c; }

    private int refresh;
    private double mapZoom = 1.0, mapPanX, mapPanY;
    private boolean mapDragging;
    private int mapLastX, mapLastY;

    @Override
    public void tick() {
        if (++refresh % 20 == 0) cmd.accept("wfsettle view panel");
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void hit(int x, int y, int w, int h, Runnable r, String title, List<String> tip) { hits.add(new Object[]{x, y, w, h, r, title, tip}); }

    private static int sev(int v) { return v >= 2 ? WFUi.BAD : v == 1 ? WFUi.GOLD : WFUi.MUTED; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        int ap = s.approval();
        WFUi.panel(g, font, x, y, W, H, s.name + "  ·  жителей " + s.workers.size() + "/" + s.housing() + "  ·  настроение " + ap);
        int tw = WFUi.tabs(g, font, x + 10, y + 24, 480, TABS, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hit(x + 10 + i * (tw + 2), y + 24, tw, 15, () -> { tab = t; scroll = 0; }, null, null); }
        String au = s.auto ? "Автоназначение: вкл" : "Автоназначение: выкл";
        int aw = font.width(au) + 16, ax = x + W - 10 - aw;
        WFUi.button(g, font, ax, y + 24, aw, 15, au, true, s.auto, WFUi.over(mx, my, ax, y + 24, aw, 15));
        hit(ax, y + 24, aw, 15, () -> cmd.accept("wfsettle auto " + (s.auto ? "off" : "on")), "Автоназначение", List.of("Свободные рабочие сами идут в здания,", "а стройкам выделяются строители."));
        int top = y + 44, h = H - 54, cx = x + 10, cw = W - 20;
        switch (tab) {
            case 0 -> overview(g, cx, top, cw, h, mx, my);
            case 1 -> buildings(g, cx, top, cw, h, mx, my);
            case 2 -> people(g, cx, top, cw, h, mx, my);
            case 3 -> build(g, cx, top, cw, h, mx, my);
            case 4 -> trade(g, cx, top, cw, h, mx, my);
            case 5 -> budget(g, cx, top, cw, h, mx, my);
            case 6 -> cities(g, cx, top, cw, h, mx, my);
            case 7 -> journal(g, cx, top, cw, h);
            case 8 -> settlementMap(g, cx, top, cw, h, mx, my);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] hh = hits.get(i);
            @SuppressWarnings("unchecked") List<String> tip = (List<String>) hh[6];
            if (tip != null && WFUi.over(mx, my, (int) hh[0], (int) hh[1], (int) hh[2], (int) hh[3])) { WFUi.tooltip(g, font, mx, my, width, height, (String) hh[5], tip); break; }
        }
        super.render(g, mx, my, pt);
    }

    // ------------------------------------------------------------------ Обзор

    private void overview(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int apv = s.approval(), tw = (cw - 24) / 4;
        int apc = apv < Settlement.APPROVAL_LEAVE_BELOW ? WFUi.BAD : apv < Settlement.APPROVAL_GROWTH_MIN ? WFUi.GOLD : WFUi.GOOD;
        double food = s.amount("food"), fnet = s.producedPerMin("food") - s.consumedPerMin("food");
        String fsub = fnet >= 0 ? "прирост +" + String.format("%.1f", fnet) + "/мин" : "хватит на " + (food <= 0 ? 0 : Math.round(food / -fnet)) + " мин";
        WFArt.tile(g, font, x, top, tw, 46, "ЖИТЕЛИ", s.workers.size() + "/" + s.housing(), s.workers.size() < s.housing() ? "есть место" : "дома заполнены", WFUi.INFO);
        WFArt.tile(g, font, x + tw + 8, top, tw, 46, "НАСТРОЕНИЕ", String.valueOf(apv), apv >= Settlement.APPROVAL_GROWTH_MIN ? "рост идёт" : apv >= Settlement.APPROVAL_LEAVE_BELOW ? "рост стоит" : "уходят", apc);
        WFArt.tile(g, font, x + (tw + 8) * 2, top, tw, 46, "СЕРЕБРО", String.valueOf((int) s.amount("silver")), (s.silverPerMin() >= 0 ? "+" : "") + String.format("%.1f", s.silverPerMin()) + "/мин", WFUi.GOLD);
        WFArt.tile(g, font, x + (tw + 8) * 3, top, tw, 46, "ЕДА", String.valueOf((int) food), fsub, fnet >= 0 ? WFUi.GOOD : WFUi.BAD);
        top += 52; h -= 52;
        int lw = 250, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        int cy = top + 6;
        g.drawString(font, "НАСТРОЕНИЕ", x + 7, cy, WFUi.GOLD_DIM);
        int ap = s.approval();
        String as = String.valueOf(ap);
        g.drawString(font, as, x + lw - 7 - font.width(as), cy, ap < Settlement.APPROVAL_LEAVE_BELOW ? WFUi.BAD : ap < Settlement.APPROVAL_GROWTH_MIN ? WFUi.GOLD : WFUi.GOOD);
        cy += 12;
        WFUi.bar(g, x + 7, cy, lw - 14, 6, ap / 100.0, ap < Settlement.APPROVAL_LEAVE_BELOW ? WFUi.BAD : ap < Settlement.APPROVAL_GROWTH_MIN ? WFUi.GOLD : WFUi.GOOD);
        cy += 12;
        for (var f : s.approvalBreakdown()) {
            g.drawString(font, font.plainSubstrByWidth(f.label, lw - 50), x + 7, cy, WFUi.MUTED);
            String v = (f.value > 0 ? "+" : "") + f.value;
            g.drawString(font, v, x + lw - 7 - font.width(v), cy, f.value > 0 ? WFUi.GOOD : f.value < 0 ? WFUi.BAD : WFUi.MUTED);
            cy += 11;
        }
        cy += 6;
        g.drawString(font, "РОСТ", x + 7, cy, WFUi.GOLD_DIM);
        cy += 12;
        String gr = ap >= Settlement.APPROVAL_GROWTH_MIN ? (s.workers.size() < s.housing() ? "новые жители приходят" : "нет места в домах") : ap >= Settlement.APPROVAL_LEAVE_BELOW ? "рост остановлен" : "жители уходят";
        g.drawString(font, gr, x + 7, cy, ap >= Settlement.APPROVAL_GROWTH_MIN && s.workers.size() < s.housing() ? WFUi.GOOD : WFUi.BAD);
        cy += 16;
        g.drawString(font, "ВРЕМЯ ГОДА", x + 7, cy, WFUi.GOLD_DIM);
        String sn = s.seasonName() + ", год " + s.year();
        g.drawString(font, sn, x + lw - 7 - font.width(sn), cy, s.seasonIndex() == 3 ? WFUi.INFO : s.seasonIndex() == 2 ? WFUi.GOLD : WFUi.GOOD);
        cy += 12;
        WFUi.bar(g, x + 7, cy, lw - 14, 4, s.seasonProgress(), s.seasonIndex() == 3 ? WFUi.INFO : WFUi.GOLD);
        cy += 9;
        WFUi.kv(g, font, x + 7, cy, lw - 14, 90, "Фермы", "x" + Settlement.FARM_MULT[s.seasonIndex()], Settlement.FARM_MULT[s.seasonIndex()] < 1 ? WFUi.BAD : WFUi.GOOD);
        cy += 11;
        WFUi.kv(g, font, x + 7, cy, lw - 14, 90, "Оборона", Math.round(s.defense()) + (s.raidIncoming ? " против " + Math.round(s.raidStrength) : ""), s.raidIncoming && s.defense() < s.raidStrength ? WFUi.BAD : WFUi.TX);
        cy += 11;
        WFUi.kv(g, font, x + 7, cy, lw - 14, 90, "Набегов", s.raidsRepelled + " отбито, " + s.raidsLost + " потеряно", WFUi.MUTED);
        cy += 16;
        g.drawString(font, "ТРЕВОГИ", x + 7, cy, WFUi.GOLD_DIM);
        cy += 12;
        var al = s.alerts();
        if (al.isEmpty()) g.drawString(font, "Всё спокойно.", x + 7, cy, WFUi.GOOD);
        for (var a : al) {
            if (cy > top + h - 12) break;
            boolean hov = WFUi.over(mx, my, x + 4, cy - 1, lw - 8, 11) && a.building != 0;
            WFUi.dot(g, x + 6, cy + 2, 5, sev(a.severity));
            g.drawString(font, font.plainSubstrByWidth(a.text, lw - 26), x + 16, cy, hov ? WFUi.GOLD : WFUi.TX);
            if (a.building != 0) { final int b = a.building; hit(x + 4, cy - 1, lw - 8, 11, () -> { selBuilding = b; tab = 1; }, null, null); }
            cy += 11;
        }
        WFUi.bevel(g, rx, top, rw, h, true);
        String[] heads = {"РЕСУРС", "ЗАПАС", "ДОБЫЧА", "РАСХОД", "ИТОГО/МИН"};
        int[] col = {rx + 8, rx + 92, rx + 196, rx + 250, rx + 296};
        for (int i = 0; i < heads.length; i++) g.drawString(font, heads[i], col[i], top + 6, WFUi.GOLD_DIM);
        int ry = top + 20, idx = 0;
        for (var e : s.defs.resourceNames.entrySet()) {
            if (ry > top + h - 14) break;
            String r = e.getKey();
            double a = s.amount(r), cap = s.capacity(r), p = s.producedPerMin(r), c = s.consumedPerMin(r), net = p - c;
            WFUi.rowBg(g, rx + 3, ry - 1, rw - 6, 13, idx++, false, WFUi.over(mx, my, rx + 3, ry - 1, rw - 6, 13));
            g.drawString(font, font.plainSubstrByWidth(e.getValue(), 106), col[0], ry + 2, WFUi.TX);
            WFUi.bar(g, col[1], ry + 4, 30, 4, cap <= 0 || cap > 1e6 ? 1 : a / cap, a < cap * 0.15 ? WFUi.BAD : a > cap * 0.9 ? WFUi.GOLD : WFUi.GOOD);
            g.drawString(font, cap > 1e6 ? String.valueOf((int) a) : (int) a + "/" + (int) cap, col[1] + 36, ry + 2, a < 1 ? WFUi.BAD : WFUi.TX);
            g.drawString(font, p > 0 ? String.format("+%.1f", p) : "-", col[2], ry + 2, p > 0 ? WFUi.GOOD : WFUi.OFF);
            g.drawString(font, c > 0 ? String.format("-%.1f", c) : "-", col[3], ry + 2, c > 0 ? WFUi.BAD : WFUi.OFF);
            g.drawString(font, (net > 0 ? "+" : "") + String.format("%.1f", net), col[4], ry + 2, net > 0.05 ? WFUi.GOOD : net < -0.05 ? WFUi.BAD : WFUi.MUTED);
            ry += 13;
        }
    }

    // ------------------------------------------------------------------ Здания

    private void buildings(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int lw = 410, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        String[] heads = {"ЗДАНИЕ", "СОСТОЯНИЕ", "ШТАТ", "КПД", "ПРИОР."};
        int[] col = {x + 8, x + 124, x + 214, x + 258, x + 306};
        for (int i = 0; i < heads.length; i++) g.drawString(font, heads[i], col[i], top + 6, WFUi.GOLD_DIM);
        int rows = (h - 22) / 14, ry = top + 19;
        scroll = Math.max(0, Math.min(Math.max(0, s.buildings.size() - rows), scroll));
        for (int k = scroll; k < s.buildings.size() && k < scroll + rows; k++) {
            Building b = s.buildings.get(k);
            var ch = s.chain(b);
            boolean sel = b.uid == selBuilding;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 14, k, sel, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 14));
            g.drawString(font, font.plainSubstrByWidth(b.def.name, 112), col[0], ry + 2, sel ? WFUi.GOLD : WFUi.TX);
            if (b.state == Building.State.BUILDING) WFUi.bar(g, col[1], ry + 4, 80, 5, b.progress / b.def.work, WFUi.GOLD);
            else g.drawString(font, font.plainSubstrByWidth(b.paused ? "пауза" : b.state == Building.State.PLANNED ? "ждёт" : b.status, 86), col[1], ry + 2, !ch.blocker.isEmpty() ? WFUi.BAD : WFUi.MUTED);
            if (ch.slots > 0) g.drawString(font, ch.staffed + "/" + ch.slots, col[2], ry + 2, ch.staffed < ch.slots ? WFUi.GOLD : WFUi.GOOD);
            if (b.state == Building.State.DONE && b.def.produces()) g.drawString(font, Math.round(ch.efficiency * 100) + "%", col[3], ry + 2, ch.efficiency > 0.7 ? WFUi.GOOD : ch.efficiency > 0 ? WFUi.GOLD : WFUi.BAD);
            g.drawString(font, String.valueOf(b.priority), col[4], ry + 2, WFUi.TX);
            final int uid = b.uid;
            hit(x + 3, ry - 1, lw - 6, 14, () -> selBuilding = uid, null, null);
            ry += 14;
        }
        if (s.buildings.isEmpty()) g.drawString(font, "Пока ничего нет: вкладка «Строить».", x + 8, top + 22, WFUi.MUTED);
        WFUi.scrollbar(g, x + lw - 5, top + 19, h - 22, s.buildings.size() * 14, scroll * 14);

        WFUi.bevel(g, rx, top, rw, h, true);
        Building b = s.building(selBuilding);
        if (b == null) { g.drawString(font, "Выберите здание.", rx + 8, top + 8, WFUi.MUTED); return; }
        var ch = s.chain(b);
        int ix = rx + 8, iw = rw - 16, cy = top + 7;
        g.drawString(font, b.def.name, ix, cy, WFUi.GOLD);
        cy += 13;
        if (!ch.bottleneck.isEmpty()) { g.drawString(font, font.plainSubstrByWidth(ch.bottleneck, iw), ix, cy, WFUi.BAD); cy += 12; }
        for (var e : ch.in.entrySet()) { g.drawString(font, "- " + s.resourceName(e.getKey()) + String.format("  %.1f/мин", e.getValue()), ix, cy, WFUi.BAD); cy += 10; }
        for (var e : ch.out.entrySet()) { g.drawString(font, "+ " + s.resourceName(e.getKey()) + String.format("  %.1f/мин", e.getValue()), ix, cy, WFUi.GOOD); cy += 10; }
        cy += 4;
        g.drawString(font, "РАБОТНИКИ", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        for (Worker w : s.assigned(b.uid)) {
            if (cy > top + h - 60) break;
            g.drawString(font, font.plainSubstrByWidth(w.name, iw - 60), ix, cy, WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(s.defs.professionName(w.profession), 56), ix + iw - 56, cy, WFUi.INFO);
            cy += 10;
        }
        int by = top + h - 40, bw = (iw - 3) / 2;
        final int uid = b.uid;
        btn(g, ix, by, bw, "Приоритет +", b.priority < 9, mx, my, () -> cmd.accept("wfsettle priority " + uid + " " + (b.priority + 1)), "Выше приоритет: раньше получает рабочих и материалы");
        btn(g, ix + bw + 3, by, bw, "Приоритет -", b.priority > 1, mx, my, () -> cmd.accept("wfsettle priority " + uid + " " + (b.priority - 1)), "Ниже приоритет");
        by += 18;
        boolean done = b.state == Building.State.DONE;
        btn(g, ix, by, bw, b.paused ? "Возобновить" : "Пауза", done, mx, my, () -> cmd.accept("wfsettle pause " + uid + " " + !b.paused), "Остановить или запустить производство");
        btn(g, ix + bw + 3, by, bw, "Снести", true, mx, my, () -> { cmd.accept("wfsettle cancel " + uid); selBuilding = 0; }, "Снести здание или отменить стройку");
    }

    private void btn(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hit(x, y, w, 15, en ? r : null, label, List.of(tip));
    }

    // ------------------------------------------------------------------ Люди

    private void people(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int lw = 400, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        String[] heads = {"ИМЯ", "ПРОФЕССИЯ", "РАБОТА", "СОСТОЯНИЕ"};
        int[] col = {x + 8, x + 110, x + 190, x + 300};
        for (int i = 0; i < heads.length; i++) g.drawString(font, heads[i], col[i], top + 6, WFUi.GOLD_DIM);
        int rows = (h - 22) / 14, ry = top + 19;
        scroll = Math.max(0, Math.min(Math.max(0, s.workers.size() - rows), scroll));
        for (int k = scroll; k < s.workers.size() && k < scroll + rows; k++) {
            Worker w = s.workers.get(k);
            boolean sel = w.id == selWorker;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 14, k, sel, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 14));
            g.drawString(font, font.plainSubstrByWidth(w.name, 98), col[0], ry + 2, sel ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(s.defs.professionName(w.profession), 76), col[1], ry + 2, WFUi.INFO);
            Building wb = s.building(w.building);
            g.drawString(font, font.plainSubstrByWidth(wb == null ? "свободен" : wb.def.name, 106), col[2], ry + 2, wb == null ? WFUi.OFF : WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(w.status, 96), col[3], ry + 2, WFUi.MUTED);
            final int id = w.id;
            hit(x + 3, ry - 1, lw - 6, 14, () -> selWorker = id, null, null);
            ry += 14;
        }
        WFUi.scrollbar(g, x + lw - 5, top + 19, h - 22, s.workers.size() * 14, scroll * 14);

        WFUi.bevel(g, rx, top, rw, h, true);
        Worker w = s.worker(selWorker);
        if (w == null) { g.drawString(font, "Выберите жителя.", rx + 8, top + 8, WFUi.MUTED); return; }
        int ix = rx + 8, iw = rw - 16, cy = top + 7;
        g.drawString(font, w.name, ix, cy, WFUi.GOLD);
        cy += 16;
        g.drawString(font, "ПРОФЕССИЯ", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        for (Defs.Profession p : s.defs.professions.values()) {
            boolean on = p.id.equals(w.profession);
            WFUi.button(g, font, ix, cy, iw, 14, p.name, true, on, WFUi.over(mx, my, ix, cy, iw, 14));
            final String pid = p.id; final int id = w.id;
            hit(ix, cy, iw, 14, () -> cmd.accept("wfsettle profession " + id + " " + pid), null, null);
            cy += 16;
            if (cy > top + h - 40) break;
        }
        if (w.building != 0) { final int id = w.id; btn(g, ix, top + h - 22, iw, "Освободить от работы", true, mx, my, () -> cmd.accept("wfsettle unassign " + id), "Рабочий станет свободным"); }
    }

    // ------------------------------------------------------------------ Строить

    private void build(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int cx = x;
        List<String> cats = new ArrayList<>();
        cats.add("");
        for (String c : Defs.CATEGORIES) for (Defs.Building d : s.defs.buildings.values()) if (d.category.equals(c) && !cats.contains(c)) cats.add(c);
        cat = Math.min(cat, cats.size() - 1);
        for (int i = 0; i < cats.size(); i++) {
            String lab = cats.get(i).isEmpty() ? "Все" : Defs.categoryName(cats.get(i));
            int w = font.width(lab) + 14;
            WFUi.button(g, font, cx, top, w, 14, lab, true, i == cat, WFUi.over(mx, my, cx, top, w, 14));
            final int t = i;
            hit(cx, top, w, 14, () -> { cat = t; scroll = 0; }, null, null);
            cx += w + 3;
        }
        List<Defs.Building> list = new ArrayList<>();
        for (Defs.Building d : s.defs.buildings.values()) if (cats.get(cat).isEmpty() || d.category.equals(cats.get(cat))) list.add(d);
        int cwid = (cw - 8) / 2, chh = 70, per = 2 * ((h - 22) / (chh + 4));
        scroll = Math.max(0, Math.min(Math.max(0, (list.size() - 1) / 2 * 2), scroll));
        for (int i = scroll; i < list.size() && i < scroll + per; i++) {
            Defs.Building d = list.get(i);
            int k = i - scroll, px = x + (k % 2) * (cwid + 8), py = top + 20 + (k / 2) * (chh + 4);
            String err = null;
            for (String rq : d.requires) if (s.count(rq, true) == 0) { err = "Нужно: " + s.defs.buildings.get(rq).name; break; }
            if (err == null && d.limit > 0 && s.count(d.id, false) >= d.limit) err = "Лимит: " + d.limit;
            String lack = s.missing(d.cost);
            if (err == null && lack != null) err = "Не хватает: " + s.resourceName(lack).toLowerCase();
            boolean hov = WFUi.over(mx, my, px, py, cwid, chh);
            WFUi.bevel(g, px, py, cwid, chh, !hov);
            g.drawString(font, d.name, px + 6, py + 5, err == null ? WFUi.GOLD : WFUi.TX);
            int ccx = px + 6, cyy = py + 18;
            for (var e : d.cost.entrySet()) {
                String t = e.getValue() + " " + s.resourceName(e.getKey()).toLowerCase();
                boolean ok = s.amount(e.getKey()) >= e.getValue();
                g.drawString(font, t, ccx, cyy, ok ? WFUi.MUTED : WFUi.BAD);
                ccx += font.width(t) + 8;
            }
            StringBuilder inf = new StringBuilder();
            if (d.housing > 0) inf.append("жильё +").append(d.housing).append("  ");
            if (d.slots() > 0) inf.append("работ ").append(d.slots()).append("  ");
            for (var e : d.out.entrySet()) inf.append("+").append(s.resourceName(e.getKey()).toLowerCase()).append(' ');
            g.drawString(font, font.plainSubstrByWidth(inf.toString().trim(), cwid - 12), px + 6, py + 32, WFUi.INFO);
            if (err != null) g.drawString(font, font.plainSubstrByWidth(err, cwid - 12), px + 6, py + 46, WFUi.BAD);
            else g.drawString(font, "Нажмите: построить", px + 6, py + 46, hov ? WFUi.GOLD : WFUi.GOOD);
            g.drawString(font, d.sizeX + "x" + d.sizeZ, px + cwid - 6 - font.width(d.sizeX + "x" + d.sizeZ), py + 5, WFUi.OFF);
            final String id = d.id; final String fe = err;
            hit(px, py, cwid, chh, () -> { if (fe == null) cmd.accept("wfsettle build " + id); }, null, null);
        }
    }

    // ------------------------------------------------------------------ Торговля

    private String tradeRes = "";

    private void trade(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        WFUi.bevel(g, x, top, cw, h, true);
        boolean market = s.tradePerMin() > 0;
        g.drawString(font, "РЫНОК", x + 8, top + 6, WFUi.GOLD_DIM);
        String st = market ? "операций: " + (int) s.tradeLeft + " из " + (int) s.tradeCap() + "  (+" + Math.round(s.tradePerMin()) + "/мин)   серебро: " + (int) s.amount(Defs.SILVER) : "Нужен рынок с торговцами (вкладка «Строить»)";
        g.drawString(font, st, x + 60, top + 6, market ? WFUi.TX : WFUi.BAD);
        String[] heads = {"ТОВАР", "ЗАПАС", "ПОКУПКА", "ПРОДАЖА", "", "", "АВТО"};
        int[] col = {x + 8, x + 130, x + 190, x + 255, x + 320, x + 400, x + 480};
        for (int i = 0; i < heads.length; i++) g.drawString(font, heads[i], col[i], top + 22, WFUi.GOLD_DIM);
        int ry = top + 36, i = 0;
        for (var e : s.defs.resourceNames.entrySet()) {
            String r = e.getKey();
            if (r.equals(Defs.SILVER) || s.price(r) <= 0) continue;
            if (ry > top + h - 16) break;
            WFUi.rowBg(g, x + 3, ry - 1, cw - 6, 16, i++, false, WFUi.over(mx, my, x + 3, ry - 1, cw - 6, 16));
            g.drawString(font, e.getValue(), col[0], ry + 3, WFUi.TX);
            g.drawString(font, (int) s.amount(r) + "/" + (int) s.capacity(r), col[1], ry + 3, WFUi.TX);
            int tr = s.priceTrend(r);
            double fac = s.priceFactor(r);
            g.drawString(font, tr > 0 ? "\u2191" : tr < 0 ? "\u2193" : " ", col[2] - 8, ry + 3, tr > 0 ? WFUi.BAD : WFUi.GOOD);
            g.drawString(font, String.format("%.1f", s.buyPrice(r)), col[2], ry + 3, fac > 1.15 ? WFUi.BAD : WFUi.TX);
            g.drawString(font, String.format("%.1f", s.sellPrice(r)), col[3], ry + 3, WFUi.GOOD);
            final String res = r;
            boolean canBuy = market && s.tradeLeft >= 5 && s.amount(Defs.SILVER) >= s.buyPrice(r) * 5, canSell = market && s.tradeLeft >= 5 && s.amount(r) >= 5;
            btn(g, col[4], ry, 70, "Купить 5", canBuy, mx, my, () -> cmd.accept("wfsettle buy " + res + " 5"), "За " + Math.round(s.buyPrice(r) * 5) + " серебра");
            btn(g, col[5], ry, 70, "Продать 5", canSell, mx, my, () -> cmd.accept("wfsettle sell " + res + " 5"), "Выручка " + Math.round(s.sellPrice(r) * 5) + " серебра");
            String rule = s.autoSell.containsKey(r) ? "продавать > " + s.autoSell.get(r) : s.autoBuy.containsKey(r) ? "докупать до " + s.autoBuy.get(r) : "выкл";
            btn(g, col[6], ry, 130, rule, market, mx, my, () -> {
                if (s.autoSell.containsKey(res)) cmd.accept("wfsettle trade-auto " + res + " buy " + Math.max(20, (int) s.amount(res)));
                else if (s.autoBuy.containsKey(res)) cmd.accept("wfsettle trade-auto " + res + " off 0");
                else cmd.accept("wfsettle trade-auto " + res + " sell " + Math.max(50, (int) s.amount(res)));
            }, "Правило автоторговли: щелчок переключает продажу излишка, докупку и выключение");
            ry += 17;
        }
    }

    // ------------------------------------------------------------------ Бюджет

    private void budget(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int lw = 300, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "КАЗНА", x + 8, top + 6, WFUi.GOLD_DIM);
        double net = s.taxPerMin() - s.upkeepPerMin();
        int cy = top + 20;
        WFUi.kv(g, font, x + 8, cy, lw - 16, 140, "Серебро", String.valueOf((int) s.amount(Defs.SILVER)), WFUi.TX); cy += 12;
        WFUi.kv(g, font, x + 8, cy, lw - 16, 140, "Налоги в минуту", String.format("+%.1f", s.taxPerMin()), WFUi.GOOD); cy += 12;
        WFUi.kv(g, font, x + 8, cy, lw - 16, 140, "Содержание зданий", String.format("-%.1f", s.upkeepPerMin()), WFUi.BAD); cy += 12;
        WFUi.kv(g, font, x + 8, cy, lw - 16, 140, "Итого (без торговли)", (net >= 0 ? "+" : "") + String.format("%.1f", net), net >= 0 ? WFUi.GOOD : WFUi.BAD); cy += 12;
        WFUi.kv(g, font, x + 8, cy, lw - 16, 140, "Фактически за минуту", (s.silverPerMin() >= 0 ? "+" : "") + String.format("%.1f", s.silverPerMin()), s.silverPerMin() >= 0 ? WFUi.GOOD : WFUi.BAD); cy += 20;
        g.drawString(font, "УРОВЕНЬ НАЛОГА", x + 8, cy, WFUi.GOLD_DIM); cy += 14;
        int bw = (lw - 16 - 9) / 4;
        for (int i = 0; i < 4; i++) {
            final int lv = i;
            int[] ap = {8, 3, 0, -10};
            WFUi.button(g, font, x + 8 + i * (bw + 3), cy, bw, 15, Settlement.TAX_NAMES[i], true, s.taxLevel == i, WFUi.over(mx, my, x + 8 + i * (bw + 3), cy, bw, 15));
            hit(x + 8 + i * (bw + 3), cy, bw, 15, () -> cmd.accept("wfsettle tax " + lv), Settlement.TAX_NAMES[i], List.of("Налоги x" + Settlement.TAX_MULT[i], "Настроение " + (ap[i] > 0 ? "+" : "") + ap[i]));
        }
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "СОДЕРЖАНИЕ ПО ЗДАНИЯМ", rx + 8, top + 6, WFUi.GOLD_DIM);
        int ry = top + 20, i = 0;
        for (Building b : s.buildings) {
            if (b.def.upkeep <= 0 || ry > top + h - 14) continue;
            WFUi.rowBg(g, rx + 3, ry - 1, rw - 6, 13, i++, false, WFUi.over(mx, my, rx + 3, ry - 1, rw - 6, 13));
            g.drawString(font, font.plainSubstrByWidth(b.def.name, rw - 90), rx + 8, ry + 2, b.unpaid ? WFUi.BAD : WFUi.TX);
            String v = b.state == Building.State.DONE ? String.format("%.1f/мин", b.def.upkeep) : "ещё не построено";
            g.drawString(font, v, rx + rw - 8 - font.width(v), ry + 2, b.unpaid ? WFUi.BAD : WFUi.MUTED);
            ry += 13;
        }
        if (i == 0) g.drawString(font, "Зданий с содержанием пока нет.", rx + 8, top + 22, WFUi.MUTED);
    }

    // ------------------------------------------------------------------ Города

    private void cities(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        WFUi.bevel(g, x, top, cw, h, true);
        String[] keys = {"name", "pop", "approval", "silver", "silverNet", "foodNet", "defense", "wealth"};
        String[] heads = {"ГОРОД", "ЖИТЕЛИ", "НАСТР.", "СЕРЕБРО", "СЕРЕБРО/МИН", "ЕДА/МИН", "ОБОРОНА", "БОГАТСТВО"};
        int[] col = {x + 24, x + 170, x + 230, x + 290, x + 360, x + 440, x + 510, x + 570};
        for (int i = 0; i < heads.length; i++) {
            boolean on = citySort.equals(keys[i]);
            g.drawString(font, heads[i] + (on ? " \u25BC" : ""), col[i], top + 6, on ? WFUi.GOLD : WFUi.GOLD_DIM);
            final String k = keys[i];
            hit(col[i] - 2, top + 3, font.width(heads[i]) + 14, 12, () -> citySort = k, "Сортировать", List.of(heads[i]));
        }
        java.util.List<dev.worldforge.settle.CityRow> rows = new ArrayList<>(cities);
        rows.sort((a, b) -> switch (citySort) {
            case "name" -> a.name.compareToIgnoreCase(b.name);
            case "approval" -> Integer.compare(b.approval, a.approval);
            case "silver" -> Double.compare(b.silver, a.silver);
            case "silverNet" -> Double.compare(b.silverNet, a.silverNet);
            case "foodNet" -> Double.compare(b.foodNet, a.foodNet);
            case "defense" -> Double.compare(b.defense, a.defense);
            case "wealth" -> Double.compare(b.wealth, a.wealth);
            default -> Integer.compare(b.pop, a.pop);
        });
        int ry = top + 20, room = (h - 50 - 128) / 14;
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - room), scroll));
        for (int i = scroll; i < rows.size() && i < scroll + room; i++) {
            var r = rows.get(i);
            boolean cur = r.id.equals(s.id);
            WFUi.rowBg(g, x + 3, ry - 1, cw - 6, 14, i, cur, WFUi.over(mx, my, x + 3, ry - 1, cw - 6, 14));
            WFUi.dot(g, x + 8, ry + 3, 6, r.raid ? WFUi.BAD : r.approval < Settlement.APPROVAL_LEAVE_BELOW ? WFUi.BAD : r.approval < Settlement.APPROVAL_GROWTH_MIN ? WFUi.GOLD : WFUi.GOOD);
            g.drawString(font, font.plainSubstrByWidth(r.name, 140), col[0], ry + 2, cur ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, r.pop + "/" + r.housing, col[1], ry + 2, r.pop >= r.housing ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, String.valueOf(r.approval), col[2], ry + 2, r.approval < Settlement.APPROVAL_LEAVE_BELOW ? WFUi.BAD : r.approval < Settlement.APPROVAL_GROWTH_MIN ? WFUi.GOLD : WFUi.GOOD);
            g.drawString(font, String.valueOf((int) r.silver), col[3], ry + 2, r.silver < 5 ? WFUi.BAD : WFUi.TX);
            g.drawString(font, (r.silverNet >= 0 ? "+" : "") + String.format("%.1f", r.silverNet), col[4], ry + 2, r.silverNet >= 0 ? WFUi.GOOD : WFUi.BAD);
            g.drawString(font, (r.foodNet >= 0 ? "+" : "") + String.format("%.1f", r.foodNet), col[5], ry + 2, r.foodNet >= 0 ? WFUi.GOOD : WFUi.BAD);
            g.drawString(font, String.valueOf(Math.round(r.defense)) + (r.raid ? " !" : ""), col[6], ry + 2, r.raid ? WFUi.BAD : WFUi.TX);
            g.drawString(font, String.valueOf(Math.round(r.wealth)), col[7], ry + 2, WFUi.MUTED);
            final String id = r.id;
            hit(x + 3, ry - 1, cw - 6, 14, cur ? null : () -> cmd.accept("wfsettle view panel " + id),
                    r.name, List.of(r.season + ", зданий " + r.buildings, "Налог " + r.tax + ", набегов отбито " + r.repelled + ", потеряно " + r.lost, cur ? "Это окно открыто" : "Нажмите, чтобы открыть окно города"));
            ry += 14;
        }
        if (rows.isEmpty()) g.drawString(font, "Других поселений нет.", x + 8, top + 22, WFUi.MUTED);
        WFUi.scrollbar(g, x + cw - 5, top + 18, h - 56 - 128, rows.size() * 14, scroll * 14);
        double pop = 0, silver = 0, sn = 0;
        for (var r : rows) { pop += r.pop; silver += r.silver; sn += r.silverNet; }
        int fy = top + h - 22 - 128;
        g.fill(x + 6, fy - 4, x + cw - 6, fy - 3, WFUi.EDGE);
        g.drawString(font, "ВСЕГО  " + rows.size() + " " + (rows.size() == 1 ? "город" : "городов") + ", жителей " + (int) pop + ", серебра " + (int) silver + " (" + (sn >= 0 ? "+" : "") + String.format("%.1f", sn) + "/мин)", x + 8, fy + 2, WFUi.GOLD);

        // ---- торговые маршруты и подсказки
        int by = top + h - 124, bh = 118, half = cw / 2 - 6;
        java.util.Map<String, dev.worldforge.settle.CityRow> byId = new java.util.LinkedHashMap<>();
        for (var r : rows) byId.put(r.id, r);
        WFArt.ribbon(g, font, x + 4, by, half, "МАРШРУТЫ  (" + net.routes.size() + ")", WFUi.INFO);
        int my2 = by + 15, k = 0;
        for (var rt : net.routes.values()) {
            if (k++ >= 6) break;
            var a = byId.get(rt.from); var b = byId.get(rt.to);
            String res = s.defs.resourceNames.getOrDefault(rt.res, rt.res);
            g.drawString(font, font.plainSubstrByWidth((a == null ? rt.from : a.name) + " > " + (b == null ? rt.to : b.name) + ": " + res, half - 100), x + 8, my2, WFUi.TX);
            String st = rt.status + ", " + (int) rt.carried + " ед.";
            g.drawString(font, font.plainSubstrByWidth(st, 90), x + half - 18 - Math.min(90, font.width(st)), my2, rt.status.equals("возит") ? WFUi.GOOD : WFUi.MUTED);
            final int rid = rt.id;
            WFUi.button(g, font, x + half - 12, my2 - 1, 11, 11, "x", true, false, WFUi.over(mx, my, x + half - 12, my2 - 1, 11, 11));
            hit(x + half - 12, my2 - 1, 11, 11, () -> cmd.accept("wfsettle route remove " + rid), "Закрыть маршрут", List.of("Прибыль " + Math.round(rt.profit) + " серебра"));
            my2 += 14;
        }
        if (net.routes.isEmpty()) g.drawString(font, "Маршрутов нет. Выберите справа выгодную пару.", x + 8, my2, WFUi.MUTED);
        int sx = x + half + 12, sw = cw - half - 18;
        WFArt.ribbon(g, font, sx - 4, by, sw + 4, "ВЫГОДНЫЕ ПАРЫ  (цена там выше, чем здесь)", WFUi.GOLD);
        java.util.List<Object[]> sug = new ArrayList<>();
        for (var a : rows) for (var b : rows) {
            if (a == b) continue;
            for (var e2 : a.prices.entrySet()) {
                Double pb = b.prices.get(e2.getKey());
                double pa = e2.getValue();
                if (pb == null || pa <= 0 || pb < pa * dev.worldforge.settle.TradeNetwork.MIN_SPREAD || a.stock.getOrDefault(e2.getKey(), 0.0) < dev.worldforge.settle.TradeNetwork.RESERVE) continue;
                boolean dup = false;
                for (var rt : net.routes.values()) if (rt.from.equals(a.id) && rt.to.equals(b.id) && rt.res.equals(e2.getKey())) dup = true;
                if (!dup) sug.add(new Object[]{a, b, e2.getKey(), pb / pa});
            }
        }
        sug.sort((p, q) -> Double.compare((double) q[3], (double) p[3]));
        int sy = by + 15;
        for (int i = 0; i < sug.size() && i < 6; i++) {
            var a = (dev.worldforge.settle.CityRow) sug.get(i)[0]; var b = (dev.worldforge.settle.CityRow) sug.get(i)[1];
            String res = (String) sug.get(i)[2]; double ratio = (double) sug.get(i)[3];
            WFUi.rowBg(g, sx, sy - 1, sw, 14, i, false, WFUi.over(mx, my, sx, sy - 1, sw, 14));
            g.drawString(font, font.plainSubstrByWidth(s.defs.resourceNames.getOrDefault(res, res) + ": " + a.name + " > " + b.name, sw - 54), sx + 4, sy + 2, WFUi.TX);
            String rs = "x" + String.format("%.1f", ratio);
            g.drawString(font, rs, sx + sw - 6 - font.width(rs), sy + 2, ratio > 1.6 ? WFUi.GOOD : WFUi.GOLD);
            final String fa = a.id, fb = b.id, fr = res;
            hit(sx, sy - 1, sw, 14, () -> cmd.accept("wfsettle route add " + fa + " " + fb + " " + fr + " 10"), "Открыть маршрут", List.of(a.prices.get(res) + " > " + b.prices.get(res) + " серебра", "10 единиц в минуту, нужны рынки в обоих городах"));
            sy += 14;
        }
        if (sug.isEmpty()) g.drawString(font, "Сейчас разницы в ценах нет.", sx + 4, sy + 2, WFUi.MUTED);
    }


    private static double visibleGridSpanX(double scale, int width) { return width / Math.max(0.0001, scale); }
    private static double visibleGridSpanZ(double scale, int height) { return height / Math.max(0.0001, scale); }

    // ------------------------------------------------------------------ Карта поселения
    // План строится только по авторитетным координатам зданий и жителей из Settlement.
    // Рельеф и дороги не рисуются искусственно: для них пока нет соответствующих данных карты.
    private void settlementMap(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        double minX = -24, maxX = 24, minZ = -24, maxZ = 24;
        for (Building b : s.buildings) {
            minX = Math.min(minX, b.x - b.def.sizeX / 2.0);
            maxX = Math.max(maxX, b.x + b.def.sizeX / 2.0);
            minZ = Math.min(minZ, b.z - b.def.sizeZ / 2.0);
            maxZ = Math.max(maxZ, b.z + b.def.sizeZ / 2.0);
        }
        for (Worker w : s.workers) {
            minX = Math.min(minX, w.x); maxX = Math.max(maxX, w.x);
            minZ = Math.min(minZ, w.z); maxZ = Math.max(maxZ, w.z);
        }
        for (SettlementMap.Cell cell : map.cells()) {
            minX = Math.min(minX, cell.x() - SettlementMap.CELL_SIZE / 2.0);
            maxX = Math.max(maxX, cell.x() + SettlementMap.CELL_SIZE / 2.0);
            minZ = Math.min(minZ, cell.z() - SettlementMap.CELL_SIZE / 2.0);
            maxZ = Math.max(maxZ, cell.z() + SettlementMap.CELL_SIZE / 2.0);
        }
        for (SettlementMap.Parcel parcel : map.parcels.values()) {
            minX = Math.min(minX, parcel.x); maxX = Math.max(maxX, parcel.x + parcel.width);
            minZ = Math.min(minZ, parcel.z); maxZ = Math.max(maxZ, parcel.z + parcel.depth);
        }
        for (SettlementMap.Warehouse warehouse : map.warehouses.values()) {
            minX = Math.min(minX, warehouse.x); maxX = Math.max(maxX, warehouse.x);
            minZ = Math.min(minZ, warehouse.z); maxZ = Math.max(maxZ, warehouse.z);
        }
        minX -= 10; maxX += 10; minZ -= 10; maxZ += 10;
        double spanX = Math.max(48, maxX - minX), spanZ = Math.max(48, maxZ - minZ);
        double baseScale = Math.min((cw - 24.0) / spanX, (h - 24.0) / spanZ);
        double scale = Math.max(0.02, baseScale * mapZoom);
        double worldCenterX = (minX + maxX) / 2.0, worldCenterZ = (minZ + maxZ) / 2.0;
        double centerScreenX = x + cw / 2.0, centerScreenY = top + h / 2.0;
        double originX = centerScreenX + mapPanX - worldCenterX * scale;
        double originY = centerScreenY + mapPanY - worldCenterZ * scale;

        WFUi.bevel(g, x, top, cw, h, true);
        g.enableScissor(x + 2, top + 2, x + cw - 2, top + h - 2);
        g.fill(x + 2, top + 2, x + cw - 2, top + h - 2, 0xFF17231F);

        // Sampled world terrain, not a synthetic background. Elevation subtly shades the biome base.
        int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;
        for (SettlementMap.Cell cell : map.cells()) { minHeight = Math.min(minHeight, cell.height); maxHeight = Math.max(maxHeight, cell.height); }
        double heightRange = Math.max(1, maxHeight - minHeight);
        for (SettlementMap.Cell cell : map.cells()) {
            int sx = (int) Math.round(originX + cell.x() * scale - SettlementMap.CELL_SIZE * scale / 2.0);
            int sy = (int) Math.round(originY + cell.z() * scale - SettlementMap.CELL_SIZE * scale / 2.0);
            int size = Math.max(1, (int) Math.ceil(SettlementMap.CELL_SIZE * scale));
            int color = cell.water ? 0xFF315F83 : cell.slope > 1.0 ? 0xFF6A5A45
                    : cell.fertility > 0.7 ? 0xFF456B48 : cell.fertility > 0.4 ? 0xFF53634A : 0xFF6C6549;
            if (!cell.water) {
                int shade = (int) Math.round((cell.height - minHeight) / heightRange * 36.0) - 18;
                int red = Math.max(0, Math.min(255, ((color >> 16) & 255) + shade));
                int green = Math.max(0, Math.min(255, ((color >> 8) & 255) + shade));
                int blue = Math.max(0, Math.min(255, (color & 255) + shade));
                color = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
            g.fill(sx, sy, sx + size, sy + size, color);
        }
        for (SettlementMap.Parcel parcel : map.parcels.values()) {
            int px = (int) Math.round(originX + parcel.x * scale);
            int pz = (int) Math.round(originY + parcel.z * scale);
            int pw = Math.max(1, (int) Math.round(parcel.width * scale));
            int ph = Math.max(1, (int) Math.round(parcel.depth * scale));
            int color = switch (parcel.zone) {
                case RESIDENTIAL -> 0x443E8BD0; case AGRICULTURE -> 0x4478B84C;
                case INDUSTRY -> 0x44B87942; case COMMERCE -> 0x44D6B64A;
                case CIVIC -> 0x448B75C4; case MILITARY -> 0x44C44B4B;
                case STORAGE -> 0x448B9299; case NONE -> 0x22000000;
            };
            g.fill(px, pz, px + pw, pz + ph, color);
            g.renderOutline(px, pz, pw, ph, 0x99D5D0B8);
        }
        // Spatial warehouses are separate from the settlement-wide inventory ledger.
        for (SettlementMap.Warehouse warehouse : map.warehouses.values()) {
            int wx = (int) Math.round(originX + warehouse.x * scale);
            int wz = (int) Math.round(originY + warehouse.z * scale);
            g.fill(wx - 4, wz - 4, wx + 5, wz + 5, 0xFFFFD36A);
            g.renderOutline(wx - 4, wz - 4, 9, 9, 0xFF4A3214);
            List<String> stock = new ArrayList<>();
            stock.add("Вместимость: " + Math.round(warehouse.capacity));
            for (var entry : warehouse.stock.entrySet()) stock.add(entry.getKey() + ": " + Math.round(entry.getValue()));
            hit(wx - 6, wz - 6, 12, 12, null, "Склад " + warehouse.id, stock);
        }

        for (SettlementMap.Road road : map.roads.values()) {
            for (int i = 1; i < road.path.size(); i++) {
                SettlementMap.Node a = road.path.get(i - 1), b = road.path.get(i);
                int ax = (int) Math.round(originX + (a.gx() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2.0) * scale);
                int ay = (int) Math.round(originY + (a.gz() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2.0) * scale);
                int bx = (int) Math.round(originX + (b.gx() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2.0) * scale);
                int by = (int) Math.round(originY + (b.gz() * SettlementMap.CELL_SIZE + SettlementMap.CELL_SIZE / 2.0) * scale);
                int thickness = Math.max(2, (int) Math.ceil(scale * 2.2));
                g.fill(Math.min(ax,bx)-thickness/2, Math.min(ay,by)-thickness/2,
                        Math.max(ax,bx)+thickness/2+1, Math.max(ay,by)+thickness/2+1, 0xFFB49A70);
            }
        }

        // Масштабируемая координатная сетка помогает читать план и соотносить его с координатами мира.
        double rawStep = 48.0 / Math.max(0.0001, scale);
        double gridStep = Math.pow(2.0, Math.round(Math.log(rawStep) / Math.log(2.0)));
        gridStep = Math.max(4.0, Math.min(256.0, gridStep));
        while ((visibleGridSpanX(scale, cw) / gridStep) > 180 || (visibleGridSpanZ(scale, h) / gridStep) > 180) gridStep *= 2.0;
        double visibleMinX = (x + 2 - originX) / scale, visibleMaxX = (x + cw - 2 - originX) / scale;
        double visibleMinZ = (top + 2 - originY) / scale, visibleMaxZ = (top + h - 2 - originY) / scale;
        double firstGridX = Math.ceil(visibleMinX / gridStep) * gridStep;
        double firstGridZ = Math.ceil(visibleMinZ / gridStep) * gridStep;
        for (double wx = firstGridX; wx <= visibleMaxX; wx += gridStep) {
            int sx = (int) Math.round(originX + wx * scale);
            g.fill(sx, top + 2, sx + 1, top + h - 2, 0x332F5147);
        }
        for (double wz = firstGridZ; wz <= visibleMaxZ; wz += gridStep) {
            int sy = (int) Math.round(originY + wz * scale);
            g.fill(x + 2, sy, x + cw - 2, sy + 1, 0x332F5147);
        }

        // Центральные оси и отметка исходной точки поселения.
        int axisX = (int) Math.round(originX), axisY = (int) Math.round(originY);
        g.fill(axisX, top + 2, axisX + 1, top + h - 2, 0x665D8D78);
        g.fill(x + 2, axisY, x + cw - 2, axisY + 1, 0x665D8D78);

        for (Building b : s.buildings) {
            int bw = Math.max(6, (int) Math.round(b.def.sizeX * scale));
            int bh = Math.max(6, (int) Math.round(b.def.sizeZ * scale));
            int bx = (int) Math.round(originX + b.x * scale - bw / 2.0);
            int by = (int) Math.round(originY + b.z * scale - bh / 2.0);
            boolean selected = b.uid == selBuilding;
            int fill = b.state == Building.State.DONE ? 0xFF496D58
                    : b.state == Building.State.BUILDING ? 0xFF9A713B : 0xFF625C4A;
            g.fill(bx, by, bx + bw, by + bh, fill);
            g.renderOutline(bx, by, bw, bh, selected ? WFUi.GOLD : 0xFFB7C5B7);
            if (bw >= 34 && bh >= 15) {
                String label = font.plainSubstrByWidth(b.def.name, Math.max(12, bw - 6));
                g.drawCenteredString(font, label, bx + bw / 2, by + (bh - 8) / 2, 0xFFF0F1E8);
            }
            final int uid = b.uid;
            hit(bx - 3, by - 3, bw + 6, bh + 6, () -> {
                selBuilding = uid;
                tab = 1;
                scroll = Math.max(0, s.buildings.indexOf(s.building(uid)));
            }, b.def.name, List.of("Координаты: " + b.x + ", " + b.z,
                    "Состояние: " + b.state, "Приоритет: " + b.priority,
                    "Нажмите, чтобы открыть здание"));
        }

        for (Worker w : s.workers) {
            int wx = (int) Math.round(originX + w.x * scale);
            int wy = (int) Math.round(originY + w.z * scale);
            boolean selected = w.id == selWorker;
            int color = selected ? WFUi.GOLD : w.moving ? 0xFF7ED7C1 : 0xFFE4D8B4;
            g.fill(wx - 3, wy - 3, wx + 4, wy + 4, 0xFF0A100E);
            g.fill(wx - 2, wy - 2, wx + 3, wy + 3, color);
            final int workerId = w.id;
            hit(wx - 5, wy - 5, 10, 10, () -> {
                selWorker = workerId;
                tab = 2;
                scroll = Math.max(0, s.workers.indexOf(s.worker(workerId)));
            }, w.name, List.of("Профессия: " + s.defs.professionName(w.profession),
                    "Статус: " + (w.status.isBlank() ? "работает" : w.status),
                    "Координаты: " + Math.round(w.x) + ", " + Math.round(w.z),
                    "Нажмите, чтобы открыть жителя"));
        }
        g.disableScissor();

        String heading = "ПЛАН ПОСЕЛЕНИЯ";
        g.drawString(font, heading, x + 8, top + 7, WFUi.GOLD);
        String stats = s.buildings.size() + " зданий  ·  " + s.workers.size() + " жителей  ·  " + map.roads.size() + " дорог  ·  " + map.parcels.size() + " участков  ·  " + map.warehouses.size() + " складов";
        g.drawString(font, stats, x + cw - 8 - font.width(stats), top + 7, WFUi.MUTED);
        String hint = "Рельеф  ·  участки  ·  дороги  ·  ЛКМ + перетаскивание — перемещение  ·  выбор здания/жителя";
        g.drawString(font, font.plainSubstrByWidth(hint, cw - 16), x + 8, top + h - 12, WFUi.MUTED);
        String zoomLabel = Math.round(mapZoom * 100) + "%";
        g.drawString(font, zoomLabel, x + cw - 8 - font.width(zoomLabel), top + h - 12, WFUi.GOLD_DIM);
    }

    // ------------------------------------------------------------------ Журнал

    private void journal(GuiGraphics g, int x, int top, int cw, int h) {
        WFUi.bevel(g, x, top, cw, h, true);
        List<String> lines = new ArrayList<>(s.log);
        java.util.Collections.reverse(lines);
        int room = (h - 12) / 11;
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - room), scroll));
        int y = top + 7;
        for (int i = scroll; i < lines.size() && i < scroll + room; i++) { g.drawString(font, font.plainSubstrByWidth(lines.get(i), cw - 24), x + 8, y, WFUi.MUTED); y += 11; }
        if (lines.isEmpty()) g.drawString(font, "Журнал пуст.", x + 8, top + 8, WFUi.MUTED);
        WFUi.scrollbar(g, x + cw - 5, top + 4, h - 8, lines.size() * 11, scroll * 11);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Object[] h = hits.get(i);
                if (WFUi.over((int) mx, (int) my, (int) h[0], (int) h[1], (int) h[2], (int) h[3]) && h[4] != null) {
                    ((Runnable) h[4]).run();
                    mapDragging = false;
                    return true;
                }
            }
        }
        if (tab == 8 && button == 0) {
            int x = (width - W) / 2 + 10, top = (height - H) / 2 + 44, cw = W - 20, h = H - 54;
            if (WFUi.over((int) mx, (int) my, x, top, cw, h)) {
                mapDragging = true;
                mapLastX = (int) mx;
                mapLastY = (int) my;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (mapDragging && tab == 8 && button == 0) {
            mapPanX += mx - mapLastX;
            mapPanY += my - mapLastY;
            mapLastX = (int) mx;
            mapLastY = (int) my;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) mapDragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (tab == 8) {
            int x = (width - W) / 2 + 10, top = (height - H) / 2 + 44, cw = W - 20, h = H - 54;
            if (WFUi.over((int) mx, (int) my, x, top, cw, h)) {
                double next = Math.max(0.5, Math.min(4.0, mapZoom * Math.exp(d * 0.18)));
                double ratio = next / mapZoom;
                double cx = x + cw / 2.0, cy = top + h / 2.0;
                mapPanX = (mx - cx) - (mx - cx - mapPanX) * ratio;
                mapPanY = (my - cy) - (my - cy - mapPanY) * ratio;
                mapZoom = next;
                return true;
            }
        }
        scroll = Math.max(0, scroll - (int) Math.signum(d) * (tab == 3 ? 2 : 1));
        return true;
    }
}
