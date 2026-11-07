package dev.worldforge.orders.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFArt;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно «Организации»: список слева, справа сведения, звания, задания и прошения выбранной организации. */
public class OrdersScreen extends Screen {
    private static final int W = 760, H = 450;
    private static final String[] TABS = {"Обзор", "Задания", "Прошения", "Звания"};
    private List<Map<String, Object>> orgs = new ArrayList<>();
    private List<String> chronicle = new ArrayList<>();
    private int sel, tab, refresh;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, List<String> tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public OrdersScreen(String payload, Consumer<String> cmd) {
        super(Component.literal("Организации"));
        this.cmd = cmd;
        setModel(payload);
    }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        Map<String, Object> m = (Map<String, Object>) Json.parse(payload);
        orgs = new ArrayList<>();
        for (Object o : (List<Object>) m.get("orgs")) orgs.add((Map<String, Object>) o);
        chronicle = new ArrayList<>();
        for (Object o : (List<Object>) m.get("chronicle")) chronicle.add(String.valueOf(o));
        orgs.sort((a, b) -> Boolean.compare(flag(b, "joined"), flag(a, "joined")));
        if (sel >= orgs.size()) sel = 0;
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static String str(Map<String, Object> m, String k) { Object o = m.get(k); return o == null ? "" : String.valueOf(o); }
    private static boolean flag(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }
    @SuppressWarnings("unchecked") private static List<Object> list(Map<String, Object> m, String k) { Object o = m.get(k); return o instanceof List ? (List<Object>) o : List.of(); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 80 == 0) cmd.accept("wforders"); }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip == null ? null : List.of(tip)));
    }

    private static final Map<String, String> TRAITS = Map.ofEntries(Map.entry("brave", "храбрый"), Map.entry("craven", "трус"), Map.entry("just", "справедливый"), Map.entry("cruel", "жестокий"),
            Map.entry("greedy", "алчный"), Map.entry("generous", "щедрый"), Map.entry("shrewd", "хитрый"), Map.entry("scholar", "книжник"), Map.entry("diligent", "усердный"),
            Map.entry("lazy", "ленивый"), Map.entry("gregarious", "общительный"), Map.entry("shy", "робкий"), Map.entry("ambitious", "честолюбивый"), Map.entry("content", "довольный"),
            Map.entry("wroth", "вспыльчивый"), Map.entry("patient", "терпеливый"));

    private String traitNames(List<Object> ids) {
        List<String> out = new ArrayList<>();
        for (Object o : ids) {
            String id = String.valueOf(o);
            String name = TRAITS.get(id);
            if (name == null) for (Map<String, Object> g : orgs) if (id.equals(str(g, "id"))) name = str(g, "name");
            out.add(name == null ? id : name);
        }
        return String.join(", ", out);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        long mine = orgs.stream().filter(o -> flag(o, "joined")).count();
        WFUi.panel(g, font, x, y, W, H, "Организации  ·  вы состоите: " + mine);
        int top = y + 24, lw = 230, lh = H - 34 - 54;
        WFUi.bevel(g, x + 10, top, lw, lh, true);
        int rowH = 34;
        for (int i = 0; i < orgs.size() && (i + 1) * rowH + 6 <= lh; i++) {
            Map<String, Object> o = orgs.get(i);
            int ry = top + 3 + i * rowH;
            WFUi.rowBg(g, x + 12, ry, lw - 6, rowH - 2, i, i == sel, WFUi.over(mx, my, x + 12, ry, lw - 6, rowH - 2));
            WFArt.shield(g, x + 16, ry + 2, 22, 28, num(o.get("color")), str(o, "id"));
            g.drawString(font, str(o, "name"), x + 44, ry + 4, WFUi.TX);
            boolean j = flag(o, "joined");
            String sub = j ? list(o, "ranks").isEmpty() ? "" : String.valueOf(((Map<?, ?>) list(o, "ranks").get(num(o.get("rank")))).get("name")) : kindName(str(o, "kind"));
            g.drawString(font, sub, x + 44, ry + 17, j ? WFUi.GOLD : WFUi.MUTED);
            final int k = i;
            hits.add(new Hit(x + 12, ry, lw - 6, rowH - 2, () -> { sel = k; }, null));
        }
        int cy = top + lh + 6;
        WFUi.bevel(g, x + 10, cy, lw, 48, true);
        g.drawString(font, "Хроника", x + 16, cy + 4, WFUi.GOLD_DIM);
        for (int i = 0; i < Math.min(3, chronicle.size()); i++) {
            String s = font.plainSubstrByWidth(chronicle.get(chronicle.size() - 1 - i), lw - 14);
            g.drawString(font, s, x + 16, cy + 15 + i * 10, WFUi.MUTED);
        }
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, H - 34, true);
        if (!orgs.isEmpty()) detail(g, orgs.get(sel), rx + 10, top + 8, rw - 20, mx, my);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && WFUi.over(mx, my, h.x, h.y, h.w, h.h)) { WFUi.tooltip(g, font, mx, my, width, height, h.tip.get(0), List.of()); break; }
        }
        super.render(g, mx, my, pt);
    }

    private static String kindName(String k) { return switch (k) { case "order" -> "Орден"; case "brotherhood" -> "Братство"; default -> "Гильдия"; }; }

    @SuppressWarnings("unchecked")
    private void detail(GuiGraphics g, Map<String, Object> o, int x, int y, int w, int mx, int my) {
        boolean joined = flag(o, "joined");
        WFArt.shield(g, x, y, 40, 50, num(o.get("color")), str(o, "id"));
        g.drawString(font, str(o, "name"), x + 50, y + 2, WFUi.GOLD);
        WFUi.badge(g, font, x + 50, y + 15, kindName(str(o, "kind")), 0xFF000000 | num(o.get("color")));
        int ty = y + 30;
        for (var l : font.split(Component.literal(str(o, "desc")), w - 60)) { g.drawString(font, l, x + 50, ty, WFUi.MUTED); ty += 10; }
        int by = y + 62;
        WFUi.stat(g, font, x, by, "Сила  ", String.valueOf(o.get("power")), WFUi.TX);
        WFUi.stat(g, font, x + 110, by, "Казна  ", String.valueOf(num(o.get("treasury"))), WFUi.GOLD);
        WFUi.stat(g, font, x + 220, by, "Участников  ", String.valueOf(num(o.get("members"))), WFUi.TX);
        WFUi.stat(g, font, x + 350, by, "Взнос  ", num(o.get("joinGold")) + " / " + num(o.get("dues")) + " в нед.", WFUi.TX);
        int tw = WFUi.tabs(g, font, x, by + 18, w, TABS, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hits.add(new Hit(x + i * tw, by + 18, tw, 15, () -> tab = t, null)); }
        int cy = by + 42;
        String id = str(o, "id");
        List<Object> ranks = list(o, "ranks");
        int rank = joined ? num(o.get("rank")) : -1;
        switch (tab) {
            case 0 -> {
                if (joined) {
                    int inf = num(o.get("influence"));
                    String next = rank + 1 < ranks.size() ? ((Map<String, Object>) ranks.get(rank + 1)).get("name") + " (" + num(((Map<String, Object>) ranks.get(rank + 1)).get("influence")) + ")" : "высшее звание";
                    g.drawString(font, "Звание: " + ((Map<String, Object>) ranks.get(rank)).get("name") + "   Влияние: " + inf + "   Далее: " + next, x, cy, WFUi.TX);
                    cy += 14;
                    double frac = rank + 1 < ranks.size() ? (inf - num(((Map<String, Object>) ranks.get(rank)).get("influence"))) / (double) (num(((Map<String, Object>) ranks.get(rank + 1)).get("influence")) - num(((Map<String, Object>) ranks.get(rank)).get("influence"))) : 1;
                    WFUi.bar(g, x, cy, w, 8, Math.max(0, Math.min(1, frac)), WFUi.GOLD);
                    cy += 16;
                    g.drawString(font, "Выполнено заданий: " + num(o.get("done")) + "   В организации: " + num(o.get("since")) + " дн." + (num(o.get("late")) > 0 ? "   Долг по взносам!" : ""), x, cy, num(o.get("late")) > 0 ? WFUi.BAD : WFUi.MUTED);
                    cy += 18;
                    button(g, x, cy, 90, "Донат 50", true, mx, my, () -> cmd.accept("wforders donate " + id + " 50"), "+10 влияния, казна растёт");
                    button(g, x + 96, cy, 90, "Донат 200", true, mx, my, () -> cmd.accept("wforders donate " + id + " 200"), "+40 влияния");
                    button(g, x + 192, cy, 90, "Покинуть", true, mx, my, () -> cmd.accept("wforders leave " + id), "Вернуться можно через 20 дней");
                } else {
                    boolean can = flag(o, "canJoin");
                    button(g, x, cy, 160, "Вступить (" + num(o.get("joinGold")) + " золота)", can, mx, my, () -> cmd.accept("wforders join " + id), can ? null : str(o, "why"));
                    if (!can) { g.drawString(font, font.plainSubstrByWidth(str(o, "why"), w - 170), x + 168, cy + 4, WFUi.BAD); }
                    cy += 22;
                }
                cy += 22;
                g.drawString(font, "Ценит: " + traitNames(list(o, "liked")), x, cy, WFUi.GOOD); cy += 12;
                g.drawString(font, "Не любит: " + traitNames(list(o, "disliked")), x, cy, WFUi.BAD); cy += 12;
                g.drawString(font, "Соперники: " + (list(o, "rivals").isEmpty() ? "нет" : traitNames(list(o, "rivals"))), x, cy, WFUi.MUTED);
            }
            case 1 -> {
                if (!joined) { g.drawString(font, "Вступите, чтобы получать задания.", x, cy, WFUi.MUTED); break; }
                List<Object> cs = list(o, "contracts");
                if (cs.isEmpty()) g.drawString(font, "Сейчас заданий нет.", x, cy, WFUi.MUTED);
                for (Object co : cs) {
                    Map<String, Object> c = (Map<String, Object>) co;
                    boolean acc = flag(c, "accepted");
                    int row = cy;
                    WFUi.bevel(g, x, row, w, 44, false);
                    for (var l : font.split(Component.literal(str(c, "text")), w - 150)) { g.drawString(font, l, x + 6, row + 5, WFUi.TX); row += 10; }
                    g.drawString(font, "Награда: " + num(c.get("reward")) + " зол.  Влияние +" + num(c.get("inf")) + "  Осталось дней: " + num(c.get("left")) + (str(c, "type").equals("deed") ? "  [" + num(c.get("progress")) + "/" + num(c.get("n")) + "]" : ""), x + 6, cy + 31, WFUi.MUTED);
                    int cid = num(c.get("id"));
                    if (!acc) button(g, x + w - 130, cy + 14, 120, "Взять", true, mx, my, () -> cmd.accept("wforders accept " + cid), null);
                    else button(g, x + w - 130, cy + 14, 120, "Сдать", true, mx, my, () -> cmd.accept("wforders complete " + cid), "Предметы или золото берутся из инвентаря");
                    cy += 50;
                }
                button(g, x, cy + 4, 140, "Обновить предложения", true, mx, my, () -> cmd.accept("wforders offers " + id), null);
            }
            case 2 -> {
                if (!joined) { g.drawString(font, "Прошения доступны членам организации.", x, cy, WFUi.MUTED); break; }
                for (Object po : list(o, "petitions")) {
                    Map<String, Object> p = (Map<String, Object>) po;
                    boolean ok = flag(p, "ok");
                    WFUi.bevel(g, x, cy, w, 36, false);
                    g.drawString(font, str(p, "name") + "  (влияние " + num(p.get("cost")) + ")", x + 6, cy + 5, ok ? WFUi.TX : WFUi.OFF);
                    g.drawString(font, font.plainSubstrByWidth(ok ? str(p, "desc") : str(p, "why"), w - 150), x + 6, cy + 20, ok ? WFUi.MUTED : WFUi.BAD);
                    String pid = str(p, "id");
                    button(g, x + w - 130, cy + 10, 120, "Подать", ok, mx, my, () -> cmd.accept("wforders petition " + id + " " + pid), ok ? null : str(p, "why"));
                    cy += 42;
                }
            }
            default -> {
                for (int i = 0; i < ranks.size(); i++) {
                    Map<String, Object> r = (Map<String, Object>) ranks.get(i);
                    boolean cur = i == rank, got = i <= rank;
                    WFUi.bevel(g, x, cy, w, 34, !cur);
                    g.drawString(font, str(r, "name") + "  ·  влияние " + num(r.get("influence")), x + 8, cy + 5, cur ? WFUi.GOLD : got ? WFUi.TX : WFUi.OFF);
                    List<String> pk = new ArrayList<>();
                    for (var e : ((Map<String, Object>) r.get("perks")).entrySet()) pk.add(perkName(e.getKey()) + " +" + num(e.getValue()));
                    g.drawString(font, pk.isEmpty() ? "Без особых привилегий" : String.join(", ", pk), x + 8, cy + 19, WFUi.MUTED);
                    cy += 38;
                }
            }
        }
    }

    private static String perkName(String k) {
        return switch (k) {
            case "discount_pct" -> "скидка в торговле %";
            case "tax_relief_pct" -> "налоговая льгота %";
            case "levy_pct" -> "ополчение %";
            case "intrigue_bonus" -> "интрига";
            case "learning_bonus" -> "знания";
            default -> k;
        };
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
