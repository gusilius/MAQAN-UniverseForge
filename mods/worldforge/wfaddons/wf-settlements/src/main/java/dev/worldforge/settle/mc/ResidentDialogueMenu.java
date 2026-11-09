package dev.worldforge.settle.mc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Read-only data container for the dedicated resident dialogue screen. */
public final class ResidentDialogueMenu extends AbstractContainerMenu {
    public final String residentId, residentName, profession, status, family, relationship, quest;
    public final int age;

    public ResidentDialogueMenu(int windowId, Inventory inventory, FriendlyByteBuf data) {
        this(windowId, inventory, data.readUtf(128), data.readUtf(128), data.readUtf(128),
                data.readUtf(256), data.readUtf(512), data.readUtf(128), data.readUtf(512), data.readVarInt());
    }

    public ResidentDialogueMenu(int windowId, Inventory inventory, String residentId, String residentName,
                                String profession, String status, String family, String relationship,
                                String quest, int age) {
        super(SettleMod.RESIDENT_DIALOGUE_MENU.get(), windowId);
        this.residentId = residentId;
        this.residentName = residentName;
        this.profession = profession;
        this.status = status;
        this.family = family;
        this.relationship = relationship;
        this.quest = quest;
        this.age = age;
    }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return player.isAlive(); }
}
