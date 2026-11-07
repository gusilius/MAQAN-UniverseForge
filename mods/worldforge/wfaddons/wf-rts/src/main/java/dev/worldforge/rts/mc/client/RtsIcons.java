package dev.worldforge.rts.mc.client;

import net.minecraft.client.gui.GuiGraphics;

import java.util.Map;

/** Пиксельные значки приказов 11x11 (имена как у глифов "@..." в CommandCard). '#' основной цвет, 'o' контур, 'h' блик, '.' пусто. */
public final class RtsIcons {
    private RtsIcons() {}

    private static final Map<String, String[]> ICONS = Map.ofEntries(
            Map.entry("flag", new String[]{"..o........", "..o###ooo..", "..o#hhh##o.", "..o#####oo.", "..o###oo...", "..oooo.....", "..o........", "..o........", "..o........", ".ooo.......", "..........."}),
            Map.entry("people", new String[]{"...ooo.ooo.", "..o###ooo#o", "..o###o.o#o", "...ooo..ooo", ".oo###oo###", "o###h#####o", "o##hh#####o", "o#########o", "o#oo###oo#o", "oo..o#o..oo", "....ooo...."}),
            Map.entry("build", new String[]{".....ooooo.", "....o####o.", "...o#hh##o.", "....o####o.", "....oo##o..", "...o##oo...", "..o##o.....", ".o##o......", "o##o.......", "oo.........", "..........."}),
            Map.entry("scroll", new String[]{".ooooooooo.", "o#########o", "o#hhhhhh##o", ".o#######o.", ".o#oooo##o.", ".o#######o.", ".o#oooo##o.", ".o#######o.", ".o#ooo###o.", "o#########o", ".ooooooooo."}),
            Map.entry("move", new String[]{".....o.....", "....o#o....", "...o#h#o...", "..o#####o..", ".ooo#h#ooo.", "...o###o...", "...o###o...", "...o###o...", "...o###o...", "...ooooo...", "..........."}),
            Map.entry("alert", new String[]{".....o.....", "....o#o....", "...o#h#o...", "...o#h#o...", "..o##h##o..", "..o##h##o..", ".o###h###o.", ".o#######o.", "o####o####o", "o#########o", ".ooooooooo."}),
            Map.entry("crown", new String[]{"...........", "o....o....o", "o#o.o#o.o#o", "o##o###o##o", "o#########o", "o#hhh#####o", "o#########o", "o#########o", ".ooooooooo.", "...........", "..........."}),
            Map.entry("heart", new String[]{"...........", ".oo.....oo.", "o##o...o##o", "o#h#o.o#h#o", "o#######h#o", "o#########o", ".o#######o.", "..o#####o..", "...o###o...", "....o#o....", ".....o....."}),
            Map.entry("home", new String[]{".....o.....", "....o#o....", "...o###o...", "..o##h##o..", ".o#######o.", "ooooooooooo", ".o##ooo##o.", ".o##o#o##o.", ".o##o#o##o.", ".o##o#o##o.", ".ooooooooo."}),
            Map.entry("shield", new String[]{".ooooooooo.", "o#########o", "o#hh######o", "o#h#######o", "o#########o", "o#########o", ".o#######o.", ".o#######o.", "..o#####o..", "...o###o...", "....ooo...."}),
            Map.entry("stop", new String[]{"...ooooo...", "..o#####o..", ".o#######o.", "o####h####o", "o###hhh###o", "o####h####o", "o#########o", ".o#######o.", "..o#####o..", "...ooooo...", "..........."}),
            Map.entry("sword", new String[]{"........oo.", ".......o##o", "......o#h#o", ".....o#h#o.", "....o#h#o..", "o..o#h#o...", "oo#h#o....."

                    , ".o#h#o.....", "o#o#o......", "#o.oo......", "oo........."}),
            Map.entry("tools", new String[]{"..ooo......", ".o###o.....", ".o##o#o....", "..oo#h#o...", "....o#h#o..", ".....o#h#o.", "......o#h#o", ".......o#o.", "......o#o..", ".....o#o...", "......o...."}),
            Map.entry("wood", new String[]{"...........", "..ooooooo..", ".o#######o.", "o#o#####o#o", "o#o#oo##o#o", "o#o#####o#o", ".o#######o.", "..ooooooo..", "...........", "...........", "..........."}));

    /** Рисует значок name (без @) в клетке size x size, по центру, с основным цветом color. Возвращает false, если такого значка нет. */
    public static boolean draw(GuiGraphics g, String name, int x, int y, int size, int color) {
        String[] m = ICONS.get(name);
        if (m == null) return false;
        int s = Math.max(1, (size - 4) / 11), ox = x + (size - 11 * s) / 2, oy = y + (size - 11 * s) / 2;
        int dark = 0xFF101216, hi = lighten(color);
        for (int r = 0; r < 11; r++) {
            String row = m[r];
            for (int c = 0; c < 11 && c < row.length(); c++) {
                char ch = row.charAt(c);
                if (ch == '.') continue;
                g.fill(ox + c * s, oy + r * s, ox + c * s + s, oy + r * s + s, ch == 'o' ? dark : ch == 'h' ? hi : color);
            }
        }
        return true;
    }

    private static int lighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 70), gg = Math.min(255, ((c >> 8) & 255) + 70), b = Math.min(255, (c & 255) + 70);
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }
}
