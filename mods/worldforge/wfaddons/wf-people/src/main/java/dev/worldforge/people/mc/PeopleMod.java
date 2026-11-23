package dev.worldforge.people.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.Json;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import dev.worldforge.people.CharacterInventoryTransactions;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.storage.CharacterWal;
import dev.worldforge.people.storage.CharacterWalWriter;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.Courts;
import dev.worldforge.people.TerritorialIdentity;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.PopulationWorldBaseline;
import dev.worldforge.people.Role;
import dev.worldforge.people.RoleDefs;
import dev.worldforge.people.bridge.DialogueBridge;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/** Склейка wf-people с Minecraft: загрузка ролей и имён из датапаков, реестр людей по картам, сохранение, сущность, спавнер, команды. */
@Mod(PeopleMod.ID)
public class PeopleMod {
    public static final String ID = "wf_people";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static volatile int YEAR = 300;
    public static final int SAVE_EVERY = 6000, SPAWN_EVERY = 100;
    public static final String LAW = "primogeniture";

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final RegistryObject<EntityType<WFNpc>> NPC = ENTITIES.register("npc",
            () -> EntityType.Builder.of(WFNpc::new, MobCategory.CREATURE).sized(0.6F, 1.95F).clientTrackingRange(10).build(ID + ":npc"));

    public static volatile RoleDefs ROLES = new RoleDefs();
    public static volatile dev.worldforge.people.ProfessionDefs PROFESSIONS = new dev.worldforge.people.ProfessionDefs();
    /** Исходные JSON ролей и событий: уходят клиенту, чтобы окно двора собрало те же определения. */
    public static volatile java.util.List<String> ROLES_RAW = java.util.List.of();
    public static volatile NameBank NAMES = new NameBank();
    private static final Map<MapData, CharacterRegistry> REGS = new ConcurrentHashMap<>();
    /** Durable per-partition incremental journal; snapshots remain the compatibility checkpoint format. */
    private static final Map<CharacterRegistry, CharacterWal> CHARACTER_WALS = new java.util.IdentityHashMap<>();
    /** Bounded asynchronous writers for explicit bulk workloads; transactional writes remain synchronous. */
    private static final Map<CharacterRegistry, CharacterWalWriter> CHARACTER_WAL_WRITERS = new java.util.IdentityHashMap<>();
    /** Canonical population partition for ordinary Minecraft dimensions without an image-map source. */
    private static final CharacterRegistry WORLD_REGISTRY = new CharacterRegistry();
    private static final String WORLD_PARTITION_ID = "__world__";
    /** Each non-map Minecraft dimension owns a separate local Character partition under the shared universe authority. */
    private static final Map<String, CharacterRegistry> DIMENSION_REGISTRIES = new ConcurrentHashMap<>();
    private static final CharacterAuthority AUTHORITY = new CharacterAuthority();
    private static final CharacterRegistry PLAYER_REGISTRY = new CharacterRegistry();
    private static final Map<String, Character> PLAYER_CHARACTERS = new ConcurrentHashMap<>();
    private static volatile boolean playerRegistryAvailable = true;
    /** True only after all world Character partitions are indexed and pending transfers recover cleanly. */
    private static volatile boolean identityPartitionsReady;
    public static boolean identityPartitionsReady() { return identityPartitionsReady; }
    public static CharacterAuthority authority() { return AUTHORITY; }
    public static CharacterRegistry playerRegistry() { return PLAYER_REGISTRY; }
    /** Canonical people partition for vanilla-generated dimensions without an image-map overlay. */
    public static CharacterRegistry worldRegistry() { return WORLD_REGISTRY; }
    public static boolean playerRegistryAvailable() { return playerRegistryAvailable; }
    /** Corrupt saves fail closed: never regenerate courts over unreadable identity data. */
    private static final Set<MapData> INVALID_REGS = ConcurrentHashMap.newKeySet();
    public static final int TICKS_PER_DAY = 1200;
    private static final Map<UUID, Set<String>> FLAGS = new ConcurrentHashMap<>();
    private static long ticks;
    private static volatile long populationProcessedDay = Long.MIN_VALUE;
    private static volatile long populationBaselineDay = Long.MIN_VALUE;
    private static volatile long populationRegistryGeneration;
    private static volatile long populationFallbackGeneration = Long.MIN_VALUE;
    /** One shared per-tick budget across every canonical population partition. */
    private static final int POPULATION_FALLBACK_BUDGET_PER_TICK = 256;
    private static int populationFallbackRegistryCursor;
    private static long populationFallbackCursorGeneration = Long.MIN_VALUE;
    /** Stable partition snapshot; rebuilt only when the canonical registry set changes. */
    private static volatile List<CharacterRegistry> populationFallbackRegistrySnapshot = List.of();
    private static volatile long populationFallbackSnapshotGeneration = Long.MIN_VALUE;

