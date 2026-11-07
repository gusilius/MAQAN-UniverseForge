package dev.worldforge.origins.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно происхождения: деревья «Раса», «Класс» (ступени слева направо, линии к предкам, сходящиеся ветви) и «Профессии» (основные и их специализации). */
public class OriginsScreen extends Screen {
    private static final int W = 860, H = 500;
    private static final String[] TABS = {"Класс", "Раса", "Профессии", "Итог"};
    private Map<String, Object> m = new LinkedHashMap<>();
    private List<Map<String, Object>> classes = new ArrayList<>(), races = new ArrayList<>(), profs = new ArrayList<>();
    private int tab, refresh, retrainIn, scrollR, scrollP;
    private String selClass = "", selRace = "", selProf = "";
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public OriginsScreen(String payload, Consumer<String> cmd) { super(Component.literal("Происхождение")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        m = (Map<String, Object>) Json.parse(payload);
        classes = maps("classTree"); races = maps("raceTree"); profs = maps("profTree");
        retrainIn = num(m.get("retrainIn"));
        if (selClass.isEmpty() && !classes.isEmpty()) for (var c : classes) if ("current".equals(str(c, "state"))) selClass = str(c, "id");
    }

    @SuppressWarnings("unchecked") private List<Map<String, Object>> maps(String k) { List<Map<String, Object>> l = new ArrayList<>(); if (m.get(k) instanceof List<?> ll) for (Object o : ll) l.add((Map<String, Object>) o); return l; }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String str(Map<String, Object> mm, String k) { Object o = mm.get(k); return o == null ? "" : String.valueOf(o); }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> mm, String k) { Object o = mm.get(k); return o instanceof List ? (List<Object>) o : List.of(); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 200 == 0) cmd.accept("wforigin"); }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    private int wrap(GuiGraphics g, String s, int x, int y, int w, int color) { for (var l : font.split(Component.literal(s), w)) { g.drawString(font, l, x, y, color); y += 10; } return y; }

    private static int stateColor(String st) { return switch (st) { case "current" -> WFUi.GOLD; case "taken", "learned" -> WFUi.GOOD; case "open" -> WFUi.TX; case "blocked" -> WFUi.BAD; default -> WFUi.OFF; }; }

    private static String modName(String k) {
        return switch (k) {
            case "melee_dmg_pct" -> "урон ближн. %"; case "ranged_dmg_pct" -> "урон дальн. %"; case "weapon_dmg_pct" -> "урон оружия %"; case "armor" -> "броня"; case "max_health" -> "здоровье";
            case "crit_pct" -> "крит %"; case "dodge_pct" -> "уклонение %"; case "stealth_pct" -> "скрытность %"; case "heal_pct" -> "лечение %"; case "opinion" -> "расположение";
            case "levy_pct" -> "ополчение %"; case "tax_pct" -> "налоги %"; case "trade_profit_pct" -> "прибыль торговли %"; case "discount_pct" -> "скидка %"; case "xp_gain_pct" -> "опыт %";
            case "craft_quality_pct" -> "качество ремесла %"; case "build_speed_pct" -> "стройка %"; case "mining_speed_pct" -> "добыча %"; case "move_speed_pct" -> "скорость %";
            case "luck" -> "удача"; case "carry" -> "ноша"; case "thorns" -> "шипы"; case "health_regen" -> "регенерация"; case "resist_pct" -> "сопротивление %";
            case "intrigue_bonus" -> "интрига"; case "spy_success_pct" -> "шпионаж %"; case "gold_find_pct" -> "золото %"; case "farm_yield_pct" -> "урожай %"; default -> k;
        };
    }

    private String mods(Map<String, Object> mm) {
        List<String> l = new ArrayList<>();
        for (var e : mm.entrySet()) { double v = e.getValue() instanceof Number n ? n.doubleValue() : 0; l.add(modName(e.getKey()) + " " + (v >= 0 ? "+" : "") + (v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v))); }
        return String.join(", ", l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Происхождение  ·  " + (str(m, "race").isEmpty() ? "раса не выбрана" : raceName(str(m, "race"))) + "  ·  " + (str(m, "class").isEmpty() ? "без класса" : className(str(m, "class"))));
        WFUi.tabs(g, font, x + 10, y + 22, TABS, tab, mx, my);
        for (int i = 0, cx = x + 10; i < TABS.length; i++) { int w = font.width(TABS[i]) + 16; final int t = i; hits.add(new Hit(cx, y + 22, w, 15, () -> tab = t, null)); cx += w + 2; }
        int top = y + 42, bh = H - 52;
        switch (tab) {
            case 0 -> classTab(g, x, top, bh, mx, my);
            case 1 -> raceTab(g, x, top, bh, mx, my);
            case 2 -> profTab(g, x, top, bh, mx, my);
            default -> {
                WFUi.bevel(g, x + 10, top, W - 20, bh, true);
                int yy = top + 8;
                g.drawString(font, "ИТОГОВЫЕ ПОПРАВКИ", x + 18, yy, WFUi.GOLD_DIM); yy += 14;
                Map<String, Object> mm = m.get("mods") instanceof Map<?, ?> q ? (Map<String, Object>) q : Map.of();
                yy = wrap(g, mm.isEmpty() ? "Пока нет." : mods(mm), x + 18, yy, W - 50, WFUi.TX) + 8;
                g.drawString(font, "ПРОФЕССИИ", x + 18, yy, WFUi.GOLD_DIM); yy += 12;
                for (Object o : list(m, "professions")) { Map<String, Object> p = (Map<String, Object>) o; g.drawString(font, str(p, "name") + " - " + str(p, "rank") + "  (опыт " + num(p.get("xp")) + ")", x + 18, yy, WFUi.TX); yy += 11; }
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, width, height, mx, my, List.of(h.tip)); break; } }
        super.render(g, mx, my, pt);
    }

    private String raceName(String id) { for (var r : races) if (str(r, "id").equals(id)) return str(r, "name"); return id; }
    private String className(String id) { for (var c : classes) if (str(c, "id").equals(id)) return str(c, "name"); return id; }

    // ---------------- классы: дерево по ступеням ----------------
    private void classTab(GuiGraphics g, int x, int top, int bh, int mx, int my) {
        int treeW = 560, treeH = bh - 110;
        WFUi.bevel(g, x + 10, top, treeW, treeH + 8, true);
        // показываем только ветвь текущего пути: корни, потомки взятых, открытые и заблокированные (дальние скрыты)
        Map<Integer, List<Map<String, Object>>> byTier = new TreeMap<>();
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (var c : classes) byId.put(str(c, "id"), c);
        String root = list(m, "history").isEmpty() ? "" : String.valueOf(list(m, "history").get(0));
        for (var c : classes) {
            boolean show = num(c.get("tier")) == 1 && (root.isEmpty() || str(c, "id").equals(root) || selClass.isEmpty());
            if (!show && !root.isEmpty() && inLine(c, root, byId, 0)) show = true;
            if (!show && root.isEmpty() && selClass.length() > 0 && inLine(c, rootOf(selClass, byId), byId, 0)) show = true;
            if (show) byTier.computeIfAbsent(num(c.get("tier")), k -> new ArrayList<>()).add(c);
        }
        int colW = treeW / 4;
        Map<String, int[]> pos = new HashMap<>();
        for (var e : byTier.entrySet()) {
            int col = Math.min(3, e.getKey() - 1);
            List<Map<String, Object>> l = e.getValue();
            int rowH = Math.min(26, Math.max(18, (treeH - 24) / Math.max(1, l.size())));
            g.drawString(font, "СТУПЕНЬ " + e.getKey(), x + 16 + col * colW, top + 4, WFUi.GOLD_DIM);
            for (int i = 0; i < l.size(); i++) {
                int bx = x + 14 + col * colW, by = top + 16 + i * rowH;
                pos.put(str(l.get(i), "id"), new int[]{bx, by + 8});
            }
        }
        for (var e : byTier.entrySet()) for (var c : e.getValue()) {
            int[] p = pos.get(str(c, "id"));
            List<String> parents = new ArrayList<>(); if (!str(c, "from").isEmpty()) parents.add(str(c, "from")); for (Object o : list(c, "also")) parents.add(String.valueOf(o));
            for (String par : parents) { int[] q = pos.get(par); if (q != null) { int x1 = q[0] + colW - 20, y1 = q[1], x2 = p[0], y2 = p[1]; int mid = (x1 + x2) / 2; g.fill(x1, y1, mid, y1 + 1, 0xFF55555C); g.fill(mid, Math.min(y1, y2), mid + 1, Math.max(y1, y2) + 1, 0xFF55555C); g.fill(mid, y2, x2, y2 + 1, 0xFF55555C); } }
        }
        for (var e : byTier.entrySet()) {
            for (var c : e.getValue()) {
                int[] p = pos.get(str(c, "id"));
                String st = str(c, "state"), id = str(c, "id");
                int bw = colW - 24, bh2 = 16;
                boolean sel = id.equals(selClass);
                g.fill(p[0], p[1] - 8, p[0] + bw, p[1] + 8, sel ? 0xFF3A3A2A : WFUi.INSET);
                g.fill(p[0], p[1] - 8, p[0] + 2, p[1] + 8, 0xFF000000 | stateColor(st));
                if (WFUi.over(mx, my, p[0], p[1] - 8, bw, bh2)) g.fill(p[0], p[1] - 8, p[0] + bw, p[1] + 8, WFUi.HOVER);
                g.drawString(font, font.plainSubstrByWidth(str(c, "name") + (list(c, "also").isEmpty() ? "" : " ⇄"), bw - 8), p[0] + 5, p[1] - 4, stateColor(st));
                hits.add(new Hit(p[0], p[1] - 8, bw, bh2, () -> selClass = id, null));
            }
        }
        // подробности
        int dy = top + treeH + 14;
        WFUi.bevel(g, x + 10, dy, W - 20, bh - treeH - 14, true);
        Map<String, Object> c = null;
        for (var cc : classes) if (str(cc, "id").equals(selClass)) c = cc;
        if (c == null) { g.drawString(font, "Выберите класс в дереве.", x + 18, dy + 8, WFUi.MUTED); }
        else {
            g.drawString(font, str(c, "name") + "  ·  ступень " + num(c.get("tier")), x + 18, dy + 6, WFUi.GOLD);
            wrap(g, str(c, "desc") + "   " + mods(c.get("mods") instanceof Map<?, ?> q ? (Map<String, Object>) q : Map.of()), x + 18, dy + 18, W - 200, WFUi.MUTED);
            String st = str(c, "state"), why = str(c, "why"), id = str(c, "id");
            if (!str(c, "excl").isEmpty()) g.drawString(font, "Путь «" + str(c, "excl") + "»: исключает соседей по группе", x + 18, dy + bh - treeH - 30, WFUi.INFO);
            if (st.equals("open")) button(g, x + W - 170, dy + 8, 150, "Выбрать класс", true, mx, my, () -> cmd.accept("wforigin class " + id), null);
            else if (st.equals("blocked")) { button(g, x + W - 170, dy + 8, 150, "Выбрать класс", false, mx, my, null, why); g.drawString(font, font.plainSubstrByWidth(why, 300), x + W - 330, dy + 28, WFUi.BAD); }
            else if (st.equals("far")) g.drawString(font, "Сначала продвиньтесь по ветке", x + W - 190, dy + 12, WFUi.OFF);
        }
        button(g, x + W - 170, dy + bh - treeH - 32, 150, retrainIn > 0 ? "Переучиться (" + retrainIn + " дн.)" : "Переучиться", retrainIn == 0, mx, my, () -> cmd.accept("wforigin retrain"), "Снять последний выбранный класс");
        // справа от дерева: путь
        int rx = x + treeW + 20;
        g.drawString(font, "ВАШ ПУТЬ", rx, top + 6, WFUi.GOLD_DIM);
        int yy = top + 20;
        for (Object o : list(m, "history")) { g.drawString(font, "› " + className(String.valueOf(o)), rx, yy, WFUi.GOOD); yy += 12; }
        if (list(m, "history").isEmpty()) g.drawString(font, "Класс не выбран", rx, yy, WFUi.MUTED);
        yy += 10;
        g.drawString(font, "⇄ - ветви сходятся:", rx, yy, WFUi.MUTED); yy += 10;
        g.drawString(font, "класс доступен из нескольких", rx, yy, WFUi.MUTED);
    }

    private boolean inLine(Map<String, Object> c, String root, Map<String, Map<String, Object>> byId, int depth) {
        if (depth > 8 || root.isEmpty()) return false;
        if (str(c, "id").equals(root)) return true;
        List<String> ps = new ArrayList<>();
        if (!str(c, "from").isEmpty()) ps.add(str(c, "from"));
        for (Object o : list(c, "also")) ps.add(String.valueOf(o));
        for (String q : ps) { var pc = byId.get(q); if (pc != null && inLine(pc, root, byId, depth + 1)) return true; }
        return false;
    }

    private String rootOf(String id, Map<String, Map<String, Object>> byId) {
        for (int i = 0; i < 8; i++) { var c = byId.get(id); if (c == null || str(c, "from").isEmpty()) return id; id = str(c, "from"); }
        return id;
    }

    // ---------------- расы ----------------
    private void raceTab(GuiGraphics g, int x, int top, int bh, int mx, int my) {
        int lw = 280;
        WFUi.bevel(g, x + 10, top, lw, bh, true);
        int yy = top + 4, rowH = 14, idx = 0, limit = top + bh - rowH;
        for (var r : races) if (str(r, "parent").isEmpty()) {
            if (idx++ >= scrollR && yy <= limit) yy = raceRow(g, r, x + 14, yy, lw - 8, 0, mx, my, rowH);
            for (var s : races) if (str(s, "parent").equals(str(r, "id"))) { if (idx++ >= scrollR && yy <= limit) yy = raceRow(g, s, x + 14, yy, lw - 8, 1, mx, my, rowH); }
        }
        WFUi.scrollbar(g, x + lw + 2, top + 2, bh - 4, races.size() * rowH, scrollR * rowH);
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bh, true);
        Map<String, Object> r = null;
        for (var rr : races) if (str(rr, "id").equals(selRace)) r = rr;
        if (r == null) { g.drawString(font, "Выберите расу слева.", rx + 10, top + 8, WFUi.MUTED); return; }
        WFUi.swatch(g, rx + 10, top + 8, num(r.get("color")));
        g.drawString(font, str(r, "name"), rx + 30, top + 8, WFUi.GOLD);
        if (!str(r, "parent").isEmpty()) g.drawString(font, "подраса: " + raceName(str(r, "parent")), rx + 30, top + 20, WFUi.MUTED);
        int ty = wrap(g, str(r, "desc"), rx + 10, top + 38, rw - 20, WFUi.TX) + 6;
        for (var e : ((Map<String, Object>) r.get("innate")).entrySet()) { g.drawString(font, "• " + e.getKey() + ": " + e.getValue(), rx + 10, ty, WFUi.GOOD); ty += 11; }
        String id = str(r, "id");
        boolean has = !str(m, "race").isEmpty();
        button(g, rx + rw - 170, top + bh - 22, 150, has ? "Раса уже выбрана" : "Выбрать расу", !has, mx, my, () -> cmd.accept("wforigin race " + id), has ? "Расу нельзя сменить" : null);
    }

    private int raceRow(GuiGraphics g, Map<String, Object> r, int x, int y, int w, int indent, int mx, int my, int rowH) {
        String id = str(r, "id");
        boolean sel = id.equals(selRace);
        if (sel) g.fill(x, y, x + w, y + rowH, 0xFF3A3A2A); else if (WFUi.over(mx, my, x, y, w, rowH)) g.fill(x, y, x + w, y + rowH, WFUi.HOVER);
        WFUi.swatch(g, x + 3 + indent * 12, y + 3, num(r.get("color")));
        g.drawString(font, (indent > 0 ? "└ " : "") + str(r, "name"), x + 14 + indent * 12, y + 3, Boolean.TRUE.equals(r.get("chosen")) ? WFUi.GOLD : WFUi.TX);
        hits.add(new Hit(x, y, w, rowH, () -> selRace = id, null));
        return y + rowH;
    }

    // ---------------- профессии ----------------
    private void profTab(GuiGraphics g, int x, int top, int bh, int mx, int my) {
        int lw = 300;
        WFUi.bevel(g, x + 10, top, lw, bh, true);
        int yy = top + 4, idx = 0, limit = top + bh - 14;
        for (var p : profs) if (str(p, "from").isEmpty()) {
            if (idx++ >= scrollP && yy <= limit) yy = profRow(g, p, x + 14, yy, lw - 8, 0, mx, my);
            for (var s : profs) if (str(s, "from").equals(str(p, "id"))) { if (idx++ >= scrollP && yy <= limit) yy = profRow(g, s, x + 14, yy, lw - 8, 1, mx, my); }
        }
        WFUi.scrollbar(g, x + lw + 2, top + 2, bh - 4, profs.size() * 13, scrollP * 13);
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bh, true);
        Map<String, Object> p = null;
        for (var pp : profs) if (str(pp, "id").equals(selProf)) p = pp;
        if (p == null) { g.drawString(font, "Выберите профессию слева.", rx + 10, top + 8, WFUi.MUTED); return; }
        g.drawString(font, str(p, "name"), rx + 10, top + 8, WFUi.GOLD);
        g.drawString(font, str(p, "from").isEmpty() ? "основная профессия" : "специализация: " + profName(str(p, "from")), rx + 10, top + 20, WFUi.MUTED);
        wrap(g, str(p, "desc"), rx + 10, top + 36, rw - 20, WFUi.TX);
        String st = str(p, "state"), id = str(p, "id"), why = str(p, "why");
        if (st.equals("learned")) { g.drawString(font, "Звание: " + str(p, "rank"), rx + 10, top + 64, WFUi.GOOD); button(g, rx + rw - 170, top + bh - 22, 150, "Оставить ремесло", true, mx, my, () -> cmd.accept("wforigin drop " + id), "Теряются и его специализации"); }
        else { button(g, rx + rw - 170, top + bh - 22, 150, "Освоить", st.equals("open"), mx, my, () -> cmd.accept("wforigin learn " + id), why.isEmpty() ? null : why); if (!why.isEmpty()) wrap(g, why, rx + 10, top + 64, rw - 20, WFUi.BAD); }
    }

    private String profName(String id) { for (var p : profs) if (str(p, "id").equals(id)) return str(p, "name"); return id; }

    private int profRow(GuiGraphics g, Map<String, Object> p, int x, int y, int w, int indent, int mx, int my) {
        String id = str(p, "id"), st = str(p, "state");
        boolean sel = id.equals(selProf);
        if (sel) g.fill(x, y, x + w, y + 13, 0xFF3A3A2A); else if (WFUi.over(mx, my, x, y, w, 13)) g.fill(x, y, x + w, y + 13, WFUi.HOVER);
        g.drawString(font, (indent > 0 ? "   └ " : "") + str(p, "name"), x + 4, y + 3, stateColor(st));
        if (st.equals("learned")) { String r = str(p, "rank"); g.drawString(font, font.plainSubstrByWidth(r, 110), x + w - 4 - font.width(font.plainSubstrByWidth(r, 110)), y + 3, WFUi.MUTED); }
        hits.add(new Hit(x, y, w, 13, () -> selProf = id, null));
        return y + 13;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        int dir = -(int) Math.signum(d) * 3;
        if (tab == 1) { scrollR = Math.max(0, Math.min(Math.max(0, races.size() - 20), scrollR + dir)); return true; }
        if (tab == 2) { scrollP = Math.max(0, Math.min(Math.max(0, profs.size() - 20), scrollP + dir)); return true; }
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) for (int i = hits.size() - 1; i >= 0; i--) { Hit h = hits.get(i); if (WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h) && h.run != null) { h.run.run(); return true; } }
        return super.mouseClicked(mx, my, button);
    }
}
