package dev.worldforge.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.mod.identity.WorldIdentityStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldForge: генерация мира по картинке.
 * Карты лежат в config/worldforge/maps/<имя>.worldmap (или папкой <имя>/).
 * Измерение подключается датапаком: "generator": {"type": "worldforge:image", "map": "<имя>"}.
 */
@Mod(WorldForgeMod.MOD_ID)
public class WorldForgeMod {
    public static final String MOD_ID = "worldforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID);
    private static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MOD_ID);

    private static final Map<String, MapData> MAPS = new ConcurrentHashMap<>();
    private static volatile Registry<Biome> biomes;
    private static volatile boolean treasuryLoadFailed;
    private static volatile boolean actionExecutionLoadFailed;
    private static volatile WorldId worldId;
    private static long treasuryTicks;

    public WorldForgeMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        GENERATORS.register("image", () -> MapChunkGenerator.CODEC);
        BIOME_SOURCES.register("image", () -> MapBiomeSource.CODEC);
        GENERATORS.register(bus);
        BIOME_SOURCES.register(bus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new RegionTracker());
        MinecraftForge.EVENT_BUS.register(new EditorEvents());
        MinecraftForge.EVENT_BUS.register(new SyncService());
        MinecraftForge.EVENT_BUS.register(new ClockService());
        MinecraftForge.EVENT_BUS.register(new WebService());
        bus.addListener(this::commonSetup);
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> dev.worldforge.mod.client.ClientSetup.register(bus));
    }

    private void commonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) {
        e.enqueueWork(WFNet::init);
        // любое изменение карты (в том числе из аддонов) поднимает версию, чтобы клиенты и веб-карта получили дельту, и дублируется событием Forge
        dev.worldforge.mod.api.WorldForgeApi.addChangeListener(c -> {
            for (MapData m : new java.util.ArrayList<>(MAPS.values())) if (m.titles == c.data) EditorManager.touch(m);
            MinecraftForge.EVENT_BUS.post(new MapChangedEvent(c));
        });
    }

    /** Canonical world-wide money ledger shared by settlements, realms, trade and military addons. */
    public static TreasuryLedger treasury() { return TreasuryService.current(); }

    /** Stable identity for the currently loaded save; null until the save identity is validated. */
    public static WorldId worldId() { return worldId; }

    private static Path treasuryFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("worldforge").resolve("wf_treasury.json");
    }

    public static Path actionExecutionFile(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("worldforge").resolve("wf_action_execution.json");
    }

    private static void loadActionExecution(MinecraftServer server) {
        dev.worldforge.mod.api.WorldForgeApi.resetActionExecution();
        Path file = actionExecutionFile(server);
        try {
            dev.worldforge.mod.api.WorldForgeApi.actionExecution().load(file);
            actionExecutionLoadFailed = false;
            LOGGER.info("WorldForge actions: loaded {} tasks and {} receipts",
                    dev.worldforge.mod.api.WorldForgeApi.actionExecution().tasks().size(),
                    dev.worldforge.mod.api.WorldForgeApi.actionExecution().receipts().size());
        } catch (Exception ex) {
            actionExecutionLoadFailed = true;
            LOGGER.error("WorldForge action journal is invalid; refusing to overwrite it: {}", ex.toString());
            throw new IllegalStateException("WorldForge cannot start with a corrupt action execution journal", ex);
        }
    }

    private static void saveActionExecution(MinecraftServer server) {
        if (actionExecutionLoadFailed) {
            LOGGER.error("WorldForge action journal save skipped because the previous load failed; preserving original file");
            return;
        }
        try { dev.worldforge.mod.api.WorldForgeApi.actionExecution().save(actionExecutionFile(server)); }
        catch (IOException ex) { LOGGER.error("WorldForge action journal save failed: {}", ex.toString()); }
    }

    private static void loadTreasury(MinecraftServer server) {
        treasuryLoadFailed = false;
        Path file = treasuryFile(server);
        try {
            TreasuryService.replace(Files.isRegularFile(file) ? TreasuryLedger.fromJson(Files.readString(file)) : new TreasuryLedger());
            LOGGER.info("WorldForge treasury: loaded {} accounts and {} journal entries", TreasuryService.current().accounts().size(), TreasuryService.current().journal().size());
        } catch (Exception ex) {
            LOGGER.error("WorldForge treasury load failed; refusing to overwrite the existing ledger: {}", ex.toString());
            treasuryLoadFailed = true;
            TreasuryService.replace(new TreasuryLedger());
        }
    }

    /** Flushes the canonical ledger after addons have migrated their legacy balances during shutdown. */
    public static boolean saveTreasuryNow(MinecraftServer server) { return saveTreasury(server); }

    private static boolean saveTreasury(MinecraftServer server) {
        if (server == null || treasuryLoadFailed) {
            LOGGER.error("WorldForge treasury save skipped because the previous load failed; preserving the original file");
            return false;
        }
        Path file = treasuryFile(server);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                TreasuryService.current().writeJson(writer);
            }
            try { Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException ex) {
            LOGGER.error("WorldForge treasury save failed: {}", ex.toString());
            return false;
        }
    }

    public static Map<String, MapData> loadedMaps() {
        return MAPS;
    }

    public static Path mapsDir() {
        return FMLPaths.CONFIGDIR.get().resolve(MOD_ID).resolve("maps");
    }

    /**
     * Карта по идентификатору; загружается один раз и кэшируется.
     * "ns:имя" ищется в датапаках (data/ns/worldforge_maps/имя/), затем в config/worldforge/maps/;
     * просто "имя" ищется в config/worldforge/maps/.
     */
    public static MapData map(String id) {
        return MAPS.computeIfAbsent(id, n -> {
            try {
                MapData d = null;
                if (n.contains(":")) {
                    try {
                        d = loadFromDatapack(n);
                    } catch (IOException e) {
                        LOGGER.warn("WorldForge: в датапаках нет карты '{}' ({}), ищу в config", n, e.getMessage());
                        d = MapData.load(n.substring(n.indexOf(':') + 1), mapsDir());
                    }
                } else {
                    d = MapData.load(n, mapsDir());
                }
                EditorManager.applySaved(ServerLifecycleHooks.getCurrentServer(), d);
                LOGGER.info("WorldForge: загружена карта '{}' {}x{} px, {} блоков/px", n, d.w, d.h, d.bpp);
                return d;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("WorldForge: карта '{}' недоступна ({}), использую запасную плоскую карту. Положите файл в {}", n, e.toString(), mapsDir());
                return MapData.fallback(n.contains(":") ? n.substring(n.indexOf(':') + 1) : n);
            }
        });
    }

    private static MapData loadFromDatapack(String id) throws IOException {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) throw new IOException("сервер ещё не запущен");
        ResourceManager rm = server.getResourceManager();
        ResourceLocation base = new ResourceLocation(id);
        return MapData.load(id, file -> {
            ResourceLocation rl = new ResourceLocation(base.getNamespace(), "worldforge_maps/" + base.getPath() + "/" + file);
            Optional<Resource> r = rm.getResource(rl);
            if (r.isEmpty()) return null;
            try (InputStream in = r.get().open()) {
                return in.readAllBytes();
            }
        });
    }

    public static Registry<Biome> biomeRegistry() {
        return biomes;
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent e) {
        biomes = e.getServer().registryAccess().registryOrThrow(Registries.BIOME);
        MAPS.clear();
        try {
            Path identityFile = e.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("worldforge").resolve("world_id.txt");
            worldId = WorldIdentityStore.loadOrCreate(identityFile);
            LOGGER.info("WorldForge: world identity {}", worldId);
        } catch (IOException ex) {
            worldId = null;
            throw new IllegalStateException("WorldForge cannot start without a valid stable WorldId; refusing to fork this save's identity", ex);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent e) { loadTreasury(e.getServer()); loadActionExecution(e.getServer()); ContentCommands.load(e.getServer()); }

    @SubscribeEvent
    public void onTreasuryTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (++treasuryTicks % 6000 == 0) { saveTreasury(e.getServer()); saveActionExecution(e.getServer()); }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent e) { saveTreasury(e.getServer()); saveActionExecution(e.getServer()); ContentCommands.save(e.getServer()); }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent e) {
        EditorManager.forget();
        worldId = null;
        dev.worldforge.mod.api.WorldForgeApi.resetActionExecution();
        actionExecutionLoadFailed = false;
        biomes = null;
        MAPS.clear();
    }

    /** Игрок появляется на суше, а не посреди океана в точке (0, 0). */
    @SubscribeEvent
    public void onCreateSpawn(LevelEvent.CreateSpawnPosition e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator gen)) return;
        try {
            BlockPos p = gen.findSpawn();
            e.getSettings().setSpawn(p, 0.0F);
            e.setCanceled(true);
        } catch (RuntimeException ex) {
            LOGGER.error("WorldForge: не удалось выбрать точку спавна", ex);
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(ActionCommands.build());
        d.register(ClockService.command());
        d.register(ContentCommands.build());
        d.register(Commands.literal("worldforge").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(EditorCommands.build())
                .then(MapCommands.marker())
                .then(Commands.literal("export").executes(c -> exportAtlasSnapshot(c.getSource())))
                .then(MapCommands.web())
                .then(MapCommands.tpxz())
                .then(Commands.literal("reload").executes(c -> {
                    EditorManager.saveDirty(c.getSource().getServer());
                    EditorManager.forget();
                    MAPS.clear();
                    c.getSource().sendSuccess(() -> Component.literal("WorldForge: кэш карт очищен (новые чанки возьмут карту заново)"), true);
                    return 1;
                }))
                .then(Commands.literal("regions").executes(c -> listRegions(c.getSource())))
                .then(Commands.literal("region").executes(c -> whereAmI(c.getSource()))
                        .then(Commands.literal("tp").then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(c -> tpRegion(c.getSource(), StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("politicalmap").executes(c -> giveMap(c.getSource(), null))
                        .then(Commands.argument("level", StringArgumentType.word())
                                .executes(c -> giveMap(c.getSource(), StringArgumentType.getString(c, "level")))))
                .then(Commands.literal("title").executes(c -> titleHere(c.getSource())))
                .then(Commands.literal("realms").executes(c -> listRealms(c.getSource())))
                .then(Commands.literal("realm").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(c -> realmInfo(c.getSource(), StringArgumentType.getString(c, "name"))))));
    }

    private static int exportAtlasSnapshot(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator)) {
            source.sendFailure(Component.literal("В этом измерении нет генератора worldforge:image"));
            return 0;
        }
        try {
            Path output = WorldForgeSnapshotExporter.export(source.getServer(), level, generator.data());
            source.sendSuccess(() -> Component.literal(
                    "Atlas snapshot exported for all loaded WorldForge dimensions · " + output), true);
            return 1;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("WorldForge: Atlas snapshot export failed", error);
            source.sendFailure(Component.literal("Atlas export failed: " + error.getMessage()));
            return 0;
        }
    }

    private static MapData dataOf(CommandSourceStack s) {
        if (s.getLevel().getChunkSource().getGenerator() instanceof MapChunkGenerator g) return g.data();
        s.sendFailure(Component.literal("В этом измерении не используется генератор worldforge:image"));
        return null;
    }

    private static int info(CommandSourceStack s) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        String text = "WorldForge: карта '" + m.name + "', " + m.w + "x" + m.h + " px, " + m.bpp + " блоков/px, мир "
                + Math.round(m.w * m.bpp) + "x" + Math.round(m.h * m.bpp) + " блоков, регионов: "
                + (m.regions == null ? 0 : m.regions.list.size());
        s.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int listRegions(CommandSourceStack s) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        if (m.regions == null) {
            s.sendFailure(Component.literal("У этой карты нет регионов (в пакете нет regions.png и regions.json)"));
            return 0;
        }
        for (RegionData.Region r : m.regions.list) {
            Component line = Component.literal("● ").withStyle(Style.EMPTY.withColor(r.rgb))
                    .append(Component.literal(r.name + "  (" + r.areaPct + "% суши)"));
            s.sendSuccess(() -> line, false);
        }
        return m.regions.list.size();
    }

    private static int whereAmI(CommandSourceStack s) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        RegionData.Region r = m.regions == null ? null : m.regions.get(m.regionAt(s.getPosition().x, s.getPosition().z));
        Component msg = r == null ? Component.literal("Вы не в одном из регионов")
                : Component.literal("Вы в регионе: ").append(Component.literal(r.name).withStyle(Style.EMPTY.withColor(r.rgb)));
        s.sendSuccess(() -> msg, false);
        return r == null ? 0 : r.id;
    }

    private static int tpRegion(CommandSourceStack s, String name) throws CommandSyntaxException {
        MapData m = dataOf(s);
        if (m == null) return 0;
        RegionData.Region r = m.regions == null ? null : m.regions.find(name);
        if (r == null) {
            s.sendFailure(Component.literal("Регион '" + name + "' не найден (список: /worldforge regions)"));
            return 0;
        }
        ServerPlayer p = s.getPlayerOrException();
        ServerLevel level = s.getLevel();
        MapChunkGenerator g = (MapChunkGenerator) level.getChunkSource().getGenerator();
        BlockPos pos = g.surfaceAt(r.capX, r.capZ);
        p.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p.getYRot(), p.getXRot());
        s.sendSuccess(() -> Component.literal("Телепорт в столицу региона " + r.name), false);
        return 1;
    }

    private static void send(CommandSourceStack s, Component c) {
        s.sendSuccess(() -> c, false);
    }

    private static Component dot(int rgb, String text) {
        return Component.literal("● ").withStyle(Style.EMPTY.withColor(rgb)).append(Component.literal(text));
    }

    /** Цепочка титулов в точке игрока и государство с цепочкой сюзеренов. */
    private static int titleHere(CommandSourceStack s) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        if (m.titles == null) {
            s.sendFailure(Component.literal("В пакете карты нет иерархии титулов (команда worldforge titles)"));
            return 0;
        }
        TitleData.Title p = m.provinceAt(s.getPosition().x, s.getPosition().z);
        if (p == null) {
            s.sendFailure(Component.literal("Вы не на земле какой-либо провинции"));
            return 0;
        }
        List<TitleData.Title> chain = m.titles.chain(p);
        for (int i = chain.size() - 1; i >= 0; i--) {
            TitleData.Title t = chain.get(i);
            send(s, Component.literal(m.titles.tiers.get(t.tier).name + ": ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(t.name).withStyle(Style.EMPTY.withColor(t.rgb))));
        }
        TitleData.Realm r = m.titles.realmOf(p);
        if (r == null) {
            send(s, Component.literal("Государство: нет (ничья земля)").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        send(s, Component.literal("Государство: ").withStyle(ChatFormatting.GRAY).append(dot(r.rgb, r.name + (r.ruler.isEmpty() ? "" : " (" + r.ruler + ")"))));
        for (TitleData.Realm cur = r; cur.overlord != null; cur = cur.overlord) {
            send(s, dot(cur.overlord.rgb, "  " + m.titles.relationLabel(cur.relation) + " государства: " + cur.overlord.name));
        }
        return 1;
    }

    private static void realmTree(CommandSourceStack s, MapData m, TitleData.Realm r, int depth, int[] budget) {
        if (budget[0]-- <= 0) return;
        String prefix = depth == 0 ? "" : "  ".repeat(depth - 1) + "└ ";
        String rel = r.overlord == null ? "" : "  [" + m.titles.relationLabel(r.relation) + "]";
        send(s, Component.literal(prefix).append(dot(r.rgb, r.name + rel)));
        for (String id : r.subjectIds) {
            TitleData.Realm sub = m.titles.realms.get(id);
            if (sub != null) realmTree(s, m, sub, depth + 1, budget);
        }
    }

    /** Дерево государств: независимые наверху, подчинённые с типом отношения. */
    private static int listRealms(CommandSourceStack s) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        if (m.titles == null || m.titles.realmList().isEmpty()) {
            s.sendFailure(Component.literal("У этой карты нет государств (нет realms.json)"));
            return 0;
        }
        int[] budget = {80};
        for (TitleData.Realm r : m.titles.realmList()) if (r.overlord == null) realmTree(s, m, r, 0, budget);
        return m.titles.realmList().size();
    }

    private static int realmInfo(CommandSourceStack s, String name) {
        MapData m = dataOf(s);
        if (m == null) return 0;
        TitleData.Realm r = m.titles == null ? null : m.titles.findRealm(name);
        if (r == null) {
            s.sendFailure(Component.literal("Государство '" + name + "' не найдено (список: /worldforge realms)"));
            return 0;
        }
        send(s, dot(r.rgb, r.name + (r.ruler.isEmpty() ? "" : "  правитель: " + r.ruler)));
        if (r.overlord != null) send(s, Component.literal("  " + m.titles.relationLabel(r.relation) + " государства: " + r.overlord.name));
        StringBuilder held = new StringBuilder();
        for (String key : r.titleKeys) {
            TitleData.Title t = m.titles.byKey(key);
            if (t != null) held.append(held.length() == 0 ? "" : ", ").append(t.name);
        }
        send(s, Component.literal("  титулы: " + held + "; площадь " + r.areaBlocks / 1_000_000 + " млн блоков²"));
        for (String id : r.subjectIds) {
            TitleData.Realm sub = m.titles.realms.get(id);
            if (sub != null) send(s, dot(sub.rgb, "  подчинённый: " + sub.name + " [" + m.titles.relationLabel(sub.relation) + "]"));
        }
        return 1;
    }

    /** Карта-предмет с политической картой (128x128, видна и без мода на клиенте). level: baron.../realms; по умолчанию государства. */
    private static int giveMap(CommandSourceStack s, String level) throws CommandSyntaxException {
        MapData m = dataOf(s);
        if (m == null) return 0;
        EditorManager.refreshMaps(m);
        byte[] bytes;
        String label;
        if (level == null) {
            bytes = m.mapItems.containsKey("realms") ? m.mapItems.get("realms") : m.regionMap;
            label = m.mapItems.containsKey("realms") ? "государства" : "регионы";
        } else {
            bytes = m.mapItems.get(level);
            label = level;
            if (bytes == null) {
                s.sendFailure(Component.literal("Нет карты '" + level + "'. Доступно: " + m.mapItems.keySet()));
                return 0;
            }
        }
        if (bytes == null) {
            s.sendFailure(Component.literal("В пакете карты нет карты-предмета (запусти worldforge regions или titles)"));
            return 0;
        }
        ServerPlayer p = s.getPlayerOrException();
        ServerLevel level2 = s.getLevel();
        ItemStack stack = MapItem.create(level2, 0, 0, (byte) 4, false, false);
        MapItemSavedData saved = MapItem.getSavedData(stack, level2);
        if (saved == null) {
            s.sendFailure(Component.literal("Не удалось создать карту"));
            return 0;
        }
        System.arraycopy(bytes, 0, saved.colors, 0, 16384);
        saved.setDirty();
        MapItem.lockMap(level2, stack);                      // замок: карта не обновляется от местности (создаёт запертую копию данных)
        stack.setHoverName(Component.literal("Политическая карта (" + label + "): " + m.name));
        if (!p.getInventory().add(stack)) p.drop(stack, false);
        s.sendSuccess(() -> Component.literal("Выдана политическая карта"), false);
        return 1;
    }
}
