package dev.worldforge.rts;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;

import java.util.Map;

/** Как выглядят рабочие и здания на RTS-экране: знак профессии, цвета, состояние. Чистая логика (ARGB-числа), без рисования. */
public final class Look {
    private Look() {}

    public enum WorkerState { IDLE, MOVING, WORKING, BUILDING, BLOCKED }

    private static final Map<String, String> GLYPH = Map.of("laborer", "Р", "builder", "С", "lumberjack", "Д", "sawyer", "П", "farmer", "З", "miner", "К", "smith", "Ку", "guard", "Ст");
    private static final Map<String, Integer> COLOR = Map.of("laborer", 0xFFB8B8B0, "builder", 0xFFE8A040, "lumberjack", 0xFF8BC34A, "sawyer", 0xFFD7B070, "farmer", 0xFFF2D14B,
            "miner", 0xFF9AA7B8, "smith", 0xFFE0643C, "guard", 0xFF4AA8FF);

    /** Короткий знак профессии для ячейки (1-2 буквы). */
    public static String glyph(Defs d, String prof) {
        String g = GLYPH.get(prof);
        if (g != null) return g;
        String n = d.professionName(prof);
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
    }

    public static int professionColor(String prof) {
        Integer c = COLOR.get(prof);
        if (c != null) return c;
        int h = Math.abs(prof.hashCode());
        return 0xFF000000 | (96 + h % 128) << 16 | (96 + (h / 7) % 128) << 8 | (96 + (h / 49) % 128);
    }

    public static WorkerState state(Settlement s, Worker w) {
        if (w.building == 0) return WorkerState.IDLE;
        if (w.moving) return WorkerState.MOVING;
        Building b = s.building(w.building);
        if (b == null) return WorkerState.IDLE;
        if (b.state != Building.State.DONE) return b.state == Building.State.BUILDING ? WorkerState.BUILDING : WorkerState.IDLE;
        String bl = s.chain(b).blocker;
        return bl.equals("inputs") || bl.equals("storage") || bl.equals("paused") ? WorkerState.BLOCKED : WorkerState.WORKING;
    }

    public static int stateColor(WorkerState st) {
        return switch (st) {
            case IDLE -> 0xFFF0C85A;
            case MOVING -> 0xFF4AA8FF;
            case WORKING -> 0xFF4FCF5A;
            case BUILDING -> 0xFFE8A040;
            case BLOCKED -> 0xFFE04A4A;
        };
    }

    public static String stateName(WorkerState st) {
        return switch (st) { case IDLE -> "свободен"; case MOVING -> "идёт"; case WORKING -> "работает"; case BUILDING -> "строит"; case BLOCKED -> "простаивает"; };
    }

    /** Цвет здания на карте: серый - ждёт, оранжевый - стройка, зелёный - работает, красный - стоит (нет сырья/работников/склад полон), синий - пауза. */
    public static int buildingColor(Settlement s, Building b) {
        if (b.state == Building.State.PLANNED) return 0xFF7A7D88;
        if (b.state == Building.State.BUILDING) return 0xFFE8A040;
        if (b.paused) return 0xFF4AA8FF;
        String bl = s.chain(b).blocker;
        if (bl.equals("inputs") || bl.equals("storage") || bl.equals("workers")) return 0xFFE04A4A;
        return 0xFF4FCF5A;
    }

    public static String stateName(Building b) {
        return b.state == Building.State.DONE ? (b.paused ? "пауза" : "готово") : b.state == Building.State.BUILDING ? "строится" : "ждёт материалов";
    }

    /** Значок здания для плашки: первая буква названия. */
    public static String initial(String name) { return name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(); }

    /** Значок ресурса WFUi и цвет. */
    public static int resourceColor(String res) {
        return switch (res) {
            case "wood" -> 0xFFB98A4E; case "planks" -> 0xFFD9B27A; case "stone" -> 0xFF9AA0A8; case "food" -> 0xFFE0A040;
            case "iron" -> 0xFFB0B8C8; case "tools" -> 0xFFC8C8C8; default -> 0xFFA6A8B3;
        };
    }
}
