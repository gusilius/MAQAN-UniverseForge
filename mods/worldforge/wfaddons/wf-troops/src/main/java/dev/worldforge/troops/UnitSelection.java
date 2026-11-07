package dev.worldforge.troops;

import java.util.*;

/**
 * Выделение мышкой, как в RTS: клик, рамка, Shift - добавить/убрать, двойной клик - все того же типа на экране, Ctrl+цифра - группа, цифра - вызвать группу.
 * Работает с экранными прямоугольниками: ключи - UUID, координаты - уже спроецированные позиции на экране.
 */
public final class UnitSelection {
    public record Screen(String id, String type, double x, double y, double radius, boolean visible) {}

    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final Map<Integer, List<String>> groups = new HashMap<>();
    private String lastClick = ""; private long lastClickMs;
    public static final long DOUBLE_MS = 350;

    public Set<String> selected() { return Collections.unmodifiableSet(selected); }
    public boolean isSelected(String id) { return selected.contains(id); }
    public void clear() { selected.clear(); }
    public void set(Collection<String> ids) { selected.clear(); selected.addAll(ids); }

    private static Screen nearest(List<Screen> all, double mx, double my) {
        Screen best = null; double bd = Double.MAX_VALUE;
        for (Screen s : all) {
            if (!s.visible()) continue;
            double d = Math.hypot(s.x() - mx, s.y() - my);
            if (d <= Math.max(s.radius(), 8) && d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Клик. Возвращает true, если попали по солдату. */
    public boolean click(List<Screen> all, double mx, double my, boolean shift, long nowMs) {
        Screen hit = nearest(all, mx, my);
        if (hit == null) { if (!shift) selected.clear(); return false; }
        if (hit.id().equals(lastClick) && nowMs - lastClickMs <= DOUBLE_MS) {
            if (!shift) selected.clear();
            for (Screen s : all) if (s.visible() && s.type().equals(hit.type())) selected.add(s.id());
            lastClick = ""; return true;
        }
        lastClick = hit.id(); lastClickMs = nowMs;
        if (shift) { if (!selected.remove(hit.id())) selected.add(hit.id()); }
        else { selected.clear(); selected.add(hit.id()); }
        return true;
    }

    /** Рамка: все видимые внутри прямоугольника. Слишком маленькая рамка считается кликом. */
    public int box(List<Screen> all, double x1, double y1, double x2, double y2, boolean shift) {
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        if (!shift) selected.clear();
        int n = 0;
        for (Screen s : all) if (s.visible() && s.x() >= minX && s.x() <= maxX && s.y() >= minY && s.y() <= maxY) { selected.add(s.id()); n++; }
        return n;
    }

    public static boolean isBox(double x1, double y1, double x2, double y2) { return Math.abs(x2 - x1) > 5 || Math.abs(y2 - y1) > 5; }

    public void saveGroup(int n) { if (n >= 0 && n <= 9) groups.put(n, new ArrayList<>(selected)); }

    /** Вызов группы; fallback - тех, кого уже нет в живых (alive), выкидываем. */
    public boolean recallGroup(int n, Set<String> alive) {
        List<String> g = groups.get(n);
        if (g == null) return false;
        g.retainAll(alive);
        selected.clear(); selected.addAll(g);
        return !g.isEmpty();
    }

    public void retain(Set<String> alive) { selected.retainAll(alive); }
}
