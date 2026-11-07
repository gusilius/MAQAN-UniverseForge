package dev.worldforge.reputation;

/** Словесный уровень отношения по значению -100..100. */
public enum Standing {
    HOSTILE("Враг", -100), UNFRIENDLY("Недруг", -59), NEUTRAL("Нейтрален", -19), FRIENDLY("Дружелюбен", 20), ALLIED("Союзник", 60);

    public final String label;
    public final int from;

    Standing(String label, int from) { this.label = label; this.from = from; }

    public static Standing of(int value) {
        Standing r = HOSTILE;
        for (Standing s : values()) if (value >= s.from) r = s;
        return r;
    }

    public static Standing byName(String name) {
        for (Standing s : values()) if (s.name().equalsIgnoreCase(name) || s.label.equalsIgnoreCase(name)) return s;
        throw new IllegalArgumentException("Нет уровня отношения '" + name + "'");
    }
}
