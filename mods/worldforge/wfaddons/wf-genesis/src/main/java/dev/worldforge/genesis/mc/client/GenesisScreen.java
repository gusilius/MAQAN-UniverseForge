package dev.worldforge.genesis.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/**
 * Мастер создания персонажа: слева шаги, в центре содержимое шага, справа живой предпросмотр вашей модели.
 * Шаги: раса, внешность, происхождение, поселение (только для правителя), профессии, атрибуты, класс, навыки, итог.
 * Весь выбор хранится на сервере; окно показывает его и шлёт команды /wfgenesis.
 */
public class GenesisScreen extends Screen {
    private static final int W = 960, H = 540, SIDE = 134, PREV = 224;
    private static final String[] STEPS = {"Раса", "Внешность", "Происхождение", "Поселение", "Профессии", "Атрибуты", "Класс", "Навыки", "Итог"};
    private Map<String, Object> m = new LinkedHashMap<>();
    private int step, scroll, maxScroll, race0 = -1;
    private String selStart = "", selClass = "";
    private final Consumer<String> cmd;
    private EditBox nameBox;
    private boolean finished;
    private String lastName = "";

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public GenesisScreen(String payload, Consumer<String> cmd) { super(Component.literal("Создание персонажа")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        m = (Map<String, Object>) Json.parse(payload);
        applyPreview();
        if (nameBox != null && !str(m, "settle").equals(lastName)) { lastName = str(m, "settle"); if (!nameBox.isFocused()) nameBox.setValue(lastName); }
    }

    /** Предпросмотр: ваша модель получает черновой облик. */
    private void applyPreview() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Map<String, String> look = new LinkedHashMap<>();
        for (var e : map(m, "look").entrySet()) look.put(e.getKey(), String.valueOf(e.getValue()));
        Set<String> f = new LinkedHashSet<>();
        for (Object o : list(m, "features")) if (Boolean.TRUE.equals(((Map<?, ?>) o).get("on"))) f.add(String.valueOf(((Map<?, ?>) o).get("id")));
        ClientLooks.put(mc.player.getUUID(), new ClientLooks.Look(str(m, "race"), look, f));
    }

    /** Мастер завершён сервером: облик теперь приходит от сервера, черновой не нужен. */
    public void finish() { finished = true; }

    @Override
    public void removed() {
        if (!finished) { var mc = Minecraft.getInstance(); if (mc.player != null) ClientLooks.put(mc.player.getUUID(), null); }
        super.removed();
    }

    @Override protected void init() {
        nameBox = new EditBox(font, 0, 0, 220, 16, Component.literal("Название"));
        nameBox.setMaxLength(Math.max(1, num(m.get("nameMax"))));
        nameBox.setValue(str(m, "settle"));
        nameBox.setVisible(false);
        addRenderableWidget(nameBox);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    // ------------------------------------------------------------------ утилиты

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Map<String, Object> mm, String k) { Object o = mm.get(k); return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> mm, String k) { Object o = mm.get(k); return o instanceof List ? (List<Object>) o : List.of(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> mo(Object o) { return (Map<String, Object>) o; }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static double dbl(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static String str(Map<String, Object> mm, String k) { Object o = mm.get(k); return o == null ? "" : String.valueOf(o); }
    private static boolean bool(Map<String, Object> mm, String k) { return Boolean.TRUE.equals(mm.get(k)); }

    private boolean hasLeader() {
        for (Object o : list(m, "starts")) { var s = mo(o); if (str(s, "id").equals(str(m, "start")) && str(s, "kind").equals("leader")) return true; }
        return false;
    }

    private boolean stepVisible(int i) { return i != 3 || hasLeader(); }

    private String statName(String k) {
        Map<String, Object> sn = map(m, "statNames");
        if (sn.containsKey(k)) return String.valueOf(sn.get(k));
        return k;
    }

    private String modsText(List<Object> mods) {
        List<String> l = new ArrayList<>();
        for (Object o : mods) { var e = mo(o); double v = dbl(e.get("v")); l.add(statName(str(e, "k")) + " " + (v >= 0 ? "+" : "") + (v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 100) / 100.0))); }
        return String.join(", ", l);
    }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int color) {
        for (var l : font.split(Component.literal(s), Math.max(20, w))) { g.drawString(font, l, x, y, color); y += 10; }
        return y;
    }

    private boolean vis(int y, int h, int top, int bot) { return y + h > top && y < bot; }

    private void btn(GuiGraphics g, int x, int y, int w, String label, boolean en, boolean on, int mx, int my, int top, int bot, String command, String tip) {
        if (!vis(y, 15, top, bot)) return;
        WFUi.button(g, font, x, y, w, 15, label, en, on, WFUi.over(mx, my, x, y, w, 15));
        if (en && command != null) hits.add(new Hit(x, y, w, 15, () -> cmd.accept(command), tip)); else if (tip != null) hits.add(new Hit(x, y, w, 15, null, tip));
    }

