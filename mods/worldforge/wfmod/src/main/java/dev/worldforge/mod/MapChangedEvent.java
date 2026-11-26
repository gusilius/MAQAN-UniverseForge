package dev.worldforge.mod;

import dev.worldforge.mod.api.MapChange;
import net.minecraftforge.eventbus.api.Event;

/** Событие на шине Forge: карта изменилась (правка редактора или выполненное действие). Для аддонов; то же, что WorldForgeApi.addChangeListener. */
public class MapChangedEvent extends Event {
    public final MapChange change;

    public MapChangedEvent(MapChange change) { this.change = change; }
}
