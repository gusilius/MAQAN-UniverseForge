package dev.worldforge.mod.client;

import dev.worldforge.mod.MapImages;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.Diplomacy;
import dev.worldforge.mod.Markers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Боковая панель окна карты: рейка вкладок справа и выезжающая панель слева от неё.
 * Вкладки: титул, государство, дипломатия, редактор (операторам в творческом режиме), метки.
 * Рисуется в «непосредственном режиме»: при каждом кадре строится список областей клика, обработчики берутся оттуда.
 */
final class SidePanel {
    enum Tab {
        TITLE("Т", "Титул"), REALM("Г", "Государство"), DIP("Д", "Дипломатия"), EDIT("Р", "Редактор"), MARKS("М", "Метки");

        final String glyph, title;

        Tab(String glyph, String title) { this.glyph = glyph; this.title = title; }
    }

    private static final int RAIL = 24, WIDTH = 226, GAP = 4;
    private static final int TX = WFUi.TX, MUTED = WFUi.MUTED, GOLD = WFUi.GOLD;
    private static final int[] BRUSH_STEPS = {4, 8, 12, 16, 24, 32, 48, 64};

    private final MapScreen host;
    private boolean open;
    private Tab tab = Tab.TITLE;
    private final Map<Tab, Integer> scroll = new EnumMap<>(Tab.class);
    private int contentH;
    private int px, py, pw, ph;                       // область панели
    private final List<Hit> hits = new ArrayList<>();
    private int mouseX, mouseY;

    // состояние редактора (отражение команд, отправленных серверу)
    private boolean editOn;
    private String editTool = "brush";
    private int brushIdx = 2;
    private int pickedProv;

    private static final class Hit {
        final int x, y, w, h;
        final Runnable run;

        Hit(int x, int y, int w, int h, Runnable run) { this.x = x; this.y = y; this.w = w; this.h = h; this.run = run; }

        boolean at(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
    }

    SidePanel(MapScreen host) {
        this.host = host;
    }

    // ------------------------------------------------------------------ состояние

    boolean isOpen() { return open; }

    void toggle() { open = !open; }

    void show(Tab t) {
        tab = t;
        open = true;
    }

    /** Редактор включён и открыта его вкладка: клики по карте превращаются в правки. */
    boolean editing() { return open && tab == Tab.EDIT && editOn; }

    void onPicked(int prov) { pickedProv = prov; }

    /** После выбора провинции на карте: показываем сведения (вкладку редактора не трогаем). */
    void onSelected(int prov) {
        if (tab == Tab.EDIT && open) return;
        show(open && (tab == Tab.REALM || tab == Tab.DIP) ? tab : Tab.TITLE);
    }

