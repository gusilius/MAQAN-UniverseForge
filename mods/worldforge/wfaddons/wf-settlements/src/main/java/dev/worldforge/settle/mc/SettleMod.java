package dev.worldforge.settle.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.Json;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Склейка wf-settlements с Minecraft. Поселения хранятся по id; тик симуляции раз в секунду (20 тиков),
 * стройка блоками и тела рабочих - только рядом с игроками. Сохранение: wf_settle.json в папке мира.
 */
@Mod(SettleMod.MOD_ID)
public class SettleMod {
    public static final String MOD_ID = "wf_settle";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final int STEP_TICKS = 20, SITE_TICKS = 10, SAVE_EVERY = 6000;

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<Item> WAND = ITEMS.register("settle_wand", () -> new SettleWand(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<EntityType<WFWorker>> WORKER = ENTITIES.register("worker",
            () -> EntityType.Builder.<WFWorker>of(WFWorker::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(10).build(MOD_ID + ":worker"));

    /** Описания зданий из датапаков; null, пока сервер не запущен. */
    public static volatile Defs DEFS;
    /** Виды поселений (types.json в датапаках). */
    public static volatile dev.worldforge.settle.SettleTypes TYPES = new dev.worldforge.settle.SettleTypes();
    /** Исходные JSON описаний (в порядке загрузки): их отправляем клиенту вместе с поселением, чтобы окна собрали те же Defs. */
    public static volatile java.util.List<String> DEFS_RAW = java.util.List.of();
    static volatile java.util.List<String> BASE_RAW = java.util.List.of();

    /** Перечитать папки со схемами без перезапуска: новые схемы появляются в меню стройки. */
    public static java.util.List<String> reloadSchematics(MinecraftServer server) {
        if (DEFS == null) return java.util.List.of("Поселения ещё не загружены");
        java.util.List<String> raw = new java.util.ArrayList<>(BASE_RAW);
        java.util.List<String> rep = SchemLibrary.loadAll(server, DEFS, raw);
        DEFS_RAW = raw;
        Blueprints.clearCache();
        return rep;
    }
    public static final Map<String, Colony> COLONIES = new ConcurrentHashMap<>();
    public static dev.worldforge.settle.TradeNetwork NETWORK = new dev.worldforge.settle.TradeNetwork();
    private static long ticks;

    public SettleMod() {
        dev.worldforge.mod.WorldForgeSnapshotExporter.registerContributor(SettleMod::appendAtlasFeatures);
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(this::attributes);
        bus.addListener(this::tabs);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new SettleWand.Events());
        MinecraftForge.EVENT_BUS.register(new TaxService());
    }

    private void attributes(EntityAttributeCreationEvent e) {
        e.put(WORKER.get(), WFWorker.attributes().build());
    }

    private void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(WAND);
    }

    // ------------------------------------------------------------------ Atlas snapshot integration

    /** Adds live settlement and building locations to the same WorldForge snapshot used by Atlas. */
    private static void appendAtlasFeatures(MinecraftServer server, ServerLevel level,
            dev.worldforge.mod.MapData map, List<Object> features) {
        String dimension = level.dimension().location().toString();
        for (Colony colony : COLONIES.values()) {
            if (colony == null || !dimension.equals(colony.dim) || colony.s == null) continue;
            dev.worldforge.mod.TitleData.Title province = map.provinceAt(colony.cx, colony.cz);
            dev.worldforge.mod.TitleData.Realm realm = province == null || map.titles == null
                    ? null : map.titles.realmOf(province);
            String realmId = realm == null ? "" : realm.id;
            String raceId = realm == null ? "" : propertyString(realm.props, "race");
            String cultureId = realm == null ? "" : propertyString(realm.props, "culture");
            String provinceFeatureId = province == null ? "" :
                    "worldforge:" + dimensionId + ":" + map.name + ":province:" + province.key;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    colony.cx, colony.cz);
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            attrs.put("population", Integer.toString(colony.s.workers.size()));
            attrs.put("housing", Integer.toString(colony.s.housing()));
            attrs.put("settlement_type", colony.s.typeId == null ? "" : colony.s.typeId);
            attrs.put("owner_assigned", Boolean.toString(colony.owner != null && !colony.owner.isBlank()));
            attrs.put("realm_id", realmId);
            attrs.put("province_id", province == null ? "" : province.key);
            attrs.put("year", Integer.toString(colony.s.year()));
            attrs.put("season", colony.s.seasonName());
            attrs.put("approval", Integer.toString(colony.s.approval()));
            attrs.put("tax_level", Integer.toString(colony.s.taxLevel));
            attrs.put("wealth", Double.toString(colony.s.wealth()));
            attrs.put("buildings", Integer.toString(colony.s.buildings.size()));
            attrs.put("map_id", map.name);
            Map<String, Object> settlementFeature = atlasFeature("worldforge:settlement:" + dimension + ":" + colony.s.id,
                    "settlement", colony.s.name, dimension, colony.cx, y, colony.cz,
                    realmId, colony.s.typeId, attrs);
            settlementFeature.put("territory_id", provinceFeatureId);
            settlementFeature.put("race_id", raceId);
            settlementFeature.put("culture_id", cultureId);
            features.add(settlementFeature);

            for (dev.worldforge.settle.Building building : colony.s.buildings) {
                if (building == null || building.def == null) continue;
                int x = colony.worldX(building), z = colony.worldZ(building);
                int by = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                Map<String, String> buildingAttrs = new java.util.LinkedHashMap<>();
                buildingAttrs.put("settlement_id", colony.s.id);
                buildingAttrs.put("building_uid", Integer.toString(building.uid));
                buildingAttrs.put("building_state", building.state == null ? "" : building.state.name().toLowerCase(java.util.Locale.ROOT));
                buildingAttrs.put("progress", Double.toString(building.progress));
                buildingAttrs.put("paused", Boolean.toString(building.paused));
                buildingAttrs.put("workers", Integer.toString(colony.s.assigned(building.uid).size()));
                buildingAttrs.put("realm_id", realmId);
                buildingAttrs.put("province_id", province == null ? "" : province.key);
                buildingAttrs.put("map_id", map.name);
                Map<String, Object> buildingFeature = atlasFeature("worldforge:settlement:" + dimension + ":" + colony.s.id
                                + ":building:" + building.uid,
                        "settlement_building", building.def.name, dimension, x, by, z,
                        realmId, building.def.id, buildingAttrs);
                buildingFeature.put("territory_id", provinceFeatureId);
                buildingFeature.put("race_id", raceId);
                buildingFeature.put("culture_id", cultureId);
                features.add(buildingFeature);
            }
        }
    }

