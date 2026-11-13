package dev.worldforge.war.mc.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.Ledger;
import dev.worldforge.mod.client.WFArt;
import dev.worldforge.mod.client.WFUi;
import dev.worldforge.war.Attitude;
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
public class WarScreen extends Screen {
    private static final int W = 780, H = 460;
    private static final String[] TABS = {"Войны", "Дипломатия", "Реестр", "Журнал"};
    private WarEngine e;
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


    public WarScreen(WarEngine e, String realmId, Consumer<String> cmd) {
        super(Component.literal("Войны"));
        this.e = e;
        this.realm = realmId;
        this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public void setModel(WarEngine e) { this.e = e; }

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

    private List<War> wars() {
        List<War> l = new ArrayList<>();
        for (War w : e.wars.values()) if (w.active && (realm == null || w.sides.containsKey(realm))) l.add(w);
        return l;
    }

    private static int attColor(int v) { return v >= 20 ? WFUi.GOOD : v <= -20 ? WFUi.BAD : WFUi.MUTED; }

    private static int statusColor(WarEngine.Status k) {
        return switch (k) { case ENEMY -> WFUi.BAD; case ALLY -> WFUi.GOOD; case OVERLORD -> 0xFFB06BE0; case SUBJECT -> WFUi.INFO; case TRUCE -> WFUi.GOLD; default -> WFUi.MUTED; };
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, "Войны и дипломатия  ·  " + (realm == null ? "все государства" : name(realm)) + "  ·  день " + e.day);
        int ty = y + 24;
        int tw = WFUi.tabs(g, font, x + 10, ty, 410, TABS, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hit(x + 10 + i * (tw + 2), ty, tw, 15, () -> { tab = t; scroll = 0; }, null, null); }
        int top = ty + 20, h = H - (top - y) - 10, cx = x + 10, cw = W - 20;
        switch (tab) {
            case 0 -> warsTab(g, cx, top, cw, h, mx, my);
            case 1 -> diploTab(g, cx, top, cw, h, mx, my);
            case 2 -> ledgerTab(g, cx, top, cw, h, mx, my);
            default -> logTab(g, cx, top, cw, h);
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

    private void warsTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        List<War> wars = wars();
        int lw = 190, dx = x + lw + 8, dw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ИДУЩИЕ ВОЙНЫ", x + 6, top + 5, WFUi.GOLD_DIM);
        War cur = null;
        for (War w : wars) if (w.id == selWar) cur = w;
        if (cur == null && !wars.isEmpty()) cur = wars.get(0);
        int ry = top + 18;
        for (War w : wars) {
            boolean on = w == cur;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 26, 0, on, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 26));
            WFArt.shield(g, x + 5, ry, 11, 13, rgb(w.attacker), w.attacker);
            WFArt.shield(g, x + 5, ry + 13, 11, 13, rgb(w.defender), w.defender);
            g.drawString(font, font.plainSubstrByWidth(name(w.attacker), lw - 50), x + 20, ry + 1, on ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(name(w.defender), lw - 50), x + 20, ry + 13, on ? WFUi.GOLD : WFUi.TX);
            String sc = String.valueOf(Math.round(w.score));
            g.drawString(font, sc, x + lw - 8 - font.width(sc), ry + 7, w.score > 5 ? WFUi.BAD : w.score < -5 ? WFUi.INFO : WFUi.MUTED);
            final int id = w.id;
            hit(x + 3, ry - 1, lw - 6, 26, () -> selWar = id, null, null);
            ry += 28;
        }
        WFUi.bevel(g, dx, top, dw, h, true);
        if (cur == null) {
            g.drawString(font, "Нет войн.", x + 7, top + 20, WFUi.MUTED);
            g.drawString(font, "Войну можно объявить на вкладке «Дипломатия».", dx + 8, top + 8, WFUi.MUTED);
            return;
        }
        War w = cur;
        int ix = dx + 8, iw = dw - 16, cy = top + 7;
        var cb = e.cbs.get(w.cb);
        WFArt.versus(g, font, ix, cy, iw, name(w.attacker), rgb(w.attacker), w.attacker, name(w.defender), rgb(w.defender), w.defender);
        cy += 40;
        String cbl = (cb == null ? w.cb : cb.name) + " · идёт " + (e.day - w.startDay) + " дн.";
        g.drawString(font, cbl, ix + (iw - font.width(cbl)) / 2, cy, WFUi.MUTED);
        cy += 14;
        WFUi.balance(g, ix, cy + 3, iw, 9, w.score / 100.0, WFUi.INFO, WFUi.BAD);
        g.drawString(font, "обороняющиеся", ix, cy + 15, WFUi.INFO);
        String r = "нападающие";
        g.drawString(font, r, ix + iw - font.width(r), cy + 15, WFUi.BAD);
        String sc = "счёт " + Math.round(w.score);
        g.drawString(font, sc, ix + (iw - font.width(sc)) / 2, cy + 15, WFUi.TX);
        cy += 30;
        double need = e.goalCost(w);
        WFUi.kv(g, font, ix, cy, iw, 110, "Очков на условия", Math.round(Math.max(0, w.score)) + " из " + Math.round(need), w.score >= need ? WFUi.GOOD : WFUi.TX);
        cy += 11;
        if (w.offerFrom != null) { WFUi.kv(g, font, ix, cy, iw, 110, "Предложение мира", (w.offerFrom == War.Side.ATTACKER ? name(w.attacker) : name(w.defender)) + (w.offerWhite ? " (белый)" : " (условия)"), WFUi.GOLD); cy += 11; }
        if (!w.goals.isEmpty()) {
            StringBuilder s = new StringBuilder();
            for (int id : w.goals) { TitleData.Title t = data().get(id); if (t != null) s.append(s.length() == 0 ? "" : ", ").append(t.name); }
            WFUi.kv(g, font, ix, cy, iw, 110, "Цели", s.toString(), WFUi.TX);
            cy += 11;
        }
        cy += 6;
        int half = iw / 2 - 4;
        g.drawString(font, "НАПАДАЮЩИЕ", ix, cy, WFUi.GOLD_DIM);
        g.drawString(font, "ОБОРОНЯЮЩИЕСЯ", ix + half + 8, cy, WFUi.GOLD_DIM);
        cy += 12;
        int ya = members(g, w.members(War.Side.ATTACKER), ix, half, cy), yd = members(g, w.members(War.Side.DEFENDER), ix + half + 8, half, cy);
        cy = Math.max(ya, yd) + 6;
        g.drawString(font, "ХОД ВОЙНЫ", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        int room = Math.max(0, (top + h - 30 - cy) / 10);
        for (int i = Math.max(0, w.log.size() - room); i < w.log.size(); i++) { g.drawString(font, font.plainSubstrByWidth(w.log.get(i), iw), ix, cy, WFUi.MUTED); cy += 10; }
        boolean leader = realm != null && (realm.equals(w.attacker) || realm.equals(w.defender));
        War.Side mine = leader ? (realm.equals(w.attacker) ? War.Side.ATTACKER : War.Side.DEFENDER) : null;
        String[] labels = {"Белый мир", "Условия", "Навязать", "Принять", "Сдаться"}, ids = {"war_peace_white", "war_peace_demand", "war_enforce", "war_peace_accept", "war_surrender"};
        String[] why = {"", "Не хватает очков войны", "Не хватает очков войны", "Нет предложения противника", ""};
        boolean[] en = {leader, leader && mine != null && w.scoreFor(mine) >= need, leader && mine != null && w.scoreFor(mine) >= need, leader && w.offerFrom != null && w.offerFrom != mine, leader};
        int bw = (iw - 4 * 3) / 5, by = top + h - 22;
        for (int i = 0; i < 5; i++) {
            int bx = ix + i * (bw + 3);
            WFUi.button(g, font, bx, by, bw, 15, labels[i], en[i], false, WFUi.over(mx, my, bx, by, bw, 15));
            final int k = i;
            hit(bx, by, bw, 15, () -> { if (en[k]) cmd.accept("wfaction run " + ids[k] + " " + realm + " " + w.id); else say(leader ? why[k] : "Мир заключают только главы сторон"); }, en[i] ? null : labels[i], en[i] ? null : List.of("-" + (leader ? why[i] : "Мир заключают только главы сторон")));
        }
    }

