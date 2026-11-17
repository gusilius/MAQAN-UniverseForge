package dev.worldforge.army.mc.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.Ledger;
import dev.worldforge.mod.client.WFArt;
import dev.worldforge.army.Economy;
import dev.worldforge.mod.client.WFUi;
import dev.worldforge.war.Attitude;
import dev.worldforge.army.Army;
import dev.worldforge.army.ArmyEngine;
import dev.worldforge.army.UnitType;
import dev.worldforge.war.War;
import dev.worldforge.war.WarEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Окно войн и дипломатии (WFUi): вкладки «Войны» (счёт, условия мира, участники, ход), «Дипломатия» (как панель EU4: отношение,
 * мнение с разбором по причинам, договоры с подсказкой почему недоступно, поводы войны), «Реестр» (сравнение государств, сортировка по
 * столбцу, как ledger Paradox) и «Журнал». Приказы уходят командами {@code /wfaction run ...} через {@code cmd}.
 */
public class ArmyScreen extends Screen {
    private static final int W = 780, H = 460;
    private static final String[] TABS = {"Армии", "Экономика", "Договоры"};
    private WarEngine e;
    private ArmyEngine ar;
    private int selArmy;
    private final String realm;
    private final Consumer<String> cmd;
    private int tab, selWar, scroll, ledgerTab, selRealm;
    private String sortKey = "area";
    private boolean asc;
    private String msg = "";
    private long msgUntil;

    private static final class Hit {
        final int x, y, w, h;
        final Runnable run;
        final String title;
        final List<String> tip;
        Runnable alt;

        Hit(int x, int y, int w, int h, Runnable run, String title, List<String> tip) { this.x = x; this.y = y; this.w = w; this.h = h; this.run = run; this.title = title; this.tip = tip; }
    }

    private final List<Hit> hits = new ArrayList<>();


    public ArmyScreen(WarEngine e, ArmyEngine armies, String realmId, Consumer<String> cmd) {
        super(Component.literal("Войны"));
        this.e = e;
        this.ar = armies;
        this.realm = realmId;
        this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public void setModel(WarEngine e, ArmyEngine a) { this.e = e; this.ar = a; }

    private int refresh;

    @Override
    public void tick() {
        if (++refresh % 40 == 0) cmd.accept("wfwar screen" + (realm == null ? "" : " " + realm));
    }

    private TitleData data() { return e.editor.data(); }

    private String name(String id) { TitleData.Realm r = data().realms.get(id); return r == null ? id : r.name; }

    private int rgb(String id) { TitleData.Realm r = data().realms.get(id); return r == null ? 0x888888 : r.rgb; }

    private void hit(int x, int y, int w, int h, Runnable run, String title, List<String> tip) { hits.add(new Hit(x, y, w, h, run, title, tip)); }

    private void say(String m) { msg = m; msgUntil = System.currentTimeMillis() + 4000; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Армии и экономика  ·  " + (realm == null ? "все государства" : name(realm)) + "  ·  день " + e.day);
        int ty = y + 24;
        int tw = WFUi.tabs(g, font, x + 10, ty, 410, TABS, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hit(x + 10 + i * (tw + 2), ty, tw, 15, () -> { tab = t; scroll = 0; }, null, null); }
        int top = ty + 20, h = H - (top - y) - 10, cx = x + 10, cw = W - 20;
        switch (tab) {
            case 0 -> armiesTab(g, cx, top, cw, h, mx, my);
            case 1 -> econTab(g, cx, top, cw, h, mx, my);
            default -> pactsTab(g, cx, top, cw, h, mx, my);
        }
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            int w = font.width(msg) + 16;
            WFUi.bevel(g, x + (W - w) / 2, y + H - 22, w, 15, true);
            g.drawString(font, msg, x + (W - w) / 2 + 8, y + H - 18, WFUi.TX);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hh = hits.get(i);
            if (hh.tip != null && WFUi.over(mx, my, hh.x, hh.y, hh.w, hh.h)) { WFUi.tooltip(g, font, mx, my, width, height, hh.title, hh.tip); break; }
        }
        super.render(g, mx, my, pt);
    }

    // ------------------------------------------------------------------ Войны

    // ------------------------------------------------------------------ Армии

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

