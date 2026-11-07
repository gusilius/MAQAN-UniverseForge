package dev.worldforge.mod.client;

import dev.worldforge.mod.api.EventLog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Всплывающие сообщения журнала: стопка в правом верхнем углу, новые сверху, не больше {@link #MAX}, гаснут по возрасту.
 * Только рисование по данным {@link EventLog}: вызывайте {@link #render} каждый кадр из HUD-слоя с текущим временем
 * в тех же единицах, что и {@code time} событий (по умолчанию миллисекунды).
 */
public final class EventFeed {
    public static final int MAX = 4, W = 190;

    private final EventLog log;
    /** Сколько живёт сообщение и за сколько до конца оно гаснет (в единицах времени событий). */
    public long life = 6000, fade = 1500;

    public EventFeed(EventLog log) { this.log = log; }

    public static int color(EventLog.Category c) {
        switch (c) {
            case WAR: return WFUi.BAD;
            case DIPLOMACY: return WFUi.INFO;
            case PEOPLE: return WFUi.GOLD;
            case SETTLEMENT: return WFUi.GOOD;
            default: return WFUi.MUTED;
        }
    }

    static int alpha(int argb, double a) {
        int al = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, a)));
        return (Math.max(al, 5) << 24) | (argb & 0xFFFFFF);   // alpha < 4 игра читает как непрозрачный
    }

    /** Сообщения, видимые сейчас: новые первыми. */
    public List<EventLog.Event> visible(long now) {
        List<EventLog.Event> out = new ArrayList<>();
        for (EventLog.Event e : log.recent(MAX * 3)) {
            if (e.time > now) continue;
            if (now - e.time >= life) break;
            out.add(e);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** Прозрачность по возрасту: 1 пока не началось затухание, затем линейно к 0. */
    public double opacity(long age) {
        if (age <= life - fade) return 1;
        return Math.max(0, (life - age) / (double) fade);
    }

    public void render(GuiGraphics g, Font f, int screenW, long now) {
        int y = 6, x = screenW - W - 6;
        for (EventLog.Event e : visible(now)) {
            double a = opacity(now - e.time);
            if (a <= 0) continue;
            List<String> lines = wrap(f, e.text, W - 16, 2);
            int h = 8 + lines.size() * 10 + 2;
            int c = color(e.category);
            g.fill(x - 1, y - 1, x + W + 1, y + h + 1, alpha(WFUi.EDGE, a));
            g.fill(x, y, x + W, y + h, alpha(WFUi.SCRIM, a));
            g.fill(x, y, x + 3, y + h, alpha(c, a));
            g.drawString(f, e.category.label.toUpperCase(), x + 8, y + 3, alpha(c, a));
            for (int i = 0; i < lines.size(); i++) g.drawString(f, lines.get(i), x + 8, y + 13 + i * 10, alpha(WFUi.TX, a));
            y += h + 4;
        }
    }

    /** Перенос по словам, не больше maxLines строк; хвост обрезается многоточием. */
    static List<String> wrap(Font f, String text, int w, int maxLines) {
        List<String> out = new ArrayList<>();
        String cur = "";
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            String t = cur.isEmpty() ? words[i] : cur + " " + words[i];
            if (f.width(t) <= w) { cur = t; continue; }
            if (out.size() == maxLines - 1) {   // последняя строка: всё остальное с обрезкой
                StringBuilder rest = new StringBuilder(cur.isEmpty() ? words[i] : cur + " " + words[i]);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                String s = rest.toString();
                if (f.width(s) > w) { s = f.plainSubstrByWidth(s, w - f.width("...")); s += "..."; }
                out.add(s);
                return out;
            }
            if (!cur.isEmpty()) out.add(cur);
            cur = words[i];
            if (f.width(cur) > w) cur = f.plainSubstrByWidth(cur, w);
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    /** Время события для списка: большие значения читаются как миллисекунды эпохи (ЧЧ:ММ), малые как номер дня. */
    public static String time(long t) {
        if (t > 100_000_000_000L) {
            long s = (t / 1000) % 86400;
            return String.format(java.util.Locale.ROOT, "%02d:%02d", s / 3600, (s / 60) % 60);
        }
        return "день " + t;
    }
}
