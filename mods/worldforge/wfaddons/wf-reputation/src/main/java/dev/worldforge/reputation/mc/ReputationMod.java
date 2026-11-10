package dev.worldforge.reputation.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.Json;
import dev.worldforge.reputation.ReputationStore;
import dev.worldforge.reputation.Standing;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Склейка wf-reputation: хранилище в мире, фракции из датапаков, команда /wfrep, уведомления, мост к диалогам (если есть). */
@Mod("wf_reputation")
public class ReputationMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final int SAVE_EVERY = 6000;
    private static volatile ReputationStore store;
    private static final Set<String> KNOWN = ConcurrentHashMap.newKeySet();
    private static long ticks;

    public ReputationMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(ReputationMod::commonSetup));
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void commonSetup() {
        if (ModList.get().isLoaded("wf_dialogue")) {
            DialogueLink.register();                 // класс грузится только здесь
            LOGGER.info("wf_reputation: мост к wf_dialogue подключён");
        }
    }

    /** Хранилище текущего мира; null, когда сервер не запущен. */
    public static ReputationStore store() { return store; }

    // ------------------------------------------------------------------ жизненный цикл

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_reputation.json");
    }

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) {
        ReputationStore s = new ReputationStore();
        s.addListener((player, target, oldV, newV, reason) -> notify(e.getServer(), s, player, target, oldV, newV));
        Path f = file(e.getServer());
        try {
            if (Files.exists(f)) s.fromJson(Files.readString(f));
        } catch (Exception ex) {
            LOGGER.error("wf_reputation: не удалось прочитать {}: {}", f, ex.toString());
        }
        store = s;
    }

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        loadFactions(e.getServer().getResourceManager());
    }

    @SubscribeEvent
    public void onReload(AddReloadListenerEvent e) {
        e.addListener((ResourceManagerReloadListener) rm -> { if (store != null) loadFactions(rm); });
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        save(e.getServer());
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent e) {
        store = null;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++ticks % SAVE_EVERY == 0) save(e.getServer());
    }

    private static void save(MinecraftServer server) {
        ReputationStore s = store;
        if (s == null) return;
        Path f = file(server);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, s.toJson());
        } catch (Exception ex) {
            LOGGER.error("wf_reputation: не удалось сохранить {}: {}", f, ex.toString());
        }
    }

    private static void loadFactions(ResourceManager rm) {
        ReputationStore s = store;
        if (s == null) return;
        Map<ResourceLocation, Resource> found = rm.listResources("wf_factions", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                s.loadFactions(json);
                collectIds(json);
            } catch (Exception ex) {
                LOGGER.error("wf_reputation: ошибка в {}: {}", r.getKey(), ex.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectIds(String json) {
        Object root = Json.parse(json);
        if (root instanceof Map && ((Map<String, Object>) root).get("factions") instanceof java.util.List) {
            for (Object o : (java.util.List<Object>) ((Map<String, Object>) root).get("factions")) {
                if (o instanceof Map && ((Map<String, Object>) o).get("id") != null) KNOWN.add(String.valueOf(((Map<String, Object>) o).get("id")));
            }
        }
    }

    private static void notify(MinecraftServer server, ReputationStore s, String player, String target, int oldV, int newV) {
        try {
            ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(player));
            if (p == null) return;
            String d = (newV > oldV ? "+" : "") + (newV - oldV);
            p.displayClientMessage(Component.literal("Репутация: " + s.nameOf(target) + " " + d + " (" + newV + ", " + Standing.of(newV).label + ")"), true);
        } catch (IllegalArgumentException ignored) {
            // ключ игрока не UUID: уведомлять некого
        }
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("wfrep")
                .then(Commands.literal("show")
                        .executes(c -> show(c.getSource(), null))
                        .then(Commands.argument("faction", ResourceLocationArgument.id())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new TreeSet<>(KNOWN), b))
                                .executes(c -> show(c.getSource(), key(ResourceLocationArgument.getId(c, "faction"))))))
                .then(Commands.literal("add").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("faction", ResourceLocationArgument.id())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new TreeSet<>(KNOWN), b))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(-200, 200))
                                                .executes(c -> add(c.getSource(), EntityArgument.getPlayer(c, "player"),
                                                        key(ResourceLocationArgument.getId(c, "faction")), IntegerArgumentType.getInteger(c, "amount"))))))));
    }

    /** "realm:stark" приходит как ResourceLocation; запись без двоеточия получила бы "minecraft:", её возвращаем к простому имени. */
    private static String key(ResourceLocation id) {
        String k = id.toString();
        if (id.getNamespace().equals("minecraft") && !KNOWN.contains(k)) return id.getPath();
        return k;
    }

    private static int show(CommandSourceStack s, String faction) {
        ReputationStore st = store;
        if (st == null) return 0;
        try {
            ServerPlayer p = s.getPlayerOrException();
            String id = p.getUUID().toString();
            if (faction != null) {
                int v = st.get(id, faction);
                s.sendSuccess(() -> Component.literal(st.nameOf(faction) + ": " + v + " (" + st.standing(id, faction).label + ")"), false);
                return 1;
            }
            Map<String, Integer> all = st.all(id);
            if (all.isEmpty()) {
                s.sendSuccess(() -> Component.literal("У вас пока нет заметного отношения ни с кем"), false);
                return 0;
            }
            for (Map.Entry<String, Integer> en : all.entrySet()) {
                s.sendSuccess(() -> Component.literal(st.nameOf(en.getKey()) + ": " + en.getValue() + " (" + Standing.of(en.getValue()).label + ")"), false);
            }
            return all.size();
        } catch (Exception ex) {
            s.sendFailure(Component.literal("Команду может выполнить только игрок"));
            return 0;
        }
    }

    private static int add(CommandSourceStack source, ServerPlayer target, String faction, int amount) {
        ReputationStore reputation = store;
        if (reputation == null) return 0;
        String actor = source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : source.getTextName();
        String playerId = target.getUUID().toString();
        long minute = WorldForgeApi.clock().worldMinute();
        String payload = playerId + "|" + faction + "|" + amount;
        String requestId = "reputation:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + source.getServer().getTickCount() + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "reputation.adjust", actor, playerId, java.util.List.of(faction, Integer.toString(amount)), minute),
                    req -> {
                        if (!source.hasPermission(2)) return "Нужны права оператора";
                        if (source.getServer().getPlayerList().getPlayer(target.getUUID()) == null)
                            return "Целевой игрок отключился до изменения репутации";
                        return null;
                    },
                    (req, attempt) -> {
                        reputation.add(playerId, faction, amount, "команда");
                        int value = reputation.get(playerId, faction);
                        return dev.worldforge.mod.api.ActionResult.ok(
                                target.getGameProfile().getName() + " / " + reputation.nameOf(faction) + ": " + value
                                        + " (" + reputation.standing(playerId, faction).label + ")");
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                source.sendFailure(Component.literal(task.message()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal(task.message()), true);
            return 1;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось изменить репутацию: " + ex.getMessage()));
            return 0;
        }
    }
}
