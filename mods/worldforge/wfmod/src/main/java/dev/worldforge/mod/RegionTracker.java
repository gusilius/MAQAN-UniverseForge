package dev.worldforge.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Раз в секунду определяет регион игрока; при смене показывает название над панелью и публикует RegionEnterEvent. */
public final class RegionTracker {
    private final Map<UUID, Integer> last = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastProvince = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer sp) || sp.tickCount % 20 != 0) return;
        ServerLevel level = sp.serverLevel();
        if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator gen)) {
            last.remove(sp.getUUID());
            lastProvince.remove(sp.getUUID());
            return;
        }
        MapData m;
        try {
            m = gen.data();
        } catch (RuntimeException ex) {
            return;
        }
        if (m.titles != null) {                                // провинции: событие для действий
            TitleData.Title now = m.provinceAt(sp.getX(), sp.getZ());
            int pid = now == null ? 0 : now.id;
            Integer pp = lastProvince.put(sp.getUUID(), pid);
            if (pp == null || pp != pid) {
                MinecraftForge.EVENT_BUS.post(new TitleEnterEvent(sp, m, pp == null ? null : m.titles.get(pp), now));
            }
        }
        if (m.regions == null) return;
        int id = m.regionAt(sp.getX(), sp.getZ());
        Integer prev = last.put(sp.getUUID(), id);
        if (prev != null && prev == id) return;
        RegionData.Region to = m.regions.get(id);
        RegionData.Region from = prev == null ? null : m.regions.get(prev);
        if (to != null) {
            Component msg = Component.literal("Вы вошли в: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(to.name).withStyle(Style.EMPTY.withColor(to.rgb).withBold(true)));
            sp.displayClientMessage(msg, true);
        }
        MinecraftForge.EVENT_BUS.post(new RegionEnterEvent(sp, from, to));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        last.remove(e.getEntity().getUUID());
        lastProvince.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent e) {
        last.remove(e.getEntity().getUUID());
        lastProvince.remove(e.getEntity().getUUID());
    }
}
