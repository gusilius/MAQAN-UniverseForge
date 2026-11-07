package dev.worldforge.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Единый набор оформления WorldForge и всех его аддонов: цвета и простые детали (рамка со скосом как у слотов инвентаря,
 * панель с заголовком, кнопка, полоса, значок, клавиша). Аддоны берут оформление отсюда, а не рисуют своё, поэтому
 * карта, панели, диалоги, поселения и войны выглядят одинаково. Только рисование: расположение и клики остаются за экраном.
 */
public final class WFUi {
    private WFUi() {}

    // ---- цвета
    public static final int BG = 0xFF080D14;          // фон экрана вокруг карты
    public static final int PANEL = 0xFF101923;       // выпуклая поверхность
    public static final int INSET = 0xFF0B121A;       // вдавленная поверхность (поля, нажатые кнопки, полосы)
    public static final int HI = 0xFF34495B;          // светлая грань скоса
    public static final int LO = 0xFF060A10;          // тёмная грань скоса
    public static final int EDGE = 0xFF000000;        // внешний контур
    public static final int TX = 0xFFE8EEF5;          // основной текст
    public static final int MUTED = 0xFF8292A5;       // второстепенный текст
    public static final int OFF = 0xFF556474;         // недоступное
    public static final int GOLD = 0xFF69CDE2;        // акцент: заголовки, выбранное
    public static final int GOLD_DIM = 0xFF477F91;    // подзаголовки разделов
    public static final int GOOD = 0xFF67B85C;        // союз, готово, прибыль
    public static final int BAD = 0xFFD9503A;         // война, нехватка, потеря
    public static final int INFO = 0xFF5AA0D8;        // подчинённые, сведения
    public static final int HOVER = 0x30FFFFFF;       // подсветка под курсором
    public static final int SCRIM = 0xEE100E0B;       // полупрозрачная тёмная панель поверх мира

    private static int mix(int a, int b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        int alpha = (int) Math.round(aa + (ba - aa) * t);
        int red = (int) Math.round(ar + (br - ar) * t);
        int green = (int) Math.round(ag + (bg - ag) * t);
        int blue = (int) Math.round(ab + (bb - ab) * t);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /** Вертикальный градиент построчно с интерполяцией alpha/RGB. */
    public static void gradient(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        if (h == 1) {
            g.fill(x, y, x + w, y + 1, top);
            return;
        }
        for (int row = 0; row < h; row++) {
            int color = mix(top, bottom, row / (double) (h - 1));
            g.fill(x, y + row, x + w, y + row + 1, color);
        }
    }

    /** Угловые украшения: золотые уголки с ромбом. */
    public static void corners(GuiGraphics g, int x, int y, int w, int h, int c) {
        // Intentionally empty: the shared MAQAN visual language uses clean geometry.
    }

    /** Рамка. inset = вдавленная (врезка): тёмная, с тонкой линией. Выпуклая - градиентная панель с тёмно-золотым кантом. */
    public static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean inset) {
        if (w <= 0 || h <= 0) return;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
        g.fill(x, y, x + w, y + h, inset ? INSET : PANEL);
        g.fill(x, y, x + w, y + 1, HI);
        g.fill(x, y + h - 1, x + w, y + h, LO);
    }

