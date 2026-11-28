package dev.worldforge.settle.mc;

/** Fair rotating work windows for resident simulation; contains no Minecraft runtime dependencies. */
public final class ResidentLifeBatch {
    private ResidentLifeBatch() {}

    public record Window(int start, int count, int nextCursor) {}

    /**
     * Returns a contiguous slice that never crosses the list boundary. Wrapping is handled
     * by the next call so each resident is visited at most once per full cycle.
     * The caller stores nextCursor per settlement. Size changes are safe because the cursor is
     * normalized against the current population every time.
     */
    public static Window next(int size, int cursor, int budget) {
        if (size < 0) throw new IllegalArgumentException("size cannot be negative");
        if (budget < 0) throw new IllegalArgumentException("budget cannot be negative");
        if (size == 0 || budget == 0) return new Window(0, 0, 0);
        int start = Math.floorMod(cursor, size);
        // Clamp at the end of the current list. A wrapped batch would revisit the
        // beginning before the cursor completes its first full population cycle.
        int count = Math.min(Math.min(size, budget), size - start);
        return new Window(start, count, (start + count) % size);
    }
}
