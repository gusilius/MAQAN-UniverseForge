package dev.worldforge.people.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.people.Character;
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
    public static CharacterAuthority authority() { return AUTHORITY; }
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
        Character global = AUTHORITY.getGlobal(id);
        return global != null ? global : AUTHORITY.getLegacy(id);
    }

    public static boolean dialogueLoaded() {
        return ModList.get().isLoaded("wf_dialogue");
    }

    // ------------------------------------------------------------------ данные

    @SubscribeEvent
    public void onAboutToStart(ServerAboutToStartEvent e) {
        REGS.clear();
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
        for (MapData m : WorldForgeMod.loadedMaps().values()) registry(m);
    }

    /** Реестр людей карты: читается из папки мира, при первом обращении достраивается дворами (Courts.ensure). Null, если у карты нет титулов. */
    public static CharacterRegistry registry(MapData m) {
        if (m == null || m.titles == null || INVALID_REGS.contains(m)) return null;
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
                LOGGER.error("wf_people: сохранение {} повреждено; генерация персонажей заблокирована во избежание потери данных: {}", f, ex.toString());
                INVALID_REGS.add(m);
                return null;
            }
        }
        int before = reg.size();
        try {
            int created = Courts.ensure(m.titles, EditorManager.editorFor(m), reg, ROLES, NAMES, seed(m), YEAR);
            LOGGER.info("wf_people: карта '{}': было {} людей, создано {}, теперь {}", m.name, before, created, reg.size());
        } catch (Exception ex) {
            LOGGER.error("wf_people: Courts.ensure для карты '{}' не удался: {}", m.name, ex.toString());
        }
        CharacterRegistry prev = REGS.putIfAbsent(m, reg);
        CharacterRegistry selected = prev != null ? prev : reg;
        if (WorldForgeMod.worldId() != null) {
            try { AUTHORITY.registerMap(m.name, selected); }
            catch (RuntimeException ex) { LOGGER.error("wf_people: global Character authority rejected map '{}': {}", m.name, ex.toString()); }
        }
        return selected;
    }

    public static long seed(MapData m) {
        return 0x57464F52L ^ ((long) m.name.hashCode() * 0x9E3779B97F4A7C15L);
    }

    /** Read-only snapshot for optional population/progression bridges. */
    public static Map<MapData, CharacterRegistry> registries() { return java.util.Collections.unmodifiableMap(new HashMap<>(REGS)); }

    public static CharacterRegistry registryFor(ServerLevel level) {
        return registry(EditorManager.dataFor(level));
    }

    /** Реестр без создания (для сущностей, которые тикают до загрузки карты). */
    public static CharacterRegistry peek(ServerLevel level) {
        MapData m = EditorManager.dataFor(level);
        return m == null ? null : REGS.get(m);
    }

    static void saveAll(MinecraftServer server) {
        for (Map.Entry<MapData, CharacterRegistry> en : REGS.entrySet()) {
            try {
                Path dir = EditorManager.overlayDir(server, en.getKey());
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("wf_people.json"), en.getValue().toJson());
            } catch (Exception ex) {
                LOGGER.error("wf_people: не удалось сохранить людей карты '{}': {}", en.getKey().name, ex.toString());
            }
        }
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        saveAll(e.getServer());
        REGS.clear();
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
