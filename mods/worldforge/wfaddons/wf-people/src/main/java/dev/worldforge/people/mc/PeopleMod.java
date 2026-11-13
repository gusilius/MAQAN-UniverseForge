package dev.worldforge.people.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterInventory;
import dev.worldforge.people.CharacterInventoryTransactions;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterAuthority;
import dev.worldforge.mod.identity.WorldId;
import dev.worldforge.people.Courts;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.PopulationSimulation;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final CharacterAuthority AUTHORITY = new CharacterAuthority();
    private static final CharacterRegistry PLAYER_REGISTRY = new CharacterRegistry();
    private static final Map<String, Character> PLAYER_CHARACTERS = new ConcurrentHashMap<>();
    private static volatile boolean playerRegistryAvailable = true;
    /** True only after all world Character partitions are indexed and pending transfers recover cleanly. */
    private static volatile boolean identityPartitionsReady;
    public static boolean identityPartitionsReady() { return identityPartitionsReady; }
    public static CharacterAuthority authority() { return AUTHORITY; }
    public static CharacterRegistry playerRegistry() { return PLAYER_REGISTRY; }
    public static boolean playerRegistryAvailable() { return playerRegistryAvailable; }
    /** Corrupt saves fail closed: never regenerate courts over unreadable identity data. */
    private static final Set<MapData> INVALID_REGS = ConcurrentHashMap.newKeySet();
    public static final int TICKS_PER_DAY = 1200;
    private static final Map<UUID, Set<String>> FLAGS = new ConcurrentHashMap<>();
    private static long ticks;

    public PeopleMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        bus.addListener(PeopleMod::onAttributes);
        bus.addListener(PeopleMod::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PlayerInventoryBridge());
        MinecraftForge.EVENT_BUS.register(new CharacterInventoryEvents());
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
        identityPartitionsReady = false;
        REGS.clear();
        PLAYER_CHARACTERS.clear();
        INVALID_REGS.clear();
        if (WorldForgeMod.worldId() != null) AUTHORITY.beginWorld(WorldForgeMod.worldId());
        else AUTHORITY.clear();
        FLAGS.clear();
        ticks = 0;
        RoleDefs roles = new RoleDefs();
        dev.worldforge.people.ProfessionDefs professions = new dev.worldforge.people.ProfessionDefs();
        NameBank names = new NameBank();
        java.util.List<String> rawRoles = new java.util.ArrayList<>();
        load(e.getServer(), "wf_roles", j -> { roles.add(j); rawRoles.add(j); });
        load(e.getServer(), "wf_professions", professions::add);
        ROLES_RAW = rawRoles;
        load(e.getServer(), "wf_names", names::add);
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
        playerRegistryAvailable = true;
        try {
            Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("worldforge").resolve("wf_players.json");
            if (Files.exists(playerFile)) {
                WorldId world = WorldForgeMod.worldId();
                if (world == null) throw new IllegalStateException("WorldId is unavailable for player identity migration");
                PLAYER_REGISTRY.fromJson(Files.readString(playerFile), world + "|__players__");
            }
            AUTHORITY.registerMap("__players__", PLAYER_REGISTRY);
            indexPlayerCharacters(PLAYER_REGISTRY);
        } catch (Exception ex) {
            playerRegistryAvailable = false;
            LOGGER.error("wf_people: player Character registry failed to load; player identity creation and overwrite are disabled: {}", ex.toString());
        }
        for (MapData m : WorldForgeMod.loadedMaps().values()) registry(m);
        reconcileIdentityPartitions(server);
    }

    private static boolean allIdentityPartitionsLoaded() {
        if (!playerRegistryAvailable || AUTHORITY.registry("__players__") != PLAYER_REGISTRY) return false;
        int expected = 1; // Dedicated player Character partition.
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
                    reg.fromJson(Files.readString(f), world + "|" + m.name);
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
            boolean emptyCampaignBootstrap = Boolean.getBoolean("worldforge.campaign.empty-bootstrap");
            int created = m.titles == null || emptyCampaignBootstrap ? 0
                    : Courts.ensure(m.titles, EditorManager.editorFor(m), reg, ROLES, NAMES, seed(m), YEAR);
            if (emptyCampaignBootstrap && before == 0)
                LOGGER.info("wf_people: campaign empty bootstrap enabled for map '{}'; authored court seeding skipped", m.name);
            LOGGER.info("wf_people: карта '{}': было {} людей, создано {}, теперь {}", m.name, before, created, reg.size());
        } catch (Exception ex) {
            LOGGER.error("wf_people: Courts.ensure для карты '{}' не удался: {}", m.name, ex.toString());
        }
        CharacterRegistry prev = REGS.putIfAbsent(m, reg);
        CharacterRegistry selected = prev != null ? prev : reg;
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
                REGS.remove(m, selected);
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
        return registry(EditorManager.dataFor(level));
    }

    /** Реестр без создания (для сущностей, которые тикают до загрузки карты). */
    public static CharacterRegistry peek(ServerLevel level) {
        MapData m = EditorManager.dataFor(level);
        return m == null ? null : REGS.get(m);
    }

    private static void writeAtomic(Path file, String content) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, content);
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
        PlayerInventoryBridge.syncOnline(server);
        persistAllRegistries(server);
    }

    /** Persist only the registry that owns this Character; unrelated map failures must not block a safe death snapshot. */
    public static boolean persistCharacter(Character character, MinecraftServer server) {
        if (character == null || server == null) return false;
        CharacterRegistry owner = AUTHORITY.registryForGlobal(character.globalId);
        if (owner == null) return false;
        // A source-side write must never get ahead of a recipient commit marker in another file.
        for (Character candidate : owner.all()) {
            if (candidate.props.containsKey("estate.transfer_pending")
                    || CharacterInventory.hasPendingTransfer(candidate))
                return persistAllRegistries(server);
        }
        if (owner == PLAYER_REGISTRY) {
            if (!playerRegistryAvailable) return false;
            try {
                Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("worldforge").resolve("wf_players.json");
                writeAtomic(playerFile, PLAYER_REGISTRY.toJson());
                return true;
            } catch (Exception ex) {
                playerRegistryAvailable = false;
                LOGGER.error("wf_people: failed to persist player Character {}: {}", character.globalId, ex.toString());
                return false;
            }
        }
        for (Map.Entry<MapData, CharacterRegistry> entry : REGS.entrySet()) if (entry.getValue() == owner) {
            try {
                Path file = EditorManager.overlayDir(server, entry.getKey()).resolve("wf_people.json");
                writeAtomic(file, owner.toJson());
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
    static boolean persistAllRegistries(MinecraftServer server) {
        if (server == null || !playerRegistryAvailable) return false;

        List<CharacterRegistry> registries = new ArrayList<>();
        registries.add(PLAYER_REGISTRY);
        java.util.IdentityHashMap<CharacterRegistry, MapData> mapOwners = new java.util.IdentityHashMap<>();
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
            for (Character character : recipientRegistry.all()) {
                for (Map.Entry<String, Object> property : character.props.entrySet()) {
                    if (!property.getKey().startsWith("estate.incoming.") || !(property.getValue() instanceof String sourceGlobalId))
                        continue;
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
            LOGGER.error("wf_people: estate persistence dependency cycle detected; refusing a potentially partial cross-registry save");
            return false;
        }

        for (CharacterRegistry registry : ordered) {
            try {
                if (registry == PLAYER_REGISTRY) {
                    Path playerFile = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .resolve("worldforge").resolve("wf_players.json");
                    writeAtomic(playerFile, PLAYER_REGISTRY.toJson());
                } else {
                    MapData map = mapOwners.get(registry);
                    if (map == null) return false;
                    Path dir = EditorManager.overlayDir(server, map);
                    writeAtomic(dir.resolve("wf_people.json"), registry.toJson());
                }
            } catch (Exception ex) {
                if (registry == PLAYER_REGISTRY) {
                    playerRegistryAvailable = false;
                    LOGGER.error("wf_people: failed to save player Character registry; further Genesis identity writes are disabled: {}", ex.toString());
                } else {
                    MapData map = mapOwners.get(registry);
                    LOGGER.error("wf_people: не удалось сохранить людей карты '{}': {}",
                            map == null ? "unknown" : map.name, ex.toString());
                }
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        identityPartitionsReady = false;
        saveAll(e.getServer());
        REGS.clear();
        PLAYER_CHARACTERS.clear();
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
            int elapsedDays = WorldForgeApi.clock().pull("wf-people");
            YEAR = WorldForgeApi.clock().year();
            if (elapsedDays > 0) {
                for (CharacterRegistry registry : REGS.values()) PopulationSimulation.advance(registry, elapsedDays, YEAR);
                PopulationSimulation.advance(PLAYER_REGISTRY, elapsedDays, YEAR);
            }
            if (ticks % SPAWN_EVERY == 0) NpcSpawner.pass(e.getServer());
            if (ticks % 20 == 0) socialInitiatives(e.getServer());
            if (ticks % SAVE_EVERY == 0) saveAll(e.getServer());
        } catch (Exception ex) {
            LOGGER.error("wf_people: ошибка тика: {}", ex.toString());
        }
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
