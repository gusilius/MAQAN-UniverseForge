package dev.worldforge.settle.mc;

/** Fair rotating work windows for resident simulation; contains no Minecraft runtime dependencies. */
public final class ResidentLifeBatch {
    private ResidentLifeBatch() {}

    public record Window(int start, int count, int nextCursor) {}

    /**
     * Returns at most {@code budget} consecutive residents, wrapping around the current list.
     * The caller stores nextCursor per settlement. Size changes are safe because the cursor is
     * normalized against the current population every time.
     */
    public static Window next(int size, int cursor, int budget) {
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");
        if (budget < 0) throw new IllegalArgumentException("budget cannot be negative");
        if (size == 0 || budget == 0) return new Window(0, 0, 0);
        int start = Math.floorMod(cursor, size);
        int count = Math.min(size, budget);
        return new Window(start, count, (start + count) % size);
    }
}
