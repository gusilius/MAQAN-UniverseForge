package dev.worldforge.mod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Игрок перешёл из региона в регион. Событие публикуется на MinecraftForge.EVENT_BUS (только на сервере).
 * Это точка, к которой потом подключаются «действия»: налоги, войны, квесты, бонусы региона и т.д.
 * from или to равны null, если игрок вышел за пределы любых регионов (море, ничейная земля).
 */
public class RegionEnterEvent extends Event {
    private final ServerPlayer player;
    private final RegionData.Region from, to;

    public RegionEnterEvent(ServerPlayer player, RegionData.Region from, RegionData.Region to) {
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public ServerPlayer getPlayer() { return player; }
    public RegionData.Region getFrom() { return from; }
    public RegionData.Region getTo() { return to; }
}
