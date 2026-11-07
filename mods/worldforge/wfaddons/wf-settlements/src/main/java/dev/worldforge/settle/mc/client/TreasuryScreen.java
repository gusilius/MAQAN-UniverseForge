package dev.worldforge.settle.mc.client;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.client.WFUi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Окно «Казна и города»: баланс и книга по источникам, города государства (налог, настроение, отправлено в казну), доля короны, налоговая политика и гранты. */
public class TreasuryScreen extends Screen {
    private static final int W = 780, H = 450;
    private static final String[] POLICY = {"Нет", "Низкий", "Обычный", "Высокий"};
    private Map<String, Object> m = new LinkedHashMap<>();
    private List<Map<String, Object>> cities = new ArrayList<>(), ledger = new ArrayList<>();
    private int sel, refresh, grant = 20;
    private final Consumer<String> cmd;

    private record Hit(int x, int y, int w, int h, Runnable run, String tip) {}
    private final List<Hit> hits = new ArrayList<>();

    public TreasuryScreen(String payload, Consumer<String> cmd) { super(Component.literal("Казна")); this.cmd = cmd; setModel(payload); }

    @SuppressWarnings("unchecked")
    public void setModel(String payload) {
        m = (Map<String, Object>) Json.parse(payload);
        cities = new ArrayList<>(); ledger = new ArrayList<>();
        for (Object o : list("cities")) cities.add((Map<String, Object>) o);
        for (Object o : list("ledger")) ledger.add((Map<String, Object>) o);
        if (sel >= cities.size()) sel = 0;
    }

