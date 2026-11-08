package dev.worldforge.people.mc.client;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.client.WFUi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterActions;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterStats;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Окно персонажа в духе карточки правителя CK3 / EU4, в общем оформлении WFUi. Только рисование по чистым данным
 * ({@link CharacterRegistry}, {@link RoleDefs}, {@link TitleData}): шапка (имя, дом, титулы, должности, возраст, статус),
 * слева семейное древо (клик по карточке переносит фокус), в центре характеристики, черты, отношение, должности и совет,
 * справа действия с причиной отказа при наведении. Команды уходят строками через {@code cmd}.
 * Репутация подключается необязательно через {@link Opinion}, поэтому wf-people не зависит от wf-reputation.
 */
public class CharacterScreen extends Screen {
    /** Отношение игрока к персонажу, -100..100. Реализуется снаружи поверх ReputationStore; null - нет данных. */
    public interface Opinion { int of(String characterId); }

    private static final int W0 = 760, H0 = 450, CARD_H = 22;
    private static final int[] TIER_COLORS = {0xFF8A8D96, 0xFF6FA86F, 0xFF4AA8FF, 0xFFB06FE0, 0xFFF0C85A, 0xFFE08A3A, 0xFFE04A4A};

    private final CharacterRegistry reg;
    private final RoleDefs defs;
    private final TitleData td;
    private final String actorId;
    private final int year;
    private final Opinion opinion;
    private final Consumer<String> cmd;
    private String focus;
    private final List<String> history = new ArrayList<>();
    private boolean appointOpen;
    private int famScroll;
    private int famContentH;
    private int famViewH;
    private int famX;

    private static final class Hit {
        final int x, y, w, h;
        final String kind, a, b;

        Hit(int x, int y, int w, int h, String kind, String a, String b) { this.x = x; this.y = y; this.w = w; this.h = h; this.kind = kind; this.a = a; this.b = b; }
    }

    private final List<Hit> hits = new ArrayList<>();

    public CharacterScreen(CharacterRegistry reg, RoleDefs defs, TitleData td, String actorId, String focusId, int year, Opinion opinion, Consumer<String> cmd) {
        super(Component.literal("Персонаж"));
        this.reg = reg;
        this.defs = defs;
        this.td = td;
        this.actorId = actorId == null ? "" : actorId;
        this.focus = focusId;
        this.year = year;
        this.opinion = opinion;
        this.cmd = cmd;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ данные

    private String placeName(String place) {
        try {
            if (place.startsWith("realm:") && td != null && td.realms.get(place.substring(6)) != null) return td.realms.get(place.substring(6)).name;
            if (place.startsWith("title:") && td != null) { TitleData.Title t = td.get(Integer.parseInt(place.substring(6))); if (t != null) return t.name; }
        } catch (RuntimeException ignored) { }
        return place.substring(place.indexOf(':') + 1);
    }

    private String tierName(int tier) { return td != null && tier >= 0 && tier < td.tiers.size() ? td.tiers.get(tier).name : "Титул"; }

    private static int houseRgb(String house) {
        if (house == null || house.isEmpty()) return 0x5A5C66;
        int h = 0x811C9DC5;
        for (char c : house.toCharArray()) h = (h ^ c) * 16777619;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12;
        int r = 70 + (h & 0x7F), g = 70 + ((h >>> 8) & 0x7F), b = 70 + ((h >>> 16) & 0x7F);
        int hi = Math.max(r, Math.max(g, b)), lo = Math.min(r, Math.min(g, b));
        if (hi - lo < 50) { if (hi == r) r = Math.min(230, r + 60); else if (hi == g) g = Math.min(230, g + 60); else b = Math.min(230, b + 60); }
        return (r << 16) | (g << 8) | b;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String p : name.split("\\s+")) if (!p.isEmpty() && sb.length() < 2) sb.append(java.lang.Character.toUpperCase(p.charAt(0)));
        return sb.toString();
    }

    private static String sub(String s, int n) { return s.length() <= n ? s : s.substring(0, Math.max(0, n - 1)) + "."; }