    private void armiesTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        if (ar == null || realm == null) { g.drawString(font, "Армии доступны для выбранного государства.", x + 6, top + 6, WFUi.MUTED); return; }
        int lw = 250, dx = x + lw + 8, dw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ВОЙСКА", x + 7, top + 5, WFUi.GOLD_DIM);
        String gs = "зол. " + Math.round(ar.gold(realm)) + "  люди " + Math.round(ar.pool(realm));
        g.drawString(font, gs, x + lw - 7 - font.width(gs), top + 5, ar.gold(realm) < 0 ? WFUi.BAD : WFUi.TX);
        List<Army> mine = ar.armiesOf(realm);
        Army cur = null;
        for (Army a : mine) if (a.id == selArmy) cur = a;
        if (cur == null && !mine.isEmpty()) cur = mine.get(0);
        if (cur != null) {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_army",
                    "army:" + realm + ":" + cur.id, "army", cur.name,
                    "Men " + cur.men() + " · supply " + Math.round(cur.supply * 100)
                            + "% · morale " + Math.round(cur.morale * 100)
                            + "% · location " + ar.title(cur.location));
        } else {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_army");
        }
        int ry = top + 18;
        for (Army a : mine) {
            if (ry > top + h - 14) break;
            boolean on = a == cur;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 24, 0, on, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 24));
            g.drawString(font, font.plainSubstrByWidth(a.name + (a.mercenary ? " (наёмники)" : ""), lw - 60), x + 7, ry + 1, on ? WFUi.GOLD : WFUi.TX);
            String m = String.valueOf(a.men());
            g.drawString(font, m, x + lw - 8 - font.width(m), ry + 1, WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(a.lastEvent.isEmpty() ? ar.title(a.location) : a.lastEvent, lw - 16), x + 7, ry + 12, a.siegeTitle != 0 ? WFUi.GOLD : WFUi.MUTED);
            final int id = a.id;
            hit(x + 3, ry - 1, lw - 6, 24, () -> selArmy = id, null, null);
            ry += 26;
        }
        if (mine.isEmpty()) g.drawString(font, "Армий нет. Наберите справа.", x + 7, top + 22, WFUi.MUTED);
        WFUi.bevel(g, dx, top, dw, h, true);
        int ix = dx + 8, iw = dw - 16, cy = top + 7;
        if (cur != null) {
            Army a = cur;
            g.drawString(font, a.name + "  ·  " + ar.title(a.location), ix, cy, WFUi.GOLD);
            cy += 13;
            WFUi.kv(g, font, ix, cy, iw, 80, "Припасы", pct(a.supply), a.supply > 0.5 ? WFUi.GOOD : a.supply > 0.15 ? WFUi.GOLD : WFUi.BAD);
            WFUi.bar(g, ix + 120, cy + 2, iw - 120, 5, a.supply, a.supply > 0.5 ? WFUi.GOOD : a.supply > 0.15 ? WFUi.GOLD : WFUi.BAD);
            cy += 11;
            WFUi.kv(g, font, ix, cy, iw, 80, "Мораль", pct(a.morale), a.morale > 0.6 ? WFUi.GOOD : a.morale > 0.3 ? WFUi.GOLD : WFUi.BAD);
            WFUi.bar(g, ix + 120, cy + 2, iw - 120, 5, a.morale, a.morale > 0.6 ? WFUi.GOOD : WFUi.GOLD);
            cy += 11;
            WFUi.kv(g, font, ix, cy, iw, 80, "Приказ", a.lastEvent.isEmpty() ? "стоит" : a.lastEvent, WFUi.TX);
            cy += 11;
            double[] sg = ar.sieges.get(a.siegeTitle);
            if (sg != null) { WFUi.kv(g, font, ix, cy, iw, 80, "Осада", Math.round(sg[1]) + " из " + Math.round(sg[2]) + " дн.", WFUi.GOLD); WFUi.bar(g, ix + 120, cy + 2, iw - 120, 5, sg[1] / sg[2], WFUi.GOLD); cy += 11; }
            if (a.moving()) { WFUi.kv(g, font, ix, cy, iw, 80, "Путь", a.path.size() + " шагов до " + ar.title(a.path.get(a.path.size() - 1)), WFUi.TX); cy += 11; }
            cy += 4;
            int max = 1;
            for (int v : a.units.values()) max = Math.max(max, v);
            for (var en : a.units.entrySet()) {
                UnitType u = ar.types.get(en.getKey());
                g.drawString(font, u == null ? en.getKey() : u.name, ix, cy, WFUi.TX);
                WFUi.bar(g, ix + 100, cy + 2, iw - 150, 5, en.getValue() / (double) max, WFUi.INFO);
                String n = String.valueOf(en.getValue());
                g.drawString(font, n, ix + iw - font.width(n), cy, WFUi.TX);
                cy += 11;
            }
            cy += 6;
            final int id = a.id;
            int bw = (iw - 6) / 3, by = cy;
            btn(g, ix, by, bw, "Стоять", a.moving(), mx, my, () -> cmd.accept("wfaction run army_stop " + realm + " " + id), "Остановить армию на месте");
            btn(g, ix + bw + 3, by, bw, "Домой", true, mx, my, () -> cmd.accept("wfaction run army_move " + realm + " " + id + "|" + ar.capital(realm)), "Вернуться в столицу");
            btn(g, ix + 2 * (bw + 3), by, bw, "Распустить", true, mx, my, () -> cmd.accept("wfaction run army_disband " + realm + " " + id), "Ополченцы вернутся в запас людей");
            cy += 20;
        }
        // набор
        int ry2 = Math.max(cy + 4, top + h - 14 - 3 * 18 - 14);
        g.drawString(font, "НАБОР  (ополчение - из запаса людей, остальные - за золото; наёмники x2)", ix, ry2, WFUi.GOLD_DIM);
        ry2 += 13;
        int bw = (iw - 3) / 2, k = 0;
        for (UnitType u : ar.types.values()) {
            int n = u.levy ? 50 : u.id.equals("siege") ? 2 : 20;
            double c = ar.cost(u.id, n, false);
            int bx = ix + (k % 2) * (bw + 3), by = ry2 + (k / 2) * 17;
            if (by > top + h - 18) break;
            final UnitType uu = u; final int nn = n;
            String lab = "+" + n + " " + u.name + (c > 0 ? "  " + (int) Math.ceil(c) + "з" : "");
            boolean enable = u.levy ? ar.pool(realm) >= n && ar.gold(realm) >= c : ar.gold(realm) >= c;
            WFUi.button(g, font, bx, by, bw, 14, font.plainSubstrByWidth(lab, bw - 6), enable, false, WFUi.over(mx, my, bx, by, bw, 14));
            hit(bx, by, bw, 14, enable ? () -> cmd.accept("wfaction run army_raise " + realm + " " + uu.id + "|" + nn) : () -> say(u.levy ? "Не хватает людей или золота" : "Не хватает золота"),
                    u.name, List.of("Атака " + u.attack + "  защита " + u.defense + "  скорость " + u.speed, u.counters.isEmpty() ? "Сильных сторон нет" : "Силён против: " + counterText(u), "Содержание " + u.upkeep + " зол./10 чел. в день", "ПКМ: нанять наёмников (x2)"));
            hits.get(hits.size() - 1).alt = () -> cmd.accept("wfaction run army_hire " + realm + " " + uu.id + "|" + nn);
            k++;
        }
    }

    private String counterText(UnitType u) {
        StringBuilder b = new StringBuilder();
        for (var c : u.counters.entrySet()) { UnitType t = ar.types.get(c.getKey()); b.append(b.length() == 0 ? "" : ", ").append(t == null ? c.getKey() : t.name).append(" x").append(c.getValue()); }
        return b.toString();
    }

    private void btn(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hit(x, y, w, 15, en ? r : null, label, List.of(tip));
    }

    // ------------------------------------------------------------------ Дипломатия

    private List<String> otherRealms() {
        List<String> l = new ArrayList<>();
        for (TitleData.Realm r : data().realmList()) if (!r.id.equals(realm)) l.add(r.id);
        l.sort((a, b) -> {
            int sa = realm == null ? 0 : e.diplomacyStatus(realm, a).kind.ordinal(), sb = realm == null ? 0 : e.diplomacyStatus(realm, b).kind.ordinal();
            return sa != sb ? Integer.compare(sa, sb) : name(a).compareTo(name(b));
        });
        return l;
    }

    // ------------------------------------------------------------------ Реестр

    // ------------------------------------------------------------------ Журнал

    // ------------------------------------------------------------------ Экономика

    private static String money(double v) { return (v >= 0 ? "" : "-") + String.format("%.1f", Math.abs(v)); }

    private void econTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        var eco = ar.eco;
        var rep = eco.report(realm);
        double cost = 0;
        for (Army a : ar.armiesOf(realm)) for (var u : a.units.entrySet()) { UnitType t = ar.types.get(u.getKey()); if (t != null) cost += u.getValue() * t.upkeep * (a.mercenary ? 2 : 1) / 10.0; }
        double net = rep.net() - cost, gold = ar.gold(realm);
        int lw = 330, rx = x + lw + 8, rw = cw - lw - 8;
        int tw = (lw - 12) / 3;
        WFArt.tile(g, font, x, top, tw, 46, "КАЗНА", String.valueOf(Math.round(gold)), "золота", gold < 0 ? WFUi.BAD : WFUi.GOLD);
        WFArt.tile(g, font, x + tw + 6, top, tw, 46, "В ДЕНЬ", (net >= 0 ? "+" : "") + money(net), net >= 0 ? "прибыль" : (gold > 0 ? "хватит на " + Math.round(gold / -net) + " дн." : "долг растёт"), net >= 0 ? WFUi.GOOD : WFUi.BAD);
        WFArt.tile(g, font, x + (tw + 6) * 2, top, tw, 46, "АРМИИ", money(cost), "содержание/день", cost > rep.in() * 0.6 ? WFUi.BAD : WFUi.INFO);
        int py = top + 52, ph = h - 52;
        WFUi.bevel(g, x, py, lw, ph, true);
        int cy = py + 6;
        WFArt.ribbon(g, font, x + 4, cy, lw - 8, "ДОХОД", WFUi.GOOD);
        cy += 15;
        for (var l : rep.income) { g.drawString(font, font.plainSubstrByWidth(l.label, lw - 70), x + 8, cy, WFUi.TX); String v = "+" + money(l.value); g.drawString(font, v, x + lw - 8 - font.width(v), cy, WFUi.GOOD); cy += 11; }
        cy += 4;
        WFArt.ribbon(g, font, x + 4, cy, lw - 8, "РАСХОД", WFUi.BAD);
        cy += 15;
        for (var l : rep.expense) { g.drawString(font, font.plainSubstrByWidth(l.label, lw - 70), x + 8, cy, WFUi.TX); String v = "-" + money(l.value); g.drawString(font, v, x + lw - 8 - font.width(v), cy, WFUi.BAD); cy += 11; }
        g.drawString(font, "Содержание армий", x + 8, cy, WFUi.TX); String cv = "-" + money(cost); g.drawString(font, cv, x + lw - 8 - font.width(cv), cy, cost > 0 ? WFUi.BAD : WFUi.OFF);
        cy += 15;
        g.fill(x + 8, cy - 4, x + lw - 8, cy - 3, WFUi.EDGE);
        g.drawString(font, "Итого в день", x + 8, cy, WFUi.GOLD);
        String nv = (net >= 0 ? "+" : "") + money(net);
        g.drawString(font, nv, x + lw - 8 - font.width(nv), cy, net >= 0 ? WFUi.GOOD : WFUi.BAD);
        cy += 18;
        WFArt.ribbon(g, font, x + 4, cy, lw - 8, "НАЛОГОВАЯ ПОЛИТИКА", WFUi.GOLD);
        cy += 15;
        int bw = (lw - 16 - 6) / 3;
        for (int i = 0; i < 3; i++) {
            final int pol = i;
            int bx = x + 8 + i * (bw + 3);
            WFUi.button(g, font, bx, cy, bw, 15, new String[]{"Низкие", "Обычные", "Высокие"}[i], true, eco.taxPolicy(realm) == i, WFUi.over(mx, my, bx, cy, bw, 15));
            hit(bx, cy, bw, 15, () -> cmd.accept("wfaction run econ_tax " + realm + " " + pol), Economy.TAX_NAMES[i], List.of("Доход x" + Economy.TAX_MULT[i]));
        }
        cy += 20;
        var vs = eco.vassals(realm);
        if (!vs.isEmpty()) {
            WFArt.ribbon(g, font, x + 4, cy, lw - 8, "ДАНЬ ВАССАЛОВ  (" + vs.size() + ")", WFUi.INFO);
            cy += 15;
            int rate = eco.tributeRate(realm);
            g.drawString(font, rate + "% от дохода вассала", x + 8, cy + 3, WFUi.TX);
            for (int k = 0; k < 2; k++) {
                final int nr = Math.max(0, Math.min(Economy.MAX_TRIBUTE, rate + (k == 0 ? -5 : 5)));
                int bx = x + lw - 8 - 2 * 30 - 3 + k * 33;
                WFUi.button(g, font, bx, cy, 30, 15, k == 0 ? "-5" : "+5", true, false, WFUi.over(mx, my, bx, cy, 30, 15));
                hit(bx, cy, 30, 15, () -> cmd.accept("wfaction run econ_tribute " + realm + " " + nr), "Дань", List.of("Высокая дань злит вассалов"));
            }
            cy += 18;
        }
        // ---- владения
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "ВЛАДЕНИЯ  (развитие даёт +25% дохода за уровень)", rx + 8, top + 6, WFUi.GOLD_DIM);
        List<Integer> bs = new ArrayList<>(ar.baroniesOf(realm));
        bs.sort((p, q) -> Integer.compare(eco.level(q), eco.level(p)));
        int room = (h - 26) / 14, ry = top + 20;
        scroll = Math.max(0, Math.min(Math.max(0, bs.size() - room), scroll));
        for (int i = scroll; i < bs.size() && i < scroll + room; i++) {
            int b = bs.get(i);
            WFUi.rowBg(g, rx + 3, ry - 1, rw - 6, 14, i, false, WFUi.over(mx, my, rx + 3, ry - 1, rw - 6, 14));
            g.drawString(font, font.plainSubstrByWidth(ar.title(b), 130), rx + 8, ry + 2, WFUi.TX);
            int lv = eco.level(b);
            for (int k = 0; k < Economy.MAX_DEV; k++) g.fill(rx + 142 + k * 9, ry + 3, rx + 149 + k * 9, ry + 9, k < lv ? WFUi.GOLD : 0xFF2A2C34);
            double f = eco.fortOf(b);
            if (f > 1) g.drawString(font, "форт " + Math.round(f), rx + 194, ry + 2, WFUi.MUTED);
            String iv = "+" + money(eco.baronyIncome(b));
            g.drawString(font, iv, rx + 242, ry + 2, WFUi.GOOD);
            boolean can = lv < Economy.MAX_DEV && gold >= eco.developCost(b);
            String lab = lv >= Economy.MAX_DEV ? "макс." : Math.round(eco.developCost(b)) + " зол.";
            int bx = rx + rw - 78;
            WFUi.button(g, font, bx, ry, 70, 13, lab, can, false, WFUi.over(mx, my, bx, ry, 70, 13));
            final int bb = b;
            hit(bx, ry, 70, 13, can ? () -> cmd.accept("wfaction run econ_develop " + realm + " " + bb) : () -> say(lv >= Economy.MAX_DEV ? "Развитие на пределе" : "Не хватает золота"), "Развить " + ar.title(b), List.of("Уровень " + lv + " из " + Economy.MAX_DEV, "Стоит " + Math.round(eco.developCost(b)) + " золота"));
            ry += 14;
        }
        WFUi.scrollbar(g, rx + rw - 5, top + 18, h - 22, bs.size() * 14, scroll * 14);
    }


    // ------------------------------------------------------------------ Договоры

    /** Торговые договоры с другими государствами: взаимный доход золота, пока нет войны. */
    private void pactsTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        if (realm == null) { g.drawString(font, "Выберите государство на карте: вкладка работает от его имени.", x + 6, top + 6, WFUi.MUTED); return; }
        WFUi.bevel(g, x, top, cw, h, true);
        g.drawString(font, "ГОСУДАРСТВО", x + 22, top + 5, WFUi.GOLD_DIM);
        g.drawString(font, "ДОХОД В ДЕНЬ", x + 300, top + 5, WFUi.GOLD_DIM);
        List<String> list = otherRealms();
        int rows = (h - 22) / 16, ry = top + 18;
        scroll = Math.max(0, Math.min(Math.max(0, list.size() - rows), scroll));
        for (int i = scroll; i < list.size() && i < scroll + rows; i++) {
            String id = list.get(i);
            boolean has = ar.hasPact(realm, id);
            boolean war = e.warBetween(realm, id) != null;
            WFUi.rowBg(g, x + 3, ry - 1, cw - 6, 16, i, false, WFUi.over(mx, my, x + 3, ry - 1, cw - 6, 16));
            WFArt.shield(g, x + 5, ry, 12, 14, rgb(id), id);
            g.drawString(font, font.plainSubstrByWidth(name(id), 250), x + 22, ry + 3, WFUi.TX);
            g.drawString(font, "+" + Math.round(ar.pactIncome(realm, id) * 10) / 10.0, x + 300, ry + 3, has ? WFUi.GOOD : WFUi.MUTED);
            String lab = has ? "Расторгнуть" : war ? "Война" : "Заключить";
            int bx = x + cw - 110;
            WFUi.button(g, font, bx, ry, 100, 14, lab, has || !war, has, WFUi.over(mx, my, bx, ry, 100, 14));
            final String target = id;
            hit(bx, ry, 100, 14, () -> { if (!has && war) { say("Идёт война"); return; } cmd.accept("wfaction run " + (has ? "trade_cancel " : "trade_propose ") + realm + " " + target); },
                    "Торговля", List.of("Оба государства получают золото каждый день", "Принимает тот, кому вы не противны; война рвёт договор"));
            ry += 16;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 || button == 1) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                Runnable r = button == 0 ? h.run : h.alt;
                if (WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h) && r != null) { r.run(); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        scroll = Math.max(0, scroll - (int) Math.signum(d) * 2);
        return true;
    }
}