    private void click(int x, int y, int w, int h, String command, String tip) { hits.add(new Hit(x, y, w, h, command == null ? null : () -> cmd.accept(command), tip)); }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        renderBackground(g);
        WFUi.panel(g, font, x, y, W, H, "Создание персонажа");
        // шаги
        int sy = y + 28;
        for (int i = 0; i < STEPS.length; i++) {
            if (!stepVisible(i)) continue;
            boolean cur = i == step;
            WFUi.rowBg(g, x + 10, sy, SIDE, 22, i, cur, WFUi.over(mx, my, x + 10, sy, SIDE, 22));
            g.drawString(font, (i + 1) + ". " + STEPS[i], x + 18, sy + 7, cur ? WFUi.GOLD : stepDone(i) ? WFUi.GOOD : WFUi.TX);
            final int target = i;
            hits.add(new Hit(x + 10, sy, SIDE, 22, () -> { step = target; scroll = 0; }, null));
            sy += 24;
        }
        // низ колонки шагов
        btn(g, x + 10, y + H - 62, SIDE, "Случайный персонаж", true, false, mx, my, 0, 10000, "wfgenesis random", "Бросить кости: раса, внешность, старт, очки и класс");
        // содержимое
        int cx = x + SIDE + 22, cw = W - SIDE - PREV - 44, top = y + 28, bot = y + H - 40;
        WFUi.bevel(g, cx - 4, top - 4, cw + 8, bot - top + 8, true);
        nameBox.setVisible(false);
        g.enableScissor(cx - 3, top - 3, cx + cw + 3, bot + 3);
        int used = switch (step) {
            case 0 -> raceStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 1 -> lookStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 2 -> startStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 3 -> settleStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 4 -> profStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 5 -> attrStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 6 -> classStep(g, cx, top - scroll, cw, mx, my, top, bot);
            case 7 -> skillStep(g, cx, top - scroll, cw, mx, my, top, bot);
            default -> summaryStep(g, cx, top - scroll, cw, mx, my, top, bot);
        };
        g.disableScissor();
        maxScroll = Math.max(0, used + scroll - (bot - top) + 6 - 0);
        maxScroll = Math.max(0, used - (bot - top) + 6 + 0);
        if (scroll > maxScroll) scroll = maxScroll;
        if (maxScroll > 0) WFUi.scrollbar(g, cx + cw + 1, top, bot - top, bot - top + maxScroll, scroll);
        // предпросмотр
        int px = x + W - PREV - 10;
        WFUi.bevel(g, px, top - 4, PREV, bot - top + 8, true);
        var mc = Minecraft.getInstance();
        if (mc.player != null) InventoryScreen.renderEntityInInventoryFollowsMouse(g, px + PREV / 2, bot - 60, 78, px + PREV / 2 - mx, bot - 60 - 90 - my, mc.player);
        int ty = bot + 4 - 0;
        String rn = raceName(str(m, "race"));
        g.drawString(font, rn.isEmpty() ? "Раса не выбрана" : rn, px + 8, top + 2, WFUi.GOLD);
        g.drawString(font, "Очки атрибутов: " + num(m.get("attrLeft")), px + 8, top + 14, num(m.get("attrLeft")) > 0 ? WFUi.GOLD_DIM : WFUi.MUTED);
        g.drawString(font, "Очки навыков: " + num(m.get("skillLeft")), px + 8, top + 26, num(m.get("skillLeft")) > 0 ? WFUi.GOLD_DIM : WFUi.MUTED);
        // навигация
        int ny = y + H - 30;
        int prev = prevStep(), next = nextStep();
        btn(g, cx, ny, 90, "← Назад", prev >= 0, false, mx, my, 0, 10000, null, null);
        if (prev >= 0) { final int p = prev; hits.add(new Hit(cx, ny, 90, 15, () -> { step = p; scroll = 0; }, null)); }
        if (step < STEPS.length - 1) {
            btn(g, cx + cw - 90, ny, 90, "Далее →", next >= 0, false, mx, my, 0, 10000, null, null);
            if (next >= 0) { final int n = next; hits.add(new Hit(cx + cw - 90, ny, 90, 15, () -> { step = n; scroll = 0; }, null)); }
        } else {
            boolean ok = list(m, "problems").isEmpty();
            btn(g, cx + cw - 150, ny, 150, "Начать игру", ok, ok, mx, my, 0, 10000, "wfgenesis confirm", ok ? null : "Сначала устраните замечания на шаге «Итог»");
        }
        if (step != 3) { /* поле названия скрыто вне шага поселения */ }
        super.render(g, mx, my, pt);
        for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; } }
    }

    private boolean stepDone(int i) {
        return switch (i) {
            case 0 -> !str(m, "race").isEmpty();
            case 2 -> !str(m, "start").isEmpty();
            case 3 -> !str(m, "settle").isEmpty();
            case 4 -> list(m, "profs").stream().anyMatch(o -> bool(mo(o), "on"));
            case 6 -> !str(m, "cls").isEmpty();
            case 8 -> list(m, "problems").isEmpty();
            default -> false;
        };
    }

    private int prevStep() { for (int i = step - 1; i >= 0; i--) if (stepVisible(i)) return i; return -1; }
    private int nextStep() { for (int i = step + 1; i < STEPS.length; i++) if (stepVisible(i)) return i; return -1; }

    private String raceName(String id) { for (Object o : list(m, "races")) if (str(mo(o), "id").equals(id)) return str(mo(o), "name"); return ""; }

    // ------------------------------------------------------------------ 0. раса

    private int raceStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "РАСА И ПОДРАСА", x, y, WFUi.GOLD_DIM); y += 14;
        int listW = 190;
        String cur = str(m, "race");
        String curRoot = cur;
        for (Object o : list(m, "races")) if (str(mo(o), "id").equals(cur)) { String p = str(mo(o), "parent"); if (!p.isEmpty()) curRoot = p; }
        for (Object o : list(m, "races")) {
            var r = mo(o);
            if (!str(r, "parent").isEmpty()) continue;
            String id = str(r, "id");
            boolean sel = id.equals(curRoot);
            if (vis(y, 16, top, bot)) {
                WFUi.rowBg(g, x, y, listW, 16, 0, sel, WFUi.over(mx, my, x, y, listW, 16));
                g.fill(x + 3, y + 3, x + 7, y + 13, 0xFF000000 | num(r.get("color")));
                g.drawString(font, font.plainSubstrByWidth(str(r, "name"), listW - 20), x + 12, y + 4, sel ? WFUi.GOLD : WFUi.TX);
                click(x, y, listW, 16, "wfgenesis race " + id, null);
            }
            y += 17;
            if (sel) {
                for (Object so : list(m, "races")) {
                    var s = mo(so);
                    if (!str(s, "parent").equals(id)) continue;
                    boolean ss = str(s, "id").equals(cur);
                    if (vis(y, 15, top, bot)) {
                        WFUi.rowBg(g, x + 14, y, listW - 14, 15, 1, ss, WFUi.over(mx, my, x + 14, y, listW - 14, 15));
                        g.drawString(font, font.plainSubstrByWidth("↳ " + str(s, "name"), listW - 24), x + 18, y + 4, ss ? WFUi.GOLD : WFUi.MUTED);
                        click(x + 14, y, listW - 14, 15, "wfgenesis race " + str(s, "id"), null);
                    }
                    y += 16;
                }
            }
        }
        int endList = y;
        // описание выбранной расы
        int dx = x + listW + 14, dw = w - listW - 14, dy = y0 + 14;
        for (Object o : list(m, "races")) {
            var r = mo(o);
            if (!str(r, "id").equals(cur)) continue;
            g.drawString(font, str(r, "name"), dx, dy, WFUi.GOLD); dy += 14;
            if (!str(r, "parent").isEmpty()) { g.drawString(font, "Подраса: " + raceName(str(r, "parent")), dx, dy, WFUi.MUTED); dy += 12; }
            dy = wrap(g, str(r, "desc"), dx, dy, dw, WFUi.TX) + 6;
            g.drawString(font, "ПОПРАВКИ", dx, dy, WFUi.GOLD_DIM); dy += 12;
            dy = wrap(g, modsText(list(r, "mods")), dx, dy, dw, WFUi.GOOD) + 4;
            Map<String, Object> inn = map(r, "innate");
            if (!inn.isEmpty()) {
                g.drawString(font, "ОСОБЕННОСТИ", dx, dy, WFUi.GOLD_DIM); dy += 12;
                for (var e : inn.entrySet()) dy = wrap(g, e.getKey() + ": " + e.getValue(), dx, dy, dw, WFUi.TX);
                dy += 4;
            }
            List<Object> perks = list(r, "perks");
            if (!perks.isEmpty()) {
                g.drawString(font, "РАСОВЫЕ ТАЛАНТЫ ПО УРОВНЯМ", dx, dy, WFUi.GOLD_DIM); dy += 12;
                for (Object po : perks) { var p = mo(po); dy = wrap(g, "ур. " + num(p.get("lv")) + "  " + str(p, "name") + ": " + modsText(list(p, "mods")), dx, dy, dw, WFUi.MUTED); }
            }
            return Math.max(endList, dy) - y0;
        }
        return endList - y0;
    }

    // ------------------------------------------------------------------ 1. внешность

    private static final String[] COLOR_KEYS = {"skin", "hair", "eyes"}, COLOR_NAMES = {"Кожа", "Волосы", "Глаза"};

    private int lookStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        Map<String, Object> look = map(m, "look");
        g.drawString(font, "ТЕЛО", x, y, WFUi.GOLD_DIM); y += 13;
        for (Object o : list(m, "sliders")) {
            var s = mo(o);
            String id = str(s, "id");
            int v = 0;
            try { v = Integer.parseInt(String.valueOf(look.getOrDefault(id, s.get("def")))); } catch (NumberFormatException ignored) { }
            int min = num(s.get("min")), max = num(s.get("max"));
            int stepSize = Math.max(1, (max - min) / 18);
            if (vis(y, 16, top, bot)) {
                g.drawString(font, str(s, "name"), x, y + 4, WFUi.TX);
                int bx = x + 130;
                btn(g, bx, y, 20, "-", v > min, false, mx, my, top, bot, "wfgenesis look " + id + " " + Math.max(min, v - stepSize), str(s, "desc").isEmpty() ? null : str(s, "desc"));
                int bw = w - 130 - 20 - 20 - 70;
                WFUi.bar(g, bx + 24, y + 3, bw, 9, (v - min) / (double) Math.max(1, max - min), WFUi.GOLD_DIM);
                final int sx0 = bx + 24, sw = bw, smin = min, smax = max; final String sid = id;
                hits.add(new Hit(sx0, y, sw, 15, () -> { int val = smin + (int) Math.round((smax - smin) * Math.max(0, Math.min(1, (lastMx - sx0) / (double) sw))); cmd.accept("wfgenesis look " + sid + " " + val); }, null));
                btn(g, bx + 28 + bw, y, 20, "+", v < max, false, mx, my, top, bot, "wfgenesis look " + id + " " + Math.min(max, v + stepSize), null);
                g.drawString(font, v + str(s, "unit"), bx + 52 + bw, y + 4, WFUi.GOLD);
            }
            y += 17;
        }
        y += 6;
        g.drawString(font, "ЦВЕТА", x, y, WFUi.GOLD_DIM); y += 13;
        Map<String, Object> pal = map(m, "palettes");
        for (int ci = 0; ci < COLOR_KEYS.length; ci++) {
            String key = COLOR_KEYS[ci];
            String cur = String.valueOf(look.getOrDefault(key, "#808080"));
            if (vis(y, 30, top, bot)) {
                g.drawString(font, COLOR_NAMES[ci], x, y + 2, WFUi.TX);
                WFUi.swatch(g, x + 60, y, parse(cur));
                int px = x + 90;
                for (Object co : list(pal, key)) {
                    String hex = String.valueOf(co);
                    WFUi.swatch(g, px, y, parse(hex));
                    if (hex.equalsIgnoreCase(cur)) g.renderOutline(px - 1, y - 1, 14, 14, WFUi.GOLD);
                    click(px, y, 12, 12, "wfgenesis look " + key + " " + hex, hex);
                    px += 15;
                }
            }
            y += 15;
            if (vis(y, 15, top, bot)) {
                int bx = x + 60;
                String[][] adj = {{"Тон -", "-12 0 0"}, {"Тон +", "12 0 0"}, {"Насыщ -", "0 -12 0"}, {"Насыщ +", "0 12 0"}, {"Темнее", "0 0 -10"}, {"Светлее", "0 0 10"}};
                for (String[] a : adj) { btn(g, bx, y, 58, a[0], true, false, mx, my, top, bot, "wfgenesis shift " + key + " " + a[1], null); bx += 60; }
            }
            y += 19;
        }
        y += 4;
        g.drawString(font, "ПРИЧЁСКА", x, y, WFUi.GOLD_DIM); y += 13;
        int px = x, rowH = 0;
        for (Object o : list(m, "hairStyles")) {
            var h = mo(o);
            int bw = font.width(str(h, "name")) + 14;
            if (px + bw > x + w - 6) { px = x; y += 17; }
            btn(g, px, y, bw, str(h, "name"), true, str(h, "id").equals(look.get("hair_style")), mx, my, top, bot, "wfgenesis look hair_style " + str(h, "id"), null);
            px += bw + 4;
        }
        y += 24;
        List<Object> fe = list(m, "features");
        if (!fe.isEmpty()) {
            g.drawString(font, "ОСОБЕННОСТИ ВНЕШНОСТИ (по одной из группы)", x, y, WFUi.GOLD_DIM); y += 13;
            px = x;
            for (Object o : fe) {
                var f = mo(o);
                int bw = font.width(str(f, "name")) + 14;
                if (px + bw > x + w - 6) { px = x; y += 17; }
                btn(g, px, y, bw, str(f, "name"), true, bool(f, "on"), mx, my, top, bot, "wfgenesis feature " + str(f, "id"), str(f, "desc").isEmpty() ? null : str(f, "desc"));
                px += bw + 4;
            }
            y += 20;
        }
        return y - y0;
    }

    private int lastMx;

    @Override public void mouseMoved(double mx, double my) { lastMx = (int) mx; super.mouseMoved(mx, my); }

    private static int parse(String hex) { try { return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); } catch (RuntimeException e) { return 0xFF808080; } }

    // ------------------------------------------------------------------ 2. происхождение

    private int startStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "ПРОИСХОЖДЕНИЕ: ГДЕ И КЕМ НАЧАТЬ", x, y, WFUi.GOLD_DIM); y += 14;
        if (selStart.isEmpty()) selStart = str(m, "start");
        int listW = 220;
        Map<String, Object> shown = null;
        int idx = 0;
        for (Object o : list(m, "starts")) {
            var s = mo(o);
            boolean ok = bool(s, "ok"), sel = str(s, "id").equals(str(m, "start"));
            if (str(s, "id").equals(selStart)) shown = s;
            if (vis(y, 20, top, bot)) {
                WFUi.rowBg(g, x, y, listW, 20, idx, sel, WFUi.over(mx, my, x, y, listW, 20));
                String badge = switch (str(s, "kind")) { case "leader" -> "правитель"; case "outlaw" -> "вне закона"; case "wanderer" -> "скиталец"; default -> ""; };
                g.drawString(font, font.plainSubstrByWidth(str(s, "name"), listW - 8), x + 6, y + 6, !ok ? WFUi.OFF : sel ? WFUi.GOLD : WFUi.TX);
                final String sid = str(s, "id");
                hits.add(new Hit(x, y, listW, 20, () -> { selStart = sid; if (ok) cmd.accept("wfgenesis start " + sid); }, ok ? null : "Недоступно вашей расе"));
            }
            y += 21; idx++;
        }
        int dx = x + listW + 14, dw = w - listW - 14, dy = y0 + 14;
        if (shown != null) {
            var s = shown;
            g.drawString(font, str(s, "name"), dx, dy, WFUi.GOLD); dy += 13;
            String kind = switch (str(s, "kind")) { case "leader" -> "Вы правитель" + (str(s, "title").isEmpty() ? "" : ": " + str(s, "title") + " (" + str(s, "noun") + ")"); case "outlaw" -> "Вне закона"; case "wanderer" -> "Без дома"; default -> "Живёте среди людей"; };
            g.drawString(font, kind, dx, dy, WFUi.INFO); dy += 12;
            dy = wrap(g, str(s, "desc"), dx, dy, dw, WFUi.TX) + 6;
            String where = switch (str(s, "spawn")) { case "settlement" -> "Начало: в поселении" + (str(s, "tier").isEmpty() ? "" : " (" + str(s, "tier") + ")"); case "here" -> "Начало: здесь, основываете поселение"; case "camp" -> "Начало: в лесном лагере"; default -> "Начало: случайное место"; };
            g.drawString(font, where, dx, dy, WFUi.MUTED); dy += 14;
            if (num(s.get("gold")) > 0) { g.drawString(font, "Золото: " + num(s.get("gold")) * 10, dx, dy, WFUi.GOLD); dy += 11; }
            Map<String, Object> items = map(s, "items");
            if (!items.isEmpty()) { dy = wrap(g, "Вещи: " + String.join(", ", items.entrySet().stream().map(e -> e.getKey().replace('_', ' ') + " x" + e.getValue()).toList()), dx, dy, dw, WFUi.TX); }
            if (num(s.get("attrBonus")) != 0) { g.drawString(font, "Очки атрибутов: " + (num(s.get("attrBonus")) > 0 ? "+" : "") + num(s.get("attrBonus")), dx, dy, num(s.get("attrBonus")) > 0 ? WFUi.GOOD : WFUi.BAD); dy += 11; }
            if (num(s.get("skillBonus")) != 0) { g.drawString(font, "Очки навыков: " + (num(s.get("skillBonus")) > 0 ? "+" : "") + num(s.get("skillBonus")), dx, dy, num(s.get("skillBonus")) > 0 ? WFUi.GOOD : WFUi.BAD); dy += 11; }
            List<Object> mods = list(s, "mods");
            if (!mods.isEmpty()) dy = wrap(g, "Поправки: " + modsText(mods), dx, dy, dw, WFUi.GOOD);
            if (bool(s, "wanted")) { g.drawString(font, "Стража будет враждебна.", dx, dy, WFUi.BAD); dy += 11; }
            if (bool(s, "panel")) { g.drawString(font, "Откроется панель правителя.", dx, dy, WFUi.GOOD); dy += 11; }
        }
        return Math.max(y, dy) - y0;
    }

    // ------------------------------------------------------------------ 3. поселение

    private int settleStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        String noun = "поселение";
        for (Object o : list(m, "starts")) { var s = mo(o); if (str(s, "id").equals(str(m, "start")) && !str(s, "noun").isEmpty()) noun = str(s, "noun"); }
        g.drawString(font, "ВАШЕ ПОСЕЛЕНИЕ: " + noun.toUpperCase(), x, y, WFUi.GOLD_DIM); y += 14;
        g.drawString(font, "Название", x, y + 4, WFUi.TX);
        if (step == 3) {
            nameBox.setX(x + 70); nameBox.setY(y);
            nameBox.setVisible(y >= top && y + 16 <= bot);
        }
        int bx = x + 70 + 226;
        btn(g, bx, y, 70, "Применить", true, false, mx, my, top, bot, null, null);
        if (vis(y, 15, top, bot)) hits.add(new Hit(bx, y, 70, 15, () -> { String t = nameBox.getValue().trim(); if (!t.isEmpty()) cmd.accept("wfgenesis settle " + t); }, "Подтвердить название"));
        btn(g, bx + 74, y, 80, "Случайное", true, false, mx, my, top, bot, "wfgenesis randomname", null);
        y += 24;
        g.drawString(font, "Направление: на чём построено начало", x, y, WFUi.GOLD_DIM); y += 13;
        for (Object o : list(m, "focusList")) {
            var f = mo(o);
            boolean sel = str(f, "id").equals(str(m, "focus"));
            if (vis(y, 28, top, bot)) {
                WFUi.rowBg(g, x, y, w - 8, 28, 0, sel, WFUi.over(mx, my, x, y, w - 8, 28));
                g.drawString(font, str(f, "name"), x + 6, y + 4, sel ? WFUi.GOLD : WFUi.TX);
                g.drawString(font, font.plainSubstrByWidth(str(f, "desc"), w - 24), x + 6, y + 15, WFUi.MUTED);
                click(x, y, w - 8, 28, "wfgenesis focus " + str(f, "id"), null);
            }
            y += 30;
        }
        y += 4;
        g.drawString(font, "Налог: " + new String[]{"нет", "низкий", "обычный", "высокий"}[Math.max(0, Math.min(3, num(m.get("tax"))))], x, y + 4, WFUi.TX);
        int tx = x + 110;
        String[] tn = {"Нет", "Низкий", "Обычный", "Высокий"};
        for (int i = 0; i < 4; i++) { btn(g, tx, y, 70, tn[i], true, num(m.get("tax")) == i, mx, my, top, bot, "wfgenesis tax " + i, null); tx += 74; }
        y += 22;
        btn(g, x, y, 220, "Набеги: " + (bool(m, "raids") ? "включены" : "выключены"), true, bool(m, "raids"), mx, my, top, bot, "wfgenesis raids", "Периодические налёты на поселение");
        btn(g, x + 228, y, 220, "Авторазмещение рабочих: " + (bool(m, "auto") ? "вкл" : "выкл"), true, bool(m, "auto"), mx, my, top, bot, "wfgenesis auto", null);
        y += 24;
        Map<String, Object> tier = map(m, "tier");
        if (!tier.isEmpty()) {
            g.drawString(font, "С ЧЕГО ВЫ НАЧНЁТЕ", x, y, WFUi.GOLD_DIM); y += 13;
            g.drawString(font, "Жителей: " + num(tier.get("workers")), x, y, WFUi.TX); y += 11;
            y = wrap(g, "Склад: " + String.join(", ", map(tier, "stock").entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()), x, y, w - 8, WFUi.TX);
            List<Object> bl = list(tier, "buildings");
            y = wrap(g, "Заложено зданий: " + bl.size() + " (" + String.join(", ", bl.stream().map(String::valueOf).toList()) + ")", x, y, w - 8, WFUi.MUTED);
            y = wrap(g, "После создания откроется панель поселения; дальше - клавиша J. Приказы вашему поселению можно отдавать без прав оператора.", x, y + 6, w - 8, WFUi.INFO);
        }
        return y - y0;
    }

    // ------------------------------------------------------------------ 4. профессии

    private int profStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        int picked = (int) list(m, "profs").stream().filter(o -> bool(mo(o), "on")).count();
        g.drawString(font, "СТАРТОВЫЕ ПРОФЕССИИ  " + picked + "/" + num(m.get("profPicks")), x, y, WFUi.GOLD_DIM); y += 14;
        y = wrap(g, "Профессия растёт от работы: звания и поправки. Некоторым нужны атрибуты - их можно поднять на шаге «Атрибуты».", x, y, w - 8, WFUi.MUTED) + 4;
        int cw = (w - 16) / 2;
        int col = 0, rowY = y, maxY = y;
        for (Object o : list(m, "profs")) {
            var p = mo(o);
            boolean on = bool(p, "on"), blocked = !str(p, "why").isEmpty();
            int cx = x + col * (cw + 8);
            if (vis(rowY, 58, top, bot)) {
                WFUi.bevel(g, cx, rowY, cw, 56, !on);
                g.drawString(font, str(p, "name"), cx + 6, rowY + 4, on ? WFUi.GOLD : blocked ? WFUi.OFF : WFUi.TX);
                g.drawString(font, "жалованье " + num(p.get("wage")), cx + cw - 70, rowY + 4, WFUi.MUTED);
                g.drawString(font, font.plainSubstrByWidth(str(p, "desc"), cw - 12), cx + 6, rowY + 16, WFUi.MUTED);
                String mt = modsText(list(p, "mods"));
                g.drawString(font, font.plainSubstrByWidth(str(p, "rank0") + (mt.isEmpty() ? "" : ": " + mt), cw - 12), cx + 6, rowY + 28, WFUi.GOOD);
                List<Object> lacks = list(p, "lacks");
                if (blocked) g.drawString(font, font.plainSubstrByWidth(str(p, "why"), cw - 12), cx + 6, rowY + 42, WFUi.BAD);
                else if (!lacks.isEmpty()) g.drawString(font, font.plainSubstrByWidth("Нужно: " + String.join(", ", lacks.stream().map(String::valueOf).toList()), cw - 12), cx + 6, rowY + 42, 0xFFE0A030);
                final String id = str(p, "id");
                hits.add(new Hit(cx, rowY, cw, 56, () -> cmd.accept("wfgenesis prof " + id), blocked && !on ? str(p, "why") : null));
            }
            maxY = Math.max(maxY, rowY + 58);
            if (++col == 2) { col = 0; rowY += 60; }
        }
        return maxY - y0;
    }

    // ------------------------------------------------------------------ 5. атрибуты

    private int attrStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "АТРИБУТЫ  ·  свободно очков: " + num(m.get("attrLeft")) + " из " + num(m.get("attrBudget")), x, y, num(m.get("attrLeft")) > 0 ? WFUi.GOLD : WFUi.MUTED);
        btn(g, x + w - 100, y - 3, 90, "Сбросить", true, false, mx, my, top, bot, "wfgenesis attrreset", null);
        y += 14;
        y = wrap(g, "Каждое очко даёт поправки; до +" + num(m.get("softCap")) + " очко стоит 1, дальше 2. Максимум +" + num(m.get("maxDelta")) + " на атрибут. Неистраченные очки останутся на потом.", x, y, w - 8, WFUi.MUTED) + 4;
        Map<String, Object> groups = map(m, "groups");
        int cw = (w - 12) / 2;
        for (var ge : groups.entrySet()) {
            boolean any = false;
            int col = 0, rowY = y + 13;
            int startY = y;
            for (Object o : list(m, "attrs")) {
                var a = mo(o);
                if (!str(a, "group").equals(ge.getKey())) continue;
                if (!any) { g.drawString(font, String.valueOf(ge.getValue()).toUpperCase(), x, y, WFUi.GOLD_DIM); any = true; }
                int cx = x + col * (cw + 8);
                int base = num(a.get("base")), d = num(a.get("delta"));
                if (vis(rowY, 40, top, bot)) {
                    WFUi.bevel(g, cx, rowY, cw, 38, true);
                    g.fill(cx + 4, rowY + 4, cx + 7, rowY + 30, 0xFF000000 | num(a.get("color")));
                    g.drawString(font, str(a, "name") + "  " + (base + d) + (d > 0 ? " (+" + d + ")" : ""), cx + 12, rowY + 4, d > 0 ? WFUi.GOLD : WFUi.TX);
                    g.drawString(font, font.plainSubstrByWidth(modsText(list(a, "per")), cw - 70), cx + 12, rowY + 15, WFUi.GOOD);
                    g.drawString(font, font.plainSubstrByWidth(str(a, "desc"), cw - 70), cx + 12, rowY + 26, WFUi.MUTED);
                    final String id = str(a, "id");
                    btn(g, cx + cw - 52, rowY + 4, 22, "-", d > 0, false, mx, my, top, bot, "wfgenesis attr " + id + " -1", null);
                    btn(g, cx + cw - 28, rowY + 4, 22, "+", num(m.get("attrLeft")) > 0 && d < num(m.get("maxDelta")), false, mx, my, top, bot, "wfgenesis attr " + id + " 1", null);
                }
                if (++col == 2) { col = 0; rowY += 40; }
            }
            if (any) { if (col != 0) rowY += 40; y = rowY + 4; }
        }
        return y - y0;
    }

    // ------------------------------------------------------------------ 6. класс

    private int classStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "КЛАСС", x, y, WFUi.GOLD_DIM); y += 14;
        y = wrap(g, "Класс задаёт путь развития. Подклассы открываются в игре по уровню; ниже показано, куда он ведёт.", x, y, w - 8, WFUi.MUTED) + 4;
        if (selClass.isEmpty()) selClass = str(m, "cls");
        int listW = 180;
        Map<String, Object> shown = null;
        int idx = 0;
        for (Object o : list(m, "classes")) {
            var c = mo(o);
            boolean blocked = !str(c, "why").isEmpty(), on = bool(c, "on");
            if (str(c, "id").equals(selClass)) shown = c;
            if (vis(y, 20, top, bot)) {
                WFUi.rowBg(g, x, y, listW, 20, idx, on, WFUi.over(mx, my, x, y, listW, 20));
                g.drawString(font, font.plainSubstrByWidth(str(c, "name"), listW - 8), x + 6, y + 6, blocked ? WFUi.OFF : on ? WFUi.GOLD : WFUi.TX);
                final String id = str(c, "id");
                hits.add(new Hit(x, y, listW, 20, () -> { selClass = id; if (!blocked) cmd.accept("wfgenesis class " + id); }, blocked ? str(c, "why") : null));
            }
            y += 21; idx++;
        }
        int dx = x + listW + 14, dw = w - listW - 14, dy = y0 + 32;
        if (shown != null) {
            var c = shown;
            g.drawString(font, str(c, "name"), dx, dy, WFUi.GOLD); dy += 13;
            dy = wrap(g, str(c, "desc"), dx, dy, dw, WFUi.TX) + 4;
            if (!str(c, "archetype").isEmpty()) { g.drawString(font, "Путь развития: " + str(c, "archetype"), dx, dy, WFUi.INFO); dy += 12; }
            dy = wrap(g, "Поправки: " + modsText(list(c, "mods")), dx, dy, dw, WFUi.GOOD) + 4;
            if (!str(c, "why").isEmpty()) dy = wrap(g, str(c, "why"), dx, dy, dw, WFUi.BAD) + 4;
            List<Object> kids = list(c, "kids");
            g.drawString(font, "ВЕТВИ РАЗВИТИЯ (подклассы)", dx, dy, WFUi.GOLD_DIM); dy += 12;
            if (kids.isEmpty()) g.drawString(font, "Нет", dx, dy, WFUi.MUTED);
            for (Object ko : kids) { var k = mo(ko); dy = wrap(g, "Ступень " + num(k.get("tier")) + " (с ур. " + num(k.get("minLevel")) + "): " + str(k, "name") + " - " + str(k, "desc"), dx, dy, dw, WFUi.TX); dy += 2; }
            dy += 4;
            dy = wrap(g, "Дальше: окно происхождения (K) и дерево умений (P).", dx, dy, dw, WFUi.MUTED);
        }
        return Math.max(y, dy) - y0;
    }

    // ------------------------------------------------------------------ 7. навыки

    private int skillStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "СТАРТОВЫЕ НАВЫКИ  ·  очков: " + num(m.get("skillLeft")) + " из " + num(m.get("skillBudget")), x, y, num(m.get("skillLeft")) > 0 ? WFUi.GOLD : WFUi.MUTED); y += 14;
        y = wrap(g, "Навыки дают поправки за каждый ранг. Остальные очки получите за уровни; дерево умений (P) откроется в игре.", x, y, w - 8, WFUi.MUTED) + 4;
        Map<String, String> gn = new LinkedHashMap<>();
        gn.put("combat", "Бой"); gn.put("survival", "Выживание"); gn.put("craft", "Ремесло"); gn.put("social", "Общение"); gn.put("realm", "Государство");
        for (var ge : gn.entrySet()) {
            boolean any = false;
            for (Object o : list(m, "skills")) {
                var s = mo(o);
                if (!str(s, "group").equals(ge.getKey())) continue;
                if (!any) { g.drawString(font, ge.getValue().toUpperCase(), x, y, WFUi.GOLD_DIM); y += 13; any = true; }
                int rank = num(s.get("rank")), max = num(s.get("max"));
                boolean can = str(s, "why").isEmpty() && num(s.get("cost")) <= num(m.get("skillLeft"));
                if (vis(y, 30, top, bot)) {
                    WFUi.bevel(g, x, y, w - 8, 28, true);
                    g.drawString(font, str(s, "name") + "  " + rank + "/" + max, x + 6, y + 4, rank > 0 ? WFUi.GOLD : WFUi.TX);
                    g.drawString(font, font.plainSubstrByWidth(str(s, "desc"), w - 180), x + 6, y + 16, WFUi.MUTED);
                    String why = str(s, "why");
                    if (!why.isEmpty() && rank < max) g.drawString(font, font.plainSubstrByWidth(why, 150), x + w - 8 - 140 - 60, y + 4, WFUi.OFF);
                    final String id = str(s, "id");
                    btn(g, x + w - 8 - 52, y + 6, 22, "-", rank > 0, false, mx, my, top, bot, "wfgenesis skill " + id + " -1", null);
                    btn(g, x + w - 8 - 26, y + 6, 22, "+", can && rank < max, false, mx, my, top, bot, "wfgenesis skill " + id + " 1", rank < max && num(s.get("cost")) > 0 ? "Цена: " + num(s.get("cost")) : null);
                }
                y += 30;
            }
            if (any) y += 4;
        }
        return y - y0;
    }

    // ------------------------------------------------------------------ 8. итог

    private int summaryStep(GuiGraphics g, int x, int y0, int w, int mx, int my, int top, int bot) {
        int y = y0;
        g.drawString(font, "ИТОГ", x, y, WFUi.GOLD_DIM); y += 14;
        List<Object> pr = list(m, "problems");
        if (!pr.isEmpty()) {
            g.drawString(font, "Нужно исправить:", x, y, WFUi.BAD); y += 12;
            for (Object o : pr) y = wrap(g, "• " + o, x + 6, y, w - 20, WFUi.BAD);
            y += 6;
        } else { g.drawString(font, "Всё готово. Нажмите «Начать игру».", x, y, WFUi.GOOD); y += 16; }
        g.drawString(font, "Раса: " + raceName(str(m, "race")), x, y, WFUi.TX); y += 11;
        for (Object o : list(m, "starts")) { var s = mo(o); if (str(s, "id").equals(str(m, "start"))) g.drawString(font, "Происхождение: " + str(s, "name"), x, y, WFUi.TX); }
        y += 11;
        if (hasLeader()) { g.drawString(font, "Поселение: " + str(m, "settle"), x, y, WFUi.GOLD); y += 11; }
        List<String> ps = new ArrayList<>();
        for (Object o : list(m, "profs")) if (bool(mo(o), "on")) ps.add(str(mo(o), "name"));
        g.drawString(font, "Профессии: " + (ps.isEmpty() ? "нет" : String.join(", ", ps)), x, y, WFUi.TX); y += 11;
        String cn = "";
        for (Object o : list(m, "classes")) if (str(mo(o), "id").equals(str(m, "cls"))) cn = str(mo(o), "name");
        g.drawString(font, "Класс: " + (cn.isEmpty() ? "не выбран" : cn), x, y, WFUi.TX); y += 16;
        g.drawString(font, "ИТОГОВЫЕ ХАРАКТЕРИСТИКИ", x, y, WFUi.GOLD_DIM); y += 13;
        int col = 0, cw = (w - 12) / 2, rowY = y;
        for (var e : map(m, "preview").entrySet()) {
            double v = dbl(e.getValue());
            if (Math.abs(v) < 1e-9) continue;
            int cx = x + col * (cw + 8);
            if (vis(rowY, 12, top, bot)) WFUi.kv(g, font, cx, rowY, cw, cw - 60, statName(e.getKey()), v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v), WFUi.TX);
            if (++col == 2) { col = 0; rowY += 12; }
        }
        if (col != 0) rowY += 12;
        return rowY - y0 + 4;
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (d * 22)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, double b) {
        lastMx = (int) mx;
        if (b == 0) {
            if (nameBox != null && nameBox.visible && nameBox.mouseClicked(mx, my, (int) b)) { setFocused(nameBox); return true; }
            for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h) && h.run != null) { h.run.run(); return true; } }
        }
        return super.mouseClicked(mx, my, (int) b);
    }

    @Override
    public boolean keyPressed(int key, int sc, int mods) {
        if (nameBox != null && nameBox.isFocused() && nameBox.visible) {
            if (key == 257 || key == 335) { String t = nameBox.getValue().trim(); if (!t.isEmpty()) cmd.accept("wfgenesis settle " + t); return true; }
            return nameBox.keyPressed(key, sc, mods);
        }
        if (key == 256) return true;
        return super.keyPressed(key, sc, mods);
    }
}