    private static String standing(int v) {
        return v >= 75 ? "Преданный" : v >= 40 ? "Друг" : v >= 10 ? "Дружелюбный" : v > -10 ? "Нейтральный" : v > -40 ? "Недоволен" : v > -75 ? "Враг" : "Заклятый враг";
    }

    private Character ch(String id) { return id == null || id.isEmpty() ? null : reg.get(id); }

    private List<Character> siblings(Character c) {
        List<Character> out = new ArrayList<>();
        for (Character o : reg.all()) {
            if (o == c) continue;
            boolean sameF = !c.father.isEmpty() && c.father.equals(o.father), sameM = !c.mother.isEmpty() && c.mother.equals(o.mother);
            if (sameF || sameM) out.add(o);
        }
        out.sort(java.util.Comparator.comparingInt((Character o) -> o.birthYear).thenComparing(o -> o.id));
        return out;
    }

    private List<Character> kids(Character c) {
        List<Character> out = new ArrayList<>();
        for (String k : c.children) { Character o = ch(k); if (o != null) out.add(o); }
        out.sort(java.util.Comparator.comparingInt((Character o) -> o.birthYear));
        return out;
    }

    private void addIf(List<Character> l, Character c) { if (c != null && !l.contains(c)) l.add(c); }

    // ------------------------------------------------------------------ рисование

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        hits.clear();
        Character c = ch(focus);
        publishWorkspaceSelection(c);
        int W = Math.min(W0, width - 8), H = Math.min(H0, height - 8);
        int x = (width - W) / 2, y = (height - H) / 2;
        WFUi.panel(g, font, x, y, W, H, c == null ? "Персонаж" : "Персонаж  ·  " + c.name);
        // кнопки окна
        int bx = x + W - 22;
        boolean hc = WFUi.over(mx, my, bx, y + 4, 14, 12);
        WFUi.button(g, font, bx, y + 4, 14, 12, "x", true, false, hc);
        hits.add(new Hit(bx, y + 4, 14, 12, "close", null, null));
        if (!history.isEmpty()) {
            boolean hb = WFUi.over(mx, my, bx - 52, y + 4, 48, 12);
            WFUi.button(g, font, bx - 52, y + 4, 48, 12, "< назад", true, false, hb);
            hits.add(new Hit(bx - 52, y + 4, 48, 12, "back", null, null));
        }
        if (c == null) { g.drawString(font, "Нет такого персонажа: " + focus, x + 12, y + 30, WFUi.BAD); return; }

