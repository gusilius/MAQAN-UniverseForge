package dev.worldforge.rts;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.*;

/**
 * Карточка приказов 4x3 (как в Warcraft III): по выбору, поселению и открытому подменю строит клетки с горячими клавишами
 * Q W E R / A S D F / Z X C V, подсказками (стоимость, причина отказа) и признаком доступности. Ничего не меняет в Settlement.
 * <p>Меню: {@link #ROOT}, {@link #PROF} (профессии), {@link #BUILD} (разделы стройки) и {@code build:<раздел>} (здания раздела).
 * Выполняется клетка через {@link #execute}: приказы превращаются в строки {@code wfsettle ...} ({@link Orders}), выбор меняется на месте.
 */
public final class CommandCard {
    private CommandCard() {}

    public static final int COLS = 4, ROWS = 3, CELLS = 12, BACK = 11;
    public static final String HOTKEYS = "QWERASDFZXCV";
    public static final String ROOT = "root", PROF = "prof", BUILD = "build";

    /** Разделы меню стройки, выводятся из содержимого описания здания (а не из поля category). */
    public static final List<String> CATS = List.of("housing", "extract", "process", "storage", "defense", "other");
    private static final String[] CAT_NAMES = {"Жильё", "Добыча", "Переработка", "Склад", "Оборона", "Прочее"};

    public static String categoryName(String cat) { int i = CATS.indexOf(cat); return CAT_NAMES[i < 0 ? 5 : i]; }

    /** Раздел здания: оборона (стражники или category=military), склад (даёт место и не производит), жильё, добыча (производит без сырья), переработка (с сырьём). */
    public static String category(Defs.Building d) {
        if (d.category.equals("military") || d.jobs.containsKey("guard")) return "defense";
        if (d.produces()) return d.in.isEmpty() ? "extract" : "process";
        if (!d.storage.isEmpty()) return "storage";
        if (d.housing > 0) return "housing";
        return "other";
    }

    /** Разделы, в которых есть здания (в порядке {@link #CATS}). */
    public static List<String> activeCategories(Defs defs) {
        Set<String> have = new HashSet<>();
        for (Defs.Building d : defs.buildings.values()) have.add(category(d));
        List<String> l = new ArrayList<>();
        for (String c : CATS) if (have.contains(c)) l.add(c);
        return l;
    }

    public static List<Defs.Building> buildingsIn(Defs defs, String cat) {
        List<Defs.Building> l = new ArrayList<>();
        for (Defs.Building d : defs.buildings.values()) if (cat == null || category(d).equals(cat)) l.add(d);
        return l;
    }

    /** Готовая карточка: клетки по номерам 0..11 (null - пустая). */
    public static final class Card {
        public final String menu, title;
        public final Command[] cells = new Command[CELLS];

        Card(String menu, String title) { this.menu = menu; this.title = title; }

        public Command at(int row, int col) { return cells[row * COLS + col]; }

        public Command byHotkey(char c) {
            int i = HOTKEYS.indexOf(Character.toUpperCase(c));
            return i < 0 ? null : cells[i];
        }

        public Command byId(String id) { for (Command c : cells) if (c != null && c.id.equals(id)) return c; return null; }

        public List<Command> all() { List<Command> l = new ArrayList<>(); for (Command c : cells) if (c != null) l.add(c); return l; }
    }

    public static String parent(String menu) { return menu.startsWith("build:") ? BUILD : ROOT; }

    /** Контекст: "workers", "building" или "none". */
    public static String context(Selection sel) { return sel.hasWorkers() ? "workers" : sel.hasBuilding() ? "building" : "none"; }

    // ------------------------------------------------------------------ сборка

    public static Card menu(String menu, Selection sel, Settlement s) {
        if (menu == null || menu.equals(ROOT)) return root(sel, s);
        if (menu.equals(PROF)) return professions(sel, s, 0);
        if (menu.startsWith(PROF + ":")) return professions(sel, s, pageOf(menu));
        if (menu.equals(BUILD)) return categories(s);
        if (menu.startsWith("build:")) {
            String[] parts = menu.substring(6).split(":");
            return buildItems(s, parts[0], parts.length > 1 ? safePage(parts[1]) : 0);
        }
        return root(sel, s);
    }

