package com.maqan.galacticnavigator.client;

/**
 * Deterministic Java fallback for the Rust workspace layout contract.
 * Keep constants and ordering behavior in sync with native/maqan-core/src/layout.rs.
 */
public final class WorkspaceLayout {
    private WorkspaceLayout() {}

    /** Returns [mapTop,mapBottom,listLeft,listRight,infoLeft,infoRight,mapLeft,mapRight]. */
    public static int[] compute(int width, int height, WorkspaceState.Snapshot state) {
        if (state == null || width < 1 || height < 1 || width > 16_384 || height > 16_384) return null;
        int gap = 8;
        int footerReserve = width < 560 ? 60 : 36;
        int top = Math.max(76, Math.min(94, (int)Math.round(height * .34)));
        int bottom = Math.min(height - 8, Math.max(top + 52, height - footerReserve));
        int listWidth = state.listVisible() && width >= 620
                ? Math.min(Math.max(140, Math.min(340, state.listWidth())), (width - 24) / 3) : 0;
        int infoWidth = state.infoVisible() && width >= 850
                ? Math.min(Math.max(180, Math.min(420, state.infoWidth())), (width - 24) * 2 / 5) : 0;
        int active = 1 + (listWidth > 0 ? 1 : 0) + (infoWidth > 0 ? 1 : 0);
        int available = Math.max(1, width - 24 - gap * (active - 1));
        int mapMinimum = Math.min(available, width < 620 ? 120 : 180);
        while (listWidth + infoWidth + mapMinimum > available) {
            if (infoWidth > 0) infoWidth = 0;
            else if (listWidth > 0) listWidth = 0;
            else break;
            active = 1 + (listWidth > 0 ? 1 : 0) + (infoWidth > 0 ? 1 : 0);
            available = Math.max(1, width - 24 - gap * (active - 1));
            mapMinimum = Math.min(available, width < 620 ? 120 : 180);
        }
        int mapWidth = Math.max(1, available - listWidth - infoWidth);
        int[] widths = {listWidth, infoWidth, mapWidth};
        int[] result = {top, bottom, 0, 0, 0, 0, 0, 0};
        int cursor = 12;
        boolean first = true;
        for (WorkspaceState.PanelId id : state.order()) {
            int index = switch (id) { case LIST -> 0; case INFO -> 1; case MAP -> 2; };
            int panelWidth = widths[index];
            if (panelWidth <= 0) continue;
            if (!first) cursor += gap;
            switch (id) {
                case LIST -> { result[2] = cursor; result[3] = cursor + panelWidth; }
                case INFO -> { result[4] = cursor; result[5] = cursor + panelWidth; }
                case MAP -> { result[6] = cursor; result[7] = cursor + panelWidth; }
            }
            cursor += panelWidth;
            first = false;
        }
        if (result[7] <= result[6]) { result[6] = 12; result[7] = Math.max(13, width - 12); }
        return result;
    }
}
