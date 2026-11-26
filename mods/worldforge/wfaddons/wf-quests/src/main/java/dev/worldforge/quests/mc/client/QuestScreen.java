package dev.worldforge.quests.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Журнал заданий: вкладки «Задания», «События», «Хроника», «Права». Слева список, справа подробности и выборы. */
public class QuestScreen extends Screen {
    private static final int W = 760, H = 450;
    private static final String[] TABS = {"Задания", "События", "Хроника", "Права"};
    private List<Map<String, Object>> active = new ArrayList<>(), events = new ArrayList<>();
    private List<String> history = new ArrayList<>(), rights = new ArrayList<>();
    private int done, day, sel, tab, refresh, scroll;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public QuestScreen(String payload, Consumer<String> cmd) {
        super(Component.literal("Журнал"));
        this.cmd = cmd;
        setModel(payload);
    }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
        active = new ArrayList<>(); events = new ArrayList<>(); history = new ArrayList<>(); rights = new ArrayList<>();
        for (Object o : list(m, "active")) active.add((Map<String, Object>) o);
        for (Object o : list(m, "events")) events.add((Map<String, Object>) o);
        for (Object o : list(m, "history")) history.add(String.valueOf(o));
        for (Object o : list(m, "rights")) rights.add(String.valueOf(o));
        done = num(m.get("done")); day = num(m.get("day"));
        if (sel >= Math.max(active.size(), events.size())) sel = 0;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : List.of(); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0) cmd.accept("wfquest"); }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int color) {
        for (var l : font.split(Component.literal(s), w)) { g.drawString(font, l, x, y, color); y += 10; }
        return y;
    }

    private static int tierColor(int t) { return t >= 4 ? 0xFFE0662A : t == 3 ? WFUi.GOLD : t == 2 ? WFUi.INFO : WFUi.MUTED; }
    private static String tierName(int t) { return switch (t) { case 1 -> "Поручение"; case 2 -> "Задание"; case 3 -> "Важное дело"; default -> "Государственное"; }; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Журнал  ·  выполнено: " + done + "  ·  день " + day);
        WFUi.tabs(g, font, x + 10, y + 22, TABS, tab, mx, my);
        for (int i = 0, cx = x + 10; i < TABS.length; i++) { int w = font.width(TABS[i]) + 16; final int t = i; hits.add(new Hit(cx, y + 22, w, 15, () -> { tab = t; sel = 0; }, null)); cx += w + 2; }
        if (!events.isEmpty()) WFUi.badge(g, font, x + W - 150, y + 25, "Ждут решения: " + events.size(), WFUi.BAD);
        int top = y + 42, bodyH = H - 52;
        switch (tab) {
            case 0 -> tasks(g, x, top, bodyH, mx, my);
            case 1 -> eventsTab(g, x, top, bodyH, mx, my);
            case 2 -> {
                WFUi.bevel(g, x + 10, top, W - 20, bodyH, true);
                if (history.isEmpty()) g.drawString(font, "Пока ничего не произошло.", x + 18, top + 8, WFUi.MUTED);
                for (int i = 0; i < history.size() && 8 + i * 11 < bodyH - 8; i++) g.drawString(font, font.plainSubstrByWidth(history.get(history.size() - 1 - i), W - 40), x + 18, top + 8 + i * 11, i == 0 ? WFUi.TX : WFUi.MUTED);
            }
            default -> {
                WFUi.bevel(g, x + 10, top, W - 20, bodyH, true);
                int yy = top + 8;
                g.drawString(font, "Права, выданные заданиями и государством", x + 18, yy, WFUi.GOLD_DIM); yy += 14;
                if (rights.isEmpty()) wrap(g, "Прав пока нет. Выполняйте поручения правителей и орденов: грамоты дают право создавать собственные задания и организации.", x + 18, yy, W - 50, WFUi.MUTED);
                for (String r : rights) { g.drawString(font, "• " + rightName(r), x + 18, yy, WFUi.GOOD); yy += 12; }
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; }
        }
        super.render(g, mx, my, pt);
    }

    private static String rightName(String r) {
        return switch (r) {
            case "create_quest" -> "создавать собственные задания";
            case "create_org" -> "основывать организации";
            default -> r;
        };
    }

    @SuppressWarnings("unchecked")
    private void tasks(GuiGraphics g, int x, int top, int bodyH, int mx, int my) {
        int lw = 250;
        WFUi.bevel(g, x + 10, top, lw, bodyH, true);
        if (active.isEmpty()) wrap(g, "Нет активных заданий. Поговорите с жителями, чиновниками, военными, главами орденов - или загляните на доску объявлений.", x + 18, top + 8, lw - 16, WFUi.MUTED);
        int rowH = 34;
        for (int i = 0; i < active.size() && (i + 1) * rowH + 4 <= bodyH; i++) {
            Map<String, Object> q = active.get(i);
            int ry = top + 3 + i * rowH;
            WFUi.rowBg(g, x + 12, ry, lw - 6, rowH - 2, i, i == sel, WFUi.over(mx, my, x + 12, ry, lw - 6, rowH - 2));
            g.fill(x + 12, ry, x + 15, ry + rowH - 2, 0xFF000000 | tierColor(num(q.get("tier"))));
            g.drawString(font, font.plainSubstrByWidth(str(q, "name"), lw - 20), x + 20, ry + 4, WFUi.TX);
            int left = num(q.get("deadline"));
            g.drawString(font, font.plainSubstrByWidth(str(q, "giver") + (left >= 0 ? "  ·  дней: " + left : ""), lw - 20), x + 20, ry + 18, left >= 0 && left <= 3 ? WFUi.BAD : WFUi.MUTED);
            final int k = i;
            hits.add(new Hit(x + 12, ry, lw - 6, rowH - 2, () -> sel = k, null));
        }
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH, true);
        if (active.isEmpty()) return;
        Map<String, Object> q = active.get(sel);
        int yy = top + 8;
        g.drawString(font, str(q, "name"), rx + 10, yy, WFUi.GOLD);
        WFUi.badge(g, font, rx + rw - 110, yy - 2, tierName(num(q.get("tier"))), 0xFF000000 | tierColor(num(q.get("tier"))));
        yy += 14;
        g.drawString(font, "Поручил: " + str(q, "giver"), rx + 10, yy, WFUi.MUTED); yy += 14;
        yy = wrap(g, str(q, "desc"), rx + 10, yy, rw - 20, WFUi.MUTED) + 8;
        g.drawString(font, "СЕЙЧАС", rx + 10, yy, WFUi.GOLD_DIM); yy += 11;
        yy = wrap(g, str(q, "now"), rx + 10, yy, rw - 20, WFUi.TX);
        String prog = str(q, "progress");
        if (!prog.isEmpty()) {
            String[] pq = prog.split("/");
            double frac = pq.length == 2 && !pq[1].isEmpty() && Integer.parseInt(pq[1]) > 0 ? Math.min(1, Integer.parseInt(pq[0]) / (double) Integer.parseInt(pq[1])) : 0;
            WFUi.bar(g, rx + 10, yy + 2, rw - 20, 8, frac, WFUi.GOOD);
            g.drawString(font, prog, rx + rw - 10 - font.width(prog), yy + 12, WFUi.MUTED);
            yy += 20;
        }
        yy += 6;
        List<Object> opts = list(q, "options");
        int qid = num(q.get("id"));
        if (!opts.isEmpty()) {
            g.drawString(font, "ВАШ ВЫБОР", rx + 10, yy, WFUi.GOLD_DIM); yy += 12;
            for (Object oo : opts) {
                Map<String, Object> o = (Map<String, Object>) oo;
                boolean ok = Boolean.TRUE.equals(o.get("ok"));
                String id = str(o, "id");
                int lines = font.split(Component.literal(str(o, "text")), rw - 40).size();
                int h = 6 + lines * 10;
                WFUi.bevel(g, rx + 10, yy, rw - 20, h, !ok);
                if (WFUi.over(mx, my, rx + 10, yy, rw - 20, h) && ok) g.fill(rx + 12, yy + 2, rx + rw - 12, yy + h - 2, WFUi.HOVER);
                wrap(g, str(o, "text"), rx + 16, yy + 4, rw - 32, ok ? WFUi.TX : WFUi.OFF);
                hits.add(new Hit(rx + 10, yy, rw - 20, h, ok ? () -> cmd.accept("wfquest choose " + qid + " " + id) : null, ok ? null : "Условия не выполнены"));
                yy += h + 4;
            }
        }
        List<Object> log = list(q, "log");
        if (!log.isEmpty()) {
            yy += 4;
            g.drawString(font, "ХОД ДЕЛА", rx + 10, yy, WFUi.GOLD_DIM); yy += 11;
            for (int i = Math.max(0, log.size() - 4); i < log.size(); i++) yy = wrap(g, "• " + log.get(i), rx + 10, yy, rw - 20, WFUi.MUTED);
        }
        int by = top + bodyH - 22;
        g.drawString(font, "Награда: " + str(q, "reward"), rx + 10, by + 4, WFUi.GOLD);
        button(g, rx + rw - 110, by, 100, "Отказаться", true, mx, my, () -> cmd.accept("wfquest abandon " + qid), "Задание будет снято, награды не будет");
    }

    @SuppressWarnings("unchecked")
    private void eventsTab(GuiGraphics g, int x, int top, int bodyH, int mx, int my) {
        WFUi.bevel(g, x + 10, top, W - 20, bodyH, true);
        if (events.isEmpty()) { g.drawString(font, "Нерешённых событий нет. Правителю государства они приходят сюда.", x + 18, top + 8, WFUi.MUTED); return; }
        int yy = top + 8;
        int first = Math.min(scroll, events.size() - 1);
        for (int ei = first; ei < events.size(); ei++) {
            Map<String, Object> e = events.get(ei);
            int need = 28 + 14 * list(e, "options").size() + 20;
            if (yy + need > top + bodyH - 14) { g.drawString(font, "Ещё событий: " + (events.size() - ei) + "  (колесо мыши)", x + 18, top + bodyH - 12, WFUi.MUTED); break; }
            int fid = num(e.get("id"));
            g.drawString(font, str(e, "name"), x + 18, yy, WFUi.GOLD);
            String left = "дней на решение: " + num(e.get("left"));
            g.drawString(font, left, x + W - 28 - font.width(left), yy, num(e.get("left")) <= 2 ? WFUi.BAD : WFUi.MUTED);
            yy = wrap(g, str(e, "text"), x + 18, yy + 12, W - 50, WFUi.TX) + 4;
            for (Object oo : list(e, "options")) {
                Map<String, Object> o = (Map<String, Object>) oo;
                boolean ok = Boolean.TRUE.equals(o.get("ok"));
                String id = str(o, "id");
                int h = 6 + font.split(Component.literal(str(o, "text")), W - 70).size() * 10;
                WFUi.bevel(g, x + 18, yy, W - 46, h, !ok);
                if (ok && WFUi.over(mx, my, x + 18, yy, W - 46, h)) g.fill(x + 20, yy + 2, x + W - 30, yy + h - 2, WFUi.HOVER);
                wrap(g, str(o, "text"), x + 24, yy + 4, W - 58, ok ? WFUi.TX : WFUi.OFF);
                hits.add(new Hit(x + 18, yy, W - 46, h, ok ? () -> cmd.accept("wfquest resolve " + fid + " " + id) : null, ok ? null : "Условия не выполнены"));
                yy += h + 4;
            }
            yy += 10;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (tab == 1) { scroll = Math.max(0, Math.min(events.size() - 1, scroll - (int) Math.signum(d))); return true; }
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h) && h.run != null) { h.run.run(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }
}
