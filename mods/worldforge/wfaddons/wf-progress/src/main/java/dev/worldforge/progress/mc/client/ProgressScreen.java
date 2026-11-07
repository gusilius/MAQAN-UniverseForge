package dev.worldforge.progress.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно прокачки: дерево пассивных умений (перетаскивание и колесо), атрибуты, навыки по группам и сводка статистик. */
public class ProgressScreen extends Screen {
    private static final int W = 860, H = 500;
    private static final String[] TABS = {"Дерево умений", "Атрибуты", "Навыки", "Статистика"};
    private Map<String, Object> d, pf;
    private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
    private final Set<String> have = new HashSet<>(), can = new HashSet<>();
    private int tab, refresh;
    private double camX, camY, zoom = 1.0;
    private boolean centered, dragging;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String title, List<String> tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public ProgressScreen(String defs, String profile, Consumer<String> cmd) {
        super(Component.literal("Прокачка"));
        this.cmd = cmd;
        setDefs(defs);
        setModel(profile);
    }

    @SuppressWarnings("unchecked")
    private void setDefs(String defs) {
        d = defs == null || defs.isEmpty() ? Map.of("attrs", List.of(), "skills", List.of(), "nodes", List.of(), "archetypes", List.of(), "stats", List.of()) : (Map<String, Object>) Json.parse(defs);
        nodes.clear();
        for (Object o : list(d, "nodes")) { Map<String, Object> n = (Map<String, Object>) o; nodes.put(str(n, "id"), n); }
    }