    private static String propertyString(Map<String, Object> properties, String key) {
        if (properties == null) return "";
        Object value = properties.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> atlasFeature(String id, String kind, String name,
            String dimension, double x, double y, double z, String realmId,
            String subtype, Map<String, String> attributes) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("id", id);
        value.put("kind", kind);
        value.put("name", name == null ? "" : name);
        value.put("dimension_id", dimension);
        value.put("x", x);
        value.put("y", y);
        value.put("z", z);
        value.put("realm_id", realmId);
        value.put("subtype", subtype == null ? "" : subtype);
        value.put("color", 0xD6B56D);
        value.put("attributes", attributes);
        return value;
    }

    // ------------------------------------------------------------------ доступ

    /** Государство, на чьей земле стоит поселение (по провинции под центром), или "". */
    public static String realmOf(Colony c) {
        try {
            for (dev.worldforge.mod.MapData m : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
                if (m.titles == null) continue;
                dev.worldforge.mod.TitleData.Title t = m.provinceAt(c.cx, c.cz);
                if (t == null) continue;
                dev.worldforge.mod.TitleData.Realm r = m.titles.realmOf(t);
                if (r != null) return r.id;
            }
        } catch (RuntimeException ex) { /* карта ещё не загружена */ }
        return "";
    }

    public static Colony colony(String id) { return COLONIES.get(id); }

    public static boolean ownsAny(String uuid) {
        for (Colony c : COLONIES.values()) if (uuid.equals(c.owner)) return true;
        return false;
    }

    /** Ближайшее к точке поселение в этом измерении (без ограничения расстояния) или null. */
    public static Colony nearest(ServerLevel lv, double x, double z) {
        String dim = lv.dimension().location().toString();
        Colony best = null;
        double bd = Double.MAX_VALUE;
        for (Colony c : COLONIES.values()) {
            if (!c.dim.equals(dim)) continue;
            double d = (c.cx - x) * (c.cx - x) + (c.cz - z) * (c.cz - z);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    /** Закладывает поселение: 4 рабочих, склад дерево 60 / еда 40 / камень 10. */
    public static Colony found(ServerLevel lv, String name, BlockPos p) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "settlement";
        String id = base;
        for (int i = 2; COLONIES.containsKey(id); i++) id = base + "_" + i;
        Settlement s = new Settlement(id, name, DEFS);
        s.types = TYPES;
        for (int i = 0; i < 4; i++) s.addWorker(null);
        s.add("wood", 60);
        s.add("food", 40);
        s.add("stone", 10);
        Colony c = new Colony(s, lv.dimension().location().toString(), p.getX(), p.getZ());
        COLONIES.put(id, c);
        return c;
    }

    // ------------------------------------------------------------------ загрузка и сохранение

    static Defs loadDefs(MinecraftServer server) {
        Defs d = new Defs();
        dev.worldforge.settle.SettleTypes types = new dev.worldforge.settle.SettleTypes();
        java.util.List<String> raw = new java.util.ArrayList<>();
        var found = new java.util.TreeMap<ResourceLocation, Resource>(server.getResourceManager().listResources("wf_settle", rl -> rl.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) {
                String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                d.add(txt);
                try { types.load(txt); } catch (RuntimeException ex) { LOGGER.error("wf_settle: виды в {}: {}", r.getKey(), ex.toString()); }
                raw.add(txt);
            } catch (Exception ex) {
                LOGGER.error("wf_settle: ошибка в {}: {}", r.getKey(), ex.toString());
            }
        }
        BASE_RAW = new java.util.ArrayList<>(raw);
        for (String line : SchemLibrary.loadAll(server, d, raw)) LOGGER.info("wf_settle: схема {}", line);
        DEFS_RAW = raw;
        for (String p : types.validate(d)) LOGGER.warn("wf_settle: {}", p);
        TYPES = types;
        for (String p : d.validate()) LOGGER.warn("wf_settle: {}", p);
        LOGGER.info("wf_settle: зданий {}, профессий {}", d.buildings.size(), d.professions.size());
        return d;
    }

    private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("wf_settle.json"); }

