package dev.worldforge.forms.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно «Состояния»: слева список форм (вампиризм, оборотничество...), справа ступени, потребность, самообладание, слабости, лечение и кнопки облика. */
public class FormsScreen extends Screen {
    private static final int W = 700, H = 420;
    private List<Map<String, Object>> forms = new ArrayList<>();
    private List<String> chronicle = new ArrayList<>();
    private int sel, refresh;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public FormsScreen(String payload, Consumer<String> cmd) { super(Component.literal("Состояния")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
        forms = new ArrayList<>(); chronicle = new ArrayList<>();
        for (Object o : list(m, "forms")) forms.add((Map<String, Object>) o);
        for (Object o : list(m, "chronicle")) chronicle.add(String.valueOf(o));
        if (sel >= forms.size()) sel = 0;
    }

    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }
    private static double dbl(Map<String, Object> m, String k) { return m.get(k) instanceof Number n ? n.doubleValue() : 0; }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : List.of(); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0) cmd.accept("wfform"); }

    private static int kindColor(String k) { return switch (k) { case "curse" -> WFUi.BAD; case "blessing" -> WFUi.GOOD; default -> WFUi.GOLD; }; }
    private static String kindName(String k) { return switch (k) { case "curse" -> "Проклятье"; case "blessing" -> "Благословение"; case "bloodline" -> "Родовая кровь"; default -> k; }; }
    private static String weakName(String w) { return switch (w) { case "sun" -> "Солнце"; case "holy" -> "Святое место"; case "silver" -> "Серебро"; case "water" -> "Вода"; case "dark" -> "Тьма"; default -> w; }; }
    private static String modName(String k) {
        return switch (k) {
            case "max_health" -> "здоровье"; case "melee_dmg_pct" -> "урон ближний %"; case "crit_pct" -> "крит %"; case "stealth_pct" -> "скрытность %";
            case "health_regen" -> "регенерация"; case "heal_pct" -> "лечение %"; case "move_speed_pct" -> "скорость %"; case "armor" -> "броня";
            case "resist_pct" -> "сопротивление %"; case "dodge_pct" -> "уклонение %"; case "luck" -> "удача"; case "opinion" -> "отношение";
            case "levy_pct" -> "набор %"; case "xp_gain_pct" -> "опыт %"; case "fish_pct" -> "рыбалка %"; case "trade_profit_pct" -> "торговля %"; case "carry" -> "ноша";
            default -> k;
        };
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int color) {
        for (var l : font.split(Component.literal(s), w)) { g.drawString(font, l, x, y, color); y += 10; }
        return y;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Состояния  ·  превращения и родовая кровь");
        int top = y + 24, bodyH = H - 34, lw = 210;
        WFUi.bevel(g, x + 10, top, lw, bodyH - 90, true);
        if (forms.isEmpty()) wrap(g, "Вы здоровы. Укусы вампиров и оборотней, проклятые гробницы, драконья родословная и чудеса могут изменить это.", x + 18, top + 8, lw - 16, WFUi.MUTED);
        for (int i = 0; i < forms.size() && (i + 1) * 34 + 4 <= bodyH - 90; i++) {
            Map<String, Object> f = forms.get(i);
            int ry = top + 3 + i * 34;
            WFUi.rowBg(g, x + 12, ry, lw - 6, 32, i, i == sel, WFUi.over(mx, my, x + 12, ry, lw - 6, 32));
            g.fill(x + 12, ry, x + 15, ry + 32, 0xFF000000 | kindColor(str(f, "kind")));
            g.drawString(font, font.plainSubstrByWidth(str(f, "name"), lw - 20), x + 20, ry + 4, WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(str(f, "stageName") + (Boolean.TRUE.equals(f.get("active")) ? "  ·  ОБЛИК" : ""), lw - 20), x + 20, ry + 18, Boolean.TRUE.equals(f.get("active")) ? WFUi.BAD : WFUi.MUTED);
            final int k = i;
            hits.add(new Hit(x + 12, ry, lw - 6, 32, () -> sel = k, null));
        }
        // хроника
        int cy = top + bodyH - 86;
        WFUi.bevel(g, x + 10, cy, lw, 86, true);
        g.drawString(font, "ХРОНИКА", x + 18, cy + 5, WFUi.GOLD_DIM);
        for (int i = 0; i < chronicle.size() && i < 6; i++) g.drawString(font, font.plainSubstrByWidth(chronicle.get(chronicle.size() - 1 - i), lw - 16), x + 18, cy + 17 + i * 11, i == 0 ? WFUi.TX : WFUi.MUTED);

        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH, true);
        if (forms.isEmpty()) { super.render(g, mx, my, pt); return; }
        Map<String, Object> f = forms.get(sel);
        int yy = top + 8;
        g.drawString(font, str(f, "name"), rx + 10, yy, WFUi.GOLD);
        WFUi.badge(g, font, rx + rw - 120, yy - 2, kindName(str(f, "kind")), 0xFF000000 | kindColor(str(f, "kind")));
        yy += 14;
        yy = wrap(g, str(f, "desc"), rx + 10, yy, rw - 20, WFUi.MUTED) + 6;
        // ступени
        List<Object> st = list(f, "stages");
        int cur = (int) dbl(f, "stage");
        g.drawString(font, "СТУПЕНИ", rx + 10, yy, WFUi.GOLD_DIM); yy += 11;
        int sx = rx + 10;
        for (int i = 0; i < st.size(); i++) {
            String nm = String.valueOf(st.get(i));
            int w = font.width(nm) + 12;
            WFUi.badge(g, font, sx, yy, nm, i < cur ? WFUi.GOOD : i == cur ? WFUi.GOLD : WFUi.MUTED);
            sx += w + 6;
        }
        yy += 16;
        WFUi.bar(g, rx + 10, yy, rw - 20, 8, dbl(f, "frac"), kindColor(str(f, "kind")));
        yy += 16;
        if (f.containsKey("need")) {
            boolean starving = Boolean.TRUE.equals(f.get("starving"));
            g.drawString(font, str(f, "need") + (starving ? "  -  ГОЛОД" : ""), rx + 10, yy, starving ? WFUi.BAD : WFUi.TX); yy += 11;
            WFUi.bar(g, rx + 10, yy, rw - 20, 8, dbl(f, "needFrac"), starving ? WFUi.BAD : WFUi.GOOD); yy += 16;
        }
        if (f.containsKey("control")) {
            g.drawString(font, "Самообладание: " + (int) dbl(f, "control") + " / " + (int) dbl(f, "controlMax") + "  (добровольный облик от " + (int) dbl(f, "voluntary") + ")", rx + 10, yy, WFUi.TX); yy += 11;
            WFUi.bar(g, rx + 10, yy, rw - 20, 8, dbl(f, "control") / Math.max(1, dbl(f, "controlMax")), WFUi.INFO); yy += 16;
        }
        // поправки
        Map<String, Object> md = f.get("mods") instanceof Map ? (Map<String, Object>) f.get("mods") : Map.of();
        if (!md.isEmpty()) {
            g.drawString(font, "ДАЁТ СЕЙЧАС", rx + 10, yy, WFUi.GOLD_DIM); yy += 11;
            int cx = rx + 10;
            for (var e : md.entrySet()) {
                double v = e.getValue() instanceof Number n ? n.doubleValue() : 0;
                String t = modName(e.getKey()) + " " + (v > 0 ? "+" : "") + (Math.abs(v) < 1 && v != 0 ? String.format("%.2f", v) : String.valueOf((int) v));
                int w = font.width(t) + 12;
                if (cx + w > rx + rw - 10) { cx = rx + 10; yy += 14; }
                WFUi.badge(g, font, cx, yy, t, v >= 0 ? WFUi.GOOD : WFUi.BAD);
                cx += w + 4;
            }
            yy += 18;
        }
        List<Object> weak = list(f, "weak");
        if (!weak.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object w : weak) sb.append(weakName(String.valueOf(w))).append(", ");
            g.drawString(font, "Сейчас вредит: " + sb.substring(0, sb.length() - 2), rx + 10, yy, WFUi.BAD); yy += 12;
        }
        yy = wrap(g, "Лечение: " + str(f, "cure"), rx + 10, yy, rw - 20, WFUi.MUTED) + 6;
        // кнопки
        int by = top + bodyH - 24;
        String id = str(f, "id");
        boolean active = Boolean.TRUE.equals(f.get("active"));
        boolean canShift = !f.containsKey("control") || dbl(f, "control") >= dbl(f, "voluntary");
        button(g, rx + 10, by, 130, active ? "Вернуть облик" : "Принять облик", active || canShift, mx, my, () -> cmd.accept("wfform shift " + id + (active ? " off" : " on")),
                active ? "Вернуться в человеческий облик" : canShift ? str(f, "shiftName") : "Не хватает самообладания");
        if (f.containsKey("need")) button(g, rx + 150, by, 110, "Утолить", true, mx, my, () -> cmd.accept("wfform feed " + id), "Тратит гнилую плоть или говядину");
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; }
        }
        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (h.run != null && WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h)) { h.run.run(); return true; } }
        return super.mouseClicked(mx, my, b);
    }
}