        List<String> tip = new ArrayList<>();
        int y0 = y + 22;
        header(g, c, x + 6, y0, W - 12, mx, my);
        int ct = y0 + 80, colH = y + H - 8 - ct;
        int lw = 212, rw = 190, cw = W - 12 - lw - rw - 8;
        int lx = x + 6, cx = lx + lw + 4, rx = cx + cw + 4;
        family(g, c, lx, ct, lw, colH, mx, my);
        centre(g, c, cx, ct, cw, colH, mx, my, tip);
        actions(g, c, rx, ct, rw, colH, mx, my, tip);
        if (!tip.isEmpty()) WFUi.tooltip(g, font, width, height, mx, my, tip);
    }

    private void header(GuiGraphics g, Character c, int x, int y, int w, int mx, int my) {
        WFUi.bevel(g, x, y, w, 76, true);
        int rgb = houseRgb(c.house);
        WFUi.portrait(g, font, x + 6, y + 6, 42, 52, c.alive ? rgb : 0x4A4A50, initials(c.name));
        if (!c.alive) g.drawString(font, "+", x + 24, y + 44, WFUi.TX);
        int tx = x + 56, right = x + w - 8;
        g.drawString(font, c.name, tx, y + 6, c.alive ? WFUi.GOLD : WFUi.OFF);
        // статус и возраст справа
        int age = CharacterStats.age(c, year);
        String ageS = age + " л.  " + (c.gender.equals("m") ? "муж." : "жен.");
        int sw = font.width(c.alive ? "ЖИВ" : "МЁРТВ") + 8;
        WFUi.badge(g, font, right - sw, y + 5, c.alive ? "ЖИВ" : "МЁРТВ", c.alive ? WFUi.GOOD : WFUi.BAD);
        g.drawString(font, ageS, right - sw - 8 - font.width(ageS), y + 6, WFUi.TX);
        // дом, культура
        int iy = y + 19;
        if (!c.house.isEmpty()) { WFUi.swatch(g, tx, iy, rgb); g.drawString(font, "Дом " + c.house, tx + 13, iy + 1, WFUi.TX); }
        else g.drawString(font, "Без дома", tx, iy + 1, WFUi.MUTED);
        String cul = c.culture.isEmpty() ? "" : "культура: " + c.culture;
        g.drawString(font, cul, tx + 13 + font.width("Дом " + c.house) + 14, iy + 1, WFUi.MUTED);
        g.drawString(font, "родился в " + c.birthYear, right - font.width("родился в " + c.birthYear), iy + 1, WFUi.MUTED);
        // роли
        int by = y + 32, bxx = tx;
        g.drawString(font, "Роли", bxx, by + 2, WFUi.MUTED);
        bxx += 30;
        if (c.posts.isEmpty()) g.drawString(font, "нет", bxx, by + 2, WFUi.OFF);
        for (Character.Post p : c.posts) {
            Role r = defs.get(p.role);
            String n = r == null ? p.role : r.name;
            int bw = font.width(n) + 8;
            if (bxx + bw > right) break;
            WFUi.badge(g, font, bxx, by, n, r != null && r.ruler ? WFUi.GOLD : r != null && r.noble ? WFUi.INFO : 0xFF8A8D96);
            bxx += bw + 3;
        }
        // титулы
        int ty = y + 47;
        g.drawString(font, "Титулы", tx, ty + 2, WFUi.MUTED);
        int px = tx + 46, startX = px, lines = 0, skipped = 0;
        for (String[] t : heldTitles(c)) {
            int tier = Integer.parseInt(t[0]);
            String tn = tierName(tier);
            int bw = font.width(tn) + 8, need = bw + 3 + font.width(t[1]) + 10;
            if (px + need > right) { if (++lines >= 2) { skipped++; continue; } px = startX; ty += 13; }
            if (lines >= 2) { skipped++; continue; }
            WFUi.badge(g, font, px, ty, tn, TIER_COLORS[Math.max(0, Math.min(TIER_COLORS.length - 1, tier))]);
            g.drawString(font, t[1], px + bw + 3, ty + 2, WFUi.TX);
            px += need;
        }
        if (skipped > 0) g.drawString(font, "+" + skipped, Math.min(px, right - 14), ty + 2, WFUi.MUTED);
        if (heldTitles(c).isEmpty()) g.drawString(font, "нет", px, ty + 2, WFUi.OFF);
    }

    /** {tier, name}: титулы государства (для правителя) и титулы, на которых стоит должность. Старшие первыми. */
    private List<String[]> heldTitles(Character c) {
        List<String[]> out = new ArrayList<>();
        for (Character.Post p : c.posts) {
            Role r = defs.get(p.role);
            try {
                if (p.place.startsWith("realm:") && r != null && r.ruler && td != null) {
                    TitleData.Realm realm = td.realms.get(p.place.substring(6));
                    if (realm != null) {
                        List<TitleData.Title> ts = new ArrayList<>();
                        for (String k : realm.titleKeys) { TitleData.Title t = td.byKey(k); if (t != null) ts.add(t); }
                        ts.sort(java.util.Comparator.comparingInt((TitleData.Title t) -> -t.tier).thenComparing(t -> t.name));
                        for (TitleData.Title t : ts) out.add(new String[]{String.valueOf(t.tier), t.name});
                    }
                } else if (p.place.startsWith("title:") && td != null) {
                    TitleData.Title t = td.get(Integer.parseInt(p.place.substring(6)));
                    if (t != null) out.add(new String[]{String.valueOf(t.tier), t.name});
                }
            } catch (RuntimeException ignored) { }
        }
        out.sort(java.util.Comparator.comparingInt((String[] a) -> -Integer.parseInt(a[0])));
        return out;
    }

    // ---- семья

    private void family(GuiGraphics g, Character c, int x, int y, int w, int h, int mx, int my) {
        WFUi.panel(g, font, x, y, w, h, "Семья");
        List<Character> gp = new ArrayList<>(), par = new ArrayList<>(), self = new ArrayList<>(), kids = kids(c), gk = new ArrayList<>();
        Character f = ch(c.father), m = ch(c.mother);
        if (f != null) { addIf(gp, ch(f.father)); addIf(gp, ch(f.mother)); }
        if (m != null) { addIf(gp, ch(m.father)); addIf(gp, ch(m.mother)); }
        addIf(par, f); addIf(par, m);
        self.add(c);
        addIf(self, ch(c.spouse));
        for (Character k : kids) for (String gc : k.children) addIf(gk, ch(gc));
        String[] names = {"Деды и бабки", "Родители", "Супруги", "Братья и сёстры", "Дети", "Внуки"};
        List<List<Character>> secs = List.of(gp, par, self, siblings(c), kids, gk);

        int vx = x + 6, vy = y + 22, vw = w - 12;
        famViewH = h - 26;
        famX = vx;
        g.enableScissor(vx - 2, vy, vx + vw + 2, vy + famViewH);
        int cy = vy - famScroll, cardW = (vw - 6) / 2;
        for (int s = 0; s < secs.size(); s++) {
            List<Character> list = secs.get(s);
            if (list.isEmpty()) continue;
            g.drawString(font, names[s].toUpperCase() + " (" + list.size() + ")", vx, cy, WFUi.GOLD_DIM);
            cy += 11;
            int shown = Math.min(list.size(), 8);
            for (int i = 0; i < shown; i++) {
                int cx = vx + (i % 2) * (cardW + 4), cyy = cy + (i / 2) * (CARD_H + 2);
                card(g, list.get(i), c, cx, cyy, cardW, mx, my, vy, vy + famViewH);
            }
            cy += (shown + 1) / 2 * (CARD_H + 2);
            if (list.size() > shown) { g.drawString(font, "и ещё " + (list.size() - shown), vx, cy, WFUi.MUTED); cy += 10; }
            cy += 4;
        }
        g.disableScissor();
        famContentH = cy + famScroll - vy;
        WFUi.scrollbar(g, x + w - 5, vy, famViewH, famContentH, famScroll);
    }

    private void card(GuiGraphics g, Character o, Character focusC, int x, int y, int w, int mx, int my, int clipTop, int clipBot) {
        if (y + CARD_H < clipTop || y > clipBot) return;
        boolean isF = o == focusC, hov = WFUi.over(mx, my, x, y, w, CARD_H) && my >= clipTop && my < clipBot;
        WFUi.bevel(g, x, y, w, CARD_H, isF);
        int rgb = houseRgb(o.house);
        g.fill(x + 3, y + 3, x + 7, y + CARD_H - 3, 0xFF000000 | (o.alive ? rgb : 0x45454B));
        if (isF) g.fill(x + 2, y + 2, x + w - 2, y + CARD_H - 2, 0x30F0C85A);
        if (hov && !isF) g.fill(x + 2, y + 2, x + w - 2, y + CARD_H - 2, WFUi.HOVER);
        int col = !o.alive ? WFUi.OFF : isF ? WFUi.GOLD : WFUi.TX;
        g.drawString(font, font.plainSubstrByWidth(o.name, w - 14), x + 10, y + 3, col);
        String kin = CharacterActions.kinship(reg, focusC, o);
        String line = (o.alive ? "" : "+ ") + (isF ? "фокус" : kin.isEmpty() ? "" : kin) + "  " + CharacterStats.age(o, year) + "л";
        g.drawString(font, font.plainSubstrByWidth(line, w - 14), x + 10, y + 12, o.alive ? WFUi.MUTED : WFUi.OFF);
        if (!isF) hits.add(new Hit(x, Math.max(y, clipTop), w, Math.min(y + CARD_H, clipBot) - Math.max(y, clipTop), "focus", o.id, null));
    }

    // ---- центр

    private void centre(GuiGraphics g, Character c, int x, int y, int w, int h, int mx, int my, List<String> tip) {
        WFUi.panel(g, font, x, y, w, h, "Сведения");
        int ix = x + 8, iw = w - 16, cy = y + 24, bottom = y + h - 4;
        g.enableScissor(x + 2, y + 21, x + w - 2, bottom);

        // характеристики
        cy += WFUi.section(g, font, ix, cy, "Характеристики");
        String[] shortN = {"Дипл.", "Война", "Интр.", "Упр.", "Знан."};
        int colW = (iw - 12) / 2;
        for (int i = 0; i < CharacterStats.ATTRS.length; i++) {
            String a = CharacterStats.ATTRS[i];
            int v = CharacterStats.value(c, a), bon = CharacterStats.bonus(c, a);
            int sx = ix + (i % 2) * (colW + 12), sy = cy + (i / 2) * 12;
            WFUi.stat(g, font, sx, sy, colW, shortN[i], v, v >= 14 ? WFUi.GOOD : v <= 6 ? WFUi.BAD : WFUi.INFO);
            if (WFUi.over(mx, my, sx, sy - 1, colW, 10)) {
                tip.add(CharacterStats.ATTR_NAMES[i] + ": " + v + "/20");
                tip.add("Базовое " + CharacterStats.base(c, a) + (bon == 0 ? "" : ", черты " + (bon > 0 ? "+" : "") + bon));
            }
        }
        cy += 3 * 12 + 4;

        // черты
        cy += WFUi.section(g, font, ix, cy, "Черты");
        int px = ix, py = cy;
        CharacterStats.Trait hover = null;
        for (String id : CharacterStats.traits(c)) {
            CharacterStats.Trait t = CharacterStats.trait(id);
            String n = t == null ? id : t.name;
            int bw = font.width(n) + 8;
            if (px + bw > ix + iw) { px = ix; py += 14; }
            WFUi.badge(g, font, px, py, n, t == null ? 0xFF8A8D96 : t.color);
            if (t != null && WFUi.over(mx, my, px, py, bw, 11)) hover = t;
            px += bw + 4;
        }
        cy = py + 15;
        WFUi.bevel(g, ix, cy, iw, 24, true);
        if (hover != null) {
            g.drawString(font, font.plainSubstrByWidth(hover.desc, iw - 8), ix + 4, cy + 3, WFUi.TX);
            StringBuilder mods = new StringBuilder();
            int mxp = ix + 4;
            for (var e : hover.mods.entrySet()) {
                int idx = java.util.Arrays.asList(CharacterStats.ATTRS).indexOf(e.getKey());
                String s = (e.getValue() > 0 ? "+" : "") + e.getValue() + " " + (idx >= 0 ? shortN[idx] : e.getKey());
                g.drawString(font, s, mxp, cy + 13, e.getValue() > 0 ? WFUi.GOOD : WFUi.BAD);
                mxp += font.width(s) + 10;
            }
        } else g.drawString(font, "Наведите на черту, чтобы увидеть описание", ix + 4, cy + 8, WFUi.OFF);
        cy += 30;

        // отношения
        cy += WFUi.section(g, font, ix, cy, "Отношения");
        Character actor = ch(actorId);
        if (c.id.equals(actorId)) g.drawString(font, "Это вы", ix, cy, WFUi.GOLD);
        else {
            Integer op = opinion == null ? null : opinion.of(c.id);
            g.drawString(font, "К вам", ix, cy + 1, WFUi.MUTED);
            if (op == null) g.drawString(font, "нет данных об отношении", ix + 40, cy + 1, WFUi.OFF);
            else {
                int v = Math.max(-100, Math.min(100, op)), bwid = iw - 40 - 96;
                WFUi.balance(g, ix + 40, cy + 2, bwid, 7, v / 100.0, WFUi.BAD, WFUi.GOOD);
                g.drawString(font, (v > 0 ? "+" : "") + v, ix + 44 + bwid, cy + 1, v >= 10 ? WFUi.GOOD : v <= -10 ? WFUi.BAD : WFUi.TX);
                g.drawString(font, standing(v), ix + 44 + bwid + 26, cy + 1, v >= 10 ? WFUi.GOOD : v <= -10 ? WFUi.BAD : WFUi.MUTED);
            }
            cy += 13;
            String kin = CharacterActions.kinship(reg, actor, c);
            kvLine(g, ix, cy, iw, "Родство", kin.isEmpty() ? "нет" : kin, kin.isEmpty() ? WFUi.OFF : WFUi.TX);
            cy += 11;
            boolean sameHouse = actor != null && !c.house.isEmpty() && c.house.equals(actor.house);
            kvLine(g, ix, cy, iw, "Дом", sameHouse ? "ваш дом" : c.house.isEmpty() ? "-" : "чужой дом", sameHouse ? WFUi.GOOD : WFUi.MUTED);
        }
        cy += 11;
        Character sp = ch(c.spouse);
        g.drawString(font, "Брак", ix, cy, WFUi.MUTED);
        if (sp == null) g.drawString(font, "не женат / не замужем", ix + 56, cy, WFUi.OFF);
        else {
            boolean hv = WFUi.over(mx, my, ix + 56, cy - 1, iw - 56, 10);
            g.drawString(font, sp.name + (sp.alive ? "" : " (+)"), ix + 56, cy, hv ? WFUi.GOLD : sp.alive ? WFUi.TX : WFUi.OFF);
            hits.add(new Hit(ix + 56, cy - 1, iw - 56, 10, "focus", sp.id, null));
        }
        cy += 15;

        // должности
        cy += WFUi.section(g, font, ix, cy, "Придворные должности");
        if (c.posts.isEmpty()) { g.drawString(font, "Не занимает должностей", ix, cy, WFUi.OFF); cy += 11; }
        int n = 0;
        for (Character.Post p : c.posts) {
            if (n++ >= 4) break;
            Role r = defs.get(p.role);
            g.drawString(font, r == null ? p.role : r.name, ix, cy, r != null && r.ruler ? WFUi.GOLD : WFUi.TX);
            g.drawString(font, font.plainSubstrByWidth(placeName(p.place), 110), ix + 80, cy, WFUi.MUTED);
            if (r != null && !r.unlocks.isEmpty()) { String u = String.join(",", r.unlocks); g.drawString(font, font.plainSubstrByWidth(u, iw - 196), ix + 196, cy, WFUi.INFO); }
            cy += 11;
        }
        cy += 4;

        // совет: главное место персонажа
        String place = null;
        for (Character.Post p : c.posts) { place = p.place; if (p.place.startsWith("realm:")) break; }
        if (place != null) {
            cy += WFUi.section(g, font, ix, cy, "Совет: " + placeName(place));
            String perKey = null;
            if (place.startsWith("title:") && td != null) { try { TitleData.Title t = td.get(Integer.parseInt(place.substring(6))); if (t != null) perKey = td.tiers.get(t.tier).key; } catch (RuntimeException ignored) { } }
            else perKey = "realm";
            for (Role r : defs.all()) {
                if (!r.per.equals(perKey) || cy + 10 > bottom) continue;
                List<Character> hs = reg.holders(r.id, place);
                g.drawString(font, r.name, ix, cy, WFUi.MUTED);
                if (hs.isEmpty()) g.drawString(font, "вакантно", ix + 80, cy, WFUi.BAD);
                int hx = ix + 80;
                for (int i = 0; i < Math.min(hs.size(), 2); i++) {
                    Character hc = hs.get(i);
                    String nm = font.plainSubstrByWidth(hc.name + (hc.house.isEmpty() ? "" : " " + hc.house), (iw - 80) / Math.min(2, hs.size()) - 6);
                    int nw = font.width(nm);
                    boolean me = hc == c, hv = WFUi.over(mx, my, hx, cy - 1, nw, 10);
                    if (me) g.fill(hx - 2, cy - 1, hx + nw + 2, cy + 9, 0x40F0C85A);
                    g.drawString(font, nm, hx, cy, me || hv ? WFUi.GOLD : WFUi.TX);
                    if (!me) hits.add(new Hit(hx, cy - 1, nw, 10, "focus", hc.id, null));
                    hx += nw + 8;
                }
                cy += 11;
            }
        }
        g.disableScissor();
    }

    private void kvLine(GuiGraphics g, int x, int y, int w, String k, String v, int col) { WFUi.kv(g, font, x, y, w, 56, k, v, col); }

    // ---- действия

    private static String icon(String id) {
        return switch (id) { case "talk" -> "scroll"; case "appoint" -> "shield"; case "dismiss" -> "flag"; case "marry" -> "heart"; default -> "crown"; };
    }

    private void actions(GuiGraphics g, Character c, int x, int y, int w, int h, int mx, int my, List<String> tip) {
        WFUi.panel(g, font, x, y, w, h, "Действия");
        Character actor = ch(actorId);
        int ix = x + 8, iw = w - 16, cy = y + 24;
        List<CharacterActions.Action> acts = actor == null ? List.of() : CharacterActions.available(reg, defs, actor, c, year);
        String hint = null;
        boolean hintBad = false;
        if (actor == null) {
            g.drawString(font, "Нет вашего персонажа:", ix, cy, WFUi.BAD);
            g.drawString(font, "действия недоступны", ix, cy + 10, WFUi.BAD);
            cy += 26;
        }
        for (CharacterActions.Action a : acts) {
            boolean hov = WFUi.over(mx, my, ix, cy, iw, 20), open = a.id.equals("appoint") && appointOpen;
            WFUi.bevel(g, ix, cy, iw, 20, open);
            if (hov && a.enabled) g.fill(ix + 2, cy + 2, ix + iw - 2, cy + 18, WFUi.HOVER);
            int col = !a.enabled ? WFUi.OFF : open ? WFUi.GOLD : WFUi.TX;
            WFUi.icon(g, ix + 5, cy + 6, icon(a.id), col, 1);
            g.drawString(font, font.plainSubstrByWidth(a.label, iw - 24), ix + 18, cy + 6, col);
            hits.add(new Hit(ix, cy, iw, 20, "action", a.id, null));
            if (hov) {
                tip.add(a.label);
                if (a.enabled) tip.add(a.id.equals("talk") ? "Открыть диалог" : "Доступно");
                else tip.add("!" + a.reason);
                hint = a.enabled ? null : a.reason;
                hintBad = true;
            }
            cy += 23;
            if (open && a.id.equals("appoint")) {
                cy += 1;
                for (CharacterActions.Slot s : CharacterActions.slots(reg, defs, actor, c)) {
                    boolean sh = WFUi.over(mx, my, ix + 6, cy, iw - 6, 14);
                    WFUi.rowBg(g, ix + 6, cy, iw - 6, 14, 0, false, sh && s.enabled);
                    g.drawString(font, font.plainSubstrByWidth(s.roleName, iw - 14), ix + 10, cy + 3, s.enabled ? WFUi.TX : WFUi.OFF);
                    hits.add(new Hit(ix + 6, cy, iw - 6, 14, "slot", s.role, s.place));
                    if (sh && !s.enabled) { tip.add(s.roleName + " - " + placeName(s.place)); tip.add("!" + s.reason); hint = s.reason; hintBad = true; }
                    cy += 15;
                }
                cy += 3;
            }
        }
        // причина отказа
        int hy = y + h - 74;
        if (cy < hy) {
            WFUi.bevel(g, ix, hy, iw, 34, true);
            if (hint != null) {
                int ly = hy + 4;
                for (var seq : font.split(Component.literal("Нельзя: " + hint), iw - 8)) { if (ly > hy + 24) break; g.drawString(font, seq, ix + 4, ly, WFUi.BAD); ly += 10; }
            } else g.drawString(font, font.plainSubstrByWidth("Наведите на серое действие: причина", iw - 8), ix + 4, hy + 13, WFUi.OFF);
        }
        // вы
        int ay = y + h - 34;
        WFUi.section(g, font, ix, ay - 12 + 2, "Вы");
        if (actor != null) {
            WFUi.swatch(g, ix, ay + 2, houseRgb(actor.house));
            g.drawString(font, font.plainSubstrByWidth(actor.name, iw - 14), ix + 13, ay + 3, WFUi.TX);
            if (!actor.id.equals(focus)) {
                boolean hv = WFUi.over(mx, my, ix, ay + 14, iw, 14);
                WFUi.button(g, font, ix, ay + 14, iw, 14, "К себе", true, false, hv);
                hits.add(new Hit(ix, ay + 14, iw, 14, "focus", actor.id, null));
            }
        } else g.drawString(font, "-", ix, ay + 3, WFUi.OFF);
    }

    // ------------------------------------------------------------------ ввод

    private void goTo(String id) {
        if (id == null || id.equals(focus) || reg.get(id) == null) return;
        history.add(focus);
        if (history.size() > 30) history.remove(0);
        focus = id;
        famScroll = 0;
        appointOpen = false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!WFUi.over((int) mx, (int) my, h.x, h.y, h.w, h.h)) continue;
            Character c = ch(focus), actor = ch(actorId);
            switch (h.kind) {
                case "close" -> onClose();
                case "back" -> { if (!history.isEmpty()) { focus = history.remove(history.size() - 1); famScroll = 0; appointOpen = false; } }
                case "focus" -> goTo(h.a);
                case "slot" -> {
                    if (c == null || actor == null) return true;
                    for (CharacterActions.Slot s : CharacterActions.slots(reg, defs, actor, c))
                        if (s.role.equals(h.a) && s.place.equals(h.b) && s.enabled) { cmd.accept("wfpeople appoint " + c.id + " " + s.role + " " + s.place); appointOpen = false; }
                }
                case "action" -> {
                    if (c == null || actor == null) return true;
                    for (CharacterActions.Action a : CharacterActions.available(reg, defs, actor, c, year)) {
                        if (!a.id.equals(h.a) || !a.enabled) continue;
                        switch (a.id) {
                            case "talk" -> cmd.accept("wfpeople talk " + c.id);
                            case "appoint" -> appointOpen = !appointOpen;
                            case "dismiss" -> { Character.Post p = CharacterActions.dismissable(reg, defs, actor, c); if (p != null) cmd.accept("wfpeople dismiss " + c.id + " " + p.role + " " + p.place); }
                            case "marry" -> cmd.accept("wfpeople marry " + c.id);
                            default -> cmd.accept("wfpeople grant " + c.id);
                        }
                    }
                }
                default -> { }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        int W = Math.min(W0, width - 8), x = (width - W) / 2;
        if (mx < x + 6 + 212) {
            int max = Math.max(0, famContentH - famViewH);
            famScroll = Math.max(0, Math.min(max, famScroll - (int) Math.signum(d) * 24));
            return true;
        }
        return super.mouseScrolled(mx, my, d);
    }

    /**
     * Optional bridge to MAQAN Platform. Reflection keeps wf-people independently
     * installable; the global inspector receives only a stable Character ID and
     * display metadata, never ownership of the Character object.
     */
    private void publishWorkspaceSelection(Character character) {
        try {
            Class<?> context = Class.forName("dev.maqan.platform.client.PlatformSelectionContext");
            if (character == null) {
                context.getMethod("clear", String.class).invoke(null, "wf_people");
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            String world = mc.level == null ? "" : mc.level.dimension().location().toString();
            String summary = (character.alive ? "Alive" : "Deceased")
                    + " · age " + CharacterStats.age(character, year)
                    + (character.house.isBlank() ? "" : " · house " + character.house)
                    + (character.culture.isBlank() ? "" : " · culture " + character.culture);
            context.getMethod("publish", String.class, String.class, String.class,
                    String.class, String.class, String.class)
                    .invoke(null, "wf_people", character.id, "character",
                            character.name, world, summary);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Platform is optional; the standalone character screen remains fully functional.
        }
    }

    @Override
    public void removed() {
        publishWorkspaceSelection(null);
        super.removed();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 259 && !history.isEmpty()) { focus = history.remove(history.size() - 1); famScroll = 0; appointOpen = false; return true; }   // Backspace
        return super.keyPressed(key, scan, mods);
    }
}
