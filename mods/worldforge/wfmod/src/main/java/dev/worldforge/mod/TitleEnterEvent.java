package dev.worldforge.mod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Игрок перешёл из провинции в провинцию (самый мелкий уровень). Публикуется на MinecraftForge.EVENT_BUS только на сервере.
 * Через data() можно подняться по иерархии: графство, герцогство, королевство, империя, а также узнать государство-владельца.
 */
public class TitleEnterEvent extends Event {
    private final ServerPlayer player;
    private final MapData data;
    private final TitleData.Title from, to;

    public TitleEnterEvent(ServerPlayer player, MapData data, TitleData.Title from, TitleData.Title to) {
        this.player = player;
        this.data = data;
        this.from = from;
        this.to = to;
    }

    public ServerPlayer getPlayer() { return player; }
    public MapData data() { return data; }
    public TitleData.Title getFrom() { return from; }
    public TitleData.Title getTo() { return to; }

    /** Государство, которому принадлежит новая провинция (или null). */
    public TitleData.Realm getRealm() { return data.titles == null ? null : data.titles.realmOf(to); }

    /** Титул заданного уровня для новой провинции: tier(3) обычно королевство. */
    public TitleData.Title tier(int tierIndex) { return data.titles == null ? null : data.titles.ancestor(to, tierIndex); }
}
