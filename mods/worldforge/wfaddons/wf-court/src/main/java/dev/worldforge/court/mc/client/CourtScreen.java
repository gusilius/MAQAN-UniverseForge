package dev.worldforge.court.mc.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.client.WFArt;
import dev.worldforge.mod.client.WFUi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.court.Council;
import dev.worldforge.court.Marriages;
import dev.worldforge.court.CourtSim;
import dev.worldforge.court.EventDef;
import dev.worldforge.court.Faction;
import dev.worldforge.court.Opinions;
import dev.worldforge.court.Scheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Окно двора (WFUi): «Совет» (правитель, советники, что даёт каждый), «Вассалы» (мнение, фракции, дары и подавление), «Козни»,
 * «События» (ответ игрока) и «Хроника». Рисует по {@link CourtSim}, приказы уходят действиями карты через {@code cmd}.
 */
public class CourtScreen extends Screen {
    private static final int W = 780, H = 460;
    private static final String[] TABS = {"Совет", "Вассалы", "Государство", "Козни", "События", "Браки", "Титулы", "Дипломатия", "Хроника"};
    private CourtSim s;
    private final String realm;
    private final Consumer<String> cmd;
    private int tab, scroll, selTarget, kind, governanceTab, governanceScroll;
    private String msg = "";
    private long msgUntil;

    private static final class Hit {
        final int x, y, w, h;
        final Runnable run;
        final String title;
        final List<String> tip;

        Hit(int x, int y, int w, int h, Runnable run, String title, List<String> tip) { this.x = x; this.y = y; this.w = w; this.h = h; this.run = run; this.title = title; this.tip = tip; }
    }

    private final List<Hit> hits = new ArrayList<>();