    public PeopleMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        bus.addListener(PeopleMod::onAttributes);
        bus.addListener(PeopleMod::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PlayerInventoryBridge());
        MinecraftForge.EVENT_BUS.register(new CharacterInventoryEvents());
        MinecraftForge.EVENT_BUS.register(new RuntimeStressLab());
    }

    private static void onAttributes(EntityAttributeCreationEvent e) {
        e.put(NPC.get(), WFNpc.attributes().build());
    }

    private static void onCommonSetup(FMLCommonSetupEvent e) {
        if (dialogueLoaded()) e.enqueueWork(DialogueHook::register);
    }

    /** Персонаж по id в любой загруженной карте (аддоны поверх людей ищут собеседников по нему). */
    public static Character findNpc(String id) {
        if (id == null || id.isBlank()) return null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) registry(map);
        Character global = AUTHORITY.getGlobal(id);
        return global != null ? global : AUTHORITY.getLegacy(id);
    }

    public static boolean dialogueLoaded() {
        return ModList.get().isLoaded("wf_dialogue");
    }

    // ------------------------------------------------------------------ данные

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) {
        NpcSpawner.clearRuntimeState();
        closeCharacterWals();
        identityPartitionsReady = false;
        REGS.clear();
        DIMENSION_REGISTRIES.clear();
        WORLD_REGISTRY.fromJson("{\"format\":\"wf-people/3\",\"schema_version\":3,\"next\":1,\"characters\":[]}");
        PLAYER_CHARACTERS.clear();
        INVALID_REGS.clear();
        if (WorldForgeMod.worldId() != null) AUTHORITY.beginWorld(WorldForgeMod.worldId());
        else AUTHORITY.clear();
        FLAGS.clear();
        ticks = 0;
        populationProcessedDay = Long.MIN_VALUE;
        populationBaselineDay = Long.MIN_VALUE;
        populationRegistryGeneration = 0L;
        populationFallbackGeneration = Long.MIN_VALUE;
        RoleDefs roles = new RoleDefs();
        dev.worldforge.people.ProfessionDefs professions = new dev.worldforge.people.ProfessionDefs();
        NameBank names = new NameBank();
        java.util.List<String> rawRoles = new java.util.ArrayList<>();
        load(e.getServer(), "wf_roles", j -> { roles.add(j); rawRoles.add(j); });
        load(e.getServer(), "wf_professions", professions::add);
        ROLES_RAW = rawRoles;
        load(e.getServer(), "wf_names", names::add);
        names.validate();
        ROLES = roles;
        PROFESSIONS = professions;
        NAMES = names;
        LOGGER.info("wf_people: загружено ролей: {}, профессий: {}", roles.all().size(), professions.all().size());
    }

    private interface Sink {
        void accept(String json) throws Exception;
    }

    private static void load(MinecraftServer server, String folder, Sink sink) {
        Map<ResourceLocation, Resource> found = new TreeMap<>(server.getResourceManager().listResources(folder, rl -> rl.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) {
                sink.accept(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ex) {
                LOGGER.error("wf_people: ошибка в {}: {}", r.getKey(), ex.toString());
            }
        }
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        YEAR = WorldForgeApi.clock().year();
        if (WorldForgeMod.worldId() != null) AUTHORITY.beginWorld(WorldForgeMod.worldId());
        MinecraftServer server = e.getServer();
        try {
            recoverRegistryJournal(server);
            String worldKey = WorldForgeMod.worldId() == null
                    ? server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString()
                    : WorldForgeMod.worldId().toString();
            Path baselineFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("worldforge").resolve("wf_population_baseline.json");
            populationBaselineDay = PopulationWorldBaseline.loadOrInitialize(
                    baselineFile, worldKey, WorldForgeApi.clock().dayNumber());
            populationProcessedDay = populationBaselineDay;
        } catch (Exception ex) {
            throw new IllegalStateException("wf_people cannot start: durable Population baseline is unreadable", ex);
        }
        playerRegistryAvailable = true;
        try {
            Path worldFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("worldforge").resolve("wf_people_world.json");
            if (Files.exists(worldFile)) {
                WorldId world = WorldForgeMod.worldId();
                if (world == null) throw new IllegalStateException("WorldId is unavailable for world Character identity migration");
                loadCharacterSnapshot(worldFile, WORLD_REGISTRY, world + "|" + WORLD_PARTITION_ID);
            }
            if (WorldForgeMod.worldId() == null) throw new IllegalStateException("WorldId is unavailable for world Character identity partition");
            openCharacterWal(server, WORLD_REGISTRY, "world", WorldForgeMod.worldId() + "|" + WORLD_PARTITION_ID);
            AUTHORITY.registerMap(WORLD_PARTITION_ID, WORLD_REGISTRY);
            // The Overworld keeps its legacy partition/file for save compatibility. Every
            // additional non-image dimension gets an independent durable Character partition.
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getChunkSource().getGenerator() instanceof dev.worldforge.mod.MapChunkGenerator) continue;
                String dimension = level.dimension().location().toString();
                if ("minecraft:overworld".equals(dimension)) continue;
                CharacterRegistry dimensionRegistry = new CharacterRegistry();
                Path dimensionFile = dimensionRegistryFile(server, dimension);
                if (Files.exists(dimensionFile))
                    loadCharacterSnapshot(dimensionFile, dimensionRegistry, WorldForgeMod.worldId() + "|dimension|" + dimension);
                openCharacterWal(server, dimensionRegistry, "dimension|" + dimension,
                        WorldForgeMod.worldId() + "|dimension|" + dimension);
                String partitionId = dimensionPartitionId(dimension);
                AUTHORITY.registerMap(partitionId, dimensionRegistry);
                DIMENSION_REGISTRIES.put(dimension, dimensionRegistry);
                LOGGER.info("wf_people: loaded dimension Character partition dimension={} characters={}",
                        dimension, dimensionRegistry.size());
            }
            Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("worldforge").resolve("wf_players.json");
            if (Files.exists(playerFile)) {
                WorldId world = WorldForgeMod.worldId();
                if (world == null) throw new IllegalStateException("WorldId is unavailable for player identity migration");
                loadCharacterSnapshot(playerFile, PLAYER_REGISTRY, world + "|__players__");
            }
            openCharacterWal(server, PLAYER_REGISTRY, "players", WorldForgeMod.worldId() + "|__players__");
            AUTHORITY.registerMap("__players__", PLAYER_REGISTRY);
            indexPlayerCharacters(PLAYER_REGISTRY);
        } catch (Exception ex) {
            playerRegistryAvailable = false;
            LOGGER.error("wf_people: player Character registry failed to load; player identity creation and overwrite are disabled: {}", ex.toString());
        }
        for (MapData m : WorldForgeMod.loadedMaps().values()) registry(m);
        reconcileIdentityPartitions(server);
        populationProcessedDay = WorldForgeApi.clock().dayNumber();
        populationRegistryGeneration = Math.incrementExact(populationRegistryGeneration);
    }

    private static boolean allIdentityPartitionsLoaded() {
        if (!playerRegistryAvailable || AUTHORITY.registry("__players__") != PLAYER_REGISTRY) return false;
        int expected = 2 + DIMENSION_REGISTRIES.size(); // Player, legacy Overworld and every additional non-map dimension.
        for (MapData map : WorldForgeMod.loadedMaps().values())
            if (map != null) expected++;
        try {
            return AUTHORITY.mapCount() == expected;
        } catch (RuntimeException ex) {
            LOGGER.error("wf_people: could not validate Character partition set: {}", ex.toString());
            return false;
        }
    }

    /** Recover pending transactions only when every known Character partition is indexed. */
    private static boolean reconcileIdentityPartitions(MinecraftServer server) {
        identityPartitionsReady = false;
        if (server == null || !allIdentityPartitionsLoaded()) {
            LOGGER.error("wf_people: shared inventory is locked because not all Character partitions are available");
            return false;
        }
        try {
            if (!CharacterInventoryTransactions.reconcile(AUTHORITY,
                    person -> persistCharacter(person, server))) {
                LOGGER.error("wf_people: pending inventory transfer recovery is blocked; source ownership was not guessed");
                return false;
            }
            CharacterInventory.reconcileEstates(AUTHORITY);
            identityPartitionsReady = true;
            return true;
        } catch (RuntimeException ex) {
            LOGGER.error("wf_people: Character recovery failed; shared inventory remains locked: {}", ex.toString(), ex);
            return false;
        }
    }

    /** Stream legacy snapshots directly from disk; never allocate a whole-file String. */
    private static void loadCharacterSnapshot(Path file, CharacterRegistry registry, String identityNamespace)
            throws java.io.IOException {
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            registry.fromJson(reader, identityNamespace);
        }
    }

    /** Реестр людей карты: читается из папки мира, при первом обращении достраивается дворами (Courts.ensure). Null, если у карты нет титулов. */
    public static CharacterRegistry registry(MapData m) {
        if (m == null || INVALID_REGS.contains(m)) return null;
        CharacterRegistry have = REGS.get(m);
        if (have != null) return have;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        CharacterRegistry reg = new CharacterRegistry();
        if (server != null) {
            Path f = EditorManager.overlayDir(server, m).resolve("wf_people.json");
            try {
                if (Files.exists(f)) {
                    WorldId world = WorldForgeMod.worldId();
                    if (world == null) throw new IllegalStateException("WorldId is unavailable; refusing to migrate Character identities");
                    loadCharacterSnapshot(f, reg, world + "|" + m.name);
                }
            } catch (Exception ex) {
                identityPartitionsReady = false;
                LOGGER.error("wf_people: сохранение {} повреждено; генерация персонажей заблокирована во избежание потери данных: {}", f, ex.toString());
                INVALID_REGS.add(m);
                return null;
            }
        }
        int before = reg.size();
        try {
            // The autonomous campaign runs in a dedicated, disposable server process.
            // It must begin from geography only; ordinary Minecraft launches retain the
            // normal Courts.ensure bootstrap because this property is absent by default.
            boolean emptyCampaignBootstrap = Boolean.getBoolean("worldforge.acceptance.empty-bootstrap");
            TitleEditor identityEditor = m.titles == null ? null : EditorManager.editorFor(m);
            if (m.titles != null && !emptyCampaignBootstrap && identityEditor != null) {
                TerritorialIdentity.DemographicResult demographics =
                        TerritorialIdentity.ensureDemographics(m.name, m.titles, identityEditor, seed(m));
                if (demographics.realmsClassified() > 0)
                    LOGGER.info("wf_people: origin-backed demographics map='{}' realms={} rootRaces={}",
                            m.name, demographics.realmsClassified(), demographics.distinctRaces());
            }
            int generatedTierRoles = m.titles == null || emptyCampaignBootstrap
                    ? 0 : ROLES.ensureTerritorialRulers(m.titles);
            int created = m.titles == null || emptyCampaignBootstrap ? 0
                    : Courts.ensure(m.titles, identityEditor, reg, ROLES, NAMES, seed(m), YEAR);
            if (generatedTierRoles > 0)
                LOGGER.info("wf_people: generated {} data-driven ruler roles for all {} map title tiers",
                        generatedTierRoles, m.titles.tiers.size());
            if (m.titles != null && !emptyCampaignBootstrap && identityEditor != null) {
                TerritorialIdentity.Result identity = TerritorialIdentity.ensure(
                        m.name, m.titles, identityEditor, reg, NAMES, seed(m));
                LOGGER.info("wf_people: identity map='{}' realmsNamed={} titlesNamed={} classifications={} rulerLinks={}",
                        m.name, identity.realmsNamed(), identity.titlesNamed(),
                        identity.titlesClassified(), identity.rulersLinked());
            }
            if (emptyCampaignBootstrap && before == 0)
                LOGGER.info("wf_people: campaign empty bootstrap enabled for map '{}'; authored court seeding skipped", m.name);
            LOGGER.info("wf_people: карта '{}': было {} людей, создано {}, теперь {}", m.name, before, created, reg.size());
        } catch (Exception ex) {
            LOGGER.error("wf_people: Courts.ensure для карты '{}' не удался: {}", m.name, ex.toString());
        }
        if (server != null) {
            try {
                WorldId world = WorldForgeMod.worldId();
                if (world == null) throw new IllegalStateException("WorldId is unavailable for Character WAL");
                openCharacterWal(server, reg, "map|" + m.name, world + "|" + m.name);
            } catch (Exception walFailure) {
                identityPartitionsReady = false;
                LOGGER.error("wf_people: map Character WAL recovery failed for '{}'; map is disabled", m.name, walFailure);
                INVALID_REGS.add(m);
                return null;
            }
        }
        CharacterRegistry prev = REGS.putIfAbsent(m, reg);
        CharacterRegistry selected = prev != null ? prev : reg;
        if (prev == null) populationRegistryGeneration = Math.incrementExact(populationRegistryGeneration);
        if (WorldForgeMod.worldId() != null) {
            try {
                AUTHORITY.registerMap(m.name, selected);
                indexPlayerCharacters(selected);
                // During ServerStarted, map registries arrive one by one. Do not reconcile
                // markers until the expected complete set (including __players__) is indexed.
                if (allIdentityPartitionsLoaded()) reconcileIdentityPartitions(server);
                else identityPartitionsReady = false;
            } catch (RuntimeException ex) {
                identityPartitionsReady = false;
                // A map rejected by global identity validation must be removed from the local cache too.
                // It must never be used as a fallback source for ambiguous or foreign Characters.
                if (REGS.remove(m, selected))
                    populationRegistryGeneration = Math.incrementExact(populationRegistryGeneration);
                INVALID_REGS.add(m);
                LOGGER.error("wf_people: global Character authority rejected map '{}'; map registry is disabled: {}",
                        m.name, ex.toString());
                return null;
            }
        }
        return selected;
    }

    public static long seed(MapData m) {
        return 0x57464F52L ^ ((long) m.name.hashCode() * 0x9E3779B97F4A7C15L);
    }

    /** Read-only snapshot for optional population/progression bridges. */
    public static Map<MapData, CharacterRegistry> registries() { return java.util.Collections.unmodifiableMap(new HashMap<>(REGS)); }
    public static Map<String, CharacterRegistry> dimensionRegistries() { return java.util.Collections.unmodifiableMap(new TreeMap<>(DIMENSION_REGISTRIES)); }
    /** Shared immutable partition view for bounded cross-addon schedulers. */
    public static List<CharacterRegistry> populationRegistriesSnapshot() {
        return populationFallbackRegistries(populationRegistryGeneration);
    }

    /** Resolve the canonical player Character by the stable Minecraft account UUID. */
    public static Character playerCharacter(UUID playerUuid) {
        return playerUuid == null ? null : PLAYER_CHARACTERS.get(playerUuid.toString());
    }

    /** Genesis binds the account only after its global Character identity has been validated. */
    public static void bindPlayerCharacter(UUID playerUuid, Character character) {
        Objects.requireNonNull(playerUuid); Objects.requireNonNull(character);
        String account = playerUuid.toString();
        Object saved = character.props.get("genesis.player_uuid");
        if (!account.equals(saved)) throw new IllegalStateException("Player account and Character binding disagree");
        Character previous = PLAYER_CHARACTERS.putIfAbsent(account, character);
        if (previous != null && previous != character)
            throw new IllegalStateException("Minecraft player UUID is already bound to another Character");
    }

    private static void indexPlayerCharacters(CharacterRegistry registry) {
        for (Character character : registry.all()) {
            Object raw = character.props.get("genesis.player_uuid");
            if (!(raw instanceof String account) || account.isBlank()) continue;
            try {
                String canonical = UUID.fromString(account).toString();
                Character previous = PLAYER_CHARACTERS.putIfAbsent(canonical, character);
                if (previous != null && previous != character)
                    throw new IllegalStateException("Minecraft player UUID is bound to multiple Characters: " + canonical);
            } catch (IllegalArgumentException ex) {
                LOGGER.error("wf_people: invalid player UUID on Character {}: {}", character.globalId, account);
            }
        }
    }

    public static CharacterRegistry registryFor(ServerLevel level) {
        if (level == null) return null;
        MapData map = EditorManager.dataFor(level);
        if (level.getChunkSource().getGenerator() instanceof dev.worldforge.mod.MapChunkGenerator)
            return map == null ? null : registry(map);
        String dimension = level.dimension().location().toString();
        if ("minecraft:overworld".equals(dimension)) return WORLD_REGISTRY;
        return DIMENSION_REGISTRIES.get(dimension);
    }

    /** Registry lookup without allocation; a non-map dimension never falls back to another dimension's people. */
    public static CharacterRegistry peek(ServerLevel level) {
        if (level == null) return null;
        MapData map = EditorManager.dataFor(level);
        if (level.getChunkSource().getGenerator() instanceof dev.worldforge.mod.MapChunkGenerator)
            return map == null ? null : REGS.get(map);
        String dimension = level.dimension().location().toString();
        if ("minecraft:overworld".equals(dimension)) return WORLD_REGISTRY;
        return DIMENSION_REGISTRIES.get(dimension);
    }

    private static String dimensionPartitionId(String dimension) {
        if (dimension == null || dimension.isBlank() || dimension.length() > 256)
            throw new IllegalArgumentException("Invalid Character dimension partition key");
        return "__dimension__:" + dimension;
    }

    private static Path dimensionRegistryFile(MinecraftServer server, String dimension) {
        String token = UUID.nameUUIDFromBytes(dimension.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("worldforge").resolve("wf_people_dimensions").resolve(token + ".json");
    }

    private static Path characterWalDirectory(MinecraftServer server, String partition) {
        String token = UUID.nameUUIDFromBytes(partition.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("worldforge").resolve("people-wal").resolve(token);
    }

    private static synchronized void openCharacterWal(MinecraftServer server, CharacterRegistry registry,
                                                        String partition, String identityNamespace) throws Exception {
        if (CHARACTER_WALS.containsKey(registry)) return;
        CharacterWal wal = new CharacterWal(characterWalDirectory(server, partition), 16);
        try {
            long[] replayed = {0L};
            wal.replayEach(record -> {
                // The registry parser validates schema, payload identity and all Character invariants
                // in one pass. Avoid parsing the same potentially large record twice during recovery.
                registry.applyJsonRecord(record.payloadUtf8(), identityNamespace, record.globalId());
                replayed[0]++;
            });
            CHARACTER_WALS.put(registry, wal);
            String writerToken = UUID.nameUUIDFromBytes(partition.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
            CHARACTER_WAL_WRITERS.put(registry, new CharacterWalWriter(wal, 8192, "wf-character-wal-" + writerToken));
            LOGGER.info("wf_people: recovered Character WAL partition={} records={} characters={}",
                    partition, replayed[0], registry.size());
        } catch (Exception failure) {
            try { wal.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static synchronized void checkpointCharacterWal(CharacterRegistry registry) throws java.io.IOException {
        CharacterWal wal = CHARACTER_WALS.get(registry);
        if (wal == null) return;
        CharacterWalWriter writer = CHARACTER_WAL_WRITERS.get(registry);
        // A forced append batch is already durable. Defer checkpointing while the
        // asynchronous writer has queued or in-flight records; never stall the tick.
        if (writer != null && writer.pending() > 0) return;
        writer = CHARACTER_WAL_WRITERS.remove(registry);
        if (writer != null) writer.close();
        try {
            wal.checkpoint();
        } finally {
            CHARACTER_WAL_WRITERS.put(registry, new CharacterWalWriter(wal, 8192,
                    "wf-character-wal-checkpoint-" + Integer.toHexString(System.identityHashCode(registry))));
        }
    }

    private static synchronized void closeCharacterWals() {
        for (CharacterWalWriter writer : CHARACTER_WAL_WRITERS.values()) {
            try { writer.close(); }
            catch (Exception failure) { LOGGER.error("wf_people: failed to drain Character WAL writer", failure); }
        }
        CHARACTER_WAL_WRITERS.clear();
        for (CharacterWal wal : CHARACTER_WALS.values()) {
            try { wal.close(); }
            catch (Exception failure) { LOGGER.error("wf_people: failed to close Character WAL cleanly", failure); }
        }
        CHARACTER_WALS.clear();
    }

    @FunctionalInterface
    private interface JsonWriteAction { void write(java.io.Writer writer) throws java.io.IOException; }

    private static void writeAtomic(Path file, String content) throws java.io.IOException {
        writeAtomic(file, writer -> writer.write(content));
    }

    private static void writeAtomic(Path file, CharacterRegistry registry) throws java.io.IOException {
        writeAtomic(file, registry::writeJson);
    }

    private static void writeAtomic(Path file, JsonWriteAction action) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            // Flush encoded characters and force file contents before publishing the new snapshot.
            // A successful rename must not expose a file whose data was only in the OS page cache.
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                java.io.Writer writer = java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8);
                action.write(writer);
                writer.flush();
                channel.force(true);
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void saveAll(MinecraftServer server) {
        persistAllNow(server);
    }

    /**
     * Coordinated identity checkpoint entry point for wf-realms.
     * Online player inventories are reconciled before every canonical Character partition is committed.
     */
    public static boolean persistAllNow(MinecraftServer server) {
        if (server == null) return false;
        PlayerInventoryBridge.syncOnline(server);
        return persistAllRegistries(server);
    }

    /** Persist only the registry that owns this Character; unrelated map failures must not block a safe death snapshot. */
    private record EstateWalTransfer(Character deceased, Character heir, String transferId) { }

    /** Finds only an active, not-yet-WAL-committed succession involving this Character. */
    private static EstateWalTransfer estateWalTransfer(Character character) {
        Object pending = character.props.get("estate.transfer_pending");
        Object receipt = character.props.get("estate.transfer_receipt");
        Object recipient = character.props.get("estate.recipient_global_id");
        if ((pending instanceof String || receipt instanceof String) && recipient instanceof String recipientId) {
            String transferId = pending instanceof String value ? value : (String) receipt;
            if (!transferId.equals(character.props.get("estate.transfer_wal_committed"))) {
                Character heir = AUTHORITY.getGlobal(recipientId);
                if (heir != null && character.globalId.equals(heir.props.get("estate.incoming." + transferId)))
                    return new EstateWalTransfer(character, heir, transferId);
            }
        }
        for (Map.Entry<String, Object> property : character.props.entrySet()) {
            if (!property.getKey().startsWith("estate.incoming.")
                    || !(property.getValue() instanceof String sourceGlobalId)) continue;
            String transferId = property.getKey().substring("estate.incoming.".length());
            Character deceased = AUTHORITY.getGlobal(sourceGlobalId);
            if (deceased == null || !character.globalId.equals(deceased.props.get("estate.recipient_global_id"))
                    || !transferId.equals(deceased.props.getOrDefault("estate.transfer_pending",
                            deceased.props.get("estate.transfer_receipt")))
                    || transferId.equals(deceased.props.get("estate.transfer_wal_committed"))) continue;
            return new EstateWalTransfer(deceased, character, transferId);
        }
        return null;
    }

    /**
     * Succession is a two-partition transaction, not a reason to serialize every population
     * registry. Force the heir's commit marker and inherited state first, then the emptied
     * deceased record, and finally its durable receipt. WAL replay plus reconcileEstates()
     * completes a crash between any two appends without duplicating the estate.
     */
    private static boolean persistEstateWalTransfer(EstateWalTransfer transfer) {
        Character deceased = transfer.deceased();
        Character heir = transfer.heir();
        CharacterRegistry sourceRegistry = AUTHORITY.registryForGlobal(deceased.globalId);
        CharacterRegistry heirRegistry = AUTHORITY.registryForGlobal(heir.globalId);
        CharacterWal sourceWal = sourceRegistry == null ? null : CHARACTER_WALS.get(sourceRegistry);
        CharacterWal heirWal = heirRegistry == null ? null : CHARACTER_WALS.get(heirRegistry);
        if (sourceWal == null || heirWal == null) return false;
        Object previousPending = deceased.props.get("estate.transfer_pending");
        Object previousReceipt = deceased.props.get("estate.transfer_receipt");
        try {
            heirWal.append(heir.globalId, heirRegistry.toJsonRecord(heir).getBytes(StandardCharsets.UTF_8));
            byte[] pendingSource = sourceRegistry.toJsonRecord(deceased).getBytes(StandardCharsets.UTF_8);
            deceased.props.put("estate.transfer_receipt", transfer.transferId());
            deceased.props.remove("estate.transfer_pending");
            deceased.props.put("estate.transfer_wal_committed", transfer.transferId());
            byte[] committedSource = sourceRegistry.toJsonRecord(deceased).getBytes(StandardCharsets.UTF_8);
            // Keep both ordered recovery states, but force them together in one source-WAL batch.
            // A surviving first frame retains the pending marker; a surviving second frame
            // additionally records the durable receipt and completes the transfer on replay.
            sourceWal.appendBatch(List.of(
                    new CharacterWal.Write(deceased.globalId, pendingSource),
                    new CharacterWal.Write(deceased.globalId, committedSource)));
            return true;
        } catch (Exception failure) {
            // If the recipient frame reached stable storage, leave a pending source marker:
            // startup reconciliation will clear it from the already committed heir record.
            if (heirWal != null) deceased.props.put("estate.transfer_pending", transfer.transferId());
            if (previousReceipt instanceof String value) deceased.props.put("estate.transfer_receipt", value);
            else deceased.props.remove("estate.transfer_receipt");
            deceased.props.remove("estate.transfer_wal_committed");
            LOGGER.error("wf_people: estate WAL transaction failed deceased={} heir={}; recovery marker retained",
                    deceased.globalId, heir.globalId, failure);
            return false;
        }
    }

    /**
     * Enqueues a canonical Character record on its partition's bounded WAL writer.
     * The returned future completes only after appendBatch has forced the frame to stable storage.
     * Queue saturation is explicit backpressure; callers must not treat enqueue as durable.
     */
    public static boolean enqueueStressCharacterWrite(Character character, MinecraftServer server,
                                                        CompletableFuture<CharacterWal.Record> durable) {
        if (character == null || server == null || durable == null) return false;
        CharacterRegistry owner = AUTHORITY.registryForGlobal(character.globalId);
        if (owner == null || estateWalTransfer(character) != null
                || CharacterInventory.hasPendingTransfer(character)) return false;
        CharacterWalWriter writer = CHARACTER_WAL_WRITERS.get(owner);
        if (writer == null) return false;
        return writer.submit(character.globalId,
                owner.toJsonRecord(character).getBytes(StandardCharsets.UTF_8), durable);
    }

    public static int characterWalQueueDepth(CharacterRegistry registry) {
        CharacterWalWriter writer = CHARACTER_WAL_WRITERS.get(registry);
        return writer == null ? -1 : writer.pending();
    }

    public static boolean persistCharacter(Character character, MinecraftServer server) {
        if (character == null || server == null) return false;
        CharacterRegistry owner = AUTHORITY.registryForGlobal(character.globalId);
        if (owner == null) return false;
        EstateWalTransfer estateTransfer = estateWalTransfer(character);
        if (estateTransfer != null) return persistEstateWalTransfer(estateTransfer);
        // Inventory transfers still use the existing cross-registry commit path. Estate
        // succession is handled above with endpoint-only WAL records, never a world snapshot.
        if (CharacterInventory.hasPendingTransfer(character)) return persistAllRegistries(server);
        // A durable per-character record is enough for routine transactional writes.
        // Full registry snapshots are reserved for bounded checkpoints and legacy compatibility.
        CharacterWal wal = CHARACTER_WALS.get(owner);
        if (wal != null) {
            try {
                // Transactional identity mutations remain synchronous: returning true means
                // this exact Character frame has been forced to stable storage. Do not replace
                // this with fire-and-forget writer.submit(); bounded async writes are reserved
                // for explicit bulk workloads that expose a durable completion future.
                wal.append(character.globalId, owner.toJsonRecord(character).getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception walFailure) {
                LOGGER.error("wf_people: Character WAL append failed for {}; identity writes are blocked", character.globalId, walFailure);
                if (owner == PLAYER_REGISTRY) playerRegistryAvailable = false;
                return false;
            }
        }
        if (owner == PLAYER_REGISTRY) {
            if (!playerRegistryAvailable) return false;
            try {
                Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("worldforge").resolve("wf_players.json");
                writeAtomic(playerFile, PLAYER_REGISTRY);
                return true;
            } catch (Exception ex) {
                playerRegistryAvailable = false;
                LOGGER.error("wf_people: failed to persist player Character {}: {}", character.globalId, ex.toString());
                return false;
            }
        }
        if (owner == WORLD_REGISTRY) {
            try {
                Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("worldforge").resolve("wf_people_world.json");
                writeAtomic(file, WORLD_REGISTRY);
                return true;
            } catch (Exception ex) {
                LOGGER.error("wf_people: failed to persist ordinary-world Character {}: {}", character.globalId, ex.toString());
                return false;
            }
        }
        for (Map.Entry<String, CharacterRegistry> entry : DIMENSION_REGISTRIES.entrySet()) {
            if (entry.getValue() != owner) continue;
            try {
                writeAtomic(dimensionRegistryFile(server, entry.getKey()), owner);
                return true;
            } catch (Exception ex) {
                LOGGER.error("wf_people: failed to persist Character {} in dimension {}: {}",
                        character.globalId, entry.getKey(), ex.toString());
                return false;
            }
        }
        for (Map.Entry<MapData, CharacterRegistry> entry : REGS.entrySet()) if (entry.getValue() == owner) {
            try {
                Path file = EditorManager.overlayDir(server, entry.getKey()).resolve("wf_people.json");
                writeAtomic(file, owner);
                return true;
            } catch (Exception ex) {
                LOGGER.error("wf_people: failed to persist Character {} in map {}: {}",
                        character.globalId, entry.getKey().name, ex.toString());
                return false;
            }
        }
        return false;
    }

    /** Persist canonical Character ledgers after a changed player inventory snapshot. */
    /**
     * Replays a world-scoped write-ahead journal before any Character partition is loaded.
     * This makes cross-registry succession durable even when estate dependencies form a cycle.
     */
    private static void recoverRegistryJournal(MinecraftServer server) throws Exception {
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        Path journalFile = root.resolve("worldforge").resolve("wf_people_registry_journal.json");
        if (!Files.exists(journalFile)) return;
        Object parsed = Json.parse(Files.readString(journalFile));
        if (!(parsed instanceof Map<?, ?> journal)
                || !"wf-people-registry-journal/1".equals(journal.get("format"))
                || !(journal.get("files") instanceof List<?> files))
            throw new IllegalStateException("Character registry write-ahead journal is malformed");
        for (Object item : files) {
            if (!(item instanceof Map<?, ?> record)
                    || !(record.get("path") instanceof String relative)
                    || !(record.get("content") instanceof String content))
                throw new IllegalStateException("Character registry write-ahead journal entry is malformed");
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(journalFile))
                throw new IllegalStateException("Character registry journal contains an unsafe target path");
            writeAtomic(target, content);
        }
        Files.deleteIfExists(journalFile);
        LOGGER.warn("wf_people: recovered interrupted cross-registry Character save from write-ahead journal");
    }

    private static boolean persistRegistriesWithJournal(MinecraftServer server,
            List<CharacterRegistry> registries,
            java.util.IdentityHashMap<CharacterRegistry, MapData> mapOwners,
            java.util.IdentityHashMap<CharacterRegistry, String> dimensionOwners) {
        try {
            Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
            List<Map<String, Object>> files = new ArrayList<>();
            java.util.Set<CharacterRegistry> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (CharacterRegistry registry : registries) {
                if (!seen.add(registry)) continue;
                Path target;
                if (registry == PLAYER_REGISTRY) {
                    target = root.resolve("worldforge").resolve("wf_players.json");
                } else if (registry == WORLD_REGISTRY) {
                    target = root.resolve("worldforge").resolve("wf_people_world.json");
                } else if (dimensionOwners.containsKey(registry)) {
                    target = dimensionRegistryFile(server, dimensionOwners.get(registry));
                } else {
                    MapData map = mapOwners.get(registry);
                    if (map == null) throw new IllegalStateException("Character registry has no durable owner");
                    target = EditorManager.overlayDir(server, map).resolve("wf_people.json");
                }
                target = target.toAbsolutePath().normalize();
                if (!target.startsWith(root)) throw new IllegalStateException("Character registry path escapes world save");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", root.relativize(target).toString().replace('\\', '/'));
                entry.put("content", registry.toJson());
                files.add(entry);
            }
            Map<String, Object> journal = new LinkedHashMap<>();
            journal.put("format", "wf-people-registry-journal/1");
            journal.put("files", files);
            Path journalFile = root.resolve("worldforge").resolve("wf_people_registry_journal.json");
            writeAtomic(journalFile, Json.write(journal));
            for (Map<String, Object> entry : files) {
                Path target = root.resolve(String.valueOf(entry.get("path"))).normalize();
                writeAtomic(target, String.valueOf(entry.get("content")));
            }
            Files.deleteIfExists(journalFile);
            LOGGER.warn("wf_people: cross-registry estate dependency cycle resolved with durable journaled snapshot");
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_people: journaled Character save failed; recovery journal is retained when available: {}", ex.toString(), ex);
            return false;
        }
    }

    static boolean persistAllRegistries(MinecraftServer server) {
        if (server == null || !playerRegistryAvailable) return false;

        List<CharacterRegistry> registries = new ArrayList<>();
        registries.add(PLAYER_REGISTRY);
        registries.add(WORLD_REGISTRY);
        java.util.IdentityHashMap<CharacterRegistry, MapData> mapOwners = new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<CharacterRegistry, String> dimensionOwners = new java.util.IdentityHashMap<>();
        for (Map.Entry<String, CharacterRegistry> entry : DIMENSION_REGISTRIES.entrySet()) {
            registries.add(entry.getValue());
            dimensionOwners.put(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<MapData, CharacterRegistry> entry : REGS.entrySet()) {
            registries.add(entry.getValue());
            mapOwners.put(entry.getValue(), entry.getKey());
        }

        // Recipient partitions must reach disk before source partitions. If a crash occurs
        // between two atomic file replacements, the incoming marker is then enough to finish
        // clearing the deceased source on the next startup.
        java.util.IdentityHashMap<CharacterRegistry, Set<CharacterRegistry>> outgoing = new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<CharacterRegistry, Integer> indegree = new java.util.IdentityHashMap<>();
        for (CharacterRegistry registry : registries) {
            outgoing.put(registry, new HashSet<>());
            indegree.put(registry, 0);
        }
        for (CharacterRegistry recipientRegistry : registries) {
            for (Character heir : recipientRegistry.all()) {
                for (Map.Entry<String, Object> property : heir.props.entrySet()) {
                    if (!property.getKey().startsWith("estate.incoming.")
                            || !(property.getValue() instanceof String sourceGlobalId)) continue;
                    String transferId = property.getKey().substring("estate.incoming.".length());
                    Character deceased = AUTHORITY.getGlobal(sourceGlobalId);
                    // Incoming markers are permanent replay receipts. Only an active source
                    // transfer creates a write-order dependency between registry files.
                    if (deceased == null
                            || !transferId.equals(deceased.props.get("estate.transfer_pending"))
                            || !heir.globalId.equals(deceased.props.get("estate.recipient_global_id"))) continue;
                    CharacterRegistry sourceRegistry = AUTHORITY.registryForGlobal(sourceGlobalId);
                    if (sourceRegistry != null && sourceRegistry != recipientRegistry
                            && outgoing.get(recipientRegistry).add(sourceRegistry))
                        indegree.put(sourceRegistry, indegree.getOrDefault(sourceRegistry, 0) + 1);
                }
            }
        }

        // Inventory transactions require destination-first writes just like estate succession.
        // Their write-ahead markers are stored on both endpoint Characters and carry global IDs.
        for (CharacterRegistry registry : registries) {
            for (Character character : registry.all()) {
                for (Map.Entry<String, Object> property : character.props.entrySet()) {
                    if (!property.getKey().startsWith(CharacterInventory.TRANSFER_MARKER_PREFIX)
                            || !(property.getValue() instanceof Map<?, ?> marker)) continue;
                    Object sourceRaw = marker.get("source_global_id");
                    Object destinationRaw = marker.get("destination_global_id");
                    if (!(sourceRaw instanceof String sourceId)
                            || !(destinationRaw instanceof String destinationId)) continue;
                    CharacterRegistry sourceRegistry = AUTHORITY.registryForGlobal(sourceId);
                    CharacterRegistry destinationRegistry = AUTHORITY.registryForGlobal(destinationId);
                    if (sourceRegistry != null && destinationRegistry != null
                            && sourceRegistry != destinationRegistry
                            && outgoing.get(destinationRegistry).add(sourceRegistry))
                        indegree.put(sourceRegistry, indegree.getOrDefault(sourceRegistry, 0) + 1);
                }
            }
        }

        java.util.ArrayDeque<CharacterRegistry> ready = new java.util.ArrayDeque<>();
        for (CharacterRegistry registry : registries)
            if (indegree.getOrDefault(registry, 0) == 0) ready.addLast(registry);
        List<CharacterRegistry> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            CharacterRegistry registry = ready.removeFirst();
            ordered.add(registry);
            for (CharacterRegistry source : outgoing.getOrDefault(registry, Set.of())) {
                int nextDegree = indegree.get(source) - 1;
                indegree.put(source, nextDegree);
                if (nextDegree == 0) ready.addLast(source);
            }
        }
        if (ordered.size() != registries.size()) {
            LOGGER.warn("wf_people: active estate dependency cycle detected; committing one write-ahead registry snapshot");
            if (!persistRegistriesWithJournal(server, registries, mapOwners, dimensionOwners)) return false;
            if (!finalizeCommittedEstateTransfers(server, registries, mapOwners, dimensionOwners)) return false;
            try { for (CharacterRegistry registry : registries) checkpointCharacterWal(registry); }
            catch (Exception checkpointFailure) {
                LOGGER.error("wf_people: journaled snapshot committed but Character WAL checkpoint failed", checkpointFailure);
                return false;
            }
            return true;
        }

        for (CharacterRegistry registry : ordered) {
            try {
                if (registry == PLAYER_REGISTRY) {
                    Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("worldforge").resolve("wf_players.json");
                    writeAtomic(playerFile, PLAYER_REGISTRY);
                } else if (registry == WORLD_REGISTRY) {
                    Path worldFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("worldforge").resolve("wf_people_world.json");
                    writeAtomic(worldFile, WORLD_REGISTRY);
                } else if (dimensionOwners.containsKey(registry)) {
                    writeAtomic(dimensionRegistryFile(server, dimensionOwners.get(registry)), registry);
                } else {
                    MapData map = mapOwners.get(registry);
                    if (map == null) return false;
                    Path dir = EditorManager.overlayDir(server, map);
                    writeAtomic(dir.resolve("wf_people.json"), registry);
                }
            } catch (Exception ex) {
                if (registry == PLAYER_REGISTRY) {
                    playerRegistryAvailable = false;
                    LOGGER.error("wf_people: failed to save player Character registry; further Genesis identity writes are disabled: {}", ex.toString());
                } else {
                    MapData map = mapOwners.get(registry);
                    String dimension = dimensionOwners.get(registry);
                    if (dimension != null)
                        LOGGER.error("wf_people: failed to save Character partition for dimension '{}': {}", dimension, ex.toString());
                    else
                        LOGGER.error("wf_people: не удалось сохранить людей карты '{}': {}",
                                map == null ? "unknown" : map.name, ex.toString());
                }
                return false;
            }
        }
        if (!finalizeCommittedEstateTransfers(server, registries, mapOwners, dimensionOwners)) return false;
        // Retire WAL records only after every owner snapshot and estate receipt is durable.
        try { for (CharacterRegistry registry : registries) checkpointCharacterWal(registry); }
        catch (Exception checkpointFailure) {
            LOGGER.error("wf_people: snapshots committed but Character WAL checkpoint failed; replay remains safe", checkpointFailure);
            return false;
        }
        return true;
    }

    /** Retire pending estate markers only after every source and recipient snapshot is durable. */
    private static boolean finalizeCommittedEstateTransfers(MinecraftServer server,
            List<CharacterRegistry> registries,
            java.util.IdentityHashMap<CharacterRegistry, MapData> mapOwners,
            java.util.IdentityHashMap<CharacterRegistry, String> dimensionOwners) {
        java.util.IdentityHashMap<CharacterRegistry, List<Character>> changed = new java.util.IdentityHashMap<>();
        for (CharacterRegistry recipientRegistry : registries) {
            for (Character heir : recipientRegistry.all()) {
                for (Map.Entry<String, Object> property : heir.props.entrySet()) {
                    if (!property.getKey().startsWith("estate.incoming.")
                            || !(property.getValue() instanceof String sourceGlobalId)) continue;
                    String transferId = property.getKey().substring("estate.incoming.".length());
                    Character deceased = AUTHORITY.getGlobal(sourceGlobalId);
                    if (deceased == null
                            || !transferId.equals(deceased.props.get("estate.transfer_pending"))
                            || !heir.globalId.equals(deceased.props.get("estate.recipient_global_id"))) continue;
                    CharacterRegistry sourceRegistry = AUTHORITY.registryForGlobal(sourceGlobalId);
                    if (sourceRegistry == null) return false;
                    deceased.props.put("estate.transfer_receipt", transferId);
                    deceased.props.remove("estate.transfer_pending");
                    changed.computeIfAbsent(sourceRegistry, ignored -> new ArrayList<>()).add(deceased);
                }
            }
        }
        for (Map.Entry<CharacterRegistry, List<Character>> entry : changed.entrySet()) {
            CharacterRegistry registry = entry.getKey();
            try {
                if (registry == PLAYER_REGISTRY) {
                    Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("worldforge").resolve("wf_players.json");
                    writeAtomic(file, registry);
                } else if (registry == WORLD_REGISTRY) {
                    Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("worldforge").resolve("wf_people_world.json");
                    writeAtomic(file, registry);
                } else if (dimensionOwners.containsKey(registry)) {
                    writeAtomic(dimensionRegistryFile(server, dimensionOwners.get(registry)), registry);
                } else {
                    MapData map = mapOwners.get(registry);
                    if (map == null) throw new IllegalStateException("Estate source registry has no durable owner");
                    writeAtomic(EditorManager.overlayDir(server, map).resolve("wf_people.json"), registry);
                }
            } catch (Exception ex) {
                LOGGER.error("wf_people: could not finalize committed estate receipts: {}", ex.toString(), ex);
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        NpcSpawner.clearRuntimeState();
        identityPartitionsReady = false;
        saveAll(e.getServer());
        closeCharacterWals();
        REGS.clear();
        DIMENSION_REGISTRIES.clear();
        PLAYER_CHARACTERS.clear();
        WORLD_REGISTRY.fromJson("{\"format\":\"wf-people/3\",\"schema_version\":3,\"next\":1,\"characters\":[]}");
        PLAYER_REGISTRY.fromJson("{\"format\":\"wf-people/3\",\"schema_version\":3,\"next\":1,\"characters\":[]}");
        playerRegistryAvailable = true;
        AUTHORITY.clear();
        FLAGS.clear();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            long currentWorldDay = RuntimeStressLab.populationTargetDay(WorldForgeApi.clock().dayNumber());
            YEAR = WorldForgeApi.clock().year();
            boolean rustOwnsPopulation = tryRustPopulationCompute(e.getServer(), currentWorldDay, YEAR);
            long registryGeneration = populationRegistryGeneration;
            if (!rustOwnsPopulation && (currentWorldDay > populationProcessedDay
                    || populationFallbackGeneration != registryGeneration)) {
                long fallbackStartDay = populationBaselineDay == Long.MIN_VALUE ? currentWorldDay : populationBaselineDay;
                boolean fallbackComplete = advancePopulationFallbackBatch(
                        currentWorldDay, fallbackStartDay, YEAR, registryGeneration);
                if (fallbackComplete) {
                    populationProcessedDay = Math.max(populationProcessedDay, currentWorldDay);
                    populationFallbackGeneration = registryGeneration;
                }
            }
            if (ticks % SPAWN_EVERY == 0) NpcSpawner.pass(e.getServer());
            if (ticks % 20 == 0 && !Boolean.getBoolean("wf.acceptance.contour02"))
                socialInitiatives(e.getServer());
            // Routine Character mutations are already committed to the per-partition WAL
            // by persistCharacter(). A population-wide JSON checkpoint here serialized every
            // Character on the server thread and stalled the tick loop at scale. Keep full
            // coordinated snapshots for explicit cross-partition transactions and shutdown.
        } catch (Exception ex) {
            LOGGER.error("wf_people: ошибка тика: {}", ex.toString());
        }
    }

    /**
     * Advances all canonical partitions under one server-wide budget. Partitions are visited
     * round-robin so a large first registry cannot monopolize the tick or starve later dimensions.
     */
    private static List<CharacterRegistry> populationFallbackRegistries(long registryGeneration) {
        if (populationFallbackSnapshotGeneration == registryGeneration)
            return populationFallbackRegistrySnapshot;
        synchronized (PeopleMod.class) {
            if (populationFallbackSnapshotGeneration == registryGeneration)
                return populationFallbackRegistrySnapshot;
            List<Map.Entry<MapData, CharacterRegistry>> mapEntries = new ArrayList<>(REGS.entrySet());
            mapEntries.sort(java.util.Comparator.comparing(entry -> entry.getKey().name));
            List<Map.Entry<String, CharacterRegistry>> dimensionEntries =
                    new ArrayList<>(DIMENSION_REGISTRIES.entrySet());
            dimensionEntries.sort(Map.Entry.comparingByKey());
            List<CharacterRegistry> registries = new ArrayList<>(
                    mapEntries.size() + dimensionEntries.size() + 2);
            Set<CharacterRegistry> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Map.Entry<MapData, CharacterRegistry> entry : mapEntries) {
                CharacterRegistry registry = entry.getValue();
                if (registry != null && seen.add(registry)) registries.add(registry);
            }
            for (Map.Entry<String, CharacterRegistry> entry : dimensionEntries) {
                CharacterRegistry registry = entry.getValue();
                if (registry != null && seen.add(registry)) registries.add(registry);
            }
            if (seen.add(WORLD_REGISTRY)) registries.add(WORLD_REGISTRY);
            if (seen.add(PLAYER_REGISTRY)) registries.add(PLAYER_REGISTRY);
            populationFallbackRegistrySnapshot = List.copyOf(registries);
            populationFallbackSnapshotGeneration = registryGeneration;
            return populationFallbackRegistrySnapshot;
        }
    }

    /**
     * Advances all canonical partitions under one server-wide budget. A stable snapshot avoids
     * per-tick map copies; a sliding round-robin window prevents starvation even when partitions
     * outnumber the budget (advancing by the window size could cycle over only a subset).
     */
    private static int rotationStride(int minimum, int size) {
        if (size <= 1) return 1;
        int stride = Math.max(1, Math.min(minimum, size - 1));
        while (stride < size && gcd(stride, size) != 1) stride++;
        return stride < size ? stride : 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int next = a % b; a = b; b = next; }
        return Math.abs(a);
    }

    private static boolean advancePopulationFallbackBatch(long targetWorldDay, long fallbackStartDay,
                                                          int year, long registryGeneration) {
        List<CharacterRegistry> registries = populationFallbackRegistries(registryGeneration);
        int count = registries.size();
        if (count == 0) return true;

        if (populationFallbackCursorGeneration != registryGeneration) {
            populationFallbackRegistryCursor = 0;
            populationFallbackCursorGeneration = registryGeneration;
        }
        int visitCount = Math.min(count, POPULATION_FALLBACK_BUDGET_PER_TICK);
        int start = Math.floorMod(populationFallbackRegistryCursor, count);
        int baseBudget = POPULATION_FALLBACK_BUDGET_PER_TICK / visitCount;
        int extra = POPULATION_FALLBACK_BUDGET_PER_TICK % visitCount;
        for (int offset = 0; offset < visitCount; offset++) {
            CharacterRegistry registry = registries.get((start + offset) % count);
            int budget = baseBudget + (offset < extra ? 1 : 0);
            PopulationSimulation.advanceToWorldDayBatch(
                    registry, targetWorldDay, fallbackStartDay, year, budget);
        }
        // Completion is global, not "all partitions visited this tick". This matters when
        // there are more partitions than the per-tick window: completed cursors remain durable
        // in memory while the sliding window advances through the remaining partitions.
        boolean complete = true;
        for (CharacterRegistry registry : registries) {
            PopulationSimulation.WorldDayProgress progress =
                    PopulationSimulation.worldDayProgress(registry);
            if (progress == null || progress.targetDay() != targetWorldDay
                    || progress.membershipGeneration() != registry.membershipGeneration()
                    || progress.catchUpPass() || progress.nextIndex() < registry.size()) {
                complete = false;
                break;
            }
        }
        // If every registry fits, revisit all of them next tick. Otherwise slide one position
        // so every partition is guaranteed to enter the bounded window.
        populationFallbackRegistryCursor = count <= POPULATION_FALLBACK_BUDGET_PER_TICK
                ? 0 : (start + rotationStride(visitCount, count)) % count;
        return complete;
    }

    private static boolean tryRustPopulationCompute(MinecraftServer server,long worldDay,int year) {
        // Contour 02 tests PeopleMod production scheduling with its bounded Java fallback.
        // Native WorldSim compute is covered separately and in full integration.
        if (Boolean.getBoolean("wf.acceptance.contour02")) return false;
        if (!ModList.get().isLoaded("wf_worldsim")) return false;
        try {
            Class<?> coordinator = Class.forName("dev.worldforge.worldsim.mc.PopulationComputeCoordinator");
            Object result = coordinator.getMethod("tick", MinecraftServer.class, long.class, int.class)
                    .invoke(null, server, worldDay, year);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError failure) {
            // Fail closed: never run Java while an optional native pipeline may still own this interval.
            LOGGER.error("wf_people: optional WorldSim population coordinator failed; Java population fallback is held to prevent double execution", failure);
            return true;
        }
    }

    public static long populationProcessedDay() { return populationProcessedDay; }
    /** Stable world-save baseline for legacy Characters without per-Character receipts. */
    public static long populationBaselineDay() { return populationBaselineDay; }
    public static long populationRegistryGeneration() { return populationRegistryGeneration; }
    public static void markPopulationProcessed(long worldDay) {
        if (worldDay > populationProcessedDay) populationProcessedDay = worldDay;
    }

    /**
     * Optional NPC-initiated conversation. Runs only for explicitly configured characters
     * or characters with unresolved semantic obligations. A per-character/player cooldown
     * prevents repeated popups.
     */
    private static void socialInitiatives(MinecraftServer server) {
        if (!dialogueLoaded()) return;
        for (ServerLevel level : server.getAllLevels()) {
            CharacterRegistry registry = peek(level);
            if (registry == null) continue;
            MapData map = EditorManager.dataFor(level);
            if (map == null || map.titles == null) continue;
            for (ServerPlayer player : level.players()) {
                if (DialogueHook.hasSession(player)) continue;
                List<WFNpc> nearby = level.getEntitiesOfClass(WFNpc.class, player.getBoundingBox().inflate(3.0));
                for (WFNpc body : nearby) {
                    if (player.distanceToSqr(body) > 9.0) continue;
                    Character person = registry.get(body.characterId());
                    if (person == null || !person.alive) continue;
                    Object configured = person.props.get("social.initiative_dialogue");
                    if (!(configured instanceof String dialogueId) || dialogueId.isBlank()) continue;
                    String intent = pendingSocialIntent(person);
                    if (!Boolean.TRUE.equals(person.props.get("social.initiative_enabled")) && intent.isEmpty()) continue;
                    Map<String,Object> cooldowns = new HashMap<>();
                    Object raw = person.props.get("social.initiative_cooldowns");
                    if (raw instanceof Map<?,?> values) for (Map.Entry<?,?> entry : values.entrySet())
                        if (entry.getKey() instanceof String key && entry.getValue() instanceof Number n) cooldowns.put(key,n.longValue());
                    String playerId = player.getUUID().toString();
                    Object last = cooldowns.get(playerId);
                    if (last instanceof Number n && ticks - n.longValue() < 2400) continue;
                    person.props.put("social.initiative_intent", intent.isEmpty() ? "greeting" : intent);
                    if (!DialogueHook.open(player, body, person, dialogueId, ROLES, map.titles,
                            FLAGS.computeIfAbsent(player.getUUID(), k -> new HashSet<>()))) continue;
                    cooldowns.put(playerId, ticks);
                    while (cooldowns.size() > 64) {
                        String oldest = null;
                        long oldestTick = Long.MAX_VALUE;
                        for (Map.Entry<String,Object> entry : cooldowns.entrySet()) {
                            if (entry.getValue() instanceof Number value && value.longValue() < oldestTick) {
                                oldest = entry.getKey();
                                oldestTick = value.longValue();
                            }
                        }
                        if (oldest == null) break;
                        cooldowns.remove(oldest);
                    }
                    person.props.put("social.initiative_cooldowns", cooldowns);
                    break;
                }
            }
        }
    }

    /** First unresolved promise/request/grievance category for a follow-up conversation. */
    private static String pendingSocialIntent(Character person) {
        Object raw = person.props.get("dialogue.memory");
        if (!(raw instanceof Map<?,?> root) || !(root.get("records") instanceof Map<?,?> records)) return "";
        for (String category : List.of("grievance", "request", "promise")) {
            String prefix = category + ".";
            for (Map.Entry<?,?> entry : records.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !key.startsWith(prefix)
                        || !(entry.getValue() instanceof Map<?,?> record)) continue;
                if (!"open".equals(String.valueOf(record.get("status")))) continue;
                person.props.put("social.initiative_subject", String.valueOf(record.containsKey("text") ? record.get("text") : ""));
                return category + "_followup";
            }
        }
        return "";
    }

    public void onCommands(RegisterCommandsEvent e) {
        PeopleCommands.register(e.getDispatcher());
    }

    // ------------------------------------------------------------------ вспомогательное

    /** "Имя Дом, Роль". */
    public static String label(Character c) {
        StringBuilder sb = new StringBuilder(c.name);
        if (!c.house.isEmpty()) sb.append(' ').append(c.house);
        else if (c.props.get("identity.surname") instanceof String surname && !surname.isBlank())
            sb.append(' ').append(surname);
        String role = roleName(c);
        if (!role.isEmpty()) sb.append(", ").append(role);
        return sb.toString();
    }

    public static String roleName(Character c) {
        for (Character.Post p : c.posts) {
            Role r = ROLES.get(p.role);
            if (r != null) return r.name;
        }
        return "";
    }

    /** Id диалога. Если wf_dialogue стоит, берётся из bridge.DialogueBridge, иначе по той же логике здесь (мост не трогаем без мода). */
    public static String dialogueId(Character c) {
        if (dialogueLoaded()) {
            try {
                return DialogueBridge.dialogueId(c, ROLES);
            } catch (Throwable t) {
                LOGGER.error("wf_people: DialogueBridge.dialogueId: {}", t.toString());
            }
        }
        Object own = c.props.get("dialogue");
        if (own != null) return String.valueOf(own);
        for (Character.Post p : c.posts) {
            Role r = ROLES.get(p.role);
            if (r != null && !r.dialogue.isEmpty()) return r.dialogue;
        }
        return "default";
    }

    /** Правый клик по телу. */
    static void interact(ServerPlayer sp, WFNpc npc) {
        ServerLevel level = sp.serverLevel();
        CharacterRegistry reg = registryFor(level);
        Character c = reg == null ? null : reg.get(npc.characterId());
        if (c == null || !c.alive) {
            sp.sendSystemMessage(Component.literal("Незнакомец молчит."));
            return;
        }
        String did = dialogueId(c);
        NpcInteractEvent ev = new NpcInteractEvent(sp, npc, c, did);
        MinecraftForge.EVENT_BUS.post(ev);
        if (!ev.isHandled() && dialogueLoaded()) {
            MapData m = EditorManager.dataFor(level);
            Set<String> flags = FLAGS.computeIfAbsent(sp.getUUID(), k -> new HashSet<>());
            if (m != null && m.titles != null) {
                if (DialogueHook.open(sp, npc, c, did, ROLES, m.titles, flags)) return;
                if (!did.equals("default") && DialogueHook.open(sp, npc, c, "default", ROLES, m.titles, flags)) return;   // у роли нет файла диалога
            }
        }
        if (!ev.isHandled()) sp.sendSystemMessage(Component.literal(label(c)));
    }

    static Map<String, Integer> tierIndex(TitleData d) {
        Map<String, Integer> out = new HashMap<>();
        for (TitleData.Tier t : d.tiers) out.put(t.key, t.index);
        return out;
    }
}