    private int members(GuiGraphics g, List<String> ids, int x, int width, int y) {
        for (int i = 0; i < ids.size() && i < 5; i++) {
            String m = ids.get(i);
            WFUi.swatch(g, x, y, rgb(m));
            g.drawString(font, font.plainSubstrByWidth(name(m), width - 14), x + 13, y + 1, m.equals(realm) ? WFUi.GOLD : WFUi.TX);
            y += 11;
        }
        if (ids.size() > 5) { g.drawString(font, "и ещё " + (ids.size() - 5), x + 13, y + 1, WFUi.MUTED); y += 11; }
        return y;
    }

    // ------------------------------------------------------------------ Армии

    private static String pct(double v) { return Math.round(v * 100) + "%"; }

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

    private void diploTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        if (realm == null) { g.drawString(font, "Выберите государство на карте: вкладка работает от его имени.", x + 6, top + 6, WFUi.MUTED); return; }
        int lw = 330, dx = x + lw + 8, dw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        String[] heads = {"Государство", "Отношение", "Мнение"};
        int[] cols = {x + 22, x + 150, x + 226};
        for (int i = 0; i < 3; i++) g.drawString(font, heads[i].toUpperCase(), cols[i], top + 5, WFUi.GOLD_DIM);
        List<String> list = otherRealms();
        int rows = (h - 22) / 14, ry = top + 18;
        scroll = Math.max(0, Math.min(Math.max(0, list.size() - rows), scroll));
        String sel = selRealm >= 0 && selRealm < list.size() ? list.get(selRealm) : null;
        for (int i = scroll; i < list.size() && i < scroll + rows; i++) {
            String id = list.get(i);
            WarEngine.DipStatus st = e.diplomacyStatus(realm, id);
            Attitude at = Attitude.of(e, realm, id);
            boolean on = i == selRealm;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 14, i, on, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 14));
            WFArt.shield(g, x + 5, ry, 12, 14, rgb(id), id);
            g.drawString(font, font.plainSubstrByWidth(name(id), 140), cols[0], ry + 2, on ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(st.text, 84), cols[1], ry + 2, statusColor(st.kind));
            String v = (at.total > 0 ? "+" : "") + at.total;
            g.drawString(font, v, cols[2], ry + 2, attColor(at.total));
            g.drawString(font, font.plainSubstrByWidth(at.label(), 70), cols[2] + 26, ry + 2, WFUi.MUTED);
            final int idx = i;
            hit(x + 3, ry - 1, lw - 6, 14, () -> selRealm = idx, null, null);
            ry += 14;
        }
        WFUi.scrollbar(g, x + lw - 5, top + 18, h - 22, list.size() * 14, scroll * 14);
        WFUi.bevel(g, dx, top, dw, h, true);
        if (sel == null) { g.drawString(font, "Выберите государство в списке.", dx + 8, top + 8, WFUi.MUTED); return; }
        int ix = dx + 8, iw = dw - 16, cy = top + 7;
        WFArt.shield(g, ix, cy, 24, 29, rgb(sel), sel);
        g.drawString(font, name(sel), ix + 32, cy + 2, WFUi.GOLD);
        TitleData.Realm sr = data().realms.get(sel);
        if (sr != null && sr.ruler != null && !sr.ruler.isEmpty()) g.drawString(font, "Правитель: " + sr.ruler, ix + 32, cy + 15, WFUi.MUTED);
        cy += 34;
        if (sr != null && Boolean.TRUE.equals(sr.props.get("emergence.virtual"))) {
            String population = String.valueOf(sr.props.getOrDefault("population", 0));
            String stability = String.valueOf(sr.props.getOrDefault("stability", 0));
            String settlements = String.valueOf(sr.props.getOrDefault("emergence.settlement_count", 0));
            String government = String.valueOf(sr.props.getOrDefault("politics.government", "Форма правления не определена"));
            String level = String.valueOf(sr.props.getOrDefault("politics.realm_level", "—"));
            int doctrines = sr.props.get("politics.doctrine_count") instanceof Number n ? n.intValue() : 0;
            int doctrineSlots = sr.props.get("politics.doctrine_slots") instanceof Number n ? n.intValue() : 0;
            int laws = sr.props.get("politics.law_count") instanceof Number n ? n.intValue() : 0;
            int lawSlots = sr.props.get("politics.law_slots") instanceof Number n ? n.intValue() : 0;
            g.drawString(font, "НАСЕЛЕНИЕ " + population + " · ПОСЕЛЕНИЯ " + settlements
                    + " · СТАБИЛЬНОСТЬ " + stability + "%", ix, cy, WFUi.GOLD_DIM);
            cy += 11;
            g.drawString(font, font.plainSubstrByWidth("Строй: " + government + " · уровень: " + level, iw), ix, cy, WFUi.TX);
            cy += 11;
            g.drawString(font, "Доктрины " + doctrines + "/" + doctrineSlots + " · законы " + laws + "/" + lawSlots,
                    ix, cy, WFUi.MUTED);
            cy += 14;
        }
        WarEngine.DipStatus st = e.diplomacyStatus(realm, sel);
        WFUi.badge(g, font, ix, cy, st.text, statusColor(st.kind));
        cy += 17;
        Attitude at = Attitude.of(e, realm, sel);
        g.drawString(font, "МНЕНИЕ О ВАС: " + at.total + " (" + at.label() + ")", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        for (int i = 0; i < at.reasons.size() && i < 6; i++) {
            int v = at.values.get(i);
            g.drawString(font, font.plainSubstrByWidth(at.reasons.get(i), iw - 40), ix, cy, WFUi.MUTED);
            String vs = (v > 0 ? "+" : "") + v;
            g.drawString(font, vs, ix + iw - font.width(vs), cy, v >= 0 ? WFUi.GOOD : WFUi.BAD);
            cy += 10;
        }
        cy += 6;
        g.drawString(font, "ДОГОВОРЫ", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        List<WarEngine.Treaty> ts = e.treatyOptions(realm, sel);
        int bw = (iw - 3) / 2;
        for (int i = 0; i < ts.size(); i++) {
            WarEngine.Treaty t = ts.get(i);
            int bx = ix + (i % 2) * (bw + 3), by = cy + (i / 2) * 18;
            WFUi.button(g, font, bx, by, bw, 15, t.label, t.available, false, WFUi.over(mx, my, bx, by, bw, 15));
            final String target = sel;
            hit(bx, by, bw, 15, () -> {
                if (!t.available) { say(t.reason); return; }
                if (t.id.equals("declare_war")) { say("Выберите повод ниже"); return; }
                cmd.accept("wfaction run " + t.action + " " + realm + " " + (t.warId != 0 ? t.warId : target));
            }, t.label, t.available ? List.of("Нажмите, чтобы отправить предложение") : List.of("-" + t.reason));
        }
        cy += ((ts.size() + 1) / 2) * 18;
        g.drawString(font, "ПОВОДЫ ВОЙНЫ", ix, cy, WFUi.GOLD_DIM);
        cy += 12;
        for (WarEngine.CbOption o : e.cbOptions(realm, sel)) {
            if (cy > top + h - 16) break;
            boolean hov = WFUi.over(mx, my, ix, cy - 1, iw, 13);
            WFUi.rowBg(g, ix, cy - 1, iw, 13, 0, false, hov && o.available);
            g.drawString(font, o.cb.name, ix + 4, cy + 1, o.available ? WFUi.TX : WFUi.OFF);
            String info = o.available ? "перемирие " + o.cb.truceDays + " дн." : o.reason;
            g.drawString(font, font.plainSubstrByWidth(info, iw - font.width(o.cb.name) - 20), ix + iw - Math.min(font.width(info), iw - font.width(o.cb.name) - 20) - 4, cy + 1, o.available ? WFUi.MUTED : WFUi.BAD);
            final String target = sel;
            final WarEngine.CbOption oo = o;
            hit(ix, cy - 1, iw, 13, () -> {
                if (!oo.available) { say(oo.reason); return; }
                String goals = "";
                if (oo.cb.goal == dev.worldforge.war.CasusBelli.Goal.CONQUER) { List<TitleData.Title> gc = e.goalCandidates(target); if (!gc.isEmpty()) goals = "|" + gc.get(0).id; }
                cmd.accept("wfaction run war_declare " + realm + " " + target + "|" + oo.cb.id + goals);
            }, o.cb.name, o.available ? List.of("Объявить войну", "Цель для завоевания: первый титул противника") : List.of("-" + o.reason));
            cy += 14;
        }
    }

    // ------------------------------------------------------------------ Реестр

    private void ledgerTab(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        Ledger L = Ledger.build(data(), realm);
        int cx = x;
        for (int i = 0; i < Ledger.TABS.length; i++) {
            int w = font.width(Ledger.TABS[i]) + 14;
            WFUi.button(g, font, cx, top, w, 14, Ledger.TABS[i], true, i == ledgerTab, WFUi.over(mx, my, cx, top, w, 14));
            final int t = i;
            hit(cx, top, w, 14, () -> { ledgerTab = t; scroll = 0; }, null, null);
            cx += w + 3;
        }
        List<Ledger.Col> cols = L.columns(ledgerTab);
        int ty = top + 20, nameW = 150;
        int avail = cw - nameW - 12, n = Math.max(1, cols.size() - 1), colW = Math.max(46, Math.min(86, avail / n));
        WFUi.bevel(g, x, ty, cw, h - 20, true);
        int hx = x + 6;
        for (Ledger.Col c : cols) {
            int w = c.key.equals("name") ? nameW : colW;
            String lab = font.plainSubstrByWidth(c.label, w - 8) + (c.key.equals(sortKey) ? (asc ? " ^" : " v") : "");
            boolean hov = WFUi.over(mx, my, hx, ty + 2, w - 2, 13);
            g.drawString(font, lab, hx, ty + 5, hov || c.key.equals(sortKey) ? WFUi.GOLD : WFUi.GOLD_DIM);
            final String key = c.key;
            hit(hx, ty + 2, w - 2, 13, () -> { if (key.equals(sortKey)) asc = !asc; else { sortKey = key; asc = false; } }, null, null);
            hx += w;
        }
        List<Ledger.Row> rows = L.view(sortKey, asc, "");
        int rh = 13, room = (h - 20 - 36) / rh;
        scroll = Math.max(0, Math.min(Math.max(0, rows.size() - room), scroll));
        int ry = ty + 18;
        for (int i = scroll; i < rows.size() && i < scroll + room; i++) {
            Ledger.Row r = rows.get(i);
            WFUi.rowBg(g, x + 3, ry - 1, cw - 6, rh, i, r.viewer, WFUi.over(mx, my, x + 3, ry - 1, cw - 6, rh));
            int px = x + 6;
            for (Ledger.Col c : cols) {
                int w = c.key.equals("name") ? nameW : colW;
                if (c.key.equals("name")) { WFUi.swatch(g, px, ry + 1, r.rgb); g.drawString(font, font.plainSubstrByWidth(r.name, w - 18), px + 13, ry + 2, r.viewer ? WFUi.GOLD : WFUi.TX); }
                else g.drawString(font, font.plainSubstrByWidth(L.cell(c, r), w - 6), px, ry + 2, c.numeric() ? WFUi.TX : WFUi.MUTED);
                px += w;
            }
            ry += rh;
        }
        WFUi.scrollbar(g, x + cw - 5, ty + 18, h - 20 - 22, rows.size() * rh, scroll * rh);
        var totals = L.totals(rows, cols);
        int px = x + 6, by = ty + h - 20 - 14;
        g.fill(x + 3, by - 2, x + cw - 3, by - 1, WFUi.HI);
        for (Ledger.Col c : cols) {
            int w = c.key.equals("name") ? nameW : colW;
            if (c.key.equals("name")) g.drawString(font, "Итого", px, by + 1, WFUi.MUTED);
            else if (totals.containsKey(c.key)) g.drawString(font, font.plainSubstrByWidth(Ledger.fmtNum(c.kind, totals.get(c.key)), w - 6), px, by + 1, WFUi.GOLD);
            px += w;
        }
    }

    // ------------------------------------------------------------------ Журнал

    // ------------------------------------------------------------------ Экономика

    private static String money(double v) { return (v >= 0 ? "" : "-") + String.format("%.1f", Math.abs(v)); }

    private void logTab(GuiGraphics g, int x, int top, int cw, int h) {
        WFUi.bevel(g, x, top, cw, h, true);
        List<String> lines = new ArrayList<>();
        for (War w : e.wars.values()) {
            lines.add("#" + w.id + " " + name(w.attacker) + " против " + name(w.defender) + (w.active ? "" : "  (закончена)"));
            for (String s : w.log) lines.add("   " + s);
        }
        if (lines.isEmpty()) lines.add("Пока ни одной войны.");
        int room = (h - 12) / 10;
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - room), scroll));
        int y = top + 6;
        for (int i = scroll; i < lines.size() && i < scroll + room; i++) {
            String s = lines.get(i);
            g.drawString(font, font.plainSubstrByWidth(s, cw - 20), x + 8, y, s.startsWith("#") ? WFUi.GOLD : WFUi.MUTED);
            y += 10;
        }
        WFUi.scrollbar(g, x + cw - 5, top + 4, h - 8, lines.size() * 10, scroll * 10);
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