    /** Панель окна: тёмный фон, двойной золотой кант, угловые украшения, заголовок с линией. Содержимое начинается с y + 20. */
    public static void panel(GuiGraphics g, Font f, int x, int y, int w, int h, String title) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, PANEL);
        g.fill(x, y, x + w, y + 1, HI);
        g.fill(x, y + h - 1, x + w, y + h, LO);
        if (title == null) return;
        String clock = ClientClock.known() ? ClientClock.date() + "  " + ClientClock.speed() : "";
        int cw = clock.isEmpty() ? 0 : f.width(clock) + 12;
        g.drawString(f, f.plainSubstrByWidth(title.toUpperCase(java.util.Locale.ROOT), Math.max(0, w - 22 - cw)), x + 10, y + 7, GOLD);
        if (!clock.isEmpty()) g.drawString(f, clock, x + w - 10 - f.width(clock), y + 7, ClientClock.paused() ? BAD : MUTED);
        g.fill(x + 8, y + 18, x + w - 8, y + 19, HI);
    }

    /** Полупрозрачная панель поверх мира (диалоги): тёмный фон и золотая планка сверху. */
    public static void overlay(GuiGraphics g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
        g.fill(x, y, x + w, y + h, SCRIM);
        g.fill(x, y, x + w, y + 1, GOLD);
        g.fill(x, y + h - 1, x + w, y + h, LO);
    }

    public static boolean over(int mx, int my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    /** Кнопка. on - включена (золотой кант и текст). */
    public static void button(GuiGraphics g, Font f, int x, int y, int w, int h, String label, boolean enabled, boolean on, boolean hover) {
        if (w <= 0 || h <= 0) return;
        int fill = !enabled ? INSET : on ? 0xFF1B3442 : hover ? 0xFF1A2B38 : PANEL;
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, on || hover ? GOLD : HI);
        if (on || hover) g.fill(x, y + 1, x + 2, y + h - 1, GOLD);
        g.fill(x, y + h - 1, x + w, y + h, LO);
        String t = f.plainSubstrByWidth(label, Math.max(0, w - 6));
        g.drawString(f, t, x + (w - f.width(t)) / 2, y + (h - 8) / 2 + 1, !enabled ? OFF : on ? GOLD : TX);
    }

    /** Заголовок раздела внутри панели (мелкий, золотисто-тёмный, прописными). Возвращает высоту. */
    public static int section(GuiGraphics g, Font f, int x, int y, String s) {
        String label = s.toUpperCase(java.util.Locale.ROOT);
        g.drawString(f, label, x, y, GOLD);
        int line = x + f.width(label) + 8;
        g.fill(line, y + 4, line + 32, y + 5, HI);
        return 13;
    }

    /** Строка «название - значение». */
    public static void kv(GuiGraphics g, Font f, int x, int y, int w, int keyW, String k, String v, int color) {
        g.drawString(f, f.plainSubstrByWidth(k, keyW - 4), x, y, MUTED);
        g.drawString(f, f.plainSubstrByWidth(v, w - keyW), x + keyW, y, color);
    }

    /** Полоса значения (очки войны, прогресс стройки, запас). frac 0..1. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int color) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF4A3C24);
        g.fill(x, y, x + w, y + h, INSET);
        int fw = (int) Math.round((w - 2) * Math.max(0, Math.min(1, frac)));
        if (fw > 0) {
            gradient(g, x + 1, y + 1, fw, h - 2, mix(color, 0xFFFFFFFF, 0.18), mix(color, 0xFF000000, 0.35));
        }
    }

    /** Полоса со средней риской, для счёта войны (-100..100): заполняется от центра. */
    public static void balance(GuiGraphics g, int x, int y, int w, int h, double v, int left, int right) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
        g.fill(x, y, x + w, y + h, INSET);
        int mid = x + w / 2, len = (int) Math.round(w / 2.0 * Math.max(-1, Math.min(1, v)));
        if (len > 0) g.fill(mid, y + 1, mid + len, y + h - 1, right);
        else if (len < 0) g.fill(mid + len, y + 1, mid, y + h - 1, left);
        g.fill(mid, y - 2, mid + 1, y + h + 2, TX);
    }

    /** Цветной значок-метка (должность, статус). Возвращает ширину. */
    public static int badge(GuiGraphics g, Font f, int x, int y, String text, int color) {
        int w = f.width(text) + 8;
        int tint = (color & 0x00FFFFFF) | 0xB0000000;
        g.fill(x, y, x + w, y + 11, tint);
        g.fill(x, y, x + w, y + 1, color);
        g.drawString(f, text, x + 4, y + 2, TX);
        return w;
    }

    /** Клавиша: «1», «Tab». Возвращает ширину. */
    public static int key(GuiGraphics g, Font f, int x, int y, String k) {
        int w = Math.max(11, f.width(k) + 6);
        g.fill(x, y, x + w, y + 11, INSET);
        g.fill(x, y, x + w, y + 1, HI);
        g.drawString(f, k, x + (w - f.width(k)) / 2, y + 2, MUTED);
        return w;
    }

    /** Цветной квадрат образца (цвет государства, титула). */
    public static void swatch(GuiGraphics g, int x, int y, int rgb) {
        g.fill(x, y, x + 9, y + 9, EDGE);
        g.fill(x + 1, y + 1, x + 8, y + 8, 0xFF000000 | rgb);
    }

    public static void scrollbar(GuiGraphics g, int x, int y, int h, int contentH, int offset) {
        if (h <= 0 || contentH <= h) return;
        int max = contentH - h;
        int thumbHeight = Math.max(Math.min(14, h), h * h / contentH);
        int clampedOffset = Math.max(0, Math.min(max, offset));
        int thumbY = y + (int) Math.round((h - thumbHeight) * (clampedOffset / (double) max));
        g.fill(x, y, x + 2, y + h, LO);
        g.fill(x, thumbY, x + 2, thumbY + thumbHeight, HI);
    }

    // ------------------------------------------------------------------ расширения для RTS-панелей и окон

    /** Вкладки в ряд. Возвращает правый край. Клик определяйте через {@link #tabAt}. */
    public static int tabs(GuiGraphics g, Font f, int x, int y, String[] labels, int active, int mx, int my) {
        int cx = x;
        for (int i = 0; i < labels.length; i++) {
            int w = f.width(labels[i]) + 20;
            boolean on = i == active;
            boolean hover = over(mx, my, cx, y, w, 18);
            if (on) g.fill(cx, y, cx + w, y + 18, 0x302A7185);
            else if (hover) g.fill(cx, y, cx + w, y + 18, 0x182A7185);
            g.fill(cx, y, cx + w, y + 1, on ? GOLD : HI);
            if (on) g.fill(cx + 4, y + 16, cx + w - 4, y + 18, GOLD);
            g.drawString(f, labels[i], cx + 10, y + 5, on ? GOLD : MUTED);
            cx += w + 2;
        }
        return cx;
    }

    public static int tabAt(Font f, int x, int y, String[] labels, int mx, int my) {
        int cx = x;
        for (int i = 0; i < labels.length; i++) {
            int w = f.width(labels[i]) + 20;
            if (over(mx, my, cx, y, w, 18)) return i;
            cx += w + 2;
        }
        return -1;
    }

    /** Подсказка у курсора: первая строка золотая, остальные светлые; красные строки начните с «!». Не выходит за экран. */
    public static void tooltip(GuiGraphics g, Font f, int screenW, int screenH, int mx, int my, java.util.List<String> lines) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (String l : lines) w = Math.max(w, f.width(l.startsWith("!") ? l.substring(1) : l));
        w += 12;
        int h = lines.size() * 10 + 8;
        int x = Math.max(2, Math.min(mx + 10, screenW - w - 2));
        int y = Math.max(2, Math.min(my + 10, screenH - h - 2));
        g.fill(x, y, x + w, y + h, EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, GOLD);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean bad = line.startsWith("!");
            g.drawString(f, bad ? line.substring(1) : line, x + 6, y + 5 + i * 10,
                    bad ? BAD : i == 0 ? GOLD : TX);
        }
    }

    /** Клетка карточки команд, как в Warcraft: значок или короткая надпись, горячая клавиша в углу. */
    public static void cell(GuiGraphics g, Font f, int x, int y, int size, String glyphOrLabel, String hotkey, boolean enabled, boolean on, boolean hover) {
        bevel(g, x, y, size, size, on);
        if (hover && enabled) g.fill(x + 2, y + 2, x + size - 2, y + size - 2, HOVER);
        int c = !enabled ? OFF : on ? GOLD : TX;
        if (glyphOrLabel != null && glyphOrLabel.startsWith("@")) icon(g, x + (size - 14) / 2, y + (size - 14) / 2 - 1, glyphOrLabel.substring(1), c, 2);
        else if (glyphOrLabel != null) {
            String t = f.plainSubstrByWidth(glyphOrLabel, size - 4);
            g.drawString(f, t, x + (size - f.width(t)) / 2, y + size / 2 - 3, c);
        }
        if (hotkey != null) g.drawString(f, hotkey, x + 3, y + 3, enabled ? MUTED : OFF);
    }

    /** Портрет-плашка: цвет дома или государства и инициалы. */
    public static void portrait(GuiGraphics g, Font f, int x, int y, int w, int h, int rgb, String initials) {
        bevel(g, x, y, w, h, true);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF000000 | rgb);
        g.fill(x + 3, y + h / 2, x + w - 3, y + h - 3, 0x55000000);
        String t = initials == null ? "" : initials;
        g.drawString(f, t, x + (w - f.width(t)) / 2, y + h / 2 - 4, 0xFFFFFFFF);
    }

    /** Фон строки таблицы: зебра, выбор, наведение. */
    public static void rowBg(GuiGraphics g, int x, int y, int w, int h, int index, boolean selected, boolean hover) {
        if (selected) g.fill(x, y, x + w, y + h, 0x40F0C85A);
        else if (hover) g.fill(x, y, x + w, y + h, HOVER);
        else if ((index & 1) == 1) g.fill(x, y, x + w, y + h, 0x10FFFFFF);
    }

    /** Характеристика с буквой и числом (дипломатия, военное дело...): плашка 0..20. */
    public static void stat(GuiGraphics g, Font f, int x, int y, int w, String name, int value, int color) {
        g.drawString(f, name, x, y, MUTED);
        bar(g, x + 44, y + 1, w - 44 - 20, 6, value / 20.0, color);
        g.drawString(f, String.valueOf(value), x + w - 16, y, TX);
    }

    /** Строка запасов для верхней панели: значок, число, прирост в минуту (зелёный/красный). Возвращает ширину. */
    public static int resource(GuiGraphics g, Font f, int x, int y, String iconId, int iconColor, String amount, double perMin) {
        icon(g, x, y - 1, iconId, iconColor, 1);
        int w = 10;
        g.drawString(f, amount, x + w, y, TX);
        w += f.width(amount) + 3;
        if (Math.abs(perMin) >= 0.05) {
            String d = (perMin > 0 ? "+" : "") + (Math.abs(perMin) >= 10 ? String.valueOf(Math.round(perMin)) : String.format(java.util.Locale.ROOT, "%.1f", perMin));
            g.drawString(f, d, x + w, y, perMin > 0 ? GOOD : BAD);
            w += f.width(d) + 2;
        }
        return w + 8;
    }

    private static final java.util.Map<String, String[]> ICONS = new java.util.HashMap<>();
    static {
        ICONS.put("wood",   new String[]{"..###..", ".#####.", ".#.#.#.", "...#...", "...#...", "..###..", "......."});
        ICONS.put("planks", new String[]{"#######", "#.....#", "#######", "#.....#", "#######", "#.....#", "#######"});
        ICONS.put("stone",  new String[]{"...##..", "..####.", ".######", "#######", "#######", ".#####.", "......."});
        ICONS.put("food",   new String[]{"...#...", "..###..", ".#####.", "###.###", ".#####.", "..###..", "...#..."});
        ICONS.put("coin",   new String[]{"..###..", ".#...#.", "#..#..#", "#..#..#", "#..#..#", ".#...#.", "..###.."});
        ICONS.put("iron",   new String[]{".#####.", "#######", "#.###.#", "#######", ".#####.", "#.....#", "#######"});
        ICONS.put("tools",  new String[]{"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"});
        ICONS.put("people", new String[]{"..###..", "..###..", "...#...", ".#####.", "#.###.#", "..#.#..", ".##.##."});
        ICONS.put("sword",  new String[]{"......#", ".....##", "....##.", "#..##..", ".###...", "..#....", ".#.#..."});
        ICONS.put("shield", new String[]{"#######", "#.....#", "#.###.#", "#.###.#", ".#...#.", "..#.#..", "...#..."});
        ICONS.put("crown",  new String[]{"#..#..#", "##.#.##", "#######", "#######", "#######", ".#####.", "......."});
        ICONS.put("build",  new String[]{"...###.", "..####.", ".#.###.", "#...#..", "#..#...", ".##....", "......."});
        ICONS.put("home",   new String[]{"...#...", "..###..", ".#####.", "#######", ".#.#.#.", ".#.#.#.", ".#####."});
        ICONS.put("stop",   new String[]{".#####.", "#######", "#######", "#######", "#######", "#######", ".#####."});
        ICONS.put("move",   new String[]{"...#...", "..###..", ".#.#.#.", "#######", ".#.#.#.", "..###..", "...#..."});
        ICONS.put("flag",   new String[]{"#######", "#.#####", "#.#####", "#.#####", "#......", "#......", "#......"});
        ICONS.put("alert",  new String[]{"...#...", "..###..", "..#.#..", ".#.#.#.", ".#.#.#.", "#.....#", "#######"});
        ICONS.put("scroll", new String[]{".#####.", "#.....#", ".#####.", "..#.#..", ".#####.", "#.....#", ".#####."});
        ICONS.put("heart",  new String[]{".##.##.", "#######", "#######", ".#####.", "..###..", "...#...", "......."});
        ICONS.put("trade",  new String[]{"..#....", ".####..", "..#.###", "......#", "###.#..", "..####.", "....#.."});
    }

    /** Значок 7x7 в пикселях; scale 1 или 2. Неизвестный id рисуется квадратом. */
    public static void icon(GuiGraphics g, int x, int y, String id, int color, int scale) {
        String[] rows = ICONS.get(id);
        if (rows == null) rows = ICONS.get("stop");
        for (int r = 0; r < 7; r++) for (int c = 0; c < 7; c++) if (rows[r].charAt(c) == '#') g.fill(x + c * scale, y + r * scale, x + (c + 1) * scale, y + (r + 1) * scale, color);
    }

    public static boolean hasIcon(String id) { return ICONS.containsKey(id); }

    // ------------------------------------------------------------------ виджеты для окон управления (RTS, дипломатия, персонаж)

    /** Вкладка. Выбранная вдавлена и золотая. */
    public static void tab(GuiGraphics g, Font f, int x, int y, int w, int h, String label, boolean selected, boolean hover) {
        bevel(g, x, y, w, h, selected);
        if (hover && !selected) g.fill(x + 2, y + 2, x + w - 2, y + h - 2, HOVER);
        String t = f.plainSubstrByWidth(label, w - 6);
        g.drawString(f, t, x + (w - f.width(t)) / 2, y + (h - 8) / 2 + 1, selected ? GOLD : MUTED);
    }

    /** Флажок с подписью. Возвращает ширину (для ряда). */
    public static int checkbox(GuiGraphics g, Font f, int x, int y, String label, boolean on, boolean hover) {
        bevel(g, x, y, 11, 11, true);
        if (on) { g.fill(x + 3, y + 3, x + 8, y + 8, GOLD); }
        if (hover) g.fill(x + 1, y + 1, x + 10, y + 10, HOVER);
        g.drawString(f, label, x + 16, y + 2, on ? TX : MUTED);
        return 16 + f.width(label);
    }

    /**
     * Кнопка карточки приказов как в RTS: квадрат со скосом, короткая подпись по центру, горячая клавиша в углу.
     * on - режим включён (например, «строить» ждёт точку), enabled=false - недоступно (серый текст).
     */
    public static void cmd(GuiGraphics g, Font f, int x, int y, int size, String label, String hotkey, boolean enabled, boolean on, boolean hover) {
        bevel(g, x, y, size, size, on);
        if (hover && enabled) g.fill(x + 2, y + 2, x + size - 2, y + size - 2, HOVER);
        String t = f.plainSubstrByWidth(label, size - 4);
        g.drawString(f, t, x + (size - f.width(t)) / 2, y + size / 2 - 3, !enabled ? OFF : on ? GOLD : TX);
        if (hotkey != null && !hotkey.isEmpty()) g.drawString(f, hotkey, x + 3, y + 3, enabled ? GOLD_DIM : OFF);
    }

    /** Портрет-заглушка: цветная плашка с инициалом и рамкой; hp 0..1 рисует полоску снизу (отрицательное - без полоски). */
    public static void portrait(GuiGraphics g, Font f, int x, int y, int size, int rgb, String initial, double hp, boolean selected) {
        bevel(g, x, y, size, size, true);
        g.fill(x + 2, y + 2, x + size - 2, y + size - 2, 0xFF000000 | rgb);
        g.fill(x + 2, y + size / 2, x + size - 2, y + size - 2, 0x30000000);
        g.drawString(f, initial, x + (size - f.width(initial)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
        if (hp >= 0) bar(g, x + 3, y + size - 6, size - 6, 3, hp, hp < 0.3 ? BAD : GOOD);
        if (selected) { g.fill(x - 2, y - 2, x + size + 2, y - 1, GOLD); g.fill(x - 2, y + size + 1, x + size + 2, y + size + 2, GOLD); g.fill(x - 2, y - 2, x - 1, y + size + 2, GOLD); g.fill(x + size + 1, y - 2, x + size + 2, y + size + 2, GOLD); }
    }

    /** Заголовок столбца таблицы со стрелкой сортировки (dir: 0 нет, 1 вверх, -1 вниз). */
    public static void th(GuiGraphics g, Font f, int x, int y, int w, String label, int dir, boolean hover) {
        g.fill(x, y, x + w, y + 13, hover ? 0xFF34343A : PANEL);
        g.fill(x, y + 12, x + w, y + 13, LO);
        g.drawString(f, f.plainSubstrByWidth(label, w - 12), x + 3, y + 3, dir != 0 ? GOLD : MUTED);
        if (dir != 0) g.drawString(f, dir > 0 ? "\u25b2" : "\u25bc", x + w - 9, y + 3, GOLD);
    }

    /** Ряд таблицы: чередующийся фон и подсветка выбранной/наведённой. */
    public static void row(GuiGraphics g, int x, int y, int w, int h, int index, boolean selected, boolean hover) {
        g.fill(x, y, x + w, y + h, selected ? 0x40F0C85A : hover ? HOVER : (index & 1) == 0 ? 0x10FFFFFF : 0x00000000);
    }

    /**
     * Разбор значения как в «мнении» CK3: заголовок, строки «причина ... +N» (зелёные плюсы, красные минусы), итог.
     * Рисуется в рамке в точке (x, y), ширина w. Возвращает высоту. Можно вызывать как подсказку у курсора.
     */
    public static int breakdown(GuiGraphics g, Font f, int x, int y, int w, String title, String[] labels, int[] values, String totalLabel, int total) {
        int h = 12 + 14 + labels.length * 11 + 16;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, EDGE);
        g.fill(x, y, x + w, y + h, 0xF0101014);
        g.fill(x, y, x + w, y + 1, GOLD_DIM);
        g.drawString(f, f.plainSubstrByWidth(title, w - 12), x + 6, y + 6, GOLD);
        int cy = y + 20;
        for (int i = 0; i < labels.length; i++) {
            String v = (values[i] > 0 ? "+" : "") + values[i];
            g.drawString(f, f.plainSubstrByWidth(labels[i], w - 40), x + 6, cy, MUTED);
            g.drawString(f, v, x + w - 6 - f.width(v), cy, values[i] > 0 ? GOOD : values[i] < 0 ? BAD : MUTED);
            cy += 11;
        }
        g.fill(x + 6, cy + 2, x + w - 6, cy + 3, LO);
        String tv = (total > 0 ? "+" : "") + total;
        g.drawString(f, totalLabel, x + 6, cy + 6, TX);
        g.drawString(f, tv, x + w - 6 - f.width(tv), cy + 6, total > 0 ? GOOD : total < 0 ? BAD : TX);
        return h;
    }

    /** Строка статистики с цветной дельтой: «Дерево  15  +3/мин». */
    public static void stat(GuiGraphics g, Font f, int x, int y, int w, String label, String value, String delta, int deltaColor) {
        g.drawString(f, label, x, y, MUTED);
        int vx = x + w - f.width(value) - (delta == null ? 0 : f.width(delta) + 6);
        g.drawString(f, value, vx, y, TX);
        if (delta != null) g.drawString(f, delta, x + w - f.width(delta), y, deltaColor);
    }

    /** Рамка выделения (резинка) в RTS. */
    public static void marquee(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int l = Math.min(x1, x2), r = Math.max(x1, x2), t = Math.min(y1, y2), b = Math.max(y1, y2);
        g.fill(l, t, r, b, 0x2056F070);
        g.fill(l, t, r, t + 1, 0xFF56F070);
        g.fill(l, b - 1, r, b, 0xFF56F070);
        g.fill(l, t, l + 1, b, 0xFF56F070);
        g.fill(r - 1, t, r, b, 0xFF56F070);
    }

    // ------------------------------------------------------------------ расширенные детали (командный центр, таблицы, вкладки)

    public static final int[] SEVERITY = {0xFF4AA8FF, 0xFFF0C85A, 0xFFE04A4A};   // сведение, внимание, тревога

    /** Полоса вкладок равной ширины. Возвращает ничего: клики считайте через {@link #tabAt}. */
    public static void tabBar(GuiGraphics g, Font f, int x, int y, int w, String[] labels, int active, int mx, int my) {
        int n = labels.length, tw = w / n;
        for (int i = 0; i < n; i++) {
            int tx = x + i * tw, ww = i == n - 1 ? w - tw * (n - 1) : tw;
            button(g, f, tx, y, ww - 2, 16, labels[i], true, i == active, over(mx, my, tx, y, ww - 2, 16));
        }
    }

    public static int tabAt(int x, int w, int n, int y, int mx, int my) {
        if (my < y || my >= y + 16 || mx < x || mx >= x + w) return -1;
        return Math.min(n - 1, (mx - x) / (w / n));
    }

    /** Фильтр-чип (профессия, состояние). Возвращает ширину. */
    public static int chip(GuiGraphics g, Font f, int x, int y, String label, boolean on, boolean hover) {
        int w = f.width(label) + 12;
        bevel(g, x, y, w, 13, on);
        if (hover && !on) g.fill(x + 2, y + 2, x + w - 2, y + 11, HOVER);
        g.drawString(f, label, x + 6, y + 3, on ? GOLD : MUTED);
        return w;
    }

    /** Заголовок таблицы: widths - ширины колонок, sortCol - сортируемая (-1 нет), стрелка показывает порядок. */
    public static void tableHeader(GuiGraphics g, Font f, int x, int y, int[] widths, String[] labels, int sortCol, boolean asc, int mx, int my) {
        int tw = 0;
        for (int w : widths) tw += w;
        g.fill(x, y, x + tw, y + 13, LO);
        g.fill(x, y + 13, x + tw, y + 14, HI);
        int cx = x;
        for (int i = 0; i < widths.length; i++) {
            boolean hov = over(mx, my, cx, y, widths[i], 13);
            String t = labels[i] + (i == sortCol ? (asc ? " \u25b2" : " \u25bc") : "");
            g.drawString(f, f.plainSubstrByWidth(t, widths[i] - 4), cx + 3, y + 3, i == sortCol ? GOLD : hov ? TX : GOLD_DIM);
            cx += widths[i];
        }
    }

    /** Плашка уведомления: цвет по важности 0..2 (сведение, внимание, тревога). */
    public static void banner(GuiGraphics g, Font f, int x, int y, int w, String text, int severity, boolean hover) {
        int c = SEVERITY[Math.max(0, Math.min(2, severity))];
        g.fill(x - 1, y - 1, x + w + 1, y + 15, EDGE);
        g.fill(x, y, x + w, y + 14, 0xFF1D1D21);
        g.fill(x, y, x + 3, y + 14, c);
        if (hover) g.fill(x + 3, y, x + w, y + 14, HOVER);
        g.drawString(f, f.plainSubstrByWidth(text, w - 10), x + 8, y + 3, TX);
    }

    public static void dot(GuiGraphics g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + size, EDGE);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
    }

    /** Рамка выбора: четыре золотых угла вокруг прямоугольника (как кольцо выбора в RTS). */
    public static void selection(GuiGraphics g, int x, int y, int w, int h) {
        int c = GOLD, l = Math.max(3, Math.min(w, h) / 3);
        g.fill(x - 1, y - 1, x - 1 + l, y, c); g.fill(x - 1, y - 1, x, y - 1 + l, c);
        g.fill(x + w + 1 - l, y - 1, x + w + 1, y, c); g.fill(x + w, y - 1, x + w + 1, y - 1 + l, c);
        g.fill(x - 1, y + h, x - 1 + l, y + h + 1, c); g.fill(x - 1, y + h + 1 - l, x, y + h + 1, c);
        g.fill(x + w + 1 - l, y + h, x + w + 1, y + h + 1, c); g.fill(x + w, y + h + 1 - l, x + w + 1, y + h + 1, c);
    }

    // ---- вкладки, ячейки, подсказки (для окон в стиле Paradox / RTS)

    /** Ряд вкладок. Возвращает ширину каждой; активная вдавлена и золотая. Расположение кликов считает экран: x + i * (ширина + 2). */
    public static int tabs(GuiGraphics g, Font f, int x, int y, int totalW, String[] labels, int active, int mx, int my) {
        int n = labels.length, gap = 2, w = (totalW - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) button(g, f, x + i * (w + gap), y, w, 15, labels[i], true, i == active, over(mx, my, x + i * (w + gap), y, w, 15));
        return w;
    }

    /** Ячейка как слот инвентаря: короткий знак по центру (1-2 символа), горячая клавиша в углу, число справа снизу. */
    public static void slot(GuiGraphics g, Font f, int x, int y, int size, String glyph, String hotkey, String count, boolean enabled, boolean on, boolean hover) {
        bevel(g, x, y, size, size, on);
        if (hover && enabled) g.fill(x + 2, y + 2, x + size - 2, y + size - 2, HOVER);
        g.drawString(f, glyph, x + (size - f.width(glyph)) / 2, y + (size - 8) / 2, !enabled ? OFF : on ? GOLD : TX);
        if (hotkey != null && !hotkey.isEmpty()) g.drawString(f, hotkey, x + 3, y + 2, MUTED);
        if (count != null && !count.isEmpty()) g.drawString(f, count, x + size - 3 - f.width(count), y + size - 10, enabled ? TX : OFF);
    }

    /** Всплывающая подсказка с заголовком и строками (цвет строки задаётся префиксом: "+" зелёный, "-" красный, иначе мутный). */
    public static void tooltip(GuiGraphics g, Font f, int mx, int my, int screenW, int screenH, String title, java.util.List<String> lines) {
        int w = f.width(title), h = 14 + lines.size() * 10 + 4;
        for (String l : lines) w = Math.max(w, f.width(l.length() > 0 && "+-".indexOf(l.charAt(0)) >= 0 ? l.substring(1) : l) + 4);
        w += 16;
        int x = Math.max(2, Math.min(mx + 12, screenW - w - 4));
        int y = Math.max(2, Math.min(my + 12, screenH - h - 4));
        bevel(g, x, y, w, h, false);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xF0181A20);
        g.drawString(f, title, x + 8, y + 5, GOLD);
        int yy = y + 17;
        for (String l : lines) {
            int c = MUTED;
            String t = l;
            if (l.startsWith("+")) { c = GOOD; t = l.substring(1); } else if (l.startsWith("-")) { c = BAD; t = l.substring(1); }
            g.drawString(f, t, x + 8, yy, c);
            yy += 10;
        }
    }

    /** Малый индикатор «точка + число» для верхней панели ресурсов (как в Paradox/RTS). Возвращает ширину. */
    public static int stat(GuiGraphics g, Font f, int x, int y, String label, String value, int color) {
        g.drawString(f, label, x, y, MUTED);
        int lw = f.width(label) + 4;
        g.drawString(f, value, x + lw, y, color);
        return lw + f.width(value) + 12;
    }
}
