package dev.worldforge.people.mc;

import dev.worldforge.people.Character;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

/**
 * Игрок кликнул правой кнопкой по телу персонажа. Публикуется в MinecraftForge.EVENT_BUS на сервере.
 * Слушатель, взявший событие на себя (открыл диалог и т.п.), вызывает {@link #setHandled()}: тогда стандартная реакция пропускается.
 */
public class NpcInteractEvent extends Event {
    private final ServerPlayer player;
    private final WFNpc npc;
    private final Character character;
    private final String dialogueId;
    private boolean handled;

    public NpcInteractEvent(ServerPlayer player, WFNpc npc, Character character, String dialogueId) {
        this.player = player;
        this.npc = npc;
        this.character = character;
        this.dialogueId = dialogueId;
    }

    public ServerPlayer getPlayer() { return player; }

    public WFNpc getNpc() { return npc; }

    public Character getCharacter() { return character; }

    public String getDialogueId() { return dialogueId; }

    public boolean isHandled() { return handled; }

    public void setHandled() { handled = true; }
}
