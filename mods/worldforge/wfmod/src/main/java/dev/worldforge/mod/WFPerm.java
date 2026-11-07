package dev.worldforge.mod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Кто может отдавать приказы из окон и командами. Оператор - всегда; владелец одиночного мира - тоже (даже без читов), иначе окна
 * в одиночной игре были бы бесполезны. На сервере остальным игрокам нужны права оператора, если они не получили роль
 * (см. Authority в wf-war и CapabilityCheck в wf-people).
 */
public final class WFPerm {
    private WFPerm() {}

    public static boolean trusted(CommandSourceStack s) {
        return s.hasPermission(2) || (s.getServer() != null && s.getServer().isSingleplayer());
    }

    public static boolean trusted(ServerPlayer p) {
        return trusted(p.createCommandSourceStack());
    }
}
