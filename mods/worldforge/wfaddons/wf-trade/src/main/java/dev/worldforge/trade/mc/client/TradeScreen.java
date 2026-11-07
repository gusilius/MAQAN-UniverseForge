package dev.worldforge.trade.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно торговли: «Товары» (цены, запасы, соседи, покупка/продажа, отправка каравана), «Караваны», «Политика» (пошлина, эмбарго, доходы), «Хроника». */
public class TradeScreen extends Screen {
    private static final int W = 780, H = 460;
    private static final String[] TABS = {"Товары", "Караваны", "Политика", "Хроника"};
    private List<Map<String, Object>> goods = new ArrayList<>(), caravans = new ArrayList<>(), ambushes = new ArrayList<>();
    private List<String> chronicle = new ArrayList<>(), embargo = new ArrayList<>(), neighbors = new ArrayList<>();
    private String realm = "";
    private double tariff;
    private long capital;
    private List<Object> income = new ArrayList<>();
    private int tab, sel, scroll, refresh, qty = 10, guards = 2, catFilter;
    private static final String[] CATS = {"", "food", "material", "craft", "luxury", "military", "magic", "contraband"};
    private static final String[] CAT_NAMES = {"Все", "Еда", "Сырьё", "Ремесло", "Роскошь", "Военное", "Магия", "Контрабанда"};
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public TradeScreen(String payload, Consumer<String> cmd) { super(Component.literal("Торговля")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
        goods = new ArrayList<>(); caravans = new ArrayList<>(); ambushes = new ArrayList<>(); chronicle = new ArrayList<>(); embargo = new ArrayList<>(); neighbors = new ArrayList<>();
        for (Object o : list(m, "goods")) goods.add((Map<String, Object>) o);
        for (Object o : list(m, "caravans")) caravans.add((Map<String, Object>) o);
        for (Object o : list(m, "ambushes")) ambushes.add((Map<String, Object>) o);
        for (Object o : list(m, "chronicle")) chronicle.add(String.valueOf(o));
        for (Object o : list(m, "embargo")) embargo.add(String.valueOf(o));
        for (Object o : list(m, "neighbors")) neighbors.add(String.valueOf(o));
        income = list(m, "income");
        realm = str(m, "realm"); tariff = dbl(m, "tariff"); capital = (long) dbl(m, "capital");
    }

    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }
    private static double dbl(Map<String, Object> m, String k) { return m.get(k) instanceof Number n ? n.doubleValue() : 0; }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : List.of(); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0) cmd.accept("wftrade market " + realm); }

    private List<Map<String, Object>> visible() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> g : goods) if (catFilter == 0 || str(g, "cat").equals(CATS[catFilter])) l.add(g);
        return l;
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int color) {
        for (var l : font.split(Component.literal(s), w)) { g.drawString(font, l, x, y, color); y += 10; }
        return y;
    }

    private static int tagColor(String t) { return t.equals("дефицит") ? WFUi.BAD : t.equals("избыток") ? WFUi.GOOD : WFUi.MUTED; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Торговля  ·  " + realm + "  ·  пошлина " + (int) tariff + "%");
        WFUi.tabs(g, font, x + 10, y + 22, TABS, tab, mx, my);
        for (int i = 0, cx = x + 10; i < TABS.length; i++) { int w = font.width(TABS[i]) + 16; final int t = i; hits.add(new Hit(cx, y + 22, w, 15, () -> { tab = t; scroll = 0; }, null)); cx += w + 2; }
        WFUi.badge(g, font, x + W - 170, y + 25, "Караванов в пути: " + caravans.stream().filter(c -> str(c, "status").equals("в пути")).count(), WFUi.INFO);
        int top = y + 42, bodyH = H - 52;
        switch (tab) {
            case 0 -> goodsTab(g, x, top, bodyH, mx, my);
            case 1 -> caravansTab(g, x, top, bodyH, mx, my);
            case 2 -> policyTab(g, x, top, bodyH, mx, my);
            default -> {
                WFUi.bevel(g, x + 10, top, W - 20, bodyH, true);
                if (chronicle.isEmpty()) g.drawString(font, "Торговых событий пока не было.", x + 18, top + 8, WFUi.MUTED);
                for (int i = 0; i < chronicle.size() && 8 + i * 11 < bodyH - 8; i++) g.drawString(font, font.plainSubstrByWidth(chronicle.get(chronicle.size() - 1 - i), W - 40), x + 18, top + 8 + i * 11, i == 0 ? WFUi.TX : WFUi.MUTED);
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; }
        }
        super.render(g, mx, my, pt);
    }

    private void goodsTab(GuiGraphics g, int x, int top, int bodyH, int mx, int my) {
        int lw = 430;
        // фильтр
        for (int i = 0, cx = x + 10; i < CATS.length; i++) {
            int w = font.width(CAT_NAMES[i]) + 12; final int k = i;
            int c = WFUi.chip(g, font, cx, top, CAT_NAMES[i], catFilter == i, WFUi.over(mx, my, cx, top, w, 14));
            hits.add(new Hit(cx, top, Math.max(c, w), 14, () -> { catFilter = k; sel = 0; scroll = 0; }, null));
            cx += Math.max(c, w) + 3;
        }
        int ty = top + 18;
        WFUi.bevel(g, x + 10, ty, lw, bodyH - 18, true);
        g.drawString(font, "Товар", x + 18, ty + 4, WFUi.GOLD_DIM); g.drawString(font, "Цена", x + 190, ty + 4, WFUi.GOLD_DIM); g.drawString(font, "Запас", x + 245, ty + 4, WFUi.GOLD_DIM);
        g.drawString(font, "Выпуск/потр.", x + 300, ty + 4, WFUi.GOLD_DIM);
        List<Map<String, Object>> vis = visible();
        int rowH = 16, rows = (bodyH - 40) / rowH;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, vis.size() - rows)));
        for (int i = 0; i < rows && scroll + i < vis.size(); i++) {
            Map<String, Object> gd = vis.get(scroll + i);
            int ry = ty + 16 + i * rowH;
            boolean on = scroll + i == sel;
            WFUi.rowBg(g, x + 12, ry, lw - 6, rowH - 1, i, on, WFUi.over(mx, my, x + 12, ry, lw - 6, rowH - 1));
            g.drawString(font, font.plainSubstrByWidth(str(gd, "name"), 165), x + 18, ry + 4, boolContra(gd) ? WFUi.BAD : WFUi.TX);
            g.drawString(font, fmt(dbl(gd, "price")), x + 190, ry + 4, tagColor(str(gd, "tag")));
            g.drawString(font, String.valueOf((long) dbl(gd, "stock")), x + 245, ry + 4, WFUi.MUTED);
            g.drawString(font, fmt(dbl(gd, "prod")) + " / " + fmt(dbl(gd, "cons")), x + 300, ry + 4, WFUi.MUTED);
            if (!str(gd, "tag").isEmpty()) g.drawString(font, str(gd, "tag").equals("дефицит") ? "▲" : "▼", x + lw - 12, ry + 4, tagColor(str(gd, "tag")));
            final int k = scroll + i;
            hits.add(new Hit(x + 12, ry, lw - 6, rowH - 1, () -> sel = k, null));
        }
        // подробности
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH, true);
        if (vis.isEmpty()) return;
        Map<String, Object> gd = vis.get(Math.min(sel, vis.size() - 1));
        int yy = top + 8;
        g.drawString(font, str(gd, "name"), rx + 10, yy, WFUi.GOLD); yy += 14;
        g.drawString(font, "Цена здесь: " + fmt(dbl(gd, "price")) + "  (база " + fmt(dbl(gd, "base")) + ")", rx + 10, yy, WFUi.TX); yy += 12;
        g.drawString(font, "Запас: " + (long) dbl(gd, "stock") + "   выпуск " + fmt(dbl(gd, "prod")) + "   спрос " + fmt(dbl(gd, "cons")), rx + 10, yy, WFUi.MUTED); yy += 14;
        g.drawString(font, "У СОСЕДЕЙ", rx + 10, yy, WFUi.GOLD_DIM); yy += 11;
        double here = dbl(gd, "price");
        for (Object o : list(gd, "near")) {
            @SuppressWarnings("unchecked") Map<String, Object> n = (Map<String, Object>) o;
            double p = dbl(n, "price");
            int c = p > here * 1.2 ? WFUi.GOOD : p < here * 0.85 ? WFUi.BAD : WFUi.MUTED;
            g.drawString(font, font.plainSubstrByWidth(str(n, "realm"), 110), rx + 12, yy, WFUi.TX);
            g.drawString(font, fmt(p) + (p > here * 1.2 ? "  выгодно везти" : ""), rx + 125, yy, c);
            yy += 11;
            if (yy > top + bodyH - 70) break;
        }
        // кнопки
        int by = top + bodyH - 60;
        g.drawString(font, "Количество: " + qty + "   Охрана: " + guards, rx + 10, by - 12, WFUi.MUTED);
        button(g, rx + rw - 150, by - 14, 20, "-", true, mx, my, () -> qty = Math.max(1, qty - 5), "Меньше");
        button(g, rx + rw - 126, by - 14, 20, "+", true, mx, my, () -> qty = Math.min(200, qty + 5), "Больше");
        button(g, rx + rw - 96, by - 14, 20, "-", true, mx, my, () -> guards = Math.max(0, guards - 1), "Меньше охраны");
        button(g, rx + rw - 72, by - 14, 20, "+", true, mx, my, () -> guards = Math.min(10, guards + 1), "Больше охраны: дороже, но безопаснее и вместительнее");
        String id = str(gd, "id");
        boolean direct = Boolean.TRUE.equals(gd.get("direct")) && !Boolean.TRUE.equals(gd.get("contra"));
        button(g, rx + 10, by + 4, 90, "Купить", direct, mx, my, () -> cmd.accept("wftrade buy " + id + " " + qty), direct ? "Купить на рынке предметами" : "Только караванами");
        button(g, rx + 104, by + 4, 90, "Продать", direct, mx, my, () -> cmd.accept("wftrade sell " + id + " " + qty), direct ? "Продать предметы из сумки" : "Только караванами");
        int cx = rx + 10;
        for (int i = 0; i < neighbors.size() && i < 3; i++) {
            String to = neighbors.get(i);
            int w = Math.min(120, (rw - 20) / Math.min(3, neighbors.size()) - 4);
            final String dest = to;
            button(g, cx, by + 24, w, "→ " + font.plainSubstrByWidth(to, w - 22), true, mx, my, () -> cmd.accept("wftrade caravan " + dest + " " + id + " " + qty + " " + guards), "Караван в " + to + ": цена товара из этого рынка + охрана");
            cx += w + 4;
        }
    }

    private static boolean boolContra(Map<String, Object> g) { return Boolean.TRUE.equals(g.get("contra")); }
    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    private void caravansTab(GuiGraphics g, int x, int top, int bodyH, int mx, int my) {
        WFUi.bevel(g, x + 10, top, W - 20, bodyH, true);
        int off = 0;
        for (Map<String, Object> a : ambushes) {
            int ay = top + 4 + off;
            g.fill(x + 12, ay, x + W - 12, ay + 22, 0x44AA2222);
            g.fill(x + 12, ay, x + 15, ay + 22, 0xFF000000 | WFUi.BAD);
            g.drawString(font, font.plainSubstrByWidth("Засада #" + (long) dbl(a, "id") + ": груз у разбойников (" + (long) dbl(a, "strength") + " чел.) на пути " + str(a, "from") + " → " + str(a, "to")
                    + " · отбить отрядом до дня " + (long) dbl(a, "expires") + " (/wftrade ambush - где)", W - 50), x + 20, ay + 7, WFUi.TX);
            off += 24;
        }
        top += off; bodyH -= off;
        if (caravans.isEmpty()) { wrap(g, "Караванов нет. Откройте вкладку «Товары», выберите товар и отправьте караван в соседнее государство, где он дороже.", x + 18, top + 8, W - 50, WFUi.MUTED); return; }
        for (int i = 0; i < caravans.size() && (i + 1) * 40 + 4 <= bodyH; i++) {
            Map<String, Object> c = caravans.get(caravans.size() - 1 - i);
            int ry = top + 4 + i * 40;
            WFUi.rowBg(g, x + 12, ry, W - 24, 38, i, false, WFUi.over(mx, my, x + 12, ry, W - 24, 38));
            String status = str(c, "status");
            int col = status.equals("в пути") ? WFUi.INFO : status.startsWith("доставлен") ? WFUi.GOOD : WFUi.BAD;
            g.fill(x + 12, ry, x + 15, ry + 38, 0xFF000000 | col);
            g.drawString(font, "№" + (long) dbl(c, "id") + "  " + str(c, "from") + " → " + str(c, "to") + (Boolean.TRUE.equals(c.get("mine")) ? "  (ваш)" : ""), x + 20, ry + 4, WFUi.TX);
            StringBuilder sb = new StringBuilder();
            @SuppressWarnings("unchecked") Map<String, Object> cargo = c.get("cargo") instanceof Map ? (Map<String, Object>) c.get("cargo") : Map.of();
            for (var e : cargo.entrySet()) sb.append(e.getKey()).append(" x").append(e.getValue() instanceof Number nn ? String.valueOf(nn.longValue()) : e.getValue()).append(' ');
            g.drawString(font, font.plainSubstrByWidth(sb + " · охрана " + (long) dbl(c, "guards") + " · затраты " + (long) dbl(c, "spent") + " · выручка " + (long) dbl(c, "revenue"), W - 300), x + 20, ry + 16, WFUi.MUTED);
            WFUi.badge(g, font, x + W - 200, ry + 4, status, 0xFF000000 | col);
            if (status.equals("в пути")) WFUi.bar(g, x + 20, ry + 29, W - 60, 5, 1.0 - dbl(c, "left") / Math.max(1, dbl(c, "total")), WFUi.INFO);
        }
    }

    private void policyTab(GuiGraphics g, int x, int top, int bodyH, int mx, int my) {
        int lw = 360;
        WFUi.bevel(g, x + 10, top, lw, bodyH, true);
        int yy = top + 8;
        g.drawString(font, "ПОШЛИНА НА ВВОЗ", x + 18, yy, WFUi.GOLD_DIM); yy += 14;
        g.drawString(font, (int) tariff + "%  (чужие купцы платят её в казну; контрабанда втрое)", x + 18, yy, WFUi.TX); yy += 14;
        for (int i = 0, bx = x + 18; i < 5; i++) {
            int v = new int[]{0, 5, 10, 20, 30}[i];
            button(g, bx, yy, 54, v + "%", true, mx, my, () -> cmd.accept("wfaction run trade_tariff " + realm + " " + v), "Установить пошлину " + v + "% (правитель)");
            bx += 58;
        }
        yy += 28;
        g.drawString(font, "ЭМБАРГО", x + 18, yy, WFUi.GOLD_DIM); yy += 14;
        if (neighbors.isEmpty()) g.drawString(font, "Соседей нет.", x + 18, yy, WFUi.MUTED);
        for (String n : neighbors) {
            boolean on = embargo.contains(n);
            g.drawString(font, font.plainSubstrByWidth(n, 160), x + 18, yy + 3, on ? WFUi.BAD : WFUi.TX);
            button(g, x + 200, yy, 110, on ? "Снять" : "Закрыть рынок", true, mx, my, () -> cmd.accept("wfaction run trade_embargo " + realm + " " + n + "|" + (on ? "off" : "on")), on ? "Снять эмбарго" : "Запретить торговлю с " + n);
            yy += 20;
            if (yy > top + bodyH - 20) break;
        }
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH, true);
        yy = top + 8;
        g.drawString(font, "ТОРГОВЫЙ ДОХОД КАЗНЫ", rx + 10, yy, WFUi.GOLD_DIM); yy += 14;
        String[] names = {"Пошлины", "Налог гильдии", "Оборот рынка"};
        for (int i = 0; i < 3 && i < income.size(); i++) { g.drawString(font, names[i], rx + 10, yy, WFUi.MUTED); String v = String.valueOf(income.get(i) instanceof Number n ? n.longValue() : 0); g.drawString(font, v, rx + rw - 10 - font.width(v), yy, WFUi.TX); yy += 12; }
        yy += 8;
        g.drawString(font, "Капитал купцов: " + capital, rx + 10, yy, WFUi.TX); yy += 14;
        wrap(g, "Купцы государства возят товар сами, пока есть выгода, и отдают 5% прибыли казне. Игрок-правитель может выключить их действием «trade_auto».", rx + 10, yy, rw - 20, WFUi.MUTED);
        button(g, rx + 10, top + bodyH - 22, 130, "Купцы: вкл/выкл", true, mx, my, () -> cmd.accept("wfaction run trade_auto " + realm + " off"), "Остановить купцов государства");
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { if (tab == 0) { scroll -= (int) Math.signum(d) * 2; return true; } return super.mouseScrolled(mx, my, d); }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (h.run != null && WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h)) { h.run.run(); return true; } }
        return super.mouseClicked(mx, my, b);
    }
}