    @SuppressWarnings("unchecked")
    static void load(MinecraftServer server) {
        COLONIES.clear();
        NETWORK = new dev.worldforge.settle.TradeNetwork();
        Path f = file(server);
        if (!Files.exists(f)) return;
        try {
            Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(f));
            for (Object o : (List<Object>) root.get("colonies")) {
                try {
                    Colony c = Colony.fromMap((Map<String, Object>) o, DEFS);
                    c.s.types = TYPES;
                    COLONIES.put(c.s.id, c);
                } catch (Exception ex) {
                    LOGGER.error("wf_settle: поселение не прочитано: {}", ex.toString());
                }
            }
            if (root.get("trade") instanceof String tj) { try { NETWORK = dev.worldforge.settle.TradeNetwork.fromJson(tj); } catch (RuntimeException ex) { LOGGER.error("wf_settle: маршруты не прочитаны: {}", ex.toString()); } }
            LOGGER.info("wf_settle: загружено поселений: {}", COLONIES.size());
        } catch (Exception ex) {
            LOGGER.error("wf_settle: не удалось прочитать {}: {}", f, ex.toString());
        }
    }

    public static void save(MinecraftServer server) {
        try {
            List<Object> l = new ArrayList<>();
            for (Colony c : COLONIES.values()) l.add(c.toMap());
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("format", "wf-settle-world/1");
            root.put("colonies", l);
            root.put("trade", NETWORK.toJson());
            Files.writeString(file(server), Json.write(root));
        } catch (Exception ex) {
            LOGGER.error("wf_settle: не удалось сохранить поселения: {}", ex.toString());
        }
    }

    // ------------------------------------------------------------------ события сервера

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        ticks = 0;
        Blueprints.clearCache();
        Bodies.LIVE.clear();
        DEFS = loadDefs(e.getServer());
        load(e.getServer());
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        if (DEFS != null) save(e.getServer());
        Bodies.LIVE.clear();
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent e) {
        COLONIES.clear();
        DEFS = null;
        Blueprints.clearCache();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || DEFS == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ticks++;
        if (ticks % STEP_TICKS == 0) {
            double speed = dev.worldforge.mod.api.WorldForgeApi.clock().multiplier();
            for (Colony c : COLONIES.values()) {
                if (speed <= 0) break;
                try {
                    c.s.tick(speed);                                   // 1 секунда симуляции = 20 тиков
                    if (ticks % (STEP_TICKS * 10) == 0) { c.s.types = TYPES; String rid = realmOf(c); c.s.refreshType(c.s.workers.size(), rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.terrain(rid), rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.races(rid)); }
                } catch (RuntimeException ex) {
                    LOGGER.error("wf_settle: ошибка симуляции поселения {}: {}", c.s.id, ex.toString());
                }
            }
            try {
                Map<String, dev.worldforge.settle.Settlement> all = new java.util.LinkedHashMap<>();
                for (Colony c : COLONIES.values()) all.put(c.s.id, c.s);
                if (speed > 0) NETWORK.tick(all, speed);
            } catch (RuntimeException ex) {
                LOGGER.error("wf_settle: ошибка торговых маршрутов: {}", ex.toString());
            }
        }
        if (ticks % SITE_TICKS == 0) {
            for (Colony c : COLONIES.values()) {
                ServerLevel lv = c.level(server);
                if (lv == null) continue;
                try {
                    BuildingSite.tick(lv, c);
                } catch (RuntimeException ex) {
                    LOGGER.error("wf_settle: ошибка стройки {}: {}", c.s.id, ex.toString());
                }
            }
        }
        if (ticks % STEP_TICKS == 5) Bodies.tick(server);
        if (ticks % SAVE_EVERY == 0) save(server);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        SettleCommands.register(e.getDispatcher());
    }
}
