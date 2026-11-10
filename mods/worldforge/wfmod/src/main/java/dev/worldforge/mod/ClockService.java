package dev.worldforge.mod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.worldforge.mod.api.GameClock;
import dev.worldforge.mod.api.WorldForgeApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Серверная часть общих часов: ход времени, сохранение в wf_clock.json и команда /wftime. */
public final class ClockService {
    private static final int SAVE_EVERY = 6000;
    private int ticks;

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("wf_clock.json"); }

    @SubscribeEvent
    public void onStart(ServerStartingEvent e) {
        ticks = 0;
        WorldForgeApi.clock().reset();
        try {
            Path f = file(e.getServer());
            if (Files.exists(f)) WorldForgeApi.clock().load(Files.readString(f));
            Path t = ledgerFile(e.getServer());
            if (Files.exists(t)) loadLedger(Files.readString(t));
        } catch (Exception ex) {
            WorldForgeMod.LOGGER.error("WorldForge: отказ запуска — сохранённое время мира не удалось проверить: {}", ex.toString());
            throw new IllegalStateException("WorldForge cannot start with an unreadable or invalid world clock; refusing to reset world time", ex);
        }
    }

    @SubscribeEvent
    public void onStop(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        WorldForgeApi.clock().advance(1);
        ticks++;
        if (ticks % 40 == 0) {
            GameClock c = WorldForgeApi.clock();
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("date", c.dateText()); m.put("speed", c.speedName()); m.put("paused", c.paused());
            String json = Json.write(m);
            for (net.minecraft.server.level.ServerPlayer sp : e.getServer().getPlayerList().getPlayers()) WFNet.sendView(sp, "wf:clock", json);
        }
        if (ticks % SAVE_EVERY == 0) save(e.getServer());
    }

    private static Path ledgerFile(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("wf_treasury.json"); }

    /** Книга казны (откуда идут деньги) переживает перезапуск вместе с часами. */
    @SuppressWarnings("unchecked")
    static void loadLedger(String json) {
        Object o = Json.parse(json);
        if (!(o instanceof java.util.Map<?, ?> m)) return;
        for (var e : m.entrySet()) {
            java.util.Map<String, Double> l = new java.util.LinkedHashMap<>();
            if (e.getValue() instanceof java.util.Map<?, ?> mm) for (var x : mm.entrySet()) l.put(String.valueOf(x.getKey()), x.getValue() instanceof Number n ? n.doubleValue() : 0.0);
            dev.worldforge.mod.api.TreasuryApi.setLedger(String.valueOf(e.getKey()), l);
        }
    }

    private static void save(MinecraftServer s) {
        try { Files.writeString(ledgerFile(s), Json.write(new java.util.LinkedHashMap<String, Object>(dev.worldforge.mod.api.TreasuryApi.allLedgers()))); } catch (Exception ex) { WorldForgeMod.LOGGER.error("WorldForge: книга казны не сохранена: {}", ex.toString()); }
        try { Files.writeString(file(s), WorldForgeApi.clock().toJson()); } catch (Exception ex) { WorldForgeMod.LOGGER.error("WorldForge: часы не сохранены: {}", ex.toString()); }
    }

    private static int say(CommandSourceStack s) {
        GameClock c = WorldForgeApi.clock();
        s.sendSuccess(() -> Component.literal(c.dateText() + "  ·  " + c.speedName()), true);
        return 1;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("wftime").requires(WFPerm::trusted)
                .executes(c -> say(c.getSource()))
                .then(Commands.literal("pause").executes(c -> { WorldForgeApi.clock().pause(); return say(c.getSource()); }))
                .then(Commands.literal("play").executes(c -> { WorldForgeApi.clock().resume(); return say(c.getSource()); }))
                .then(Commands.literal("toggle").executes(c -> { WorldForgeApi.clock().toggle(); return say(c.getSource()); }))
                .then(Commands.literal("faster").executes(c -> { WorldForgeApi.clock().faster(); return say(c.getSource()); }))
                .then(Commands.literal("slower").executes(c -> { WorldForgeApi.clock().slower(); return say(c.getSource()); }))
                .then(Commands.literal("speed").then(Commands.argument("n", IntegerArgumentType.integer(0, GameClock.SPEEDS.length - 1)).executes(c -> {
                    WorldForgeApi.clock().setSpeed(IntegerArgumentType.getInteger(c, "n"));
                    return say(c.getSource());
                })));
    }
}