    @SuppressWarnings("unchecked")
    public void setModel(String profile) {
        pf = (Map<String, Object>) Json.parse(profile);
        have.clear(); can.clear();
        for (Object o : list(pf, "nodes")) have.add(String.valueOf(o));
        for (Object o : list(pf, "allocatable")) can.add(String.valueOf(o));
    }

    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : List.of(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0) cmd.accept("wfprog"); }

    private String statName(String k) {
        String base = k.endsWith("_pct") ? k.substring(0, k.length() - 4) : k;
        for (String key : new String[]{k, base})
            for (Object o : list(d, "stats")) { @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) o; if (str(s, "id").equals(key)) return str(s, "name").replace(", %", ""); }
        for (Object o : list(d, "attrs")) { @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) o; if (str(s, "id").equals(base)) return str(s, "name"); }
        return base;
    }

    private String modText(Map<String, Object> mods) {
        List<String> out = new ArrayList<>();
        for (var e : mods.entrySet()) {
            double v = dbl(e.getValue());
            String num = (Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((int) v) : String.valueOf(v));
            out.add((v >= 0 ? "+" : "") + num + (e.getKey().endsWith("_pct") ? "%" : "") + " " + statName(e.getKey()));
        }
        return String.join(", ", out);
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip == null ? null : tip, null));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Прокачка  ·  уровень " + num(pf.get("level")));
        // шапка: опыт и очки
        int xpw = 260, nx = num(pf.get("next"));
        WFUi.bar(g, x + 10, y + 26, xpw, 9, nx == 0 ? 1 : dbl(pf.get("xp")) / nx, WFUi.GOLD);
        g.drawString(font, nx == 0 ? "Максимальный уровень" : "Опыт " + num(pf.get("xp")) + " / " + nx, x + 16, y + 28, WFUi.TX);
        String pts = "Очки: атрибуты " + num(pf.get("attrFree")) + "   навыки " + num(pf.get("skillFree")) + "   умения " + num(pf.get("passiveFree"));
        g.drawString(font, pts, x + 285, y + 27, WFUi.GOLD);
        int tw = WFUi.tabs(g, font, x + 10, y + 40, 520, TABS, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hits.add(new Hit(x + 10 + i * tw, y + 40, tw, 15, () -> tab = t, null, null)); }
        int top = y + 60, h = H - 70;
        WFUi.bevel(g, x + 10, top, W - 20, h, true);
        switch (tab) {
            case 0 -> tree(g, x + 10, top, W - 20, h, mx, my);
            case 1 -> attrs(g, x + 18, top + 8, W - 36, h - 16, mx, my);
            case 2 -> skills(g, x + 18, top + 8, W - 36, h - 16, mx, my);
            default -> sheet(g, x + 18, top + 8, W - 36);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hh = hits.get(i);
            if ((hh.title != null) && WFUi.over(mx, my, hh.x, hh.y, hh.w, hh.h)) { WFUi.tooltip(g, font, mx, my, width, height, hh.title, hh.tip == null ? List.of() : hh.tip); break; }
        }
        super.render(g, mx, my, pt);
    }

    // ---------------------------------------------------------------- дерево

    private int sx(Map<String, Object> n, int cx) { return (int) Math.round(cx + (dbl(n.get("x")) * 34 - camX) * zoom); }
    private int sy(Map<String, Object> n, int cy) { return (int) Math.round(cy + (dbl(n.get("y")) * 34 - camY) * zoom); }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), n = Math.max(dx, dy);
        if (n == 0) return;
        for (int i = 0; i <= n; i += 1) { int px = x0 + (x1 - x0) * i / n, py = y0 + (y1 - y0) * i / n; g.fill(px, py, px + 2, py + 2, color); }
    }

    private void tree(GuiGraphics g, int x, int top, int w, int h, int mx, int my) {
        String arch = str(pf, "archetype");
        if (arch.isEmpty()) { chooseArchetype(g, x, top, w, h, mx, my); return; }
        int cx = x + w / 2, cy = top + h / 2;
        if (!centered) {
            Map<String, Object> st = null;
            for (Object o : list(d, "archetypes")) { @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) o; if (str(a, "id").equals(arch)) st = nodes.get(str(a, "start")); }
            if (st != null) { camX = dbl(st.get("x")) * 34; camY = dbl(st.get("y")) * 34; }
            zoom = 0.9; centered = true;
        }
        g.enableScissor(x + 1, top + 1, x + w - 1, top + h - 1);
        // рёбра
        for (Map<String, Object> n : nodes.values()) {
            for (Object lo : list(n, "links")) {
                String l = String.valueOf(lo);
                if (str(n, "id").compareTo(l) > 0) continue;
                Map<String, Object> o = nodes.get(l);
                if (o == null) continue;
                int x0 = sx(n, cx), y0 = sy(n, cy), x1 = sx(o, cx), y1 = sy(o, cy);
                if (Math.max(x0, x1) < x || Math.min(x0, x1) > x + w || Math.max(y0, y1) < top || Math.min(y0, y1) > top + h) continue;
                boolean a = have.contains(str(n, "id")), b = have.contains(l);
                int col = a && b ? 0xFFF0C85A : (a || b) ? 0xFF6E6A4A : 0xFF3A3C46;
                line(g, x0, y0, x1, y1, col);
            }
        }
        String hover = null;
        for (Map<String, Object> n : nodes.values()) {
            int px = sx(n, cx), py = sy(n, cy);
            if (px < x - 12 || px > x + w + 12 || py < top - 12 || py > top + h + 12) continue;
            String id = str(n, "id"), kind = str(n, "kind");
            int r = (int) Math.round((kind.equals("keystone") ? 9 : kind.equals("notable") ? 7 : kind.equals("start") ? 8 : 4) * Math.max(0.7, zoom));
            boolean got = have.contains(id), ok = can.contains(id);
            int fill = got ? 0xFFF0C85A : ok ? 0xFF4FCF5A : 0xFF2C2E38;
            int edge = got ? 0xFFFFE9A0 : ok ? 0xFF9BF0A0 : kind.equals("keystone") ? 0xFFB05050 : kind.equals("notable") ? 0xFF7A7C8A : 0xFF50525C;
            g.fill(px - r - 1, py - r - 1, px + r + 1, py + r + 1, edge);
            g.fill(px - r, py - r, px + r, py + r, fill);
            if (kind.equals("keystone")) g.fill(px - r / 2, py - r / 2, px + r / 2, py + r / 2, got ? 0xFF8A2A2A : 0xFF5A2A2A);
            if (WFUi.over(mx, my, px - r - 2, py - r - 2, 2 * r + 4, 2 * r + 4) && WFUi.over(mx, my, x, top, w, h)) hover = id;
        }
        g.disableScissor();
        if (hover != null) {
            Map<String, Object> n = nodes.get(hover);
            List<String> tip = new ArrayList<>();
            tip.add(modText(map(n, "mods")).isEmpty() ? "Стартовая точка пути" : modText(map(n, "mods")));
            if (!str(n, "desc").isEmpty()) tip.add(str(n, "desc"));
            if (num(n.get("level")) > 1) tip.add("Нужен уровень " + num(n.get("level")));
            tip.add(have.contains(hover) ? "ПКМ: снять (если держится дерево)" : can.contains(hover) ? "ЛКМ: изучить (1 очко)" : "Не соединён или не хватает очков/уровня");
            WFUi.tooltip(g, font, mx, my, width, height, str(n, "name") + "  [" + kindName(str(n, "kind")) + "]", tip);
        }
        g.drawString(font, "Перетаскивайте и крутите колесо. Свободных умений: " + num(pf.get("passiveFree")), x + 8, top + h - 12, WFUi.MUTED);
        if (num(pf.get("respec")) > 0) button(g, x + w - 168, top + h - 22, 160, "Сбросить всё (" + num(pf.get("respec")) + " зол.)", true, mx, my, () -> cmd.accept("wfprog respec"), "Вернёт все очки атрибутов, навыков и умений");
    }

    private static String kindName(String k) { return switch (k) { case "keystone" -> "ключевое"; case "notable" -> "значимое"; case "start" -> "старт"; default -> "малое"; }; }

    @SuppressWarnings("unchecked")
    private void chooseArchetype(GuiGraphics g, int x, int top, int w, int h, int mx, int my) {
        g.drawString(font, "Выберите путь. Вы сможете выучить умения и других путей, дойдя по мостам.", x + 20, top + 14, WFUi.GOLD);
        int cy = top + 40, col = 0;
        for (Object o : list(d, "archetypes")) {
            Map<String, Object> a = (Map<String, Object>) o;
            int bx = x + 20 + (col % 2) * (w / 2 - 10), by = cy + (col / 2) * 70;
            WFUi.bevel(g, bx, by, w / 2 - 30, 60, false);
            g.drawString(font, str(a, "name"), bx + 10, by + 8, WFUi.GOLD);
            g.drawString(font, font.plainSubstrByWidth(str(a, "desc"), w / 2 - 170), bx + 10, by + 22, WFUi.MUTED);
            String id = str(a, "id");
            button(g, bx + w / 2 - 130, by + 22, 90, "Выбрать", true, mx, my, () -> cmd.accept("wfprog archetype " + id), null);
            col++;
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (tab == 0 && button == 0 && dragging) { camX -= dx / zoom; camY -= dy / zoom; return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) { dragging = false; return super.mouseReleased(mx, my, button); }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == 0) { zoom = Math.max(0.4, Math.min(2.0, zoom * (delta > 0 ? 1.1 : 0.9))); return true; }
        if (tab == 1) { scrollA = Math.max(0, Math.min(maxScrollA, scrollA - (int) (delta * 24))); return true; }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hh = hits.get(i);
            if (button == 0 && WFUi.over((int) mx, (int) my, hh.x, hh.y, hh.w, hh.h) && hh.run != null) { hh.run.run(); return true; }
        }
        if (tab == 0 && !str(pf, "archetype").isEmpty()) {
            int x = (width - W) / 2 + 10, top = (height - H) / 2 + 60, w = W - 20, h = H - 70;
            int cx = x + w / 2, cy = top + h / 2;
            for (Map<String, Object> n : nodes.values()) {
                int px = sx(n, cx), py = sy(n, cy), r = (int) Math.round((str(n, "kind").equals("keystone") ? 9 : str(n, "kind").equals("notable") ? 7 : 4) * Math.max(0.7, zoom));
                if (WFUi.over((int) mx, (int) my, px - r - 2, py - r - 2, 2 * r + 4, 2 * r + 4)) {
                    String id = str(n, "id");
                    if (button == 0 && can.contains(id)) { cmd.accept("wfprog node " + id); return true; }
                    if (button == 1 && have.contains(id)) { cmd.accept("wfprog refund " + id); return true; }
                }
            }
            if (button == 0 && WFUi.over((int) mx, (int) my, x, top, w, h)) dragging = true;
        }
        return super.mouseClicked(mx, my, button);
    }

    // ---------------------------------------------------------------- атрибуты

    private static final String[] AGROUPS = {"body", "mind", "social", "court", "politics", "econ", "soul"};
    private static final String[] ATITLES = {"Тело", "Разум", "Общение", "Двор: дела государства", "Власть и право", "Хозяйство и ремесло", "Дух"};
    private int scrollA;

    @SuppressWarnings("unchecked")
    private void attrs(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        int colW = w / 3, cardH = 40;
        g.enableScissor(x - 6, y - 4, x + w + 6, y + h);
        int[] colY = {y - scrollA, y - scrollA, y - scrollA};
        int contentH = 0;
        for (int gi = 0; gi < AGROUPS.length; gi++) {
            int col = 0;
            for (int c = 1; c < 3; c++) if (colY[c] < colY[col]) col = c;
            int cx = x + col * colW, cy = colY[col];
            g.drawString(font, ATITLES[gi], cx, cy, WFUi.GOLD_DIM); cy += 13;
            for (Object o : list(d, "attrs")) {
                Map<String, Object> a = (Map<String, Object>) o;
                if (!str(a, "group").equals(AGROUPS[gi])) continue;
                int pts = num(map(pf, "attrs").get(str(a, "id"))), val = num(a.get("base")) + pts;
                WFUi.bevel(g, cx, cy, colW - 10, cardH - 3, false);
                g.fill(cx + 4, cy + 4, cx + 7, cy + cardH - 10, 0xFF000000 | num(a.get("color")));
                g.drawString(font, font.plainSubstrByWidth(str(a, "name") + "  " + val + (pts > 0 ? " (+" + pts + ")" : ""), colW - 70), cx + 12, cy + 4, WFUi.TX);
                g.drawString(font, font.plainSubstrByWidth(modText(map(a, "per")), colW - 70), cx + 12, cy + 15, WFUi.GOOD);
                g.drawString(font, font.plainSubstrByWidth(str(a, "desc"), colW - 26), cx + 12, cy + 25, WFUi.MUTED);
                String id = str(a, "id");
                if (cy > y - cardH && cy < y + h) button(g, cx + colW - 58, cy + 3, 40, "+1", num(pf.get("attrFree")) > 0, mx, my, () -> cmd.accept("wfprog attr " + id), null);
                cy += cardH;
            }
            colY[col] = cy + 6;
        }
        for (int c = 0; c < 3; c++) contentH = Math.max(contentH, colY[c] + scrollA - y);
        maxScrollA = Math.max(0, contentH - h + 8);
        g.disableScissor();
    }
    private int maxScrollA;

    // ---------------------------------------------------------------- навыки

    @SuppressWarnings("unchecked")
    private void skills(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        String[] groups = {"combat", "survival", "craft", "social", "realm"}, titles = {"Бой", "Выживание", "Ремесло", "Общение", "Государство"};
        int colW = w / 5;
        for (int gi = 0; gi < groups.length; gi++) {
            int cx = x + gi * colW, cy = y;
            g.drawString(font, titles[gi], cx, cy, WFUi.GOLD_DIM); cy += 13;
            for (Object o : list(d, "skills")) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (!str(s, "group").equals(groups[gi])) continue;
                String id = str(s, "id");
                int rank = num(map(pf, "skills").get(id)), max = num(s.get("max"));
                String why = str(map(pf, "skillWhy"), id);
                boolean ok = why.isEmpty();
                WFUi.bevel(g, cx, cy, colW - 6, 38, false);
                g.drawString(font, font.plainSubstrByWidth(str(s, "name"), colW - 50), cx + 5, cy + 4, rank > 0 ? WFUi.GOLD : WFUi.TX);
                for (int i = 0; i < max; i++) g.fill(cx + 5 + i * 8, cy + 17, cx + 11 + i * 8, cy + 23, i < rank ? 0xFFF0C85A : 0xFF3A3C46);
                List<String> tip = new ArrayList<>();
                tip.add(str(s, "desc")); tip.add("За ранг: " + modText(map(s, "per")));
                tip.add(rank >= max ? "Максимум" : ok ? "ЛКМ: изучить" : why);
                hits.add(new Hit(cx, cy, colW - 6, 38, ok ? () -> cmd.accept("wfprog skill " + id) : null, str(s, "name") + " " + rank + "/" + max, tip));
                g.drawString(font, rank >= max ? "макс." : ok ? "изучить" : "закрыто", cx + 5, cy + 26, rank >= max ? WFUi.MUTED : ok ? WFUi.GOOD : WFUi.OFF);
                cy += 41;
            }
        }
    }

    // ---------------------------------------------------------------- статистика

    @SuppressWarnings("unchecked")
    private void sheet(GuiGraphics g, int x, int y, int w) {
        Map<String, Object> sh = map(pf, "sheet");
        int cy = y, col = 0;
        g.drawString(font, "Итоговые характеристики", x, cy, WFUi.GOLD_DIM); cy += 14;
        int rowH = 13, per = 18;
        int i = 0;
        for (Object o : list(d, "stats")) {
            Map<String, Object> s = (Map<String, Object>) o;
            String id = str(s, "id");
            double v = dbl(sh.get(id)), pct = dbl(sh.get(id + "_pct"));
            int px = x + (i / per) * (w / 2), py = cy + (i % per) * rowH;
            String val = str(s, "fmt").equals("dec") ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v * 10) / 10.0).replace(".0", "");
            WFUi.kv(g, font, px, py, w / 2 - 20, 190, str(s, "name"), val + (pct != 0 ? "  (" + (pct > 0 ? "+" : "") + Math.round(pct * 10) / 10.0 + "%)" : ""), v > dbl(s.get("base")) ? WFUi.GOOD : WFUi.TX);
            i++;
        }
    }
}
