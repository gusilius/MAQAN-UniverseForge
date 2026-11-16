package dev.worldforge.troops;

/**
 * Canonical work-area commands shared by the RTS client and server.
 * Keep enum order stable: it is the compact kind value sent in OrderPacket.
 */
public enum WorkArea {
    WOOD("wood", "целые деревья", "lumberjack"),
    STONE("stone", "камень", "stonemason"),
    ORE("ore", "руда", "miner"),
    CLEAR("clear", "расчистка", "builder"),
    LEVEL("level", "выравнивание", "builder"),
    HARVEST("harvest", "урожай", "farmer"),
    HONEY("honey", "мёд", "beekeeper"),
    HERBS("herbs", "лекарственные растения", "herbalist");

    public final String mode;
    public final String label;
    public final String profession;

    WorkArea(String mode, String label, String profession) {
        this.mode = mode;
        this.label = label;
        this.profession = profession;
    }

    public static boolean validKind(int kind) {
        return kind >= 0 && kind < values().length;
    }

    public static WorkArea byKind(int kind) {
        if (!validKind(kind)) throw new IllegalArgumentException("Unknown work-area kind: " + kind);
        return values()[kind];
    }
}