    private static boolean canEdit() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2) && mc.player.isCreative();
    }

    private static boolean isOp() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasPermissions(2);
    }

    private List<Tab> tabs() {
        List<Tab> out = new ArrayList<>(List.of(Tab.TITLE, Tab.REALM, Tab.DIP));
        if (canEdit()) out.add(Tab.EDIT);
        out.add(Tab.MARKS);
        return out;
    }

    boolean contains(double mx, double my) {
        int rx = host.width() - 8 - RAIL;
        if (mx >= rx && mx < rx + RAIL && my >= 8 && my < 8 + tabs().size() * (RAIL + GAP)) return true;
        return open && mx >= px - 2 && mx < px + pw + 2 && my >= py - 2 && my < py + ph + 2;
    }

    // ------------------------------------------------------------------ рисование

    void render(GuiGraphics g, int mx, int my) {
        mouseX = mx;
        mouseY = my;
        hits.clear();
        List<Tab> tl = tabs();
        if (!tl.contains(tab)) tab = Tab.TITLE;
        int rx = host.width() - 8 - RAIL;
        for (int i = 0; i < tl.size(); i++) {
            Tab t = tl.get(i);
            int y = 8 + i * (RAIL + GAP);
            boolean on = open && t == tab;
            MapScreen.bevel(g, rx, y, RAIL, RAIL, on);
            if (on) g.fill(rx - 3, y + 2, rx - 1, y + RAIL - 2, GOLD);
            else if (mx >= rx && mx < rx + RAIL && my >= y && my < y + RAIL) g.fill(rx + 2, y + 2, rx + RAIL - 2, y + RAIL - 2, 0x30FFFFFF);
            g.drawCenteredString(host.font(), t.glyph, rx + RAIL / 2, y + 8, on ? GOLD : TX);
            final Tab ft = t;
            hits.add(new Hit(rx, y, RAIL, RAIL, () -> { if (open && tab == ft) open = false; else show(ft); }));
        }
        if (!open) return;
        pw = WIDTH;
        px = rx - GAP - pw;
        py = 8;
        ph = Math.max(150, host.panelBottom() - py);
        MapScreen.bevel(g, px, py, pw, ph, false);
        g.drawString(host.font(), tab.title.toUpperCase(), px + 8, py + 7, GOLD);
        g.fill(px, py + 19, px + pw, py + 21, 0xFF000000);
        int bx = px + pw - 15, by = py + 5;
        g.drawString(host.font(), "x", bx + 3, by + 1, MUTED);
        hits.add(new Hit(bx, by, 11, 11, () -> open = false));

        int cx = px + 8, cy = py + 25, cw = pw - 16, ch = ph - 30;
        g.enableScissor(px + 1, cy - 2, px + pw - 1, py + ph - 1);
        int off = scroll.getOrDefault(tab, 0);
        int[] cur = {cy - off};
        Ctx c = new Ctx(g, cx, cw, cur, cy, cy + ch);
        switch (tab) {
            case TITLE -> titleTab(c);
            case REALM -> realmTab(c);
            case DIP -> dipTab(c);
            case EDIT -> editTab(c);
            case MARKS -> marksTab(c);
        }
        g.disableScissor();
        contentH = cur[0] + off - cy;
        int max = Math.max(0, contentH - ch);
        if (off > max) scroll.put(tab, max);
        if (max > 0) {                                           // полоса прокрутки
            int bh = Math.max(14, ch * ch / contentH), bpos = cy + (int) ((ch - bh) * (off / (double) max));
            g.fill(px + pw - 5, cy, px + pw - 3, cy + ch, 0xFF0C0C0E);
            g.fill(px + pw - 5, bpos, px + pw - 3, bpos + bh, 0xFF5D5D66);
        }
    }

    boolean click(double mx, double my, int button) {
        if (button != 0) return contains(mx, my);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.at(mx, my)) {
                h.run.run();
                return true;
            }
        }
        return contains(mx, my);
    }

    boolean scrolled(double mx, double my, double delta) {
        if (!open || mx < px || mx >= px + pw || my < py || my >= py + ph) return false;
        int max = Math.max(0, contentH - (ph - 30));
        int v = scroll.getOrDefault(tab, 0) - (int) (delta * 18);
        scroll.put(tab, Math.max(0, Math.min(max, v)));
        return true;
    }

    // ------------------------------------------------------------------ строительные блоки содержимого

    /** Контекст рисования одной вкладки: курсор по вертикали и список кликов. */
    private final class Ctx {
        final GuiGraphics g;
        final int x, w, top, bottom;
        final int[] y;

        Ctx(GuiGraphics g, int x, int w, int[] y, int top, int bottom) {
            this.g = g; this.x = x; this.w = w; this.y = y; this.top = top; this.bottom = bottom;
        }

        boolean visible(int h) { return y[0] + h > top - 12 && y[0] < bottom + 12; }

        void gap(int n) { y[0] += n; }

        void text(String s, int color) {
            for (String line : wrap(s, w)) {
                if (visible(11)) g.drawString(host.font(), line, x, y[0], color);
                y[0] += 11;
            }
        }

        void head(String s) {
            gap(5);
            if (visible(11)) g.drawString(host.font(), s.toUpperCase(), x, y[0], WFUi.GOLD_DIM);
            y[0] += 13;
        }

        void kv(String k, String v) {
            int kw = 78;
            List<String> lines = wrap(v, w - kw);
            if (visible(11 * lines.size())) {
                g.drawString(host.font(), host.font().plainSubstrByWidth(k, kw - 4), x, y[0], MUTED);
                for (int i = 0; i < lines.size(); i++) g.drawString(host.font(), lines.get(i), x + kw, y[0] + 11 * i, TX);
            }
            y[0] += 11 * Math.max(1, lines.size());
        }

        void swatch(int rgb, String label, int color, int indent, Runnable click) {
            int yy = y[0];
            if (visible(12)) {
                g.fill(x + indent, yy, x + indent + 9, yy + 9, 0xFF000000);
                g.fill(x + indent + 1, yy + 1, x + indent + 8, yy + 8, 0xFF000000 | rgb);
                String t = host.font().plainSubstrByWidth(label, w - indent - 14);
                boolean hov = click != null && mouseX >= x && mouseX < x + w && mouseY >= yy && mouseY < yy + 11 && mouseY >= top && mouseY < bottom;
                g.drawString(host.font(), t, x + indent + 13, yy + 1, hov ? GOLD : color);
                if (click != null && yy + 11 > top && yy < bottom) hits.add(new Hit(x, yy, w, 11, click));
            }
            y[0] += 12;
        }

        void button(String label, boolean enabled, Runnable run) {
            button(label, enabled, false, run);
        }

        void button(String label, boolean enabled, boolean on, Runnable run) {
            int h = 15, yy = y[0];
            if (visible(h)) drawButton(x, yy, w, h, label, enabled, on, run);
            y[0] += h + 3;
        }

        /** Несколько кнопок в ряд. */
        void row(String[] labels, boolean[] on, Runnable[] runs) {
            int n = labels.length, gap = 3, bw = (w - gap * (n - 1)) / n, yy = y[0];
            if (visible(15)) for (int i = 0; i < n; i++) drawButton(x + i * (bw + gap), yy, bw, 15, labels[i], true, on != null && on[i], runs[i]);
            y[0] += 18;
        }

        private void drawButton(int bx, int by, int bw, int bh, String label, boolean enabled, boolean on, Runnable run) {
            boolean hov = enabled && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh && mouseY >= top && mouseY < bottom;
            MapScreen.bevel(g, bx, by, bw, bh, on);
            if (hov) g.fill(bx + 2, by + 2, bx + bw - 2, by + bh - 2, 0x30FFFFFF);
            String t = host.font().plainSubstrByWidth(label, bw - 6);
            g.drawString(host.font(), t, bx + (bw - host.font().width(t)) / 2, by + 4, !enabled ? WFUi.OFF : on ? GOLD : TX);
            if (enabled && by + bh > top && by < bottom) hits.add(new Hit(bx, by, bw, bh, run));
        }
    }

    private List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) { out.add(""); return out; }
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            while (host.font().width(word) > width && word.length() > 1) {            // слишком длинное слово режем
                int cut = word.length();
                while (cut > 1 && host.font().width(word.substring(0, cut)) > width) cut--;
                if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                out.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String t = line.length() == 0 ? word : line + " " + word;
            if (host.font().width(t) > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(t);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ вкладки

    private TitleData data() { return MapTextures.titles; }

    private static String relLabel(TitleData d, TitleData.Realm r) { return r.relation == null ? "Сюзерен" : d.relationLabel(r.relation); }

    private void empty(Ctx c, String s) { c.text(s, MUTED); }

    private void titleTab(Ctx c) {
        TitleData d = data();
        TitleData.Title p = d == null ? null : d.get(host.selectedProv());
        if (p == null) { empty(c, "Выберите землю на карте: здесь появятся сведения о титуле и его иерархии."); return; }
        c.swatch(p.rgb, p.name, GOLD, 0, null);
        c.text(d.tiers.get(p.tier).name + " · " + p.areaBlocks / 1000 + " тыс. блоков²", MUTED);
        c.head("Иерархия");
        List<TitleData.Title> chain = d.chain(p);
        for (int i = chain.size() - 1; i >= 0; i--) {
            TitleData.Title t = chain.get(i);
            c.swatch(t.rgb, d.tiers.get(t.tier).name + ": " + t.name, i == 0 ? TX : MUTED, (chain.size() - 1 - i) * 4, null);
        }
        boolean any = false;
        for (Map.Entry<String, Object> e : p.props.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Map || v instanceof List || v.toString().isEmpty()) continue;
            if (!any) { c.head("Свойства"); any = true; }
            String name = MapImages.propName(d, e.getKey());
            c.kv(name != null ? name : e.getKey(), v.toString());
        }
        c.head("Сведения");
        c.kv("Соседей", String.valueOf(p.neighbors.length));
        c.kv("Столица", "X " + p.capX + "  Z " + p.capZ);
        c.gap(4);
        c.button("Показать на карте", true, () -> host.centerOnProvince(p.id));
        if (isOp()) c.button("Телепорт к столице", true, () -> host.command("worldforge tpxz " + p.capX + " " + p.capZ));
    }

    private void realmTab(Ctx c) {
        TitleData d = data();
        TitleData.Title p = d == null ? null : d.get(host.selectedProv());
        TitleData.Realm r = p == null ? null : d.realmOf(p);
        if (p == null) { empty(c, "Выберите землю на карте."); return; }
        if (r == null) { empty(c, "У этой земли нет государства."); return; }
        c.swatch(r.rgb, r.name, GOLD, 0, null);
        c.text(r.overlord != null ? relLabel(d, r) + " · " + r.overlord.name : "Независимое государство", MUTED);
        c.gap(4);
        c.kv("Правитель", r.ruler == null || r.ruler.isEmpty() ? "не назначен" : r.ruler);
        c.kv("Площадь", r.areaBlocks / 1000 + " тыс. блоков²");
        for (Map.Entry<String, Object> e : r.props.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Map || v instanceof List || v.toString().isEmpty()) continue;
            String k = e.getKey();
            if (k.equals("allies") || k.equals("enemies")) {
                c.kv(k.equals("allies") ? "Союзники" : "Воюет с", realmNames(d, Diplomacy.ids(r, k)));
            } else {
                String name = MapImages.propName(d, k);
                c.kv(name != null ? name : k, v.toString());
            }
        }
        c.head("Титулы");
        for (String key : r.titleKeys) {
            TitleData.Title t = d.byKey(key);
            if (t != null) c.swatch(t.rgb, d.tiers.get(t.tier).name + " " + t.name, TX, 0, () -> host.selectProvince(provinceOf(d, t)));
        }
        if (!r.subjectIds.isEmpty()) {
            c.head("Подчинённые · " + r.subjectIds.size());
            for (String id : r.subjectIds) {
                TitleData.Realm s = d.realms.get(id);
                if (s != null) c.swatch(s.rgb, s.name + " [" + relLabel(d, s) + "]", TX, 0, () -> selectRealm(d, s));
            }
        }
        c.gap(4);
        c.button("Дипломатический слой", true, () -> host.setMode(MapImages.DIP + r.id, true));
    }

    private static String realmNames(TitleData d, Set<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            TitleData.Realm x = d.realms.get(id);
            if (sb.length() > 0) sb.append(", ");
            sb.append(x == null ? id : x.name);
        }
        return sb.toString();
    }

    /** Любая провинция (барство) под титулом: сам титул или первый потомок-барство; для столицы берём capProvince. */
    private static int provinceOf(TitleData d, TitleData.Title t) {
        TitleData.Title cur = t;
        for (int g = 0; cur != null && cur.tier > 0 && g < 8; g++) cur = d.get(cur.capProvince);
        return cur == null ? 0 : cur.id;
    }

    private void selectRealm(TitleData d, TitleData.Realm r) {
        TitleData.Title top = null;
        for (String k : r.titleKeys) {
            TitleData.Title t = d.byKey(k);
            if (t != null && (top == null || t.tier > top.tier)) top = t;
        }
        if (top != null) host.selectProvince(provinceOf(d, top));
    }

    private void dipTab(Ctx c) {
        TitleData d = data();
        TitleData.Title p = d == null ? null : d.get(host.selectedProv());
        TitleData.Realm r = p == null ? null : d.realmOf(p);
        if (r == null) { empty(c, "Выберите государство на карте: здесь будут дерево подчинения, отношения и действия сервера."); return; }
        TitleData.Realm top = r;
        for (int g = 0; top.overlord != null && g < 32; g++) top = top.overlord;
        c.head("Дерево подчинения");
        tree(c, d, top, r, 0);
        c.head("Отношения");
        Map<String, Set<String>> adj = Diplomacy.adjacency(d);
        int[] cnt = new int[Diplomacy.Rel.values().length];
        for (TitleData.Realm o : d.realmList()) cnt[Diplomacy.relation(d, r, o, adj).ordinal()]++;
        for (Diplomacy.Rel rel : Diplomacy.Rel.values()) {
            if (rel == Diplomacy.Rel.NONE || cnt[rel.ordinal()] == 0) continue;
            c.swatch(rel.rgb, rel.label + " · " + cnt[rel.ordinal()], TX, 0, null);
        }
        Set<String> en = Diplomacy.ids(r, "enemies"), al = Diplomacy.ids(r, "allies");
        if (!en.isEmpty()) c.kv("Воюет с", realmNames(d, en));
        if (!al.isEmpty()) c.kv("Союзники", realmNames(d, al));
        c.gap(3);
        c.button("Дипломатический слой", true, () -> host.setMode(MapImages.DIP + r.id, true));
        c.head("Действия");
        List<ClientAction> acts = ClientState.actions;
        if (acts.isEmpty()) c.text("Сервер не передал действий.", MUTED);
        for (ClientAction a : acts) {
            if ("map".equals(a.scope)) continue;
            boolean allowed = !a.op() || isOp();
            String target = "realm".equals(a.scope) ? r.id : String.valueOf(p.id);
            c.button(a.label + (a.op() ? " *" : ""), allowed, () -> host.runAction(a, target));
            if (!a.hint.isEmpty()) c.text(a.hint, MUTED);
        }
        c.text("* только оператор", MUTED);
    }

    private void tree(Ctx c, TitleData d, TitleData.Realm node, TitleData.Realm cur, int depth) {
        String label = node.name + (node.overlord != null ? " [" + relLabel(d, node) + "]" : "");
        c.swatch(node.rgb, label, node == cur ? GOLD : TX, Math.min(depth, 6) * 8, () -> selectRealm(d, node));
        if (depth >= 8) return;
        for (String id : node.subjectIds) {
            TitleData.Realm s = d.realms.get(id);
            if (s != null) tree(c, d, s, cur, depth + 1);
        }
    }

    private void editTab(Ctx c) {
        TitleData d = data();
        c.text("Правки границ, титулов и государств. Выполняются на сервере командами редактора; работают операторы в творческом режиме.", MUTED);
        c.gap(4);
        c.row(new String[]{"Включить", "Выключить", "Жезл"}, new boolean[]{editOn, !editOn, false}, new Runnable[]{
                () -> { host.command("worldforge editor on"); editOn = true; },
                () -> { host.command("worldforge editor off"); editOn = false; },
                () -> host.command("worldforge editor wand")});
        if (!editOn) { c.gap(2); c.text("Включите редактор, чтобы править карту кликами.", GOLD); }
        c.head("Инструмент");
        c.row(new String[]{"Кисть", "Сглажив.", "Назначить"}, new boolean[]{editTool.equals("brush"), editTool.equals("smooth"), editTool.equals("assign")}, new Runnable[]{
                () -> { host.command("worldforge editor tool brush"); editTool = "brush"; },
                () -> { host.command("worldforge editor tool smooth"); editTool = "smooth"; },
                () -> { editTool = "assign"; }});
        if (editTool.equals("assign") && d != null) {
            c.text("Выберите (Shift+клик) провинцию, затем уровень титула, который ей назначить:", MUTED);
            for (TitleData.Tier t : d.tiers) {
                if (t.index == 0) continue;
                c.button("Назначить: " + t.name, pickedProv > 0, () -> host.command("worldforge editor tool assign " + t.key));
            }
        }
        c.head("Кисть");
        c.row(new String[]{"-", BRUSH_STEPS[brushIdx] + " бл.", "+"}, null, new Runnable[]{
                () -> { brushIdx = Math.max(0, brushIdx - 1); host.command("worldforge editor brush " + BRUSH_STEPS[brushIdx]); },
                () -> { },
                () -> { brushIdx = Math.min(BRUSH_STEPS.length - 1, brushIdx + 1); host.command("worldforge editor brush " + BRUSH_STEPS[brushIdx]); }});
        c.head("Выбор");
        TitleData.Title pk = d == null ? null : d.get(pickedProv);
        c.kv("Провинция", pk == null ? "не выбрана" : pk.name);
        c.text("Клик по карте: применить инструмент. Shift+клик: выбрать провинцию для закраски.", MUTED);
        c.head("Границы на земле");
        if (d != null) {
            String[] labels = new String[d.tiers.size()];
            Runnable[] runs = new Runnable[d.tiers.size()];
            for (int i = 0; i < labels.length; i++) {
                final TitleData.Tier t = d.tiers.get(i);
                labels[i] = t.name.length() > 5 ? t.name.substring(0, 4) + "." : t.name;
                runs[i] = () -> host.command("worldforge editor level " + t.key);
            }
            int per = 3;
            for (int i = 0; i < labels.length; i += per) {
                int n = Math.min(per, labels.length - i);
                c.row(java.util.Arrays.copyOfRange(labels, i, i + n), null, java.util.Arrays.copyOfRange(runs, i, i + n));
            }
        }
        c.head("История и проверка");
        c.row(new String[]{"Отменить", "Повторить"}, null, new Runnable[]{() -> host.command("worldforge editor undo"), () -> host.command("worldforge editor redo")});
        c.row(new String[]{"Проверить", "Сохранить"}, null, new Runnable[]{() -> host.command("worldforge editor validate"), () -> host.command("worldforge editor save")});
    }

    private void marksTab(Ctx c) {
        c.head("Метки сервера · " + ClientState.markers.list().size());
        if (ClientState.markers.list().isEmpty()) c.text("Меток нет. Оператор: /worldforge marker add <тип> <название>", MUTED);
        for (Markers.Marker m : ClientState.markers.list()) c.swatch(0xFFD24A, m.name + " [" + m.type + "]", TX, 0, () -> host.centerOnBlock(m.x, m.z));
        c.head("Мои вейпойнты · " + Waypoints.LIST.size());
        if (Waypoints.LIST.isEmpty()) c.text("Наведите курсор и нажмите B, чтобы поставить вейпойнт.", MUTED);
        for (Waypoints.Wp w : new ArrayList<>(Waypoints.LIST)) {
            c.swatch(w.rgb, w.name, 0xFF9CF2E8, 0, () -> host.centerOnBlock(w.x, w.z));
        }
        if (!Waypoints.LIST.isEmpty()) c.text("Наведите на вейпойнт и нажмите Delete, чтобы удалить.", MUTED);
    }
}
