package dev.worldforge.rts;

import java.util.List;

/**
 * Одна клетка карточки приказов 4x3. Чистые данные: экран рисует glyph/hotkey/label, подсказку берёт из tooltip
 * (первая строка - заголовок, строки с "!" - красные причины отказа), а нажатие выполняет через {@link CommandCard#execute}
 * или по {@link Kind}.
 */
public final class Command {
    /** RUN - выполнить сразу; SUBMENU - открыть подменю arg; BACK - выйти из подменю; TARGET - режим «укажи цель» (arg = assign);
     *  PLACE - режим размещения здания arg; OPEN - открыть окно arg (priorities, build); CAMERA - запрос камере. */
    public enum Kind { RUN, SUBMENU, BACK, TARGET, PLACE, OPEN, CAMERA }

    public final int slot;
    public final String id, glyph, hotkey, label, arg;
    public final Kind kind;
    public final boolean enabled, on;
    public final List<String> tooltip;

    Command(int slot, String id, Kind kind, String arg, String glyph, String label, List<String> tooltip, boolean enabled, boolean on) {
        this.slot = slot; this.id = id; this.kind = kind; this.arg = arg; this.glyph = glyph; this.label = label;
        this.tooltip = tooltip; this.enabled = enabled; this.on = on;
        this.hotkey = String.valueOf(CommandCard.HOTKEYS.charAt(slot));
    }

    public int row() { return slot / CommandCard.COLS; }

    public int col() { return slot % CommandCard.COLS; }

    @Override public String toString() { return hotkey + ":" + id + (enabled ? "" : "(off)"); }
}
