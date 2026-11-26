package dev.worldforge.relations.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFArt;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно «Отношения»: все знакомые с сердцами, статусом (пара, брак, служба), открытыми просьбами и чертами. */
public class RelationsScreen extends Screen {
    private static final int W = 700, H = 420;
    private List<Map<String, Object>> rows = new ArrayList<>();
    private int day, sel, scroll, filter, refresh;
    private String partner = "";
    private final Consumer<String> cmd;
    private static final String[] FILTERS = {"Все", "Друзья", "Служба", "Просьбы"};

    private record Hit(int x, int y, int w, int h, Runnable run) {}
    private final List<Hit> hits = new ArrayList<>();

    public RelationsScreen(String payload, Consumer<String> cmd) {
        super(Component.literal("Отношения"));
        this.cmd = cmd;
        setModel(payload);
    }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
        rows = new ArrayList<>();
        for (Object o : (List<Object>) m.get("rows")) rows.add((Map<String, Object>) o);
        day = num(m.get("day"));
        partner = String.valueOf(m.get("partner"));
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }
    private static boolean flag(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() { if (++refresh % 60 == 0) cmd.accept("wfrel"); }

    private List<Map<String, Object>> shown() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            boolean ok = switch (filter) { case 1 -> num(r.get("aff")) >= 30; case 2 -> flag(r, "employed"); case 3 -> r.get("req") != null; default -> true; };
            if (ok) out.add(r);
        }
        return out;
    }

    private static int affColor(int a) { return a >= 30 ? WFUi.GOOD : a <= -25 ? WFUi.BAD : WFUi.MUTED; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Отношения  ·  знакомых: " + rows.size());
        int fx = x + 10;
        for (int i = 0; i < FILTERS.length; i++) {
            final int f = i;
            int w = WFUi.chip(g, font, fx, y + 24, FILTERS[i], filter == i, WFUi.over(mx, my, fx, y + 24, 60, 14));
            hits.add(new Hit(fx, y + 24, w, 14, () -> { filter = f; sel = 0; scroll = 0; }));
            fx += w + 4;
        }
        List<Map<String, Object>> list = shown();
        int top = y + 44, lh = H - 54, lw = 280;
        WFUi.bevel(g, x + 10, top, lw, lh, true);
        int rowH = 30, vis = (lh - 6) / rowH;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - vis)));
        if (sel >= list.size()) sel = Math.max(0, list.size() - 1);
        if (list.isEmpty()) g.drawString(font, "Пока никого. Поговорите с жителями.", x + 18, top + 10, WFUi.MUTED);
        for (int i = 0; i < vis && i + scroll < list.size(); i++) {
            Map<String, Object> r = list.get(i + scroll);
            int ry = top + 3 + i * rowH, idx = i + scroll;
            WFUi.rowBg(g, x + 12, ry, lw - 6, rowH - 2, i, idx == sel, WFUi.over(mx, my, x + 12, ry, lw - 6, rowH - 2));
            WFArt.bust(g, x + 16, ry + 1, 20, 26, 0x777777, str(r, "id"), !"f".equals(str(r, "g")), 25, false);
            g.drawString(font, str(r, "name"), x + 42, ry + 3, WFUi.TX);
            g.drawString(font, str(r, "hearts"), x + 42, ry + 15, 0xFFE06070);
            String tag = flag(r, "married") ? "Брак" : flag(r, "dating") ? "Пара" : flag(r, "employed") ? "Служба" : r.get("req") != null ? "Просьба" : "";
            if (!tag.isEmpty()) WFUi.badge(g, font, x + lw - 52, ry + 8, tag, flag(r, "married") || flag(r, "dating") ? 0xFFE06070 : WFUi.GOLD);
            final int k = idx;
            hits.add(new Hit(x + 12, ry, lw - 6, rowH - 2, () -> sel = k));
        }
        int rx = x + lw + 18, rw = W - lw - 28;
        WFUi.bevel(g, rx, top, rw, lh, true);
        if (!list.isEmpty()) detail(g, list.get(sel), rx + 10, top + 8, rw - 20, mx, my);
        super.render(g, mx, my, pt);
    }

    @SuppressWarnings("unchecked")
    private void detail(GuiGraphics g, Map<String, Object> r, int x, int y, int w, int mx, int my) {
        WFArt.bust(g, x, y, 48, 60, 0x777777, str(r, "id"), !"f".equals(str(r, "g")), 25, false);
        g.drawString(font, str(r, "name") + (str(r, "house").isEmpty() ? "" : " " + str(r, "house")), x + 58, y + 2, WFUi.GOLD);
        g.drawString(font, str(r, "role").isEmpty() ? "Житель" : str(r, "role"), x + 58, y + 14, WFUi.MUTED);
        g.drawString(font, str(r, "hearts") + "  " + str(r, "mood"), x + 58, y + 28, affColor(num(r.get("aff"))));
        int by = y + 70;
        g.drawString(font, "Симпатия " + num(r.get("aff")), x, by, WFUi.TX);
        WFUi.bar(g, x + 110, by + 1, w - 110, 7, (num(r.get("aff")) + 100) / 200.0, affColor(num(r.get("aff"))));
        by += 16;
        g.drawString(font, "Любовь " + num(r.get("love")), x, by, WFUi.TX);
        WFUi.bar(g, x + 110, by + 1, w - 110, 7, num(r.get("love")) / 100.0, 0xFFE06070);
        by += 20;
        if (flag(r, "married")) by = line(g, x, by, "Вы состоите в браке.", 0xFFE06070);
        else if (flag(r, "dating")) by = line(g, x, by, "Вы встречаетесь. Для свадьбы нужны симпатия и любовь 60+.", 0xFFE06070);
        if (flag(r, "employed")) {
            by = line(g, x, by, "На вашей службе, жалованье: " + num(r.get("wage")) + ".", WFUi.GOLD);
            int bx = x, bw = 130;
            WFUi.button(g, font, bx, by, bw, 15, "Освободить от службы", true, false, WFUi.over(mx, my, bx, by, bw, 15));
            final String id = str(r, "id");
            hits.add(new Hit(bx, by, bw, 15, () -> cmd.accept("wfrel dismiss " + id)));
            by += 22;
        }
        List<Object> tr = (List<Object>) r.getOrDefault("traits", List.of());
        if (!tr.isEmpty()) {
            g.drawString(font, "Характер", x, by, WFUi.GOLD_DIM); by += 12;
            for (Object o : tr) {
                var t = dev.worldforge.people.CharacterStats.trait(String.valueOf(o));
                if (t == null) continue;
                WFUi.badge(g, font, x, by, t.name, t.color);
                for (var l : font.split(Component.literal(t.desc), w - 110)) { g.drawString(font, l, x + 100, by, WFUi.MUTED); by += 11; }
                by += 5;
            }
        }
        Map<String, Object> q = (Map<String, Object>) r.get("req");
        if (q != null) {
            by += 6;
            g.drawString(font, "Просьба", x, by, WFUi.GOLD); by += 12;
            by = line(g, x, by, str(q, "text"), WFUi.TX);
            g.drawString(font, "Нужно: " + num(q.get("n")) + " × " + str(q, "item") + "   Осталось дней: " + num(q.get("left")), x, by, WFUi.MUTED);
        }
        g.drawString(font, "Поговорите с человеком лично, чтобы улучшить отношения.", x, y + 330, WFUi.MUTED);
    }

    private int line(GuiGraphics g, int x, int y, String s, int color) {
        for (var l : font.split(Component.literal(s), 300)) { g.drawString(font, l, x, y, color); y += 11; }
        return y + 3;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h)) { h.run.run(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) { scroll = Math.max(0, scroll - (int) Math.signum(d)); return true; }
}