    @SuppressWarnings("unchecked") private List<Object> list(String k) { return m.get(k) instanceof List ? (List<Object>) m.get(k) : List.of(); }
    private static String str(Map<String, Object> x, String k) { Object o = x.get(k); return o == null ? "" : String.valueOf(o); }
    private static double dbl(Map<String, Object> x, String k) { return x.get(k) instanceof Number n ? n.doubleValue() : 0; }
    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v); }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (++refresh % 100 == 0) cmd.accept("wftax"); }

    private void button(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hits.add(new Hit(x, y, w, 15, en ? r : null, tip));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        String realm = str(m, "realm");
        WFUi.panel(g, font, x, y, W, H, "Казна и города  ·  " + realm);
        int top = y + 24, bodyH = H - 34, lw = 300;
        // казна
        WFUi.bevel(g, x + 10, top, lw, 78, true);
        g.drawString(font, "КАЗНА", x + 18, top + 6, WFUi.GOLD_DIM);
        String bal = fmt(dbl(m, "balance")) + " зол.";
        g.drawString(font, bal, x + 18, top + 20, dbl(m, "balance") < 0 ? WFUi.BAD : WFUi.GOLD);
        g.drawString(font, "Города отдают: ~" + fmt(dbl(m, "perMin")) + " зол./мин", x + 18, top + 36, WFUi.TX);
        g.drawString(font, "Получено налогов: " + fmt(dbl(m, "remittedGold")) + "   гранты: " + fmt(dbl(m, "grantedGold")), x + 18, top + 48, WFUi.MUTED);
        // книга
        int ly = top + 84;
        WFUi.bevel(g, x + 10, ly, lw, bodyH - 84, true);
        g.drawString(font, "ОТКУДА ДЕНЬГИ (за всё время)", x + 18, ly + 6, WFUi.GOLD_DIM);
        double max = 1;
        for (Map<String, Object> l : ledger) max = Math.max(max, Math.abs(dbl(l, "value")));
        for (int i = 0; i < ledger.size() && 22 + i * 24 < bodyH - 100; i++) {
            Map<String, Object> l = ledger.get(i);
            int ry = ly + 20 + i * 24; double v = dbl(l, "value");
            g.drawString(font, font.plainSubstrByWidth(str(l, "label"), lw - 90), x + 18, ry, WFUi.TX);
            String vs = (v > 0 ? "+" : "") + fmt(v);
            g.drawString(font, vs, x + 10 + lw - 8 - font.width(vs), ry, v >= 0 ? WFUi.GOOD : WFUi.BAD);
            WFUi.bar(g, x + 18, ry + 11, lw - 16, 5, Math.abs(v) / max, v >= 0 ? WFUi.GOOD : WFUi.BAD);
        }
        if (ledger.isEmpty()) g.drawString(font, "Операций пока нет.", x + 18, ly + 22, WFUi.MUTED);
        // города
        int rx = x + lw + 20, rw = W - lw - 30;
        WFUi.bevel(g, rx, top, rw, bodyH - 66, true);
        g.drawString(font, "Город", rx + 10, top + 6, WFUi.GOLD_DIM); g.drawString(font, "Жители", rx + 150, top + 6, WFUi.GOLD_DIM); g.drawString(font, "Настр.", rx + 200, top + 6, WFUi.GOLD_DIM);
        g.drawString(font, "Налог/мин", rx + 250, top + 6, WFUi.GOLD_DIM); g.drawString(font, "Отдано", rx + 320, top + 6, WFUi.GOLD_DIM); g.drawString(font, "Налог", rx + 380, top + 6, WFUi.GOLD_DIM);
        if (cities.isEmpty()) g.drawString(font, "В государстве нет поселений.", rx + 10, top + 24, WFUi.MUTED);
        for (int i = 0; i < cities.size() && (i + 1) * 18 + 22 < bodyH - 66; i++) {
            Map<String, Object> c = cities.get(i);
            int ry = top + 20 + i * 18;
            WFUi.rowBg(g, rx + 2, ry, rw - 4, 17, i, i == sel, WFUi.over(mx, my, rx + 2, ry, rw - 4, 17));
            g.drawString(font, font.plainSubstrByWidth(str(c, "name"), 135), rx + 10, ry + 5, WFUi.TX);
            g.drawString(font, fmt(dbl(c, "people")), rx + 150, ry + 5, WFUi.MUTED);
            double ap = dbl(c, "approval");
            g.drawString(font, fmt(ap), rx + 200, ry + 5, ap < 25 ? WFUi.BAD : ap < 45 ? WFUi.GOLD : WFUi.GOOD);
            g.drawString(font, fmt(dbl(c, "taxMin")), rx + 250, ry + 5, WFUi.MUTED);
            g.drawString(font, fmt(dbl(c, "remitted")), rx + 320, ry + 5, WFUi.MUTED);
            g.drawString(font, POLICY[(int) Math.max(0, Math.min(3, dbl(c, "tax")))], rx + 380, ry + 5, WFUi.MUTED);
            final int k = i;
            hits.add(new Hit(rx + 2, ry, rw - 4, 17, () -> sel = k, null));
        }
        // управление
        int by = top + bodyH - 60;
        WFUi.bevel(g, rx, by, rw, 60, true);
        int share = (int) dbl(m, "share");
        g.drawString(font, "Доля короны: " + share + "%", rx + 10, by + 6, share > 40 ? WFUi.BAD : WFUi.TX);
        for (int i = 0, bx = rx + 130; i < 5; i++) {
            int v = new int[]{5, 15, 25, 40, 60}[i];
            button(g, bx, by + 3, 42, v + "%", true, mx, my, () -> cmd.accept("wfaction run city_crown " + realm + " " + v), "Доля налогов, уходящая в казну. Выше 25% горожане недовольны");
            bx += 46;
        }
        int pol = (int) dbl(m, "policy");
        g.drawString(font, "Политика: " + (pol < 0 ? "города решают сами" : POLICY[pol]), rx + 10, by + 24, WFUi.TX);
        for (int i = -1, bx = rx + 190; i < 4; i++) {
            final int v = i;
            button(g, bx, by + 21, 40, i < 0 ? "Сам" : POLICY[i].substring(0, Math.min(4, POLICY[i].length())), true, mx, my, () -> cmd.accept("wfaction run city_tax " + realm + " " + v), "Налоговый уровень во всех городах государства");
            bx += 44;
        }
        if (!cities.isEmpty()) {
            Map<String, Object> c = cities.get(Math.min(sel, cities.size() - 1));
            String id = str(c, "id");
            g.drawString(font, "Грант " + str(c, "name") + ": " + grant + " зол.", rx + 10, by + 43, WFUi.MUTED);
            button(g, rx + 200, by + 40, 20, "-", true, mx, my, () -> grant = Math.max(5, grant - 5), "Меньше");
            button(g, rx + 224, by + 40, 20, "+", true, mx, my, () -> grant = Math.min(500, grant + 5), "Больше");
            button(g, rx + 250, by + 40, 90, "Выдать", dbl(m, "balance") >= grant, mx, my, () -> cmd.accept("wfaction run city_grant " + realm + " " + id + "|" + grant), "Золото казны превращается в серебро города (1 к 5)");
        }
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