    public CourtScreen(CourtSim s, String realm, Consumer<String> cmd) {
        super(Component.literal("Двор"));
        this.s = s;
        this.realm = realm;
        this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public void setModel(CourtSim n) { this.s = n; }

    private int refresh;

    @Override
    public void tick() {
        if (++refresh % 40 == 0) cmd.accept("wfcourt " + realm);
    }

    private TitleData d() { return s.d; }

    private void hit(int x, int y, int w, int h, Runnable r, String title, List<String> tip) { hits.add(new Hit(x, y, w, h, r, title, tip)); }

    private void say(String m) { msg = m; msgUntil = System.currentTimeMillis() + 4000; }

    private static int opColor(int v) { return v >= 15 ? WFUi.GOOD : v <= -15 ? WFUi.BAD : WFUi.MUTED; }

    private void btn(GuiGraphics g, int x, int y, int w, String label, boolean en, int mx, int my, Runnable r, String tip) {
        WFUi.button(g, font, x, y, w, 15, label, en, false, WFUi.over(mx, my, x, y, w, 15));
        hit(x, y, w, 15, en ? r : null, label, tip == null ? null : List.of(tip));
    }

    private String realmName(String id) { return s.realmName(id); }

    private List<String> traitTip(Character c) {
        List<String> l = new ArrayList<>();
        for (String t : CharacterStats.traits(c)) {
            CharacterStats.Trait tr = CharacterStats.trait(t);
            if (tr != null) l.add(tr.name + ": " + tr.desc);
        }
        return l;
    }

    private boolean embeddedInMaqan;

    public void setEmbeddedInMaqan(boolean embedded) { this.embeddedInMaqan = embedded; }

    private void publishWorkspaceSelection() {
        Character ruler = s.ruler(realm);
        Character selected = ruler;
        String context = "Ruler · " + realmName(realm);
        if (tab == 2) {
            List<Character> candidates = targets(ruler);
            if (!candidates.isEmpty()) {
                selected = candidates.get(Math.min(selTarget, candidates.size() - 1));
                context = "Scheme target · " + realmName(s.realmOf(selected));
            }
        } else if (tab == 4) {
            if (!partners.isEmpty()) {
                selected = (Character) partners.get(Math.min(selPartner, partners.size() - 1))[0];
                context = "Marriage candidate · " + realmName(Marriages.family(s, selected));
            } else {
                List<Character> own = Marriages.candidates(s, realm);
                if (!own.isEmpty()) {
                    selected = own.get(Math.min(selMine, own.size() - 1));
                    context = "Dynasty candidate · " + realmName(realm);
                }
            }
        }
        if (selected == null) {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_court",
                    "realm:" + realm, "realm", realmName(realm), "Realm court · year " + s.year());
            return;
        }
        dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_court",
                selected.id, "character", selected.name,
                context + " · age " + CharacterStats.age(selected, s.year())
                        + (selected.alive ? " · alive" : " · deceased"));
    }

    @Override
    public void removed() {
        dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_court");
        super.removed();
    }

    @Override
    public void onClose() {
        if (embeddedInMaqan && dev.worldforge.mod.client.PlatformWorkspaceBridge.closeEmbedded(this)) return;
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        int x = (width - W) / 2, y = (height - H) / 2;
        TitleData.Realm re = d().realms.get(realm);
        Character ruler = s.ruler(realm);
        WFUi.panel(g, font, x, y, W, H, "Двор  ·  " + (re == null ? realm : re.name) + "  ·  " + s.year() + " год");
        Character mine = s.me.isEmpty() ? null : s.reg.get(s.me);
        if (mine != null) { String yo = "Вы: " + mine.name; g.drawString(font, yo, x + W - 10 - font.width(yo), y + 7 + 12, WFUi.GOLD); }
        String[] labels = TABS.clone();
        int pend = (int) s.pending.stream().filter(p -> p.realm.equals(realm)).count();
        if (pend > 0) labels[4] = "События (" + pend + ")";
        int tw = WFUi.tabs(g, font, x + 10, y + 24, 430, labels, tab, mx, my);
        for (int i = 0; i < TABS.length; i++) { final int t = i; hit(x + 10 + i * (tw + 2), y + 24, tw, 15, () -> { tab = t; scroll = 0; }, null, null); }
        int top = y + 44, h = H - 54, cx = x + 10, cw = W - 20;
        switch (tab) {
            case 0 -> council(g, cx, top, cw, h, mx, my, ruler);
            case 1 -> vassals(g, cx, top, cw, h, mx, my, ruler);
            case 2 -> governance(g, cx, top, cw, h, mx, my);
            case 3 -> schemes(g, cx, top, cw, h, mx, my, ruler);
            case 4 -> events(g, cx, top, cw, h, mx, my);
            case 5 -> marriages(g, cx, top, cw, h, mx, my);
            case 6 -> titles(g, cx, top, cw, h, mx, my);
            case 7 -> diplomacy(g, cx, top, cw, h, mx, my);
            default -> chronicle(g, cx, top, cw, h);
        }
        publishWorkspaceSelection();
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

    // ------------------------------------------------------------------ Совет

    private void council(GuiGraphics g, int x, int top, int cw, int h, int mx, int my, Character ruler) {
        int lw = 470, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        int cy = top + 7;
        if (ruler != null) {
            WFArt.bust(g, x + 8, cy, 40, 48, d().realms.get(realm) == null ? 0x888888 : d().realms.get(realm).rgb, ruler.id, !"f".equalsIgnoreCase(ruler.gender), s.year() - ruler.birthYear, true);
            g.drawString(font, ruler.name + (ruler.house.isEmpty() ? "" : " " + ruler.house), x + 56, cy + 2, WFUi.GOLD);
            g.drawString(font, "Правитель, " + (s.year() - ruler.birthYear) + " лет", x + 56, cy + 14, WFUi.MUTED);
            hit(x + 8, cy, 300, 48, null, ruler.name, traitTip(ruler));
            for (int i = 0; i < CharacterStats.ATTRS.length; i++) {
                int v = CharacterStats.value(ruler, CharacterStats.ATTRS[i]);
                int sx = x + 56 + i * 80;
                g.drawString(font, CharacterStats.ATTR_NAMES[i].substring(0, Math.min(6, CharacterStats.ATTR_NAMES[i].length())), sx, cy + 28, WFUi.MUTED);
                g.drawString(font, String.valueOf(v), sx + 46, cy + 28, v >= 14 ? WFUi.GOOD : v <= 6 ? WFUi.BAD : WFUi.TX);
                WFUi.bar(g, sx, cy + 39, 70, 4, v / 20.0, WFUi.INFO);
            }
        }
        cy += 62;
        g.drawString(font, "СОВЕТНИКИ", x + 8, cy, WFUi.GOLD_DIM);
        g.drawString(font, "ВЛАДЕЕТ", x + 100, cy, WFUi.GOLD_DIM);
        g.drawString(font, "НАВЫК", x + 222, cy, WFUi.GOLD_DIM);
        g.drawString(font, "ДАЁТ ГОСУДАРСТВУ", x + 300, cy, WFUi.GOLD_DIM);
        cy += 13;
        int i = 0;
        for (Council.Seat st : Council.seats(s.reg, s.defs, realm)) {
            WFUi.rowBg(g, x + 3, cy - 1, lw - 6, 26, i++, false, WFUi.over(mx, my, x + 3, cy - 1, lw - 6, 26));
            g.drawString(font, font.plainSubstrByWidth(st.role.name, 66), x + 8, cy + 8, WFUi.TX);
            if (st.holder != null) WFArt.bust(g, x + 76, cy, 20, 24, d().realms.get(realm) == null ? 0x888888 : d().realms.get(realm).rgb, st.holder.id, !"f".equalsIgnoreCase(st.holder.gender), s.year() - st.holder.birthYear, false);
            if (st.holder == null) { g.drawString(font, "пусто", x + 100, cy + 8, WFUi.BAD); }
            else {
                g.drawString(font, font.plainSubstrByWidth(st.holder.name, 120), x + 100, cy + 3, WFUi.TX);
                g.drawString(font, (s.year() - st.holder.birthYear) + " лет", x + 100, cy + 14, WFUi.MUTED);
                g.drawString(font, font.plainSubstrByWidth(st.attrName(), 56) + " " + st.value, x + 222, cy + 8, st.value >= 14 ? WFUi.GOOD : st.value <= 6 ? WFUi.BAD : WFUi.TX);
                hit(x + 3, cy - 1, lw - 6, 26, null, st.holder.name, traitTip(st.holder));
            }
            int ey = cy + 2;
            for (Map.Entry<String, Double> e : st.role.councilEffects.entrySet()) {
                double v = st.holder == null ? 0 : e.getValue() * (st.value - 5);
                String t = (v >= 0 ? "+" : "") + Math.round(v * 10) / 10.0 + " " + Council.EFFECT_NAMES.getOrDefault(e.getKey(), e.getKey());
                g.drawString(font, font.plainSubstrByWidth(t, lw - 308), x + 300, ey, st.holder == null ? WFUi.OFF : v >= 0 ? WFUi.GOOD : WFUi.BAD);
                ey += 10;
                if (ey > cy + 20) break;
            }
            cy += 28;
        }
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "ИТОГО ОТ СОВЕТА И ПРАВИТЕЛЯ", rx + 8, top + 7, WFUi.GOLD_DIM);
        int ry = top + 22;
        for (Map.Entry<String, Double> e : s.council(realm).entrySet()) {
            double v = Math.round(e.getValue() * 10) / 10.0;
            g.drawString(font, font.plainSubstrByWidth(Council.EFFECT_NAMES.getOrDefault(e.getKey(), e.getKey()), rw - 60), rx + 8, ry, WFUi.MUTED);
            String vs = (v >= 0 ? "+" : "") + v;
            g.drawString(font, vs, rx + rw - 8 - font.width(vs), ry, v >= 0 ? WFUi.GOOD : WFUi.BAD);
            ry += 12;
        }
        ry += 8;
        g.drawString(font, "Эффекты читают другие моды:", rx + 8, ry, WFUi.OFF);
        g.drawString(font, "доход - поселения, ополчение и", rx + 8, ry + 11, WFUi.OFF);
        g.drawString(font, "полководцы - армии, мнение вассалов -", rx + 8, ry + 22, WFUi.OFF);
        g.drawString(font, "фракции.", rx + 8, ry + 33, WFUi.OFF);
    }

    // ------------------------------------------------------------------ Вассалы

    private void vassals(GuiGraphics g, int x, int top, int cw, int h, int mx, int my, Character ruler) {
        TitleData.Realm re = d().realms.get(realm);
        int lw = 440, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ВАССАЛЫ", x + 8, top + 6, WFUi.GOLD_DIM);
        g.drawString(font, "МНЕНИЕ", x + 128, top + 6, WFUi.GOLD_DIM);
        g.drawString(font, "ЛОЯЛЬН.", x + 190, top + 6, WFUi.GOLD_DIM);
        g.drawString(font, "НАЛОГ / ОПОЛЧ.", x + 234, top + 6, WFUi.GOLD_DIM);
        g.drawString(font, "ФРАКЦИЯ", x + 310, top + 6, WFUi.GOLD_DIM);
        int ry = top + 20, i = 0;
        List<String> subs = re == null ? List.of() : re.subjectIds;
        for (String sid : subs) {
            Character sr = s.ruler(sid);
            if (ry > top + h - 16) break;
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 17, i++, false, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 17));
            TitleData.Realm sre = d().realms.get(sid);
            WFArt.shield(g, x + 5, ry, 10, 12, sre == null ? 0x888888 : sre.rgb, sre == null ? "?" : sre.id);
            g.drawString(font, font.plainSubstrByWidth(realmName(sid), 110), x + 20, ry + 3, WFUi.TX);
            if (sr != null && ruler != null) {
                Opinions.Result op = s.opinions.of(s.reg, sr.id, ruler.id, s.year());
                double bonus = s.council(realm).getOrDefault("vassal_opinion", 0.0);
                int tot = (int) Math.round(op.total + bonus);
                g.drawString(font, (tot > 0 ? "+" : "") + tot, x + 128, ry + 3, opColor(tot));
                List<String> tip = new ArrayList<>();
                for (int k = 0; k < op.reasons.size(); k++) tip.add((op.values.get(k) >= 0 ? "+" : "-") + op.reasons.get(k) + ": " + op.values.get(k));
                if (bonus != 0) tip.add("Совет (канцлер): " + Math.round(bonus * 10) / 10.0);
                hit(x + 3, ry - 1, 185, 17, null, sr.name + " о вас · " + op.label(), tip);
            }
            Object loyaltyRaw = sre == null ? null : sre.props.get("politics.vassal_loyalty");
            int loyalty = loyaltyRaw instanceof Number n ? Math.max(0, Math.min(100, n.intValue())) : 50;
            double tax = sre != null && sre.props.get("tax_share") instanceof Number n ? n.doubleValue() : 0;
            double levy = sre != null && sre.props.get("levy_share") instanceof Number n ? n.doubleValue() : 0;
            g.drawString(font, loyalty + "%", x + 190, ry + 3, loyalty < 25 ? WFUi.BAD : loyalty < 50 ? WFUi.GOLD : WFUi.GOOD);
            String obligations = Math.round(tax * 100) + "/" + Math.round(levy * 100) + "%";
            g.drawString(font, obligations, x + 234, ry + 3, tax > 0.35 || levy > 0.5 ? WFUi.GOLD : WFUi.TX);
            hit(x + 186, ry - 1, 104, 17, null, "Феодальный договор · " + realmName(sid),
                    List.of("Лояльность: " + loyalty + "%", "Доля налогов сюзерену: " + Math.round(tax * 100) + "%",
                            "Военная повинность: " + Math.round(levy * 100) + "%"));
            Faction in = null;
            for (Faction f : s.factions.values()) if (f.liege.equals(realm) && f.members.contains(sid)) in = f;
            g.drawString(font, in == null ? "-" : in.typeName(), x + 310, ry + 3, in == null ? WFUi.OFF : WFUi.BAD);
            final String id = sid;
            btn(g, x + lw - 66, ry, 60, "Одарить", true, mx, my, () -> cmd.accept("wfaction run faction_appease " + realm + " " + id), "Мнение +20 на 5 лет");
            ry += 18;
        }
        if (subs.isEmpty()) g.drawString(font, "Вассалов нет.", x + 8, top + 22, WFUi.MUTED);
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "ФРАКЦИИ ПРОТИВ ВАС", rx + 8, top + 6, WFUi.GOLD_DIM);
        int fy = top + 20;
        boolean any = false;
        for (Faction f : s.factions.values()) {
            if (!f.liege.equals(realm) || fy > top + h - 70) continue;
            any = true;
            g.drawString(font, f.typeName(), rx + 8, fy, WFUi.BAD);
            fy += 12;
            for (String m : f.members) { g.drawString(font, "- " + realmName(m), rx + 12, fy, m.equals(f.leader) ? WFUi.GOLD : WFUi.TX); fy += 10; }
            WFUi.kv(g, font, rx + 8, fy + 2, rw - 16, 70, "Мощь", Math.round(f.power * 100) + "%", f.power > 0.5 ? WFUi.BAD : WFUi.TX);
            WFUi.bar(g, rx + 130, fy + 4, rw - 146, 5, f.power, WFUi.BAD);
            fy += 12;
            WFUi.kv(g, font, rx + 8, fy + 2, rw - 16, 70, "Ультиматум", Math.round(f.progress) + "%", WFUi.TX);
            WFUi.bar(g, rx + 130, fy + 4, rw - 146, 5, f.progress / 100.0, WFUi.GOLD);
            fy += 14;
            final int id = f.id;
            double intr = 0;
            for (Council.Seat st : Council.seats(s.reg, s.defs, realm)) if (st.attr.equals("intrigue")) intr = Math.max(intr, st.value);
            long chance = Math.round(Math.max(0.1, Math.min(0.9, 0.55 + 0.04 * (intr - 10) - 0.5 * f.power)) * 100);
            btn(g, rx + 8, fy, rw - 16, "Подавить заговор  (шанс " + chance + "%)", true, mx, my, () -> cmd.accept("wfaction run faction_crush " + realm + " " + id), "Провал разозлит вассалов и ускорит ультиматум");
            fy += 24;
        }
        if (!any) {
            g.drawString(font, "Заговоров нет.", rx + 8, top + 22, WFUi.GOOD);
            g.drawString(font, "Вассал с мнением ниже -25 о вас", rx + 8, top + 38, WFUi.MUTED);
            g.drawString(font, "начнёт собирать фракцию; когда она", rx + 8, top + 48, WFUi.MUTED);
            g.drawString(font, "окрепнет, потребует независимости", rx + 8, top + 58, WFUi.MUTED);
            g.drawString(font, "войной.", rx + 8, top + 68, WFUi.MUTED);
        }
    }

    // ------------------------------------------------------------------ Государство: живая проекция wf-realms + доступные решения

    private static String prop(TitleData.Realm realm, String key, String fallback) {
        if (realm == null) return fallback;
        Object value = realm.props.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intProp(TitleData.Realm realm, String key, int fallback) {
        if (realm == null) return fallback;
        Object value = realm.props.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private void governance(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        TitleData.Realm re = d().realms.get(realm);
        if (re == null) {
            g.drawString(font, "Политическая проекция государства пока недоступна.", x + 8, top + 10, WFUi.MUTED);
            return;
        }
        int lw = 300, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ГОСУДАРСТВЕННОЕ УСТРОЙСТВО", x + 8, top + 6, WFUi.GOLD_DIM);
        int yy = top + 22;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Форма правления", prop(re, "politics.government", "Не определена"), WFUi.TX); yy += 15;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Уровень", prop(re, "politics.realm_level", "—"), WFUi.TX); yy += 15;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Закон наследования", prop(re, "politics.succession_law", "Не задан"), WFUi.TX); yy += 15;
        String[] successionIds = {"PRIMOGENITURE", "COGNATIC", "ULTIMOGENITURE"};
        String[] successionLabels = {"Первородство", "Когнат.", "Младший"};
        int sw = (lw - 24) / 3;
        for (int i = 0; i < successionIds.length; i++) {
            String id = successionIds[i];
            boolean active = id.equalsIgnoreCase(prop(re, "politics.succession_law", ""));
            btn(g, x + 8 + i * (sw + 2), yy, sw, successionLabels[i], !active, mx, my,
                    () -> cmd.accept("wfrealm succession " + id), "Изменить закон наследования");
        }
        yy += 19;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Очки развития", prop(re, "politics.development_points", "0"), WFUi.GOLD); yy += 18;
        int stability = Math.max(0, Math.min(100, intProp(re, "stability", 50)));
        int unrest = Math.max(0, Math.min(100, intProp(re, "politics.unrest", 0)));
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Стабильность", stability + "%", stability < 30 ? WFUi.BAD : WFUi.GOOD);
        WFUi.bar(g, x + 112, yy + 11, lw - 128, 5, stability / 100.0, stability < 30 ? WFUi.BAD : WFUi.GOOD); yy += 21;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Недовольство", unrest + "%", unrest > 60 ? WFUi.BAD : WFUi.TX);
        WFUi.bar(g, x + 112, yy + 11, lw - 128, 5, unrest / 100.0, unrest > 60 ? WFUi.BAD : WFUi.GOLD); yy += 22;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Доктрины", prop(re, "politics.doctrine_count", "0") + " / " + prop(re, "politics.doctrine_slots", "0"), WFUi.TX); yy += 15;
        WFUi.kv(g, font, x + 8, yy, lw - 16, 105, "Законы", prop(re, "politics.law_count", "0") + " / " + prop(re, "politics.law_slots", "0"), WFUi.TX); yy += 18;
        g.drawString(font, "ДЕЙСТВУЮЩИЕ ДОКТРИНЫ", x + 8, yy, WFUi.GOLD_DIM); yy += 12;
        yy = drawWrapped(g, prop(re, "politics.doctrines", "Нет принятых доктрин"), x + 8, yy, lw - 16, top + h - 8);
        yy += 5;
        if (yy < top + h - 10) {
            g.drawString(font, "ДЕЙСТВУЮЩИЕ ЗАКОНЫ", x + 8, yy, WFUi.GOLD_DIM); yy += 12;
            drawWrapped(g, prop(re, "politics.laws", "Нет принятых законов"), x + 8, yy, lw - 16, top + h - 8);
        }

        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "ПОЛИТИЧЕСКИЕ РЕШЕНИЯ", rx + 8, top + 6, WFUi.GOLD_DIM);
        String[] managementTabs = {"Строй", "Законы", "Доктрины"};
        int mtw = WFUi.tabs(g, font, rx + 8, top + 20, rw - 16, managementTabs, governanceTab, mx, my);
        for (int i = 0; i < managementTabs.length; i++) {
            final int selected = i;
            hit(rx + 8 + i * (mtw + 2), top + 20, mtw, 15, () -> {
                governanceTab = selected;
                governanceScroll = 0;
            }, null, null);
        }
        int ry = top + 42;
        if (governanceTab == 0) {
            String govRaw = prop(re, "politics.government_options", "");
            if (!govRaw.isBlank()) for (String item : govRaw.split(";")) {
                if (ry > top + h - 22) break;
                String[] parts = item.split("\\|", -1);
                if (parts.length < 3) continue;
                String id = parts[0], label = parts[1], reason = parts[2];
                boolean current = id.equals(prop(re, "politics.government_id", ""));
                g.drawString(font, font.plainSubstrByWidth(label, rw - 105), rx + 8, ry + 3,
                        current ? WFUi.GOOD : reason.isEmpty() ? WFUi.TX : WFUi.MUTED);
                String status = current ? "Текущий" : reason.isEmpty() ? "Подходит" : reason;
                String shown = font.plainSubstrByWidth(status, 82);
                g.drawString(font, shown, rx + rw - 94 - font.width(shown), ry + 3,
                        current ? WFUi.GOOD : reason.isEmpty() ? WFUi.MUTED : WFUi.BAD);
                btn(g, rx + rw - 76, ry, 68, "Выбрать", !current && reason.isEmpty(), mx, my,
                        () -> cmd.accept("wfrealm gov " + id), reason.isEmpty() ? "Смена строя проверяется сервером; решение доступно правителю" : reason);
                ry += 17;
            } else g.drawString(font, "Нет загруженных форм правления.", rx + 8, ry, WFUi.MUTED);
        } else if (governanceTab == 1) {
            String lawRaw = prop(re, "politics.law_options", "");
            if (!lawRaw.isBlank()) for (String item : lawRaw.split(";")) {
                if (ry > top + h - 22) break;
                String[] parts = item.split("\\|", -1);
                if (parts.length < 5) continue;
                String lawId = parts[0], lawName = parts[1], optionId = parts[2], optionName = parts[3], reason = parts[4];
                String label = lawName + ": " + optionName;
                g.drawString(font, font.plainSubstrByWidth(label, rw - 86), rx + 8, ry + 3, reason.isEmpty() ? WFUi.TX : WFUi.MUTED);
                btn(g, rx + rw - 76, ry, 68, "Принять", reason.isEmpty(), mx, my,
                        () -> cmd.accept("wfrealm law " + lawId + " " + optionId),
                        reason.isEmpty() ? "Принятие закона изменит действующие эффекты государства" : reason);
                ry += 17;
            } else g.drawString(font, "Нет загруженных вариантов законов.", rx + 8, ry, WFUi.MUTED);
        } else {
            String doctrineRaw = prop(re, "politics.doctrine_options", "");
            List<String> doctrines = doctrineRaw.isBlank() ? List.of() : Arrays.asList(doctrineRaw.split(";"));
            int rows = Math.max(1, (top + h - 24 - ry) / 17);
            governanceScroll = Math.max(0, Math.min(Math.max(0, doctrines.size() - rows), governanceScroll));
            for (int i = governanceScroll; i < doctrines.size() && i < governanceScroll + rows; i++) {
                String[] parts = doctrines.get(i).split("\\|", -1);
                if (parts.length < 4) continue;
                String key = parts[0], label = parts[1], cost = parts[2], reason = parts[3];
                String shown = font.plainSubstrByWidth(label + " · " + cost + " очк.", rw - 96);
                g.drawString(font, shown, rx + 8, ry + 3, reason.isEmpty() ? WFUi.TX : WFUi.MUTED);
                btn(g, rx + rw - 76, ry, 68, "Изучить", reason.isEmpty(), mx, my,
                        () -> cmd.accept("wfrealm unlock " + key),
                        reason.isEmpty() ? "Изучение расходует очки развития" : reason);
                ry += 17;
            }
            WFUi.scrollbar(g, rx + rw - 5, top + 42, h - 52, doctrines.size() * 17, governanceScroll * 17);
            if (doctrines.isEmpty()) g.drawString(font, "Нет доступных доктрин.", rx + 8, ry, WFUi.MUTED);
        }
    }

    private int drawWrapped(GuiGraphics g, String value, int x, int y, int width, int bottom) {
        if (value == null || value.isBlank()) value = "—";
        String remaining = value;
        while (!remaining.isEmpty() && y <= bottom - 9) {
            String line = font.plainSubstrByWidth(remaining, width);
            if (line.isEmpty()) break;
            g.drawString(font, line, x, y, WFUi.MUTED);
            remaining = remaining.substring(line.length()).stripLeading();
            y += 10;
        }
        return y;
    }

    // ------------------------------------------------------------------ Титулы и династические права
    private void titles(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        TitleData.Realm realmData = d().realms.get(realm);
        WFUi.bevel(g, x, top, cw, h, true);
        g.drawString(font, "ТИТУЛЬНАЯ ИЕРАРХИЯ И ПРЕТЕНЗИИ", x + 8, top + 6, WFUi.GOLD_DIM);
        Character ruler = s.ruler(realm);
        int y = top + 22;
        if (ruler != null) {
            String house = ruler.house == null || ruler.house.isBlank() ? "Без династического дома" : ruler.house;
            g.drawString(font, "Правящий дом: " + house, x + 8, y, WFUi.GOLD);
            hit(x + 8, y - 2, cw - 16, 14, null, house,
                    List.of("Правитель: " + ruler.name, "Персонажи и родственные связи берутся из общего CharacterRegistry"));
            y += 18;
        }
        if (realmData == null || realmData.titleKeys.isEmpty()) {
            g.drawString(font, "У государства пока нет титулов на карте.", x + 8, y, WFUi.MUTED);
            return;
        }
        g.drawString(font, "ТИТУЛ", x + 8, y, WFUi.GOLD_DIM);
        g.drawString(font, "ДЕРЖАТЕЛЬ", x + 205, y, WFUi.GOLD_DIM);
        g.drawString(font, "ПРЕТЕНДЕНТЫ", x + 390, y, WFUi.GOLD_DIM);
        y += 13;
        for (String key : realmData.titleKeys) {
            if (y > top + h - 20) break;
            TitleData.Title title = d().byKey(key);
            if (title == null) continue;
            String holderId = String.valueOf(title.props.getOrDefault("holderCharacterId",
                    title.props.getOrDefault("politics.holder_character", "")));
            Character holder = holderId.isBlank() ? null : s.reg.get(holderId);
            String holderName = holder == null ? (holderId.isBlank() ? "Не назначен" : holderId) : holder.name;
            String claims = String.valueOf(title.props.getOrDefault("politics.claimants", ""));
            List<String> claimantIds = claims.isBlank() ? List.of() : Arrays.asList(claims.split(";"));
            String claimantNames = claimantIds.stream().map(id -> {
                Character c = s.reg.get(id);
                return c == null ? id : c.name + (c.house.isBlank() ? "" : " " + c.house);
            }).limit(3).collect(java.util.stream.Collectors.joining(", "));
            WFUi.rowBg(g, x + 4, y - 2, cw - 8, 19, title.id, false,
                    WFUi.over(mx, my, x + 4, y - 2, cw - 8, 19));
            String rank = title.tier >= 0 && title.tier < d().tiers.size() ? d().tiers.get(title.tier).name : "Титул";
            g.drawString(font, font.plainSubstrByWidth(title.name + " · " + rank, 188), x + 8, y + 3, WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(holderName, 175), x + 205, y + 3, WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(claimantIds.isEmpty() ? "Нет" : claimantNames, cw - 410),
                    x + 390, y + 3, claimantIds.isEmpty() ? WFUi.MUTED : WFUi.BAD);
            List<String> titleDetails = new ArrayList<>();
            titleDetails.add("Держатель: " + holderName);
            titleDetails.add("Претендентов: " + claimantIds.size());
            titleDetails.add(claimantIds.isEmpty() ? "Спорных прав нет" : String.join(", ", claimantIds));
            Object lastDeceased = title.props.get("succession.last_deceased");
            Object lastHeir = title.props.get("succession.last_heir");
            Object lastYear = title.props.get("succession.last_year");
            if (lastHeir != null) {
                Character successor = s.reg.get(String.valueOf(lastHeir));
                Character predecessor = lastDeceased == null ? null : s.reg.get(String.valueOf(lastDeceased));
                titleDetails.add("Последнее наследование: " + (lastYear == null ? "год неизвестен" : lastYear));
                titleDetails.add("Предшественник: " + (predecessor == null ? String.valueOf(lastDeceased) : predecessor.name));
                titleDetails.add("Наследник: " + (successor == null ? String.valueOf(lastHeir) : successor.name));
            }
            hit(x + 4, y - 2, cw - 8, 19, null, title.name, titleDetails);
            y += 21;
        }
        g.drawString(font, "Наследование: " + realmData.props.getOrDefault("politics.succession_law", "primogeniture"),
                x + 8, top + h - 13, WFUi.MUTED);
    }

    // ------------------------------------------------------------------ Дипломатия
    private void diplomacy(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        TitleData.Realm realmData = d().realms.get(realm);
        WFUi.bevel(g, x, top, cw, h, true);
        g.drawString(font, "ДИПЛОМАТИЧЕСКИЕ ОТНОШЕНИЯ", x + 8, top + 6, WFUi.GOLD_DIM);
        int y = top + 22;
        if (realmData == null) return;
        String raw = String.valueOf(realmData.props.getOrDefault("politics.diplomacy_options", ""));
        String firstOther = "";
        if (raw.isBlank()) {
            g.drawString(font, "Нет дипломатических контактов. Контакты возникают между соседними государствами.", x + 8, y, WFUi.MUTED);
        } else {
            g.drawString(font, "ГОСУДАРСТВО", x + 8, y, WFUi.GOLD_DIM);
            g.drawString(font, "МНЕНИЕ", x + 220, y, WFUi.GOLD_DIM);
            g.drawString(font, "ДОГОВОРЫ", x + 290, y, WFUi.GOLD_DIM);
            y += 14;
            for (String entry : raw.split(";")) {
                if (y > top + h - 65) break;
                String[] p = entry.split("\\|", -1);
                if (p.length < 5) continue;
                if (firstOther.isBlank()) firstOther = p[0];
                int opinion = 0, truce = 0;
                try { opinion = Integer.parseInt(p[2]); truce = Integer.parseInt(p[4]); } catch (NumberFormatException ignored) { }
                WFUi.rowBg(g, x + 4, y - 2, cw - 8, 18, p[0].hashCode(), false,
                        WFUi.over(mx, my, x + 4, y - 2, cw - 8, 18));
                g.drawString(font, font.plainSubstrByWidth(p[1], 202), x + 8, y + 3, WFUi.TX);
                g.drawString(font, (opinion > 0 ? "+" : "") + opinion, x + 220, y + 3,
                        opinion >= 20 ? WFUi.GOOD : opinion <= -20 ? WFUi.BAD : WFUi.MUTED);
                g.drawString(font, font.plainSubstrByWidth(p[3].isBlank() ? "Нет договора" : p[3], 145),
                        x + 290, y + 3, p[3].isBlank() ? WFUi.MUTED : WFUi.GOLD);
                hit(x + 4, y - 2, cw - 8, 18, null, p[1],
                        List.of("Мнение: " + opinion, "Перемирие до дня: " + truce,
                                "Договоры: " + (p[3].isBlank() ? "нет" : p[3])));
                y += 20;
            }
        }
        String wars = String.valueOf(realmData.props.getOrDefault("politics.active_wars", ""));
        int warCount = wars.isBlank() ? 0 : wars.split(";").length;
        int controlsY = top + h - 48;
        g.drawString(font, "Активные войны: " + warCount, x + 8, controlsY - 11, warCount > 0 ? WFUi.BAD : WFUi.GOOD);
        String[] types = {"NON_AGGRESSION", "TRADE_PACT", "DEFENSIVE_PACT", "ALLIANCE"};
        String[] labels = {"Ненап.", "Торговля", "Оборона", "Союз"};
        int bw = (cw - 24) / 4;
        final String target = firstOther;
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            btn(g, x + 8 + i * (bw + 2), controlsY, bw, labels[i], !target.isBlank(), mx, my,
                    () -> cmd.accept("wfrealm treaty " + target + " " + type + " true"),
                    target.isBlank() ? "Нет доступного дипломатического контакта" : "Предложить договор государству " + target);
        }
    }

    // ------------------------------------------------------------------ Козни

    private List<Character> targets(Character ruler) {
        List<Character> l = new ArrayList<>();
        for (Council.Seat st : Council.seats(s.reg, s.defs, realm)) if (st.holder != null && st.holder != ruler) l.add(st.holder);
        for (TitleData.Realm r : d().realmList()) { Character c = s.ruler(r.id); if (c != null && c != ruler && !r.id.equals(realm) && l.size() < 30) l.add(c); }
        return l;
    }

    private void schemes(GuiGraphics g, int x, int top, int cw, int h, int mx, int my, Character ruler) {
        int lw = 380, rx = x + lw + 8, rw = cw - lw - 8;
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ВАШИ КОЗНИ", x + 8, top + 6, WFUi.GOLD_DIM);
        int ry = top + 20;
        for (Scheme sc : s.schemes.values()) {
            if (ruler == null || !sc.owner.equals(ruler.id)) continue;
            Character t = s.reg.get(sc.target);
            g.drawString(font, sc.kind.title + ": " + (t == null ? "?" : t.name), x + 8, ry, WFUi.TX);
            WFUi.bar(g, x + 8, ry + 13, lw - 150, 5, sc.progress / sc.kind.days, WFUi.GOLD);
            g.drawString(font, "успех " + Math.round(s.schemeChance(sc) * 100) + "%  тайна " + Math.round(sc.secrecy * 100) + "%", x + lw - 136, ry + 11, WFUi.MUTED);
            final int id = sc.id;
            btn(g, x + lw - 66, ry - 2, 60, "Отмена", true, mx, my, () -> cmd.accept("wfaction run scheme_cancel " + realm + " " + id), null);
            ry += 26;
        }
        if (ry == top + 20) g.drawString(font, "Козней нет.", x + 8, ry, WFUi.MUTED);
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "НОВЫЕ КОЗНИ", rx + 8, top + 6, WFUi.GOLD_DIM);
        Scheme.Kind[] kinds = Scheme.Kind.values();
        int kx = rx + 8;
        for (int i = 0; i < kinds.length; i++) {
            int w = font.width(kinds[i].title) + 12;
            WFUi.button(g, font, kx, top + 20, w, 14, kinds[i].title, true, i == kind, WFUi.over(mx, my, kx, top + 20, w, 14));
            final int k = i;
            hit(kx, top + 20, w, 14, () -> kind = k, kinds[i].title, List.of(kinds[i].days + " дней, базовый шанс " + Math.round(kinds[i].base * 100) + "%"));
            kx += w + 3;
        }
        List<Character> ts = targets(ruler);
        selTarget = Math.min(selTarget, Math.max(0, ts.size() - 1));
        int room = (h - 62) / 13, ty = top + 40;
        scroll = Math.max(0, Math.min(Math.max(0, ts.size() - room), scroll));
        for (int i = scroll; i < ts.size() && i < scroll + room; i++) {
            Character t = ts.get(i);
            WFUi.rowBg(g, rx + 3, ty - 1, rw - 6, 13, i, i == selTarget, WFUi.over(mx, my, rx + 3, ty - 1, rw - 6, 13));
            String rn = s.realmOf(t).isEmpty() ? "" : realmName(s.realmOf(t));
            g.drawString(font, font.plainSubstrByWidth(t.name + (rn.isEmpty() ? "" : "  ·  " + rn), rw - 20), rx + 8, ty + 2, i == selTarget ? WFUi.GOLD : WFUi.TX);
            final int idx = i;
            hit(rx + 3, ty - 1, rw - 6, 13, () -> selTarget = idx, t.name, traitTip(t));
            ty += 13;
        }
        boolean can = ruler != null && !ts.isEmpty();
        Character tgt = can ? ts.get(selTarget) : null;
        btn(g, rx + 8, top + h - 22, rw - 16, can ? "Начать: " + kinds[kind].title + " - " + tgt.name : "Нет цели", can, mx, my,
                () -> cmd.accept("wfaction run scheme_start " + realm + " " + kinds[kind].name().toLowerCase() + "|" + tgt.id), "Не более двух козней; раскрытые козни портят мнение");
    }

    // ------------------------------------------------------------------ Браки

    private int selMine, selPartner;
    private CourtSim marrySrc;
    private String marryKey = "";
    private final List<Object[]> partners = new ArrayList<>();       // {Character, Marriages.Offer}

    private void rebuildPartners(List<Character> own) {
        String key = selMine + "|" + own.size() + "|" + s.year();
        if (marrySrc == s && key.equals(marryKey)) return;
        marrySrc = s; marryKey = key; partners.clear();
        if (own.isEmpty()) return;
        Character mine = own.get(Math.min(selMine, own.size() - 1));
        for (TitleData.Realm r : d().realmList()) {
            if (r.id.equals(realm)) continue;
            for (Character c : Marriages.candidates(s, r.id)) {
                if (c.gender.equals(mine.gender)) continue;
                Marriages.Offer o = Marriages.evaluate(s, mine.id, c.id);
                partners.add(new Object[]{c, o});
            }
        }
        partners.sort((a, b) -> {
            Marriages.Offer x = (Marriages.Offer) a[1], y = (Marriages.Offer) b[1];
            if (x.problems.isEmpty() != y.problems.isEmpty()) return x.problems.isEmpty() ? -1 : 1;
            return Integer.compare(y.score, x.score);
        });
        selPartner = 0;
    }

    private void marriages(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        int lw = 230, rx = x + lw + 8, rw = cw - lw - 8;
        int rgb = d().realms.get(realm) == null ? 0x888888 : d().realms.get(realm).rgb;
        List<Character> own = Marriages.candidates(s, realm);
        if (own.size() > 8) own = own.subList(0, 8);
        selMine = Math.min(selMine, Math.max(0, own.size() - 1));
        rebuildPartners(own);
        WFUi.bevel(g, x, top, lw, h, true);
        g.drawString(font, "ВАШ ДВОР: НЕЖЕНАТЫЕ", x + 8, top + 6, WFUi.GOLD_DIM);
        int ry = top + 20;
        if (own.isEmpty()) g.drawString(font, "Нет подходящих.", x + 8, ry, WFUi.MUTED);
        for (int i = 0; i < own.size(); i++) {
            Character c = own.get(i);
            WFUi.rowBg(g, x + 3, ry - 1, lw - 6, 28, i, i == selMine, WFUi.over(mx, my, x + 3, ry - 1, lw - 6, 28));
            WFArt.bust(g, x + 6, ry, 20, 26, rgb, c.id, !"f".equals(c.gender), s.year() - c.birthYear, false);
            g.drawString(font, font.plainSubstrByWidth(c.name, lw - 44), x + 32, ry + 3, i == selMine ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, (s.year() - c.birthYear) + " лет, " + ("f".equals(c.gender) ? "жен." : "муж."), x + 32, ry + 15, WFUi.MUTED);
            final int idx = i;
            hit(x + 3, ry - 1, lw - 6, 28, () -> { selMine = idx; selPartner = 0; }, c.name, traitTip(c));
            ry += 29;
        }
        WFUi.bevel(g, rx, top, rw, h, true);
        g.drawString(font, "ПОДХОДЯЩИЕ ПАРТИИ  (оценка: примут при 0 и выше)", rx + 8, top + 6, WFUi.GOLD_DIM);
        int listH = h - 104, room = Math.max(1, listH / 14), py = top + 20;
        selPartner = Math.min(selPartner, Math.max(0, partners.size() - 1));
        scroll = Math.max(0, Math.min(Math.max(0, partners.size() - room), scroll));
        for (int i = scroll; i < partners.size() && i < scroll + room; i++) {
            Character c = (Character) partners.get(i)[0];
            Marriages.Offer o = (Marriages.Offer) partners.get(i)[1];
            String pr = Marriages.family(s, c);
            TitleData.Realm prr = d().realms.get(pr);
            WFUi.rowBg(g, rx + 3, py - 1, rw - 6, 14, i, i == selPartner, WFUi.over(mx, my, rx + 3, py - 1, rw - 6, 14));
            WFArt.shield(g, rx + 6, py, 9, 11, prr == null ? 0x888888 : prr.rgb, pr);
            g.drawString(font, font.plainSubstrByWidth(c.name + "  ·  " + realmName(pr), rw - 90), rx + 20, py + 2, i == selPartner ? WFUi.GOLD : WFUi.TX);
            String sc = !o.problems.isEmpty() ? "-" : (o.score >= 0 ? "+" : "") + o.score;
            g.drawString(font, (s.year() - c.birthYear) + "л", rx + rw - 62, py + 2, WFUi.MUTED);
            g.drawString(font, sc, rx + rw - 14 - font.width(sc), py + 2, !o.problems.isEmpty() ? WFUi.OFF : o.ok ? WFUi.GOOD : WFUi.BAD);
            final int idx = i;
            hit(rx + 3, py - 1, rw - 6, 14, () -> selPartner = idx, c.name, traitTip(c));
            py += 14;
        }
        if (partners.isEmpty()) g.drawString(font, "Партий не найдено.", rx + 8, top + 22, WFUi.MUTED);
        WFUi.scrollbar(g, rx + rw - 5, top + 18, listH, partners.size() * 14, scroll * 14);
        int dy = top + h - 80;
        g.fill(rx + 4, dy - 4, rx + rw - 4, dy - 3, WFUi.EDGE);
        if (!partners.isEmpty() && !own.isEmpty()) {
            Marriages.Offer o = (Marriages.Offer) partners.get(selPartner)[1];
            Character c = (Character) partners.get(selPartner)[0], mine = own.get(selMine);
            List<String> lines = o.problems.isEmpty() ? o.reasons : o.problems;
            for (int i = 0; i < lines.size() && i < 4; i++) {
                String ln = lines.get(i);
                g.drawString(font, font.plainSubstrByWidth(ln, rw - 16), rx + 8, dy + i * 11, !o.problems.isEmpty() ? WFUi.BAD : ln.startsWith("-") ? WFUi.BAD : WFUi.GOOD);
            }
            btn(g, rx + 8, top + h - 22, rw - 16, "Предложить брак: " + mine.name + " и " + c.name, o.ok, mx, my,
                    () -> cmd.accept("wfaction run marriage_propose " + realm + " " + mine.id + "|" + c.id), "Правитель партии решает по мнению о вас, вашему канцлеру и разнице в возрасте");
        }
    }

    // ------------------------------------------------------------------ События

    private static String who(Object w) {
        return switch (String.valueOf(w)) { case "liege" -> "сюзерена"; case "vassals" -> "вассалов"; case "spouse" -> "супруга(и)"; case "council" -> "совета"; default -> String.valueOf(w); };
    }

    private static String effectText(Map<String, Object> fx) {
        String op = String.valueOf(fx.get("op"));
        return switch (op) {
            case "gold" -> (((Number) fx.get("value")).intValue() >= 0 ? "+" : "") + ((Number) fx.get("value")).intValue() + " золота";
            case "stat" -> { String a = String.valueOf(fx.get("attr")); for (int i = 0; i < CharacterStats.ATTRS.length; i++) if (CharacterStats.ATTRS[i].equals(a)) a = CharacterStats.ATTR_NAMES[i].toLowerCase(); yield a + " " + (((Number) fx.get("delta")).intValue() > 0 ? "+" : "") + ((Number) fx.get("delta")).intValue(); }
            case "trait" -> fx.get("add") != null ? "черта: " + (CharacterStats.trait(String.valueOf(fx.get("add"))) == null ? fx.get("add") : CharacterStats.trait(String.valueOf(fx.get("add"))).name) : "теряет черту";
            case "opinion" -> "мнение " + who(fx.get("who")) + " " + (((Number) fx.get("value")).intValue() > 0 ? "+" : "") + ((Number) fx.get("value")).intValue();
            case "kill" -> "смерть";
            case "child" -> "рождение ребёнка";
            default -> op;
        };
    }

    private void events(GuiGraphics g, int x, int top, int cw, int h, int mx, int my) {
        WFUi.bevel(g, x, top, cw, h, true);
        CourtSim.Pending p = null;
        for (CourtSim.Pending q : s.pending) if (q.realm.equals(realm)) { p = q; break; }
        if (p == null) { g.drawString(font, "Новых событий нет. Двор живёт своей жизнью.", x + 10, top + 10, WFUi.MUTED); return; }
        EventDef e = s.events.get(p.event);
        int cy = top + 10, iw = cw - 20;
        g.drawString(font, e.title, x + 10, cy, WFUi.GOLD);
        cy += 16;
        String text = s.text(p);
        while (!text.isEmpty()) {
            String line = font.plainSubstrByWidth(text, iw);
            if (line.length() < text.length()) { int sp = line.lastIndexOf(' '); if (sp > 10) line = line.substring(0, sp); }
            g.drawString(font, line, x + 10, cy, WFUi.TX);
            text = text.substring(line.length()).trim();
            cy += 11;
        }
        cy += 14;
        for (int i = 0; i < e.options.size(); i++) {
            EventDef.Option o = e.options.get(i);
            boolean hov = WFUi.over(mx, my, x + 10, cy, iw, 28);
            WFUi.bevel(g, x + 10, cy, iw, 28, !hov);
            g.drawString(font, o.text, x + 18, cy + 4, hov ? WFUi.GOLD : WFUi.TX);
            StringBuilder b = new StringBuilder();
            for (Map<String, Object> fx : o.effects) b.append(b.length() == 0 ? "" : "  ·  ").append(effectText(fx));
            g.drawString(font, font.plainSubstrByWidth(b.length() == 0 ? "без последствий" : b.toString(), iw - 16), x + 18, cy + 16, WFUi.MUTED);
            final int id = p.id, n = i + 1;
            hit(x + 10, cy, iw, 28, () -> cmd.accept("wfaction run event_choose " + realm + " " + id + "|" + n), null, null);
            cy += 32;
        }
    }

    // ------------------------------------------------------------------ Хроника

    private void chronicle(GuiGraphics g, int x, int top, int cw, int h) {
        WFUi.bevel(g, x, top, cw, h, true);
        List<String> all = new ArrayList<>(s.news);
        java.util.Collections.reverse(all);
        int room = (h - 12) / 11;
        scroll = Math.max(0, Math.min(Math.max(0, all.size() - room), scroll));
        int y = top + 7;
        String mine = realmName(realm);
        for (int i = scroll; i < all.size() && i < scroll + room; i++) {
            String t = all.get(i);
            g.drawString(font, font.plainSubstrByWidth(t, cw - 24), x + 8, y, t.contains(mine) ? WFUi.TX : WFUi.MUTED);
            y += 11;
        }
        WFUi.scrollbar(g, x + cw - 5, top + 4, h - 8, all.size() * 11, scroll * 11);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hh = hits.get(i);
                if (WFUi.over((int) mx, (int) my, hh.x, hh.y, hh.w, hh.h) && hh.run != null) { hh.run.run(); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (tab == 2 && governanceTab == 2)
            governanceScroll = Math.max(0, governanceScroll - (int) Math.signum(d) * 2);
        else scroll = Math.max(0, scroll - (int) Math.signum(d) * 2);
        return true;
    }
}
