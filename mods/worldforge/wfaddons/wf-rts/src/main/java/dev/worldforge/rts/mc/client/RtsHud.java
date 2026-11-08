package dev.worldforge.rts.mc.client;

import dev.worldforge.mod.client.WFUi;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;
import dev.worldforge.rts.Command;
import dev.worldforge.rts.CommandCard;
import dev.worldforge.rts.ControlGroups;
import dev.worldforge.rts.Look;
import dev.worldforge.rts.MapView;
import dev.worldforge.rts.Orders;
import dev.worldforge.rts.Selection;
import dev.worldforge.rts.Target;
import dev.worldforge.rts.WorkPriorities;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Окно управления поселением как в RTS (Warcraft III, Manor Lords, Total War): сверху запасы, жители, настроение и кнопка
 * «Свободные»; снизу миникарта, панель выбора и карточка приказов 4x3 с горячими клавишами. Середина прозрачна: мир виден.
 * Все приказы уходят командами /wfsettle через {@code cmd}; всё решение «что можно» берёт {@link CommandCard}/{@link Orders}.
 *
 * <p>Выбор в мире (для склейки с Forge): луч из камеры по сущностям WFWorker даёт {@code Target.worker(id, x, z)}, по блоку внутри
 * здания {@code Target.building(uid, x, z)}, иначе {@code Target.ground(x, z)} (координаты от центра поселения). Эти цели подаются
 * в {@link #click(Target, int, boolean, boolean, boolean)} так же, как щелчок по миникарте; рамка выбора на экране собирается из
 * проекций положения WFWorker в прямоугольники и отдаётся в {@link Selection#boxSelect}.
 */
public class RtsHud extends Screen {
    private static final int TOP = 22, BH = 118, CELL = 30, GAP = 2;
    private Settlement s;
    private final Consumer<String> cmd;
    public final Selection sel = new Selection();
    public final ControlGroups groups = new ControlGroups();
    public final WorkPriorities prio = new WorkPriorities();
    private String menu = CommandCard.ROOT, pending, msg = "";
    private boolean targetMode, showPrio;
    private long msgUntil;
    private int dragX = -1, dragY = -1, curX, curY, prioScroll, lastClickId = -1;
    private long lastClickAt;
    private MapView mv;
    private final List<Hit> hits = new ArrayList<>();
    private String tipTitle;
    private List<String> tipLines;

    private static final class Hit {
        final int x, y, w, h;
        final Runnable left, right;
        final String tipTitle;
        final List<String> tip;

        Hit(int x, int y, int w, int h, Runnable left, Runnable right, String tipTitle, List<String> tip) { this.x = x; this.y = y; this.w = w; this.h = h; this.left = left; this.right = right; this.tipTitle = tipTitle; this.tip = tip; }

        boolean at(int mx, int my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    public RtsHud(Settlement s, Consumer<String> cmd) {
        super(Component.literal(s.name));
        this.s = s;
        this.cmd = cmd;
    }

    public void setModel(Settlement n) { this.s = n; }

    private int refresh;

    @Override
    public void tick() {
        if (++refresh % 20 == 0) cmd.accept("wfsettle view hud");
    }

    @Override
    public boolean isPauseScreen() { return false; }

    public void setMenu(String m) { menu = m; }

    public void setPending(String defId) { pending = defId; }

    public void setPrioritiesOpen(boolean b) { showPrio = b; }

    private void say(String m) { msg = m; msgUntil = System.currentTimeMillis() + 4500; }

    private void send(Orders.Result r) {
        if (!r.message.isEmpty()) say(r.message);
        for (String c : r.commands) cmd.accept(c);
        if (r.cancelPending) pending = null;
    }

    private void hit(int x, int y, int w, int h, Runnable left, Runnable right, String title, List<String> tip) { hits.add(new Hit(x, y, w, h, left, right, title, tip)); }

    // ------------------------------------------------------------------ рисование

    private boolean embeddedInMaqan;

    public void setEmbeddedInMaqan(boolean embedded) { this.embeddedInMaqan = embedded; }

    private void publishWorkspaceSelection() {
        if (sel.hasBuilding()) {
            Building b = sel.building(s);
            if (b == null) { dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_rts"); return; }
            dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_rts",
                    "building:" + b.uid, "building", b.def.name,
                    "Settlement building · " + Look.stateName(b) + " · " + b.x + ", " + b.z);
            return;
        }
        List<Worker> workers = sel.workers(s);
        if (workers.isEmpty()) {
            dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_rts");
            return;
        }
        if (workers.size() == 1) {
            Worker worker = workers.get(0);
            String id = worker.person == null || worker.person.isBlank()
                    ? "worker:" + worker.id : worker.person;
            dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_rts", id,
                    worker.person == null || worker.person.isBlank() ? "worker" : "character",
                    worker.name, "Profession: " + s.defs.professionName(worker.profession)
                            + " · " + (worker.status == null ? "" : worker.status));
            return;
        }
        String ids = workers.stream().map(w -> String.valueOf(w.id)).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        String professions = sel.byProfession(s).entrySet().stream()
                .map(e -> s.defs.professionName(e.getKey()) + " ×" + e.getValue())
                .collect(java.util.stream.Collectors.joining(" · "));
        dev.worldforge.mod.client.PlatformWorkspaceBridge.publish("wf_rts",
                "worker-group:" + ids, "worker group", workers.size() + " selected",
                workers.size() + " workers · " + professions);
    }

    @Override
    public void removed() {
        dev.worldforge.mod.client.PlatformWorkspaceBridge.clear("wf_rts");
        super.removed();
    }

    @Override
    public void onClose() {
        if (embeddedInMaqan && dev.worldforge.mod.client.PlatformWorkspaceBridge.closeEmbedded(this)) return;
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        sel.prune(s);
        publishWorkspaceSelection();
        hits.clear();
        tipTitle = null;
        curX = mx; curY = my;
        topBar(g, mx, my);
        alertLine(g, mx, my);
        bottomBar(g, mx, my);
        if (pending != null) {
            Defs.Building d = s.defs.buildings.get(pending);
            String t = "Размещение: " + (d == null ? pending : d.name) + " - ЛКМ по земле на миникарте, ПКМ или Esc отменяют";
            int w = font.width(t) + 16, x = (width - w) / 2, y = height - BH - 38;
            WFUi.bevel(g, x, y, w, 16, false);
            g.drawString(font, t, x + 8, y + 4, WFUi.GOLD);
        } else if (targetMode) {
            String t = "Укажите здание для назначения (ЛКМ по зданию), Esc отменяет";
            int w = font.width(t) + 16, x = (width - w) / 2, y = height - BH - 38;
            WFUi.bevel(g, x, y, w, 16, false);
            g.drawString(font, t, x + 8, y + 4, WFUi.GOLD);
        }
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            int w = font.width(msg) + 16, x = (width - w) / 2, y = height - BH - 20;
            WFUi.bevel(g, x, y, w, 15, true);
            g.drawString(font, msg, x + 8, y + 4, WFUi.TX);
        }
        if (showPrio) priorityWindow(g, mx, my);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.tip != null && h.at(mx, my)) { tipTitle = h.tipTitle; tipLines = h.tip; break; }
        }
        if (tipTitle != null) WFUi.tooltip(g, font, mx, my, width, height, tipTitle, tipLines);
        super.render(g, mx, my, pt);
    }

    private void topBar(GuiGraphics g, int mx, int my) {
        WFUi.bevel(g, 4, 2, width - 8, TOP - 4, false);
        int x = 12, y = 7;
        for (var e : s.defs.resourceNames.entrySet()) {
            double a = s.amount(e.getKey());
            String name = e.getValue();
            int rx = x;
            g.fill(x, y + 1, x + 6, y + 7, Look.resourceColor(e.getKey()));
            x += 9;
            x += WFUi.stat(g, font, x, y, name.length() > 7 ? name.substring(0, 6) + "." : name, String.valueOf((int) a), a < 1 ? WFUi.BAD : a >= s.capacity(e.getKey()) - 1 ? WFUi.GOLD : WFUi.TX);
            List<String> tip = new ArrayList<>();
            tip.add("Запас " + (int) a + " / " + (int) s.capacity(e.getKey()));
            double net = s.income(e.getKey());
            tip.add((net >= 0 ? "+" : "-") + String.format("%+.1f", net).substring(1) + " в минуту (добыча " + String.format("%.1f", s.producedPerMin(e.getKey())) + ", расход " + String.format("%.1f", s.consumedPerMin(e.getKey())) + ")");
            hit(rx, 2, x - rx, TOP - 4, null, null, name, tip);
            if (x > width - 330) break;
        }
        // справа: жители, настроение, свободные, работы
        int rx = width - 18;
        String ap = "Настроение " + s.approval();
        int apc = s.approval() < 30 ? WFUi.BAD : s.approval() < 50 ? WFUi.GOLD : WFUi.GOOD;
        rx -= font.width(ap);
        g.drawString(font, ap, rx, y, apc);
        List<String> atip = new ArrayList<>();
        for (var f : s.approvalBreakdown()) atip.add((f.value > 0 ? "+" : f.value < 0 ? "-" : "") + f.label + ": " + (f.value > 0 ? "+" : "") + f.value);
        hit(rx, 2, font.width(ap), TOP - 4, null, null, "Настроение жителей", atip);
        rx -= 12;
        String pop = "Жители " + s.workers.size() + "/" + s.housing();
        rx -= font.width(pop);
        g.drawString(font, pop, rx, y, s.workers.size() >= s.housing() ? WFUi.GOLD : WFUi.TX);
        rx -= 14;
        int idle = s.idleWorkers().size();
        String it = "Свободные " + idle + " [.]";
        int iw = font.width(it) + 12;
        rx -= iw;
        WFUi.button(g, font, rx, 3, iw, TOP - 6, it, idle > 0, false, WFUi.over(mx, my, rx, 3, iw, TOP - 6));
        if (idle > 0) hit(rx, 3, iw, TOP - 6, () -> { say("Свободных выбрано: " + sel.selectIdle(s, false)); }, null, "Свободные рабочие", List.of("Выбрать всех без работы", "Клавиша: ."));
        rx -= 6;
        String pt = "Работы [P]";
        int pw = font.width(pt) + 12;
        rx -= pw;
        WFUi.button(g, font, rx, 3, pw, TOP - 6, pt, true, showPrio, WFUi.over(mx, my, rx, 3, pw, TOP - 6));
        hit(rx, 3, pw, TOP - 6, () -> showPrio = !showPrio, null, "Приоритеты работ", List.of("Матрица: рабочий x профессия, 0-4"));
    }

    private void alertLine(GuiGraphics g, int mx, int my) {
        List<Settlement.Alert> al = s.alerts();
        if (al.isEmpty()) return;
        int y = TOP + 2;
        for (int i = 0; i < Math.min(3, al.size()); i++) {
            Settlement.Alert a = al.get(i);
            int c = a.severity >= 2 ? WFUi.BAD : a.severity == 1 ? WFUi.GOLD : WFUi.MUTED;
            int w = font.width(a.text) + 22;
            g.fill(6, y, 6 + w, y + 13, 0xC0101216);
            g.fill(6, y, 8, y + 13, c);
            g.drawString(font, a.text, 14, y + 3, c == WFUi.MUTED ? WFUi.TX : c);
            if (a.building != 0) {
                int uid = a.building;
                hit(6, y, w, 13, () -> { sel.selectBuilding(uid); menu = CommandCard.ROOT; }, null, "Перейти к зданию", List.of("Выбрать здание"));
            }
            y += 14;
        }
    }

    private void bottomBar(GuiGraphics g, int mx, int my) {
        int y0 = height - BH, total = Math.min(width - 12, 1040), bx0 = (width - total) / 2;
        g.fill(bx0 - 6, y0, bx0 + total + 6, height, 0xE8101216);
        g.fill(bx0 - 6, y0, bx0 + total + 6, y0 + 1, WFUi.EDGE);
        g.fill(bx0 - 6, y0 + 1, bx0 + total + 6, y0 + 2, WFUi.HI);
        // миникарта
        int mw = 124, mh = BH - 14, mx0 = bx0, my0 = y0 + 8;
        WFUi.bevel(g, mx0 - 2, my0 - 2, mw + 4, mh + 4, true);
        g.fill(mx0, my0, mx0 + mw, my0 + mh, 0xFF1B2418);
        mv = MapView.fit(s, mx0, my0, mw, mh);
        g.enableScissor(mx0, my0, mx0 + mw, my0 + mh);
        for (Building b : s.buildings) {
            int w = Math.max(4, (int) Math.round(b.def.sizeX * mv.scale)), h = Math.max(4, (int) Math.round(b.def.sizeZ * mv.scale));
            int cx = mv.sx(b.x), cz = mv.sy(b.z), col = Look.buildingColor(s, b);
            g.fill(cx - w / 2 - 1, cz - h / 2 - 1, cx + w / 2 + 2, cz + h / 2 + 2, b.uid == sel.building() ? WFUi.GOLD : 0xFF000000);
            g.fill(cx - w / 2, cz - h / 2, cx + w / 2 + 1, cz + h / 2 + 1, col);
        }
        for (Worker w : s.workers) {
            int cx = mv.sx(w.x), cz = mv.sy(w.z);
            boolean on = sel.contains(w.id);
            g.fill(cx - 2, cz - 2, cx + 3, cz + 3, on ? WFUi.GOLD : 0xFF000000);
            g.fill(cx - 1, cz - 1, cx + 2, cz + 2, on ? 0xFFFFFFFF : Look.stateColor(Look.state(s, w)));
        }
        if (dragX >= 0) {
            int x1 = Math.min(dragX, curX), y1 = Math.min(dragY, curY), x2 = Math.max(dragX, curX), y2 = Math.max(dragY, curY);
            g.fill(x1, y1, x2, y1 + 1, 0xFF4FCF5A); g.fill(x1, y2, x2, y2 + 1, 0xFF4FCF5A); g.fill(x1, y1, x1 + 1, y2, 0xFF4FCF5A); g.fill(x2, y1, x2 + 1, y2 + 1, 0xFF4FCF5A);
        }
        g.disableScissor();
        // карточка приказов
        int cw = CommandCard.COLS * (CELL + GAP) + 8, cx0 = bx0 + total - cw;
        WFUi.bevel(g, cx0, y0 + 6, cw, BH - 12, true);
        card(g, cx0 + 4, y0 + 10, mx, my);
        // панель выбора
        int sx = mx0 + mw + 12, sw = cx0 - sx - 8;
        WFUi.bevel(g, sx, y0 + 6, sw, BH - 12, true);
        selectionPanel(g, sx + 6, y0 + 10, sw - 12, BH - 20, mx, my);
        if (menu.startsWith("build:")) buildCards(g, sx, y0 - 76, sw + 8 + cw, mx, my);
    }

    /** Карточки зданий выбранной категории над нижней панелью (как меню стройки в Manor Lords): название, стоимость, что даёт, чего не хватает. */
    private void buildCards(GuiGraphics g, int x, int y, int w, int mx, int my) {
        String cat = menu.substring(6);
        List<Defs.Building> list = CommandCard.buildingsIn(s.defs, cat);
        int cwid = 168, ch = 70, per = Math.max(1, w / (cwid + 4));
        WFUi.bevel(g, x - 4, y - 14, Math.min(list.size(), per) * (cwid + 4) + 4, ch + 18, false);
        g.drawString(font, CommandCard.categoryName(cat), x, y - 10, WFUi.GOLD);
        for (int i = 0; i < list.size() && i < per; i++) {
            Defs.Building d = list.get(i);
            int cx = x + i * (cwid + 4), cy = y + 2;
            String why = Orders.buildBlocker(s, d.id);
            boolean hov = WFUi.over(mx, my, cx, cy, cwid, ch);
            WFUi.bevel(g, cx, cy, cwid, ch, d.id.equals(pending));
            if (hov && why == null) g.fill(cx + 2, cy + 2, cx + cwid - 2, cy + ch - 2, WFUi.HOVER);
            g.drawString(font, font.plainSubstrByWidth(d.name, cwid - 14), cx + 7, cy + 6, why == null ? WFUi.GOLD : WFUi.OFF);
            int px = cx + 7;
            for (var e : d.cost.entrySet()) {
                boolean ok = s.amount(e.getKey()) + 1e-9 >= e.getValue();
                g.fill(px, cy + 20, px + 6, cy + 26, Look.resourceColor(e.getKey()));
                String t = String.valueOf(e.getValue());
                g.drawString(font, t, px + 8, cy + 19, ok ? WFUi.TX : WFUi.BAD);
                px += 8 + font.width(t) + 8;
            }
            StringBuilder sb = new StringBuilder();
            if (d.housing > 0) sb.append("жильё ").append(d.housing).append("  ");
            if (d.slots() > 0) sb.append("мест ").append(d.slots()).append("  ");
            if (d.produces()) for (var e : d.out.entrySet()) sb.append(String.format("+%.1f %s/мин", e.getValue() * 60.0 / d.cycle, s.resourceName(e.getKey()).toLowerCase())).append("  ");
            g.drawString(font, font.plainSubstrByWidth(sb.toString().trim(), cwid - 14), cx + 7, cy + 33, WFUi.MUTED);
            g.drawString(font, "работа " + (int) d.work, cx + 7, cy + 44, WFUi.MUTED);
            if (why != null) g.drawString(font, font.plainSubstrByWidth(why, cwid - 14), cx + 7, cy + 56, WFUi.BAD);
            else g.drawString(font, "ЛКМ - поставить", cx + 7, cy + 56, WFUi.GOOD);
            final String id = d.id;
            List<String> tip = new ArrayList<>(CommandCard.buildingTip(s, d));
            String title = tip.isEmpty() ? d.name : tip.remove(0);
            hit(cx, cy, cwid, ch, () -> { if (why == null) { pending = id; targetMode = false; } else say(why); }, null, title, tip);
        }
    }

    private void card(GuiGraphics g, int x, int y, int mx, int my) {
        CommandCard.Card c = CommandCard.menu(menu, sel, s);
        for (Command cm : c.all()) {
            int cx = x + cm.col() * (CELL + GAP), cy = y + cm.row() * (CELL + GAP);
            boolean hov = WFUi.over(mx, my, cx, cy, CELL, CELL);
            boolean active = cm.on || (cm.kind == Command.Kind.TARGET && targetMode) || (cm.kind == Command.Kind.PLACE && cm.arg.equals(pending));
            WFUi.bevel(g, cx, cy, CELL, CELL, active);
            if (hov && cm.enabled) g.fill(cx + 2, cy + 2, cx + CELL - 2, cy + CELL - 2, WFUi.HOVER);
            int col = !cm.enabled ? 0xFF55575F : active ? WFUi.GOLD : 0xFFD8D6CC;
            if (cm.glyph.startsWith("@")) { if (!RtsIcons.draw(g, cm.glyph.substring(1), cx, cy, CELL, col)) g.drawString(font, "?", cx + 12, cy + 11, col); }
            else g.drawString(font, cm.glyph, cx + (CELL - font.width(cm.glyph)) / 2, cy + 11, col);
            g.drawString(font, cm.hotkey, cx + 3, cy + 2, cm.enabled ? WFUi.MUTED : 0xFF55575F);
            String lab = font.plainSubstrByWidth(cm.label, CELL + 2);
            if (hov) { /* подпись в подсказке */ }
            final Command cc = cm;
            List<String> tip = cm.tooltip.size() > 1 ? new ArrayList<>(cm.tooltip.subList(1, cm.tooltip.size())) : new ArrayList<>();
            tip.replaceAll(l -> l.startsWith("!") ? "-" + l.substring(1) : l);
            hit(cx, cy, CELL, CELL, () -> run(cc), null, cm.tooltip.get(0), tip);
        }
        String tt = c.title == null ? "" : c.title;
        if (!tt.isEmpty()) g.drawString(font, tt, x, y - 8, WFUi.GOLD_DIM);
    }

    private void run(Command cm) {
        if (!cm.enabled) { say(cm.tooltip.stream().filter(l -> l.startsWith("!")).map(l -> l.substring(1)).findFirst().orElse("Недоступно")); return; }
        switch (cm.kind) {
            case SUBMENU -> menu = cm.arg;
            case BACK -> menu = CommandCard.parent(menu);
            case TARGET -> { targetMode = !targetMode; pending = null; }
            case PLACE -> { pending = cm.arg; targetMode = false; }
            case OPEN -> showPrio = !showPrio;
            case CAMERA -> say("Камера: центр выбора " + describeCenter());
            default -> {
                Orders.Result r = CommandCard.execute(cm, sel, s);
                send(r);
                if (r.ok && menu.equals(CommandCard.PROF)) menu = CommandCard.ROOT;
            }
        }
    }

    private String describeCenter() {
        double[] c = sel.center(s);
        return c == null ? "-" : (int) c[0] + ", " + (int) c[1];
    }

    private void selectionPanel(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        if (sel.isEmpty()) {
            g.drawString(font, "Ничего не выбрано", x, y, WFUi.MUTED);
            String[] help = {"ЛКМ - выбрать, рамка на миникарте - несколько", "ПКМ - приказ (рабочего на здание, строителя на стройку)", "Ctrl+1..9 - сохранить, Shift+Ctrl+1..9 - дополнить группу", "1..9 - вызвать, Shift+1..9 - добавить к выбору", "Q W E R / A S D F / Z X C V - клетки приказов", ". - свободные рабочие, P - приоритеты работ"};
            int yy = y + 16;
            for (String l : help) { g.drawString(font, l, x, yy, WFUi.MUTED); yy += 11; }
            groupsRow(g, x, y + h - 14, mx, my);
            return;
        }
        if (sel.hasBuilding()) {
            Building b = sel.building(s);
            if (b == null) return;
            g.drawString(font, b.def.name, x, y, WFUi.GOLD);
            int bx = x + font.width(b.def.name) + 8;
            WFUi.badge(g, font, bx, y - 1, Look.stateName(b), Look.buildingColor(s, b));
            Settlement.Chain ch = s.chain(b);
            int yy = y + 16;
            if (b.state == Building.State.BUILDING) {
                g.drawString(font, "Стройка", x, yy, WFUi.MUTED);
                WFUi.bar(g, x + 70, yy + 1, w - 76, 7, b.progress / b.def.work, WFUi.GOLD);
                yy += 13;
            }
            int slots = b.state == Building.State.DONE ? b.def.slots() : b.def.builders;
            String staff = s.assigned(b.uid).size() + "/" + slots;
            WFUi.kv(g, font, x, yy, w, 70, "Работники", staff, s.assigned(b.uid).size() < slots ? WFUi.GOLD : WFUi.GOOD); yy += 11;
            WFUi.kv(g, font, x, yy, w, 70, "Приоритет", String.valueOf(b.priority) + " из 9", WFUi.TX); yy += 11;
            if (b.def.produces()) {
                StringBuilder sb = new StringBuilder();
                for (var e : ch.in.entrySet()) sb.append(String.format("%.1f %s ", e.getValue(), s.resourceName(e.getKey()).toLowerCase()));
                if (!ch.in.isEmpty()) sb.append("-> ");
                for (var e : ch.out.entrySet()) sb.append(String.format("%.1f %s ", e.getValue(), s.resourceName(e.getKey()).toLowerCase()));
                WFUi.kv(g, font, x, yy, w, 70, "В минуту", sb.toString().trim(), WFUi.TX); yy += 11;
                WFUi.kv(g, font, x, yy, w, 70, "КПД", (int) Math.round(ch.efficiency * 100) + "%", ch.efficiency < 0.5 ? WFUi.BAD : WFUi.GOOD); yy += 11;
            }
            if (!ch.bottleneck.isEmpty()) g.drawString(font, font.plainSubstrByWidth(ch.bottleneck, w), x, yy, WFUi.BAD);
            // работники здания плитками справа
            int tx = x + w - 6 * 24;
            int k = 0;
            for (Worker wk : s.assigned(b.uid)) { workerSlot(g, wk, tx + (k % 6) * 24, y + (k / 6) * 24, mx, my); if (++k >= 12) break; }
            return;
        }
        List<Worker> ws = sel.workers(s);
        int cols = Math.max(1, Math.min(12, (w - 150) / 24)), k = 0;
        for (Worker wk : ws) {
            if (k >= cols * 4) break;
            workerSlot(g, wk, x + (k % cols) * 24, y + (k / cols) * 24, mx, my);
            k++;
        }
        if (ws.size() > cols * 4) g.drawString(font, "+" + (ws.size() - cols * 4), x + cols * 24 - 10, y + 4 * 24, WFUi.MUTED);
        int sx = x + cols * 24 + 8, yy = y;
        g.drawString(font, "Выбрано " + ws.size(), sx, yy, WFUi.GOLD); yy += 13;
        for (var e : sel.byProfession(s).entrySet()) {
            int col = Look.professionColor(e.getKey());
            g.fill(sx, yy + 1, sx + 7, yy + 8, WFUi.EDGE);
            g.fill(sx + 1, yy + 2, sx + 6, yy + 7, col);
            String pn = s.defs.professionName(e.getKey());
            String t = pn + " x" + e.getValue();
            final String prof = e.getKey();
            g.drawString(font, t, sx + 11, yy, WFUi.TX);
            hit(sx, yy, font.width(t) + 11, 10, () -> say("Выбрано: " + sel.selectProfession(s, prof, false)), null, "Только " + pn.toLowerCase(), List.of("Оставить в выборе одну профессию"));
            yy += 11;
            if (yy > y + h - 14) break;
        }
        groupsRow(g, x, y + h - 14, mx, my);
    }

    private void groupsRow(GuiGraphics g, int x, int y, int mx, int my) {
        int xx = x;
        boolean any = false;
        for (int n = 1; n <= ControlGroups.COUNT; n++) {
            if (!groups.has(n)) continue;
            any = true;
            final int gn = n;
            WFUi.key(g, font, xx, y, String.valueOf(n));
            String t = "x" + groups.size(n);
            g.drawString(font, t, xx + 14, y + 2, WFUi.MUTED);
            hit(xx, y, 14 + font.width(t) + 4, 11, () -> groups.recall(gn, sel, s, System.currentTimeMillis(), false), null, "Группа " + n, List.of("Вызвать группу (Ctrl+" + n + " - запомнить)"));
            xx += 14 + font.width(t) + 10;
        }
        if (!any) g.drawString(font, "Группы: Ctrl+1..9", x, y + 2, 0xFF55575F);
    }

    private void workerSlot(GuiGraphics g, Worker w, int x, int y, int mx, int my) {
        Look.WorkerState st = Look.state(s, w);
        boolean hov = WFUi.over(mx, my, x, y, 22, 22);
        WFUi.bevel(g, x, y, 22, 22, false);
        if (hov) g.fill(x + 2, y + 2, x + 20, y + 20, WFUi.HOVER);
        String gl = Look.glyph(s.defs, w.profession);
        g.drawString(font, gl, x + (22 - font.width(gl)) / 2, y + 7, Look.professionColor(w.profession));
        g.fill(x + 2, y + 17, x + 20, y + 19, Look.stateColor(st));
        final int id = w.id;
        List<String> tip = new ArrayList<>();
        tip.add(s.defs.professionName(w.profession) + " - " + Look.stateName(st));
        Building b = w.building == 0 ? null : s.building(w.building);
        tip.add(b == null ? "Нет места работы" : "Место: " + b.def.name);
        tip.add("ЛКМ - оставить только его, Shift - убрать");
        hit(x, y, 22, 22, () -> { if (isShift()) sel.remove(id); else { sel.clear(); sel.add(id); } }, null, w.name, tip);
    }

    private static boolean isShift() { return Screen.hasShiftDown(); }

    // ------------------------------------------------------------------ окно приоритетов работ

    private void priorityWindow(GuiGraphics g, int mx, int my) {
        List<String> profs = new ArrayList<>(WorkPriorities.professions(s.defs));
        int cw = 66, rowH = 15, nameW = 120;
        int w = nameW + profs.size() * cw + 24, rows = Math.min(s.workers.size(), 14);
        int h = 62 + rows * rowH, x = (width - w) / 2, y = Math.max(TOP + 50, (height - BH - h) / 2);
        WFUi.panel(g, font, x, y, w, h, "Приоритеты работ");
        g.drawString(font, "1 - сначала, 4 - в последнюю очередь, 0 - не делать. ЛКМ +1, ПКМ -1, заголовок столбца - всем", x + 8, y + 24, WFUi.MUTED);
        int hy = y + 38;
        for (int i = 0; i < profs.size(); i++) {
            final String p = profs.get(i);
            int cx = x + 12 + nameW + i * cw;
            String t = font.plainSubstrByWidth(s.defs.professionName(p), cw - 3);
            boolean hov = WFUi.over(mx, my, cx, hy, cw - 2, 13);
            WFUi.button(g, font, cx, hy, cw - 2, 13, t, true, false, hov);
            hit(cx, hy, cw - 2, 13, () -> { for (String c : prio.cycleColumn(s, p)) cmd.accept(c); }, null, s.defs.professionName(p), List.of("Следующее значение всем рабочим"));
        }
        int ry = hy + 17;
        for (int r = prioScroll; r < Math.min(s.workers.size(), prioScroll + rows); r++) {
            Worker wk = s.workers.get(r);
            WFUi.rowBg(g, x + 8, ry - 1, w - 16, rowH - 1, r, false, WFUi.over(mx, my, x + 8, ry - 1, w - 16, rowH - 1));
            g.drawString(font, font.plainSubstrByWidth(wk.name, nameW - 4), x + 12, ry + 2, WFUi.TX);
            for (int i = 0; i < profs.size(); i++) {
                final String p = profs.get(i);
                int v = prio.get(wk, p), cx = x + 12 + nameW + i * cw;
                boolean hov = WFUi.over(mx, my, cx, ry - 1, cw - 2, rowH - 1);
                WFUi.bevel(g, cx + 6, ry, cw - 14, rowH - 3, v > 0 && p.equals(wk.profession));
                if (hov) g.fill(cx + 8, ry + 2, cx + cw - 10, ry + rowH - 5, WFUi.HOVER);
                String t = v == 0 ? "-" : String.valueOf(v);
                g.drawString(font, t, cx + (cw - 2 - font.width(t)) / 2, ry + 2, v == 0 ? 0xFF55575F : v == 1 ? WFUi.GOLD : v == 2 ? WFUi.TX : v == 3 ? WFUi.INFO : WFUi.MUTED);
                final Worker ww = wk;
                hit(cx, ry - 1, cw - 2, rowH - 1, () -> cmd.accept(prio.cycle(ww, p, false)), () -> cmd.accept(prio.cycle(ww, p, true)), null, null);
            }
            ry += rowH;
        }
        hits.add(new Hit(x, y, w, h, () -> { }, () -> { }, null, null));          // окно перехватывает клики
    }

    // ------------------------------------------------------------------ мышь и клавиши

    /** Щелчок по цели (миникарта или мир): button 0 - выбор/размещение, 1 - приказ. */
    public void click(Target t, int button, boolean shift, boolean ctrl, boolean dbl) {
        if (button == 1) {
            Orders.Result r = Orders.rightClick(sel, s, t, pending);
            if (r.isEmpty() && r.message.isEmpty() && pending == null) r.message = sel.isEmpty() ? "" : "Для этого цели приказа нет";
            send(r);
            return;
        }
        if (pending != null) {
            if (t.kind == Target.Kind.GROUND) { Orders.Result r = Orders.place(s, pending, t.x, t.z); send(r); if (r.ok && !shift) pending = null; }
            else say("Здесь нельзя строить: место занято");
            return;
        }
        if (targetMode && t.kind == Target.Kind.BUILDING) {
            send(Orders.assign(s, sel.workers(), t.id));
            targetMode = false;
            return;
        }
        switch (t.kind) {
            case WORKER -> sel.clickWorker(s, t.id, shift, ctrl, dbl);
            case BUILDING -> { sel.selectBuilding(t.id); menu = CommandCard.ROOT; }
            default -> { if (!shift) sel.clear(); }
        }
    }

    @Override
    public boolean mouseClicked(double mxd, double myd, int button) {
        int mx = (int) mxd, my = (int) myd;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.at(mx, my)) continue;
            Runnable r = button == 1 ? h.right : h.left;
            if (r != null) { r.run(); return true; }
            if (h.left != null || h.right != null) return true;
        }
        if (mv != null && mv.contains(mx, my)) {
            if (button == 0) { dragX = mx; dragY = my; }
            Target t = mv.pick(s, mx, my);
            boolean dbl = t.kind == Target.Kind.WORKER && lastClickId == t.id && System.currentTimeMillis() - lastClickAt < 400;
            if (button == 0 && t.kind != Target.Kind.GROUND) { click(t, 0, hasShiftDown(), false, dbl); dragX = -1; lastClickId = t.id; lastClickAt = System.currentTimeMillis(); }
            else if (button == 1) click(t, 1, false, false, false);
            return true;
        }
        if (button == 1) { pending = null; targetMode = false; menu = CommandCard.ROOT; }
        return super.mouseClicked(mxd, myd, button);
    }

    @Override
    public boolean mouseReleased(double mxd, double myd, int button) {
        if (button == 0 && dragX >= 0 && mv != null) {
            int mx = (int) mxd, my = (int) myd;
            if (Math.abs(mx - dragX) < 4 && Math.abs(my - dragY) < 4) click(mv.pick(s, mx, my), 0, hasShiftDown(), false, false);
            else say("Выбрано рамкой: " + sel.boxSelect(mv.rects(s), dragX, dragY, mx, my, hasShiftDown()));
        }
        dragX = dragY = -1;
        return super.mouseReleased(mxd, myd, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        curX = (int) mx; curY = (int) my;
        return dragX >= 0 || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (showPrio) { prioScroll = Math.max(0, Math.min(Math.max(0, s.workers.size() - 14), prioScroll - (int) Math.signum(d))); return true; }
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = (mods & 2) != 0, shift = (mods & 1) != 0;
        if (key == 256) {                                                  // Esc: отмена по уровням
            if (pending != null) pending = null;
            else if (targetMode) targetMode = false;
            else if (showPrio) showPrio = false;
            else if (!menu.equals(CommandCard.ROOT)) menu = CommandCard.parent(menu);
            else if (!sel.isEmpty()) sel.clear();
            else onClose();
            return true;
        }
        if (key >= 49 && key <= 57) {                                      // 1..9
            int n = key - 48;
            if (ctrl) {
                groups.capture(n, sel, shift);
                say((shift ? "Группа " + n + " дополнена: " : "Группа " + n + ": ") + groups.size(n));
            } else {
                ControlGroups.Result r = groups.recall(n, sel, s, System.currentTimeMillis(), shift);
                if (r == ControlGroups.Result.EMPTY) say("Группа " + n + " пуста");
                else if (r == ControlGroups.Result.CENTER) say("Камера: центр группы " + describeCenter());
            }
            return true;
        }
        if (key == 46) { say("Свободных выбрано: " + sel.selectIdle(s, shift)); return true; }
        if (key == 80) { showPrio = !showPrio; return true; }              // P
        if (key >= 65 && key <= 90 && !ctrl) {
            Command c = CommandCard.menu(menu, sel, s).byHotkey((char) key);
            if (c != null) { run(c); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }
}
