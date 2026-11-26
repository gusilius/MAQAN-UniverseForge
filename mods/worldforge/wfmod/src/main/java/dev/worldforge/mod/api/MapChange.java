package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleData;

/** Уведомление об изменении карты: правка редактора, смена отношений, выполненное действие. */
public final class MapChange {
    public final TitleData data;
    public final String kind;            // "edit" | "action"
    public final String detail;          // id действия или пусто

    public MapChange(TitleData data, String kind, String detail) {
        this.data = data;
        this.kind = kind;
        this.detail = detail == null ? "" : detail;
    }
}