    private static int safePage(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static int pageOf(String menu) {
        int separator = menu.lastIndexOf(':');
        return separator < 0 ? 0 : safePage(menu.substring(separator + 1));
    }

    private static int pageStart(int page) { return page == 0 ? 0 : 10 + (page - 1) * 9; }
    private static int pageSize(int page) { return page == 0 ? 10 : 9; }

    private static List<String> tip(String title, String... more) { List<String> l = new ArrayList<>(); l.add(title); l.addAll(Arrays.asList(more)); return l; }

    private static void put(Card c, int slot, String id, Command.Kind k, String arg, String glyph, String label, List<String> tip, boolean enabled, boolean on) {
        List<String> t = new ArrayList<>(tip);
        t.set(0, t.get(0) + " [" + HOTKEYS.charAt(slot) + "]");
        c.cells[slot] = new Command(slot, id, k, arg, glyph, label, t, enabled, on);
    }

    private static void run(Card c, int slot, String id, String glyph, String label, boolean enabled, String why, String title, String... more) {
        List<String> t = tip(title, more);
        if (!enabled && why != null) t.add("!" + why);
        put(c, slot, id, Command.Kind.RUN, null, glyph, label, t, enabled, false);
    }

    private static void back(Card c) {
        put(c, BACK, "back", Command.Kind.BACK, null, "@move", "Назад", tip("Назад", "Вернуться в прошлое меню (Esc)"), true, false);
    }

    private static int idleCount(Settlement s) { return s.idleWorkers().size(); }

    private static Card root(Selection sel, Settlement s) {
        String ctx = context(sel);
        Card c = new Card(ROOT, ctx.equals("workers") ? "Рабочие" : ctx.equals("building") ? "Здание" : "Поселение");
        int idle = idleCount(s);
        boolean anyBuilding = !s.buildings.isEmpty();
        if (ctx.equals("workers")) {
            List<Worker> ws = sel.workers(s);
            int busy = 0, builders = 0;
            for (Worker w : ws) { if (w.building != 0) busy++; if (w.workRole().equals("builder")) builders++; }
            put(c, 0, "assign", Command.Kind.TARGET, "assign", "@flag", "Назначить",
                    anyBuilding ? tip("Назначить", "Затем щёлкните здание или стройку", "ПКМ по зданию делает то же") : tip("Назначить", "!Зданий пока нет"), anyBuilding, false);
            run(c, 1, "unassign", "@stop", "Снять", busy > 0, "Все выбранные уже свободны", "Снять с работы", "Выбранные станут свободными", "Сейчас заняты: " + busy);
            put(c, 2, "prof", Command.Kind.SUBMENU, PROF, "@people", "Профессия", tip("Профессия", "Сменить профессию выбранным", "Они будут сняты с работы"), true, false);
            put(c, 3, "build", Command.Kind.SUBMENU, BUILD, "@build", "Построить", tip("Построить", "Меню стройки: жильё, добыча, склад...", builders > 0 ? "Строителей среди выбранных: " + builders : "Строить будут строители"), true, false);
            put(c, 4, "priorities", Command.Kind.OPEN, "priorities", "@scroll", "Работы", tip("Приоритеты работ", "Матрица: рабочий x профессия, 0-4", "Горячая клавиша окна: P"), true, false);
            run(c, 5, "selectSame", "@people", "Такие же", !ws.isEmpty(), null, "Все такой же профессии", "Выбрать всех с профессией первого выбранного", "Двойной щелчок по рабочему делает то же");
            run(c, 6, "selectIdle", "@alert", "Свободные", idle > 0, "Свободных рабочих нет", "Выбрать свободных", "Рабочие без работы: " + idle, "Клавиша [.]");
            run(c, 7, "selectBuilders", "@build", "Строители", s.workers.stream().anyMatch(w -> w.workRole().equals("builder")), "В поселении нет строителей", "Выбрать строителей", "Все рабочие-строители");
            put(c, 8, "camera", Command.Kind.CAMERA, null, "@move", "Камера", tip("Камера на выбор", "Двойное нажатие цифры группы делает то же"), true, false);
            run(c, 9, "selectAll", "@people", "Все", !s.workers.isEmpty(), null, "Выбрать всех рабочих", "Ctrl+A");
            put(c, 10, "auto", Command.Kind.RUN, null, "@heart", s.auto ? "Авто: да" : "Авто: нет", tip("Автоназначение", "Сейчас: " + (s.auto ? "включено" : "выключено"), "Свободные рабочие сами идут на работу"), true, s.auto);
        } else if (ctx.equals("building")) {
            Building b = sel.building(s);
            boolean done = b.state == Building.State.DONE;
            run(c, 0, "prio+", "+", "Приоритет +", b.priority < 9, "Приоритет уже наивысший (9)", "Приоритет выше", "Сейчас: " + b.priority + " из 9", "Выше приоритет - раньше получает рабочих");
            run(c, 1, "prio-", "-", "Приоритет -", b.priority > 1, "Приоритет уже наименьший (1)", "Приоритет ниже", "Сейчас: " + b.priority + " из 9");
            Map<String, Integer> free = Orders.freeSlots(s, b);
            int fs = 0;
            for (int v : free.values()) fs += v;
            String why = fs == 0 ? "В здании нет свободных мест" : Orders.fillFromIdle(s, b.uid).isEmpty() ? "Нет свободных рабочих подходящей профессии" : null;
            run(c, 2, "fill", "@people", "Рабочих", why == null, why, "Назначить рабочих", "Свободные места: " + fs, "Берёт свободных; работники переучатся при необходимости");
            run(c, 3, "unassignAll", "@stop", "Снять всех", !s.assigned(b.uid).isEmpty(), "В здании никого нет", "Снять всех с работы", "Работает: " + s.assigned(b.uid).size());
            String err = Orders.demolishError(s, b);
            int pc = (int) Math.round(Orders.refundShare(b) * 100);
            run(c, 4, "demolish", "@sword", done ? "Снести" : "Отмена", err == null, err, done ? "Снести здание" : "Отменить стройку", "Вернёт " + pc + "% материалов", "Рабочие станут свободными");
            run(c, 5, "selectStaff", "@people", "Работники", !s.assigned(b.uid).isEmpty(), "В здании никого нет", "Выбрать работников", "Рабочие этого здания");
            put(c, 8, "build", Command.Kind.SUBMENU, BUILD, "@build", "Построить", tip("Построить", "Меню стройки"), true, false);
            put(c, 9, "priorities", Command.Kind.OPEN, "priorities", "@scroll", "Работы", tip("Приоритеты работ", "Матрица рабочих"), true, false);
            put(c, 10, "camera", Command.Kind.CAMERA, null, "@move", "Камера", tip("Камера на здание"), true, false);
        } else {
            put(c, 0, "build", Command.Kind.SUBMENU, BUILD, "@build", "Построить", tip("Построить", "Меню стройки", "Клавиша окна: B"), true, false);
            run(c, 1, "selectIdle", "@alert", "Свободные", idle > 0, "Свободных рабочих нет", "Выбрать свободных", "Рабочие без работы: " + idle, "Клавиша [.]");
            run(c, 2, "selectAll", "@people", "Все", !s.workers.isEmpty(), "В поселении нет рабочих", "Выбрать всех рабочих", "Ctrl+A");
            run(c, 3, "selectBuilders", "@build", "Строители", s.workers.stream().anyMatch(w -> w.profession.equals("builder")), "В поселении нет строителей", "Выбрать строителей", "Все рабочие-строители");
            put(c, 4, "priorities", Command.Kind.OPEN, "priorities", "@scroll", "Работы", tip("Приоритеты работ", "Матрица: рабочий x профессия", "Клавиша окна: P"), true, false);
            put(c, 5, "auto", Command.Kind.RUN, null, "@heart", s.auto ? "Авто: да" : "Авто: нет", tip("Автоназначение", "Сейчас: " + (s.auto ? "включено" : "выключено"), "Свободные рабочие сами идут на работу"), true, s.auto);
        }
        return c;
    }

    private static Card professions(Selection sel, Settlement s, int requestedPage) {
        List<Defs.Profession> all = new ArrayList<>(s.defs.professions.values());
        int lastPage = all.size() <= 10 ? 0 : 1 + (int) Math.ceil((all.size() - 10) / 9.0) - 1;
        int page = Math.min(Math.max(0, requestedPage), lastPage);
        int start = pageStart(page), size = pageSize(page);
        Card c = new Card(page == 0 ? PROF : PROF + ":" + page,
                "Профессии " + (start + 1) + "–" + Math.min(all.size(), start + size) + " / " + all.size());
        List<Worker> ws = sel.workers(s);
        int slot = 0;
        for (int index = start; index < Math.min(all.size(), start + size); index++) {
            Defs.Profession p = all.get(index);
            int change = 0;
            for (Worker w : ws) if (!w.profession.equals(p.id)) change++;
            List<String> jobs = new ArrayList<>();
            for (Defs.Building d : s.defs.buildings.values()) if (d.jobs.containsKey(p.id)) jobs.add(d.name);
            List<String> t = tip(p.name, jobs.isEmpty() ? (p.id.equals("builder") ? "Строит здания" : "Работает без здания") : "Работа: " + String.join(", ", jobs));
            if (change > 0) t.add("Сменят: " + change + " (будут сняты с работы)"); else t.add("!Все выбранные уже " + p.name.toLowerCase());
            put(c, slot++, "prof:" + p.id, Command.Kind.RUN, p.id, Look.glyph(s.defs, p.id), p.name, t,
                    change > 0, ws.stream().allMatch(w -> w.profession.equals(p.id)) && !ws.isEmpty());
        }
        if (page > 0) put(c, 9, "prof:prev", Command.Kind.SUBMENU, page == 1 ? PROF : PROF + ":" + (page - 1),
                "@move", "Предыдущие", tip("Предыдущая страница", "Вернуться к предыдущим профессиям"), true, false);
        if (page < lastPage) put(c, 10, "prof:next", Command.Kind.SUBMENU, PROF + ":" + (page + 1),
                "@move", "Следующие", tip("Следующая страница", "Показать следующие профессии"), true, false);
        back(c);
        return c;
    }

    private static Card categories(Settlement s) {
        Card c = new Card(BUILD, "Построить");
        int i = 0;
        for (String cat : activeCategories(s.defs)) {
            if (i >= BACK) break;
            List<Defs.Building> l = buildingsIn(s.defs, cat);
            List<String> t = tip(categoryName(cat));
            StringBuilder sb = new StringBuilder();
            for (Defs.Building d : l) { if (sb.length() > 0) sb.append(", "); sb.append(d.name); }
            t.add(sb.toString());
            String glyph = switch (cat) { case "housing" -> "@home"; case "extract" -> "@wood"; case "process" -> "@tools"; case "storage" -> "@shield"; case "defense" -> "@sword"; default -> "@build"; };
            put(c, i, "cat:" + cat, Command.Kind.SUBMENU, "build:" + cat, glyph, categoryName(cat), t, true, false);
            i++;
        }
        back(c);
        return c;
    }

    /** Текст стоимости: «Дерево 20/60» не нужен, достаточно сравнить; красные строки начинаются с "!". Общая подсказка здания для меню и окна. */
    public static List<String> buildingTip(Settlement s, Defs.Building d) {
        List<String> t = new ArrayList<>();
        t.add(d.name);
        StringBuilder cost = new StringBuilder("Цена:");
        for (Map.Entry<String, Integer> e : d.cost.entrySet()) cost.append(' ').append(s.resourceName(e.getKey())).append(' ').append(e.getValue());
        t.add(cost.toString());
        for (Map.Entry<String, Integer> e : d.cost.entrySet()) {
            double have = s.amount(e.getKey());
            if (have + 1e-9 < e.getValue()) t.add("!Не хватает: " + s.resourceName(e.getKey()) + " (" + (int) have + " из " + e.getValue() + ")");
        }
        t.add("Работа: " + (int) d.work + " (строителей до " + d.builders + ")");
        if (d.housing > 0) t.add("Жильё: +" + d.housing);
        if (!d.jobs.isEmpty()) {
            StringBuilder sb = new StringBuilder("Места:");
            for (Map.Entry<String, Integer> e : d.jobs.entrySet()) sb.append(' ').append(s.defs.professionName(e.getKey())).append(" x").append(e.getValue());
            t.add(sb.toString());
        }
        if (d.produces()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : d.in.entrySet()) sb.append(sb.length() == 0 ? "Берёт: " : ", ").append(s.resourceName(e.getKey())).append(' ').append(e.getValue());
            if (sb.length() > 0) t.add(sb.toString());
            sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : d.out.entrySet()) sb.append(sb.length() == 0 ? "Даёт: " : ", ").append(s.resourceName(e.getKey())).append(' ').append(e.getValue());
            t.add(sb + " за " + (int) d.cycle + " с");
        }
        if (!d.storage.isEmpty()) {
            StringBuilder sb = new StringBuilder("Склад:");
            for (Map.Entry<String, Integer> e : d.storage.entrySet()) sb.append(' ').append(s.resourceName(e.getKey())).append(" +").append(e.getValue());
            t.add(sb.toString());
        }
        String blocker = Orders.buildBlocker(s, d.id);
        if (blocker != null) t.add("!" + blocker);
        return t;
    }

    private static Card buildItems(Settlement s, String cat, int requestedPage) {
        List<Defs.Building> all = buildingsIn(s.defs, cat);
        int lastPage = all.size() <= 10 ? 0 : 1 + (int) Math.ceil((all.size() - 10) / 9.0) - 1;
        int page = Math.min(Math.max(0, requestedPage), lastPage);
        int start = pageStart(page), size = pageSize(page);
        Card c = new Card(page == 0 ? "build:" + cat : "build:" + cat + ":" + page,
                categoryName(cat) + " " + (start + 1) + "–" + Math.min(all.size(), start + size) + " / " + all.size());
        int slot = 0;
        for (int index = start; index < Math.min(all.size(), start + size); index++) {
            Defs.Building d = all.get(index);
            String blocker = Orders.buildBlocker(s, d.id);
            List<String> t = buildingTip(s, d);
            put(c, slot++, "place:" + d.id, Command.Kind.PLACE, d.id, Look.initial(d.name), d.name, t, blocker == null, false);
        }
        if (page > 0) put(c, 9, "build:prev", Command.Kind.SUBMENU, page == 1 ? "build:" + cat : "build:" + cat + ":" + (page - 1),
                "@move", "Предыдущие", tip("Предыдущая страница", "Вернуться к предыдущим постройкам"), true, false);
        if (page < lastPage) put(c, 10, "build:next", Command.Kind.SUBMENU, "build:" + cat + ":" + (page + 1),
                "@move", "Следующие", tip("Следующая страница", "Показать следующие постройки"), true, false);
        back(c);
        return c;
    }

    // ------------------------------------------------------------------ выполнение

    /**
     * Выполнить RUN-клетку: возвращает приказы (строки команд) и сообщение; выбор может измениться (клетки «Выбрать ...»).
     * Для остальных видов (подменю, режим цели, размещение, окно, камера) возвращает пустой результат: их обрабатывает экран.
     */
    public static Orders.Result execute(Command c, Selection sel, Settlement s) {
        Orders.Result r = new Orders.Result();
        if (c == null || !c.enabled || c.kind != Command.Kind.RUN) return r;
        switch (c.id) {
            case "unassign": return Orders.unassign(s, sel.workers());
            case "prio+": return Orders.setBuildingPriority(s, sel.building(), 1);
            case "prio-": return Orders.setBuildingPriority(s, sel.building(), -1);
            case "fill": return Orders.fillFromIdle(s, sel.building());
            case "unassignAll": return Orders.unassignAll(s, sel.building());
            case "demolish": return Orders.demolish(s, sel.building());
            case "auto": { Orders.Result a = new Orders.Result(); a.commands.add(Orders.autoCmd(!s.auto)); a.ok = true; a.message = "Автоназначение: " + (s.auto ? "выключить" : "включить"); return a; }
            case "selectIdle": r.message = "Свободных выбрано: " + sel.selectIdle(s, false); r.ok = true; return r;
            case "selectAll": r.message = "Выбрано рабочих: " + sel.selectAll(s); r.ok = true; return r;
            case "selectBuilders": r.message = "Строителей выбрано: " + sel.selectBuilders(s, false); r.ok = true; return r;
            case "selectStaff": r.message = "Выбрано работников: " + sel.selectStaff(s, sel.building()); r.ok = true; return r;
            case "selectSame": {
                List<Worker> ws = sel.workers(s);
                if (!ws.isEmpty()) { String p = ws.get(0).workRole(); r.message = s.defs.professionName(p) + ": " + sel.selectProfession(s, p, false); r.ok = true; }
                return r;
            }
            default:
                if (c.id.startsWith("prof:")) return Orders.setProfession(s, sel.workers(), c.arg);
                return r;
        }
    }
}
