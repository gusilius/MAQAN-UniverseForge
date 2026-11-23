package dev.worldforge.settle.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.Json;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;
import dev.worldforge.settle.SettlementSpacing;
import dev.worldforge.settle.ResidentLifeEvents;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.NameBank;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.LifeSimulation;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.MapChunkGenerator;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.economy.TreasuryService;
import dev.worldforge.people.PopulationSimulation.Duty;
import dev.worldforge.people.mc.PeopleMod;
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
import java.util.*;
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
    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<ResidentDialogueMenu>> RESIDENT_DIALOGUE_MENU =
            MENUS.register("resident_dialogue", () -> net.minecraftforge.common.extensions.IForgeMenuType.create(ResidentDialogueMenu::new));
    public static final RegistryObject<Item> WAND = ITEMS.register("settle_wand", () -> new SettleWand(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<EntityType<WFWorker>> WORKER = ENTITIES.register("worker",
            () -> EntityType.Builder.<WFWorker>of(WFWorker::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(10).build(MOD_ID + ":worker"));

    /** Описания зданий из датапаков; null, пока сервер не запущен. */
    public static volatile Defs DEFS;
    /** Виды поселений (types.json в датапаках). */
    public static volatile dev.worldforge.settle.SettleTypes TYPES = new dev.worldforge.settle.SettleTypes();
    /** Data-driven structure classes and identity dimensions from structure_catalog.json. */
    public static volatile dev.worldforge.settle.StructureCatalog STRUCTURE_CATALOG =
            dev.worldforge.settle.StructureCatalog.empty();
    /** Исходные JSON описаний (в порядке загрузки): их отправляем клиенту вместе с поселением, чтобы окна собрали те же Defs. */
    public static volatile java.util.List<String> DEFS_RAW = java.util.List.of();
    static volatile java.util.List<String> BASE_RAW = java.util.List.of();

    /** Перечитать папки со схемами без перезапуска: новые схемы появляются в меню стройки. */
    public static java.util.List<String> reloadSchematics(MinecraftServer server) {
        if (DEFS == null) return java.util.List.of("Поселения ещё не загружены");
        java.util.List<String> raw = new java.util.ArrayList<>(BASE_RAW);
        java.util.List<String> rep = SchemLibrary.loadAll(server, DEFS, raw);
        // The same in-game admin reload refreshes catalogue metadata as well as schematic
        // assets, so new structures can be added without restarting the world.
        var catalogueFiles = new java.util.TreeMap<ResourceLocation, Resource>(
                server.getResourceManager().listResources("wf_settle",
                        location -> location.getPath().endsWith("structure_catalog.json")));
        for (Map.Entry<ResourceLocation, Resource> entry : catalogueFiles.entrySet()) {
            try (InputStream input = entry.getValue().open()) {
                dev.worldforge.settle.StructureCatalog refreshed =
                        dev.worldforge.settle.StructureCatalog.load(
                                new String(input.readAllBytes(), StandardCharsets.UTF_8));
                List<String> problems = refreshed.validate(DEFS);
                if (!problems.isEmpty()) {
                    rep.addAll(problems.stream().map(problem -> "Каталог: " + problem).toList());
                    continue;
                }
                STRUCTURE_CATALOG = refreshed;
                rep.add("Каталог структур обновлён: " + refreshed.structureClasses().size()
                        + " классов, " + refreshed.identityDimensions().size() + " измерений совместимости");
            } catch (Exception error) {
                rep.add("Каталог структур не обновлён: " + entry.getKey() + " — " + error.getMessage());
            }
        }
        DEFS_RAW = raw;
        Blueprints.clearCache();
        return rep;
    }
    public static final Map<String, Colony> COLONIES = new ConcurrentHashMap<>();
    /** Registry identity changes only when a Colony is inserted, replaced or removed. */
    private static volatile long colonyRegistryRevision;
    private static volatile long colonySimulationIndexRevision = Long.MIN_VALUE;
    private record TradeViews(Map<String, Settlement> settlements,
                              Map<String, dev.worldforge.settle.TradeNetwork.Location> anchors) { }
    private static volatile TradeViews cachedTradeViews = new TradeViews(Map.of(), Map.of());
    private static volatile long tradeViewsRevision = Long.MIN_VALUE;

    private static void markColonyRegistryChanged(Colony colony) {
        colonyRegistryRevision++;
        ColonyInterestIndex.upsert(colony);
    }

    private static void markColonyRegistryRemoved(String colonyId) {
        colonyRegistryRevision++;
        ColonyInterestIndex.remove(colonyId);
    }

    private static void resetColonyRegistryIndex() {
        colonyRegistryRevision++;
        ColonyInterestIndex.clear();
    }

    /** Rebuilds the large trade lookup maps only when the canonical colony registry changes. */
    private static TradeViews tradeViews() {
        long revision = colonyRegistryRevision;
        TradeViews current = cachedTradeViews;
        if (tradeViewsRevision == revision) return current;
        synchronized (SettleMod.class) {
            revision = colonyRegistryRevision;
            if (tradeViewsRevision == revision) return cachedTradeViews;
            Map<String, Settlement> settlements = new LinkedHashMap<>();
            Map<String, dev.worldforge.settle.TradeNetwork.Location> anchors = new LinkedHashMap<>();
            for (Colony colony : COLONIES.values()) {
                if (colony == null || colony.s == null || colony.s.id == null || colony.s.id.isBlank()) continue;
                settlements.put(colony.s.id, colony.s);
                anchors.put(colony.s.id,
                        new dev.worldforge.settle.TradeNetwork.Location(colony.dim, colony.cx, colony.cz));
            }
            cachedTradeViews = new TradeViews(
                    Collections.unmodifiableMap(new LinkedHashMap<>(settlements)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(anchors)));
            tradeViewsRevision = revision;
            return cachedTradeViews;
        }
    }
    private static final Map<String, String> WORKER_COLONY_BY_PERSON = new HashMap<>();
    private static dev.worldforge.mod.api.DomainMutationRevisions.Snapshot workerOwnershipRevision;
    private static boolean workerOwnershipIndexBuilt;
    private static final Map<String, Set<String>> MILITARY_DEPARTURE_COLONIES = new HashMap<>();
    private static boolean militaryDepartureIndexBuilt;
    /** A map blueprint is imported once per dimension/map after the durable settlement snapshot succeeds. */
    private static final Set<String> IMPORTED_BLUEPRINTS = ConcurrentHashMap.newKeySet();
    public static dev.worldforge.settle.TradeNetwork NETWORK = new dev.worldforge.settle.TradeNetwork();
    private static long ticks;
    private static int lifeRegistryCursor;
    private static void refreshColonySimulationIndex(int interval, long tick, long worldMinute) {
        if (interval < 1) return;
        long registryRevision = colonyRegistryRevision;
        if (colonySimulationIndexInterval == interval
                && colonySimulationIndexRevision == registryRevision) return;
        Map<Integer, List<Colony>> mutable = new HashMap<>();
        Set<String> activeIds = new HashSet<>();
        for (Colony colony : COLONIES.values()) {
            if (colony == null || colony.s == null || colony.s.id == null || colony.s.id.isBlank()) continue;
            int phase = ColonySimulationCadence.phase(colony.s.id, interval);
            mutable.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(colony);
            activeIds.add(colony.s.id);
            LAST_SIMULATED_WORLD_MINUTE.putIfAbsent(colony.s.id, worldMinute);
        }
        Map<Integer, List<Colony>> frozen = new HashMap<>();
        mutable.forEach((phase, colonies) -> {
            colonies.sort(Comparator.comparing(colony -> colony.s.id));
            frozen.put(phase, List.copyOf(colonies));
        });
        LAST_SIMULATED_WORLD_MINUTE.keySet().retainAll(activeIds);
        COLONY_SIMULATION_PHASES = Map.copyOf(frozen);
        colonySimulationIndexInterval = interval;
        colonySimulationIndexBuiltAt = tick;
        colonySimulationIndexRevision = registryRevision;
    }

    private static List<Colony> coloniesDueForSimulation(long tick, int interval) {
        if (interval < 1 || tick < 1) return List.of();
        int phase = (int) Math.floorMod(tick - 1L, interval);
        return COLONY_SIMULATION_PHASES.getOrDefault(phase, List.of());
    }

    /** Last shared-calendar minute simulated by each colony; runtime-only and rebuilt on server start. */
    private static final Map<String, Long> LAST_SIMULATED_WORLD_MINUTE = new HashMap<>();
    /** Rotating per-colony resident cursor: only a bounded slice receives a life-AI decision per pass. */
    private static final Map<String, Integer> LIFE_AI_CURSORS = new HashMap<>();
    /** Short-lived resident indexes avoid repeated O(residents) companion searches for each resident. */
    private static final Map<String, ResidentLifeIndex> RESIDENT_LIFE_INDEXES = new HashMap<>();
    private static final long RESIDENT_LIFE_INDEX_REFRESH_TICKS = 100L;

    private static final class ResidentLifeIndex {
        final int workerCount;
        final long builtAtTick;
        final boolean hasCompanion;
        final boolean hasLeisure;
        final Map<String, dev.worldforge.settle.Worker> byPerson;
        final Map<Long, List<dev.worldforge.settle.Worker>> bySpatialCell;

        ResidentLifeIndex(int workerCount, long builtAtTick, boolean hasCompanion, boolean hasLeisure,
                          Map<String, dev.worldforge.settle.Worker> byPerson,
                          Map<Long, List<dev.worldforge.settle.Worker>> bySpatialCell) {
            this.workerCount = workerCount;
            this.builtAtTick = builtAtTick;
            this.hasCompanion = hasCompanion;
            this.hasLeisure = hasLeisure;
            this.byPerson = byPerson;
            this.bySpatialCell = bySpatialCell;
        }
    }

    private static long residentSpatialCell(double coordinate) {
        return (long) Math.floor(coordinate / 32.0);
    }

    private static long residentSpatialKey(long x, long z) {
        return (x << 32) ^ (z & 0xffffffffL);
    }

    private static ResidentLifeIndex residentLifeIndex(Colony colony, long serverTick) {
        String id = colony.s.id;
        List<dev.worldforge.settle.Worker> workers = colony.s.workers;
        ResidentLifeIndex cached = RESIDENT_LIFE_INDEXES.get(id);
        if (cached != null && cached.workerCount == workers.size()
                && serverTick >= cached.builtAtTick
                && serverTick - cached.builtAtTick < RESIDENT_LIFE_INDEX_REFRESH_TICKS) return cached;

        Map<String, dev.worldforge.settle.Worker> byPerson = new HashMap<>();
        Map<Long, List<dev.worldforge.settle.Worker>> byCell = new HashMap<>();
        int eligible = 0;
        for (dev.worldforge.settle.Worker worker : workers) {
            if (worker.person == null || worker.person.isBlank()) continue;
            eligible++;
            byPerson.putIfAbsent(worker.person, worker);
            long cellX = residentSpatialCell(worker.x), cellZ = residentSpatialCell(worker.z);
            byCell.computeIfAbsent(residentSpatialKey(cellX, cellZ), ignored -> new ArrayList<>()).add(worker);
        }
        boolean hasLeisure = colony.s.buildings.stream().anyMatch(building ->
                building.state == Building.State.DONE && !building.paused && building.def.category != null
                        && (building.def.category.equalsIgnoreCase("leisure")
                        || building.def.id.contains("tavern") || building.def.id.contains("inn")));
        ResidentLifeIndex refreshed = new ResidentLifeIndex(workers.size(), serverTick, eligible > 1,
                hasLeisure, byPerson, byCell);
        RESIDENT_LIFE_INDEXES.put(id, refreshed);
        return refreshed;
    }

    private static dev.worldforge.settle.Worker nearestResidentCompanion(
            dev.worldforge.settle.Worker worker, ResidentLifeIndex index, CharacterRegistry registry) {
        long cellX = residentSpatialCell(worker.x), cellZ = residentSpatialCell(worker.z);
        double nearest = 32.0 * 32.0;
        dev.worldforge.settle.Worker best = null;
        for (long dx = -2; dx <= 2; dx++) for (long dz = -2; dz <= 2; dz++) {
            List<dev.worldforge.settle.Worker> candidates =
                    index.bySpatialCell.get(residentSpatialKey(cellX + dx, cellZ + dz));
            if (candidates == null) continue;
            for (dev.worldforge.settle.Worker other : candidates) {
                if (other == worker || other.person == null || other.person.isBlank()) continue;
                double offsetX = worker.x - other.x, offsetZ = worker.z - other.z;
                double distance = offsetX * offsetX + offsetZ * offsetZ;
                if (distance >= nearest) continue;
                Character candidate = resolvePerson(registry, other.person);
                if (candidate != null && candidate.alive) {
                    nearest = distance;
                    best = other;
                }
            }
        }
        return best;
    }
    /** Phase buckets avoid scanning the complete colony registry on every server tick. */
    private static Map<Integer, List<Colony>> COLONY_SIMULATION_PHASES = Map.of();
    private static long colonySimulationIndexBuiltAt = Long.MIN_VALUE;
    private static int colonySimulationIndexInterval = -1;
    /** Static profession definitions are synchronized once per server lifecycle, not every tick. */
    private static boolean professionsSynced;
    /** Fail-closed gate when an army logistics transfer cannot persist its ledger/settlement checkpoint. */
    private static volatile boolean logisticsPersistenceBlocked;
    private static long lastResidentAuditTick = Long.MIN_VALUE;
    private static final Set<String> REPORTED_RESIDENT_LINK_ERRORS = ConcurrentHashMap.newKeySet();

    public SettleMod() {
        dev.worldforge.mod.WorldForgeSnapshotExporter.registerContributor(SettleMod::appendAtlasFeatures);
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        bus.addListener(this::attributes);
        bus.addListener(this::tabs);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new SettleWand.Events());
        MinecraftForge.EVENT_BUS.register(new BuildingSite.Events());
        MinecraftForge.EVENT_BUS.register(new ResidentInteraction());
        MinecraftForge.EVENT_BUS.register(new TaxService());
        MinecraftForge.EVENT_BUS.register(new VanillaVillageAdapter());
        MinecraftForge.EVENT_BUS.register(new SettlementCaravans());
        // Settlement sovereignty is changed only by canonical realm grants (bindEmergentRealm*).
        // Generic map edit/action notifications are not an ownership authority.
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = MOD_ID,
            bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,
            value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class ClientEvents {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void clientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            event.enqueueWork(() -> net.minecraft.client.gui.screens.MenuScreens.register(
                    RESIDENT_DIALOGUE_MENU.get(), ResidentDialogueScreen::new));
        }
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
            String realmId = colony.realmId != null && !colony.realmId.isBlank()
                    ? colony.realmId : realm == null ? "" : realm.id;
            String raceId = realm == null ? "" : propertyString(realm.props, "race");
            String cultureId = realm == null ? "" : propertyString(realm.props, "culture");
            String provinceFeatureId = province == null ? "" :
                    "worldforge:" + dimension + ":" + map.name + ":province:" + province.key;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    colony.cx, colony.cz);
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            attrs.put("population", Integer.toString(colony.s.population()));
            attrs.put("workforce", Integer.toString(colony.s.workers.size()));
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
                Colony.Site site=colony.sites.get(building.uid);
                int footprintWidth=site!=null&&site.sx>0?site.sx:building.def.sizeX;
                int footprintDepth=site!=null&&site.sz>0?site.sz:building.def.sizeZ;
                int x=site!=null?site.x+footprintWidth/2:colony.worldX(building);
                int z=site!=null?site.z+footprintDepth/2:colony.worldZ(building);
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
                int minBuildingX=site!=null?site.x:x-footprintWidth/2;
                int minBuildingZ=site!=null?site.z:z-footprintDepth/2;
                int maxBuildingX=minBuildingX+Math.max(1,footprintWidth);
                int maxBuildingZ=minBuildingZ+Math.max(1,footprintDepth);
                List<Object> footprint = List.of(
                        atlasVertex(minBuildingX,by,minBuildingZ),atlasVertex(maxBuildingX,by,minBuildingZ),
                        atlasVertex(maxBuildingX,by,maxBuildingZ),atlasVertex(minBuildingX,by,maxBuildingZ),
                        atlasVertex(minBuildingX,by,minBuildingZ));
                buildingFeature.put("rings", List.of(footprint));
                features.add(buildingFeature);
            }

            // Planned parcels and road centre-lines share the exact colony/world coordinate frame.
            for (dev.worldforge.settle.SettlementMap.Parcel parcel : colony.map.parcels.values()) {
                int x0=colony.cx+parcel.x, z0=colony.cz+parcel.z;
                int x1=x0+parcel.width, z1=z0+parcel.depth;
                int py=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x0+parcel.width/2,z0+parcel.depth/2);
                Map<String,String> parcelAttrs=new java.util.LinkedHashMap<>();
                parcelAttrs.put("settlement_id",colony.s.id);
                parcelAttrs.put("parcel_id",parcel.id);
                parcelAttrs.put("zone",parcel.zone.name().toLowerCase(java.util.Locale.ROOT));
                parcelAttrs.put("owner",parcel.owner==null?"":parcel.owner);
                Map<String,Object> parcelFeature=atlasFeature(
                        "worldforge:settlement:"+dimension+":"+colony.s.id+":parcel:"+parcel.id,
                        "land_parcel",parcel.id,dimension,x0+parcel.width/2,py,z0+parcel.depth/2,
                        realmId,parcel.zone.name().toLowerCase(java.util.Locale.ROOT),parcelAttrs);
                parcelFeature.put("territory_id",provinceFeatureId);
                parcelFeature.put("rings",List.of(List.of(atlasVertex(x0,py,z0),atlasVertex(x1,py,z0),
                        atlasVertex(x1,py,z1),atlasVertex(x0,py,z1),atlasVertex(x0,py,z0))));
                features.add(parcelFeature);
            }
            for (dev.worldforge.settle.SettlementMap.Road road : colony.map.roads.values()) {
                if (road.path==null||road.path.size()<2) continue;
                List<Object> path=new ArrayList<>();
                int pathStep=Math.max(1,(int)Math.ceil((road.path.size()-1)/4095.0));
                for (int index=0;index<road.path.size();index+=pathStep) {
                    dev.worldforge.settle.SettlementMap.Node node=road.path.get(index);
                    int wx=colony.cx+node.gx()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                            +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                    int wz=colony.cz+node.gz()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                            +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                    dev.worldforge.settle.SettlementMap.Cell cell=colony.map.cell(node.gx(),node.gz());
                    int ry=cell==null?y:cell.height;
                    path.add(atlasVertex(wx,ry,wz));
                }
                if((road.path.size()-1)%pathStep!=0){
                    dev.worldforge.settle.SettlementMap.Node node=road.path.get(road.path.size()-1);
                    int wx=colony.cx+node.gx()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                            +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                    int wz=colony.cz+node.gz()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                            +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                    dev.worldforge.settle.SettlementMap.Cell cell=colony.map.cell(node.gx(),node.gz());
                    path.add(atlasVertex(wx,cell==null?y:cell.height,wz));
                }
                var first=road.path.get(0);
                int rx=colony.cx+first.gx()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                        +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                int rz=colony.cz+first.gz()*dev.worldforge.settle.SettlementMap.CELL_SIZE
                        +dev.worldforge.settle.SettlementMap.CELL_SIZE/2;
                Map<String,String> roadAttrs=new java.util.LinkedHashMap<>();
                roadAttrs.put("settlement_id",colony.s.id);
                roadAttrs.put("road_id",Integer.toString(road.id));
                roadAttrs.put("surface",road.surface);
                roadAttrs.put("built_segments",Integer.toString(road.builtSegments));
                roadAttrs.put("segments",Integer.toString(road.path.size()-1));
                Map<String,Object> roadFeature=atlasFeature(
                        "worldforge:settlement:"+dimension+":"+colony.s.id+":road:"+road.id,
                        "settlement_road","Road "+road.id,dimension,rx,y,rz,realmId,road.surface,roadAttrs);
                roadFeature.put("territory_id",provinceFeatureId);
                roadFeature.put("path",path);
                features.add(roadFeature);
            }
        }

        // Active settlement consignments are projected onto the same Atlas world snapshot as roads and settlements.
        for (dev.worldforge.settle.TradeNetwork.Shipment shipment : NETWORK.shipments.values()) {
            if (!shipment.physical() || !dimension.equals(shipment.fromDimension)
                    || !(shipment.status.equals("в пути") || shipment.status.equals("ожидает места")
                    || shipment.status.equals("ожидает расчёта"))) continue;
            double[][] route = shipment.path();
            if (route.length < 2) route = new double[][]{{shipment.fromX,shipment.fromZ},{shipment.toX,shipment.toZ}};
            List<Object> path = new ArrayList<>();
            for (double[] point : route) {
                int px=(int)Math.floor(point[0]), pz=(int)Math.floor(point[1]);
                int py=level.hasChunkAt(new net.minecraft.core.BlockPos(px,level.getMinBuildHeight(),pz))
                        ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,px,pz) : 64;
                path.add(atlasVertex(point[0],py+0.15,point[1]));
            }
            double[] head = SettlementCaravans.routePositionForSnapshot(route, shipment.progress(),
                    shipment.fromX, shipment.fromZ, shipment.toX, shipment.toZ);
            int hx=(int)Math.floor(head[0]), hz=(int)Math.floor(head[1]);
            int hy=level.hasChunkAt(new net.minecraft.core.BlockPos(hx,level.getMinBuildHeight(),hz))
                    ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,hx,hz) : 64;
            Map<String,String> attrs=new LinkedHashMap<>();
            attrs.put("shipment_id",Long.toString(shipment.id));
            attrs.put("route_id",Integer.toString(shipment.routeId));
            attrs.put("from_settlement",shipment.from);
            attrs.put("to_settlement",shipment.to);
            attrs.put("resource",shipment.res);
            attrs.put("quantity",Double.toString(shipment.quantity));
            attrs.put("progress",Double.toString(shipment.progress()));
            attrs.put("remaining_seconds",Double.toString(shipment.remainingSeconds()));
            attrs.put("status",shipment.status);
            Map<String,Object> feature=atlasFeature("worldforge:shipment:"+shipment.id,
                    "trade_caravan","Caravan "+shipment.id,dimension,head[0],hy,head[1],
                    "",shipment.res,attrs);
            feature.put("path",path);
            features.add(feature);
        }
    }

    private static Map<String,Object> atlasVertex(double x,double y,double z) {
        Map<String,Object> vertex=new java.util.LinkedHashMap<>();
        vertex.put("x",x);vertex.put("y",y);vertex.put("z",z);
        return vertex;
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

    /** Realm ownership is explicit canonical state; raster title overlays are never ownership authority. */
    private static String realmOf(Colony colony, ServerLevel level) {
        if (colony == null || level == null
                || !colony.dim.equals(level.dimension().location().toString())) return "";
        return colony.realmId == null ? "" : colony.realmId;
    }

    public static String realmOf(Colony colony) {
        return colony == null || colony.realmId == null ? "" : colony.realmId;
    }

    /** Optional bridge to wf-trade's existing province-graph caravan planner; no duplicate route graph. */
    private static double[][] planShipmentPath(String from, String to,
            dev.worldforge.settle.TradeNetwork.Location origin,
            dev.worldforge.settle.TradeNetwork.Location destination) {
        if (origin == null || destination == null || !origin.dimension().equals(destination.dimension()))
            return new double[0][];
        Colony a = COLONIES.get(from), b = COLONIES.get(to);
        if (a == null || b == null) return new double[][]{{origin.x(), origin.z()}, {destination.x(), destination.z()}};
        try {
            Class<?> worldLayer = Class.forName("dev.worldforge.trade.mc.WorldLayer");
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            ServerLevel sourceLevel = server == null ? null : a.level(server);
            ServerLevel destinationLevel = server == null ? null : b.level(server);
            String sourceRealm = sourceLevel == null ? realmOf(a) : realmOf(a, sourceLevel);
            String destinationRealm = destinationLevel == null ? realmOf(b) : realmOf(b, destinationLevel);
            Object result = worldLayer.getMethod("settlementRoute", String.class, String.class,
                    double.class, double.class, double.class, double.class)
                    .invoke(null, sourceRealm, destinationRealm, origin.x(), origin.z(), destination.x(), destination.z());
            if (result instanceof double[][] path && path.length >= 2) return path;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // wf-trade is optional. Keep the shipment functional with a direct strategic route.
        }
        return new double[][]{{origin.x(), origin.z()}, {destination.x(), destination.z()}};
    }

    public static Colony colony(String id) { return COLONIES.get(id); }

    /** Exact, durable identity for a vanilla structure even when its colony keeps an emergent ID. */
    public static Colony colonyByGenesisOperation(String operationId) {
        if (operationId == null || operationId.isBlank()) return null;
        return COLONIES.values().stream()
                .filter(colony -> colony != null && (operationId.equals(colony.genesisOperationId)
                        || ("minecraft:village:pending:" + operationId).equals(colony.genesisOperationId)))
                .min(java.util.Comparator.comparing(colony -> colony.s.id))
                .orElse(null);
    }

    /** Finds an already-adopted vanilla village near a generated founding site. */
    public static Colony nearbyVanillaVillage(String dimension, int x, int z, int radius) {
        return nearbyVanillaVillage(dimension, x, z, radius, java.util.Set.of());
    }

    public static Colony nearbyVanillaVillage(String dimension, int x, int z, int radius,
                                               java.util.Set<String> excludedSettlementIds) {
        if (dimension == null || dimension.isBlank() || radius < 0) return null;
        java.util.Set<String> excluded = excludedSettlementIds == null ? java.util.Set.of() : excludedSettlementIds;
        long limit = (long) radius * radius;
        return COLONIES.values().stream()
                .filter(colony -> colony != null && dimension.equals(colony.dim)
                        && !excluded.contains(colony.s.id)
                        && colony.genesisOperationId != null
                        && colony.genesisOperationId.startsWith("minecraft:village:"))
                .filter(colony -> {
                    long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                    return dx * dx + dz * dz <= limit;
                })
                .min(java.util.Comparator
                        .comparingLong((Colony colony) -> {
                            long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                            return dx * dx + dz * dz;
                        })
                        .thenComparing(colony -> colony.s.id))
                .orElse(null);
    }

    /** Finds the nearest named settlement anchor imported from this exact image-map package.
     * WorldSim binds a generated population band to this canonical Colony instead of creating
     * a second settlement beside the authored map anchor.
     */
    public static Colony nearbyBlueprintSettlement(String dimension, String mapId, int x, int z, int radius,
                                                    java.util.Set<String> excludedSettlementIds) {
        if (dimension == null || dimension.isBlank() || mapId == null || mapId.isBlank() || radius < 0) return null;
        java.util.Set<String> excluded = excludedSettlementIds == null ? java.util.Set.of() : excludedSettlementIds;
        long limit = (long) radius * radius;
        return COLONIES.values().stream()
                .filter(colony -> colony != null && colony.s != null && dimension.equals(colony.dim)
                        && mapId.equals(colony.sourceMapId)
                        && colony.blueprintSettlementId != null && !colony.blueprintSettlementId.isBlank()
                        && !excluded.contains(colony.s.id))
                .filter(colony -> {
                    long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                    return dx * dx + dz * dz <= limit;
                })
                .min(java.util.Comparator
                        .comparingLong((Colony colony) -> {
                            long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                            return dx * dx + dz * dz;
                        })
                        .thenComparing(colony -> colony.s.id))
                .orElse(null);
    }

    /** Finds an emergent colony when vanilla structure discovery arrives after emergence. */
    public static Colony nearbyEmergentSettlement(String dimension, int x, int z, int radius) {
        if (dimension == null || dimension.isBlank() || radius < 0) return null;
        long limit = (long) radius * radius;
        return COLONIES.values().stream()
                .filter(colony -> colony != null && dimension.equals(colony.dim)
                        && colony.genesisOperationId != null
                        && colony.genesisOperationId.startsWith("worldsim:settlement:"))
                .filter(colony -> {
                    long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                    return dx * dx + dz * dz <= limit;
                })
                .min(java.util.Comparator
                        .comparingLong((Colony colony) -> {
                            long dx = (long) colony.cx - x, dz = (long) colony.cz - z;
                            return dx * dx + dz * dz;
                        })
                        .thenComparing(colony -> colony.s.id))
                .orElse(null);
    }

    /**
     * Finds a deterministic nearby founding position that preserves the 128-block spacing
     * invariant without merging two independent population bands into one resident ledger.
     */
    public static int[] availableEmergentPosition(String dimension, int x, int z, int searchRadius) {
        if (dimension == null || dimension.isBlank() || searchRadius < 0) return null;
        List<long[]> occupied = COLONIES.values().stream()
                .filter(colony -> colony != null && colony.s != null && dimension.equals(colony.dim))
                .filter(colony -> colony.s.population() > 0 || !colony.residentCharacterIds.isEmpty()
                        || !colony.s.buildings.isEmpty() || !colony.s.workers.isEmpty())
                .map(colony -> new long[]{colony.cx, colony.cz})
                .toList();
        return SettlementSpacing.findAvailablePosition(x, z, occupied, searchRadius);
    }

    private record PersonBinding(CharacterRegistry registry, Character character) {}

    /** Resolve a resident body to the Character and the persistence partition that actually owns that person. */
    private static PersonBinding resolvePersonBinding(CharacterRegistry fallbackRegistry, String id) {
        if (id == null || id.isBlank()) return null;
        Character global = PeopleMod.authority().getGlobal(id);
        if (global != null) {
            CharacterRegistry owner = PeopleMod.authority().registryForGlobal(id);
            return owner == null ? null : new PersonBinding(owner, global);
        }
        // Pre-global UUID worker saves can only be resolved in their original local map partition.
        Character legacy = fallbackRegistry == null ? null : fallbackRegistry.get(id);
        return legacy == null ? null : new PersonBinding(fallbackRegistry, legacy);
    }

    private static Character resolvePerson(CharacterRegistry fallbackRegistry, String id) {
        PersonBinding binding = resolvePersonBinding(fallbackRegistry, id);
        return binding == null ? null : binding.character();
    }

    public static Character residentCharacter(WFWorker body) {
        if (body == null) return null;
        Colony colony = COLONIES.get(body.colonyId);
        Worker worker = body.worker();
        CharacterRegistry registry = registryForColony(colony);
        return registry == null || worker == null || worker.person == null || worker.person.isBlank()
                ? null : resolvePerson(registry, worker.person);
    }

    public static boolean ownsAny(String uuid) {
        for (Colony c : COLONIES.values()) if (uuid.equals(c.owner)) return true;
        return false;
    }

    /**
     * Pulls real food from the nearest settlements belonging to this realm and dimension into the
     * army's food account. Each source settlement has its own durable idempotency reference.
     */
    public static double transferFoodToArmy(ServerLevel level, String realmId,
            String targetAccountId, String targetOwnerId, double requested,
            double armyX, double armyZ, String referencePrefix) {
        if (level == null || realmId == null || realmId.isBlank()
                || targetAccountId == null || targetAccountId.isBlank()
                || targetOwnerId == null || targetOwnerId.isBlank()
                || !Double.isFinite(requested) || requested <= 0
                || !Double.isFinite(armyX) || !Double.isFinite(armyZ)
                || referencePrefix == null || referencePrefix.isBlank()
                || actionsBlocked()) return 0;
        MinecraftServer server = level.getServer();
        if (server == null) return 0;

        String dimension = level.dimension().location().toString();
        List<Colony> sources = COLONIES.values().stream()
                .filter(colony -> colony != null && dimension.equals(colony.dim)
                        && realmId.equals(realmOf(colony, level)))
                .sorted(java.util.Comparator.comparingDouble(colony ->
                        (colony.cx - armyX) * (colony.cx - armyX) + (colony.cz - armyZ) * (colony.cz - armyZ)))
                .toList();
        double remaining = requested;
        double delivered = 0;
        boolean changed = false;
        for (Colony colony : sources) {
            if (remaining <= 1e-9) break;
            double available = colony.s.amount("food");
            double amount = Math.min(available, remaining);
            if (amount <= 1e-9) continue;
            String referenceStem = referencePrefix + ":" + colony.s.id;
            // The caller's army/day operation ID plus the source settlement is already
            // globally unique. Keep it stable for retries instead of scanning this account's
            // entire archived journal to manufacture a monotonically increasing suffix.
            String reference = referenceStem;
            boolean moved = colony.s.transferResourceToAccount("food", targetAccountId, targetOwnerId,
                    amount, TreasuryLedger.Kind.LOGISTICS, "army-field-rations", reference);
            if (!moved) continue;
            delivered += amount;
            remaining -= amount;
            changed = true;
        }
        if (!changed) return 0;

        // Persist the canonical treasury first. A stale settlement stock mirror can never overwrite it:
        // Settlement.amount now always reads the shared ledger, then the settlement projection is flushed.
        if (!WorldForgeMod.saveTreasuryNow(server)) {
            logisticsPersistenceBlocked = true;
            LOGGER.error("wf_settle: army food transfer reached memory but canonical treasury save failed; logistics paused");
            return 0;
        }
        if (!saveDurably(server)) {
            logisticsPersistenceBlocked = true;
            LOGGER.error("wf_settle: army food transfer committed in treasury but settlement projection save failed; logistics paused");
        }
        return delivered;
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

    /** Validated completed recruitment building owned by the player and staffed by a trainer. */
    public record RecruitmentSite(Colony colony, Building building, BlockPos spawn) {}

    public static RecruitmentSite recruitmentSite(ServerLevel level, String owner, String buildingId,
                                                   double playerX, double playerZ) {
        String dimension = level.dimension().location().toString();
        Colony bestColony = null;
        Building bestBuilding = null;
        double bestDistance = 128.0 * 128.0;
        for (Colony colony : COLONIES.values()) {
            if (!dimension.equals(colony.dim)) continue;
            if (!colony.owner.isEmpty() && !colony.owner.equals(owner)) continue;
            for (Building building : colony.s.buildings) {
                if (!building.def.id.equals(buildingId) || building.state != Building.State.DONE || building.paused) continue;
                Colony.Site physicalSite = colony.sites.get(building.uid);
                if (physicalSite == null) continue; // A simulated building is not a recruitment facility until its world structure exists.
                Blueprints.Plan plan = Blueprints.get(level, building.def);
                if (plan == null || !plan.valid() || physicalSite.placed < plan.blocks().size()) continue;
                boolean staffed = colony.s.assigned(building.uid).stream()
                        .anyMatch(worker -> worker.lifeAction.isBlank() && "trainer".equals(worker.workRole()));
                if (!staffed) continue;
                double x = colony.worldX(building), z = colony.worldZ(building);
                double distance = (x - playerX) * (x - playerX) + (z - playerZ) * (z - playerZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestColony = colony;
                    bestBuilding = building;
                }
            }
        }
        if (bestColony == null) return null;
        return new RecruitmentSite(bestColony, bestBuilding, bestColony.workPoint(level, bestBuilding));
    }

    /** Deducts materials only after the caller has prepared the whole recruit batch. */
    public static boolean payRecruitment(RecruitmentSite site, java.util.Map<String, Integer> cost) {
        return site != null && site.colony().s.spend(cost);
    }

    /** Crash-replay-safe recruitment debit. Treasury journal is committed before the settlement projection. */
    public static boolean payRecruitmentOnce(String colonyId, java.util.Map<String, Integer> cost,
                                             String operationId, MinecraftServer server) {
        if (server == null || colonyId == null || colonyId.isBlank()
                || operationId == null || operationId.isBlank() || actionsBlocked()) return false;
        Colony colony = COLONIES.get(colonyId);
        if (colony == null || colony.s == null) return false;
        if (!colony.s.spendOnce(cost, operationId)) return false;
        if (!WorldForgeMod.saveTreasuryNow(server)) {
            colony.actionPersistenceBlocked = true;
            LOGGER.error("wf_settle: recruitment debit {} is in memory but treasury persistence failed; settlement actions paused",
                    operationId);
            return false;
        }
        if (!saveDurably(server)) {
            colony.actionPersistenceBlocked = true;
            LOGGER.error("wf_settle: recruitment debit {} is in treasury but settlement snapshot failed; settlement actions paused",
                    operationId);
            return false;
        }
        return true;
    }

    /** Rolls back a just-paid recruitment batch if its second payment leg fails. */
    public static void refundRecruitment(RecruitmentSite site, java.util.Map<String, Integer> cost) {
        if (site == null || cost == null) return;
        for (var entry : cost.entrySet()) site.colony().s.add(entry.getKey(), entry.getValue());
    }

    /** Map pixels are geography, not sovereignty; founding checks wait for canonical territorial ownership. */
    private static String politicalOwnerAt(ServerLevel level, int x, int z) { return ""; }

    /**
     * A player cannot establish a new colony inside another realm by simply placing
     * a settlement marker. Legal grants must be wired to a canonical political
     * permission/charter before any bypass can be exposed.
     */
    private static void requireFoundingTerritoryPermission(ServerLevel level, String name, BlockPos position) {
        String owner = politicalOwnerAt(level, position.getX(), position.getZ());
        if (owner.isBlank()) return;
        String settlementName = name == null || name.isBlank() ? "новое поселение" : name.trim();
        dev.worldforge.mod.api.WorldForgeApi.postEvent("diplomacy",
                "Попытка основания поселения «" + settlementName + "» на территории государства "
                        + owner + " пресечена пограничной властью",
                List.of(owner), dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute());
        throw new IllegalStateException("Эта земля принадлежит государству " + owner
                + ". Основание поселения требует разрешения владельца или законного изменения границы.");
    }

    private static String normalizeSettlementName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Enforces one canonical settlement display name across every dimension in this save. */
    private static void requireUniqueSettlementName(String name, String exceptId) {
        String normalized = normalizeSettlementName(name);
        if (normalized.isBlank()) throw new IllegalArgumentException("Settlement name is required");
        for (Colony existing : COLONIES.values()) {
            if (existing == null || existing.s == null || Objects.equals(existing.s.id, exceptId)) continue;
            if (normalized.equals(normalizeSettlementName(existing.s.name)))
                throw new IllegalStateException("Settlement name is already reserved: " + name);
        }
    }

    /** Закладывает поселение: 4 рабочих, склад дерево 60 / еда 40 / камень 10. */
    public static Colony found(ServerLevel lv, String name, BlockPos p) {
        return foundInternal(lv, name, p, "");
    }

    /** Idempotent Genesis-aware variant. The same operation always resolves to the same colony. */
    public static Colony found(ServerLevel lv, String name, BlockPos p, String genesisOperationId) {
        return foundInternal(lv, name, p, genesisOperationId);
    }

    private static Colony foundInternal(ServerLevel lv, String name, BlockPos p,
                                        String genesisOperationId) {
        String dimension = lv.dimension().location().toString();
        if (genesisOperationId != null && !genesisOperationId.isBlank()) {
            for (Colony existing : COLONIES.values()) {
                if (!genesisOperationId.equals(existing.genesisOperationId)) continue;
                if (dimension.equals(existing.dim)) return existing;
                throw new IllegalStateException("Genesis operation " + genesisOperationId
                        + " is already bound to dimension " + existing.dim + " and cannot create a second colony in " + dimension);
            }
        }
        // Check only after idempotent replay has been resolved: a committed founding
        // remains recoverable even if borders changed after the original operation.
        requireFoundingTerritoryPermission(lv, name, p);
        requireUniqueSettlementName(name, null);
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "settlement";
        String id = base;
        for (int i = 2; COLONIES.containsKey(id); i++) id = base + "_" + i;
        Settlement s = new Settlement(id, name, DEFS);
        s.setLedgerScope(dimension);
        s.types = TYPES;
        for (int i = 0; i < 4; i++) s.addWorker(null);
        s.add("wood", 60);
        s.add("food", 40);
        s.add("stone", 10);
        Colony c = new Colony(s, dimension, p.getX(), p.getZ());
        c.genesisOperationId = genesisOperationId == null ? "" : genesisOperationId;
        c.captureTerrain(lv);
        COLONIES.put(id, c);
        markColonyRegistryChanged(c);
        return c;
    }

    /** Creates a map-first settlement without fabricated workers, stock or buildings. */
    public static Colony foundEmergent(MinecraftServer server, String dimension, String id, String name,
                                       int x, int z, String operationId) {
        Colony existing = COLONIES.get(id);
        Colony colony = foundEmergentInMemory(server, dimension, id, name, x, z, operationId);
        if (existing == null && !saveDurably(server)) {
            COLONIES.remove(id, colony);
            markColonyRegistryRemoved(id);
            throw new IllegalStateException("Could not durably persist emergent settlement " + id);
        }
        return colony;
    }

    /** Creates a deterministic settlement projection in memory; caller batches one durable snapshot per map. */
    public static Colony foundEmergentInMemory(MinecraftServer server, String dimension, String id, String name,
                                                int x, int z, String operationId) {
        if (server == null || DEFS == null) throw new IllegalStateException("Settlement definitions are not ready");
        if (id == null || !id.matches("[a-z0-9_:-]{3,96}")) throw new IllegalArgumentException("Invalid emergent settlement id");
        String dim = dimension == null || dimension.isBlank() ? server.overworld().dimension().location().toString() : dimension;
        Colony existing = COLONIES.get(id);
        if (existing != null) {
            if (!Objects.equals(existing.genesisOperationId, operationId) || !Objects.equals(existing.dim, dim))
                throw new IllegalStateException("Emergent settlement identity collision: " + id);
            return existing;
        }
        // Blueprint records are empty geographic candidates. Only a new physical
        // emergent colony is subject to this active-settlement overlap guard.
        if (id.startsWith("emg_")) {
            final long minimumDistance = 128L;
            final long minimumDistanceSq = minimumDistance * minimumDistance;
            Colony conflict = ColonyInterestIndex.nearbyAt(dim, x, z,
                            SettlementSpacing.MINIMUM_CENTER_DISTANCE_BLOCKS).stream()
                    .filter(other -> other.s.population() > 0 || !other.residentCharacterIds.isEmpty()
                            || !other.s.buildings.isEmpty() || !other.s.workers.isEmpty())
                    .filter(other -> SettlementSpacing.conflicts(
                            (long) other.cx - x, (long) other.cz - z))
                    .min(Comparator.comparingLong(other -> {
                        long dx = (long) other.cx - x, dz = (long) other.cz - z;
                        return dx * dx + dz * dz;
                    })).orElse(null);
            if (conflict != null)
                throw new IllegalStateException("Emergent settlement " + id + " would overlap active settlement "
                        + conflict.s.id + " in " + dim + " (minimum center spacing 128 blocks)");
        }
        String canonicalName = name == null ? "" : name.trim();
        requireUniqueSettlementName(canonicalName, id);
        Settlement settlement = new Settlement(id, canonicalName, DEFS);
        settlement.setLedgerScope(dim);
        settlement.types = TYPES;
        Colony colony = new Colony(settlement, dim, x, z);
        colony.genesisOperationId = operationId == null ? "" : operationId;
        COLONIES.put(id, colony);
        markColonyRegistryChanged(colony);
        LOGGER.info("wf_settle: map-first settlement staged id={} dim={} x={} z={} (no synthetic workers or stock)",
                id, dim, x, z);
        return colony;
    }


    /**
     * Turns a map-first emergent settlement into a real, economically-backed village
     * when it enters the local physical LOD. The normal Settlement, BuildingSite and
     * WFWorker systems perform construction; this method only submits idempotent
     * founding stock and building orders. It never creates a parallel population.
     */
    private static boolean ensureFoundingStock(Colony colony, String resource, double amount, String operationId,
                                                TreasuryLedger.Kind kind, Set<String> newlyPosted) {
        String reference = "worldgen:" + colony.s.id + ":" + operationId + ":" + resource;
        TreasuryLedger ledger = TreasuryService.current();
        boolean alreadyPosted = ledger.hasTransaction(colony.s.ledgerAccountId(resource), kind, reference);
        boolean accepted = colony.s.addOnce(resource, amount, operationId);
        if (accepted && !alreadyPosted) newlyPosted.add(reference);
        return accepted;
    }

    /** Select a deterministic sampled buildable site; the search never loads chunks. */
    private static int[] findAutonomousBuildSite(Colony colony, String definitionId,
                                                  int preferredX, int preferredZ, int radius,
                                                  boolean preferFertile, boolean preferForest) {
        Defs.Building definition = colony.s.defs.buildings.get(definitionId);
        if (definition == null) return null;
        int[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (dev.worldforge.settle.SettlementMap.Cell cell : colony.map.cells()) {
            int x = cell.x(), z = cell.z();
            int dx = x - preferredX, dz = z - preferredZ;
            int distance = dx * dx + dz * dz;
            if (distance > radius * radius || cell.water) continue;
            try {
                colony.map.validatePlacement(definition.category, x, z, definition.sizeX, definition.sizeZ);
            } catch (IllegalArgumentException unsuitable) {
                continue;
            }
            if (colony.s.placeError(definitionId, x, z) != null) continue;
            double score = distance + cell.slope * 96.0;
            if (preferFertile) score -= cell.fertility * 180.0;
            if (preferForest) {
                String biome = cell.biome == null ? "" : cell.biome.toLowerCase(Locale.ROOT);
                score += biome.contains("forest") || biome.contains("wood") ? -220.0 : 140.0;
            }
            if (score < bestScore || (score == bestScore && (best == null || x < best[0]
                    || (x == best[0] && z < best[1])))) {
                bestScore = score;
                best = new int[]{x, z};
            }
        }
        return best;
    }

    /** Selects a structure against the settlement's immutable identity before site search/placement. */
    private static String identityStructure(Colony colony, String requested, long seed) {
        if (colony == null || colony.s == null) return requested;
        String selected = colony.s.defs.selectIdentityBuilding(requested, colony.identityRace, null,
                colony.identityCulture, colony.governmentId, colony.settlementType,
                colony.polityFormationId, colony.architectureStyleId, seed);
        Defs.Building selectedDefinition = selected == null ? null : colony.s.defs.buildings.get(selected);
        if (selectedDefinition != null && !STRUCTURE_CATALOG.recognizesStructure(selectedDefinition)) {
            LOGGER.warn("wf_settle: building {} rejected: structure_class '{}' is absent from the active structure catalogue",
                    selected, selectedDefinition.structureClass);
            return "";
        }
        if (selected == null || selected.isBlank()) {
            LOGGER.debug("wf_settle: no compatible structure candidate for {} (race={}, culture={}, government={}, type={})",
                    requested, colony.identityRace, colony.identityCulture, colony.governmentId, colony.settlementType);
            return "";
        }
        return selected;
    }

    private static boolean placeAutonomousIdentityBuilding(Colony colony, String requestId, String requested,
                                                         int preferredX, int preferredZ, int radius,
                                                         boolean preferFertile, boolean preferForest) {
        if (colony == null || colony.s == null || colony.s.placement(requestId) != null) return false;
        String candidate = identityStructure(colony, requested, requestId.hashCode());
        if (candidate.isBlank()) return false;
        int[] site = findAutonomousBuildSite(colony, candidate, preferredX, preferredZ, radius, preferFertile, preferForest);
        if (site == null) return false;
        int before = colony.s.count(candidate, false);
        colony.s.placeOnce(requestId, candidate, site[0], site[1]);
        return colony.s.count(candidate, false) > before;
    }

    /**
     * Adds one residential building only when canonical demographic pressure, food security,
     * public approval and the shared stock ledger all support expansion. Pending houses are
     * counted so repeated local ticks cannot queue an unbounded housing backlog.
     */
    private static boolean ensureAutonomousHousingGrowth(Colony colony) {
        if (colony == null || colony.s == null) return false;
        Settlement settlement = colony.s;
        if (!settlement.hasCanonicalPopulation() || !settlement.defs.buildings.containsKey("house")) return false;
        int completed = settlement.count("house", true);
        int planned = Math.max(0, settlement.count("house", false) - completed);
        int projectedHousing = settlement.housing() + planned * settlement.defs.buildings.get("house").housing;
        int population = settlement.population();
        if (population < Math.max(1, projectedHousing - 2)) return false;
        if (settlement.approval() < Settlement.APPROVAL_GROWTH_MIN) return false;
        double food = settlement.amount("food");
        double foodNet = settlement.producedPerMin("food") - settlement.consumedPerMin("food");
        if (food < Math.max(40, population * 1.5) || foodNet < -1e-6) return false;
        if (settlement.amount("wood") < settlement.defs.buildings.get("house").cost.getOrDefault("wood", 0)) return false;

        int ordinal = settlement.count("house", false);
        String request = "wfworldgen.expansion.house." + ordinal;
        if (settlement.placement(request) != null) return false;
        int ring = 28 + (ordinal / 8) * 16;
        int[][] anchors = {{-ring, -ring / 2}, {-ring / 2, -ring}, {ring / 2, -ring},
                {ring, -ring / 2}, {ring, ring / 2}, {ring / 2, ring},
                {-ring / 2, ring}, {-ring, ring / 2}};
        int[] anchor = anchors[ordinal % anchors.length];
        return placeAutonomousIdentityBuilding(colony, request, "house", anchor[0], anchor[1],
                Math.max(36, ring + 20), false, false);
    }

    /**
     * Adds at most one production building per reconciliation pass when the settlement's
     * actual stock and consumption show a persistent bottleneck. Recipes, staffing, storage,
     * prices and upkeep remain owned by the existing Defs/Settlement economy.
     */
    private static boolean ensureEconomicSpecialization(Colony colony) {
        if (colony == null || colony.s == null || !colony.s.hasCanonicalPopulation()) return false;
        Settlement settlement = colony.s;
        int population = settlement.population();
        if (population < 8 || settlement.approval() < Settlement.APPROVAL_GROWTH_MIN) return false;

        String candidate = null;
        int preferredX = 0, preferredZ = 0;
        boolean preferForest = false;

        // Stone is a common construction bottleneck; do not add extraction while reserves recover.
        if (settlement.defs.buildings.containsKey("stone_quarry")
                && settlement.count("stone_quarry", false) == 0
                && settlement.amount("stone") < Math.max(24, population * 0.8)
                && settlement.producedPerMin("stone") <= settlement.consumedPerMin("stone") + 0.05) {
            candidate = "stone_quarry";
            preferredX = 34;
            preferredZ = -24;
        }

        // Convert surplus timber into planks only when raw wood remains safely above reserve.
        if (candidate == null && settlement.defs.buildings.containsKey("sawmill")
                && settlement.count("sawmill", false) == 0
                && settlement.amount("planks") < Math.max(18, population * 0.45)
                && settlement.amount("wood") >= Math.max(60, population * 2.0)
                && settlement.producedPerMin("planks") <= settlement.consumedPerMin("planks") + 0.05) {
            candidate = "sawmill";
            preferredX = -36;
            preferredZ = -24;
            preferForest = true;
        }

        if (candidate == null) return false;
        String requestId = "wfworldgen.specialization." + candidate;
        if (settlement.placement(requestId) != null) return false;
        return placeAutonomousIdentityBuilding(colony, requestId, candidate, preferredX, preferredZ,
                64, false, preferForest);
    }

    /** Connects every autonomous building to the civic hub using the existing A* road graph. */
    private static boolean ensureAutonomousRoads(Colony colony) {
        if (colony == null || colony.s == null || colony.s.buildings.isEmpty()) return false;
        Building hub = colony.s.buildings.stream()
                .filter(b -> b != null && b.def != null && b.def.id.equals("village_hall"))
                .min(java.util.Comparator.comparingInt(b -> b.uid)).orElse(null);
        if (hub == null) return false;
        int hubX = hub.x;
        int hubZ = hub.z + Math.max(4, hub.def.sizeZ / 2 + 4);
        boolean changed = false;
        List<Building> buildings = new ArrayList<>(colony.s.buildings);
        buildings.sort(java.util.Comparator.comparingInt(b -> b.uid));
        for (Building building : buildings) {
            if (building == null || building.def == null || building.uid == hub.uid) continue;
            int accessX = building.x;
            int accessZ = building.z + Math.max(4, building.def.sizeZ / 2 + 4);
            if (colony.map.hasRoadBetween(hubX, hubZ, accessX, accessZ)) continue;
            try {
                colony.map.addInfrastructureRoad(hubX, hubZ, accessX, accessZ);
                changed = true;
            } catch (IllegalArgumentException terrainNotReady) {
                // Incomplete terrain samples are normal during streaming generation; retry next local tick.
            }
        }
        return changed;
    }

    /**
     * Turns a map-first emergent settlement into a physically grounded village when it enters
     * local LOD. The layout is chosen from real sampled Minecraft terrain; canonical domain
     * systems remain the only owners of stock, buildings and people.
     */
    public static boolean ensureAutonomousInfrastructure(MinecraftServer server, String settlementId) {
        Colony colony = COLONIES.get(settlementId);
        if (server == null || colony == null || colony.s == null
                || colony.genesisOperationId == null
                || !colony.genesisOperationId.startsWith("worldsim:settlement:")) return false;
        if (!SimulationLod.shouldMaterializeSettlement(colony.dim, colony.cx, colony.cz)) return false;
        ServerLevel level = colony.level(server);
        if (level == null) return false;
        BlockPos probe = new BlockPos(colony.cx, level.getMinBuildHeight(), colony.cz);
        if (!level.hasChunkAt(probe)) return false;

        Settlement settlement = colony.s;
        if (colony.autonomousFoundingPopulation <= 0) {
            colony.autonomousFoundingPopulation = Math.max(1, settlement.population());
            if (!saveDurably(server)) {
                colony.autonomousFoundingPopulation = 0;
                colony.actionPersistenceBlocked = true;
                LOGGER.error("wf_settle: founding population snapshot for {} could not be persisted; settlement frozen for recovery",
                        settlementId);
                return false;
            }
        }
        int foundingPopulation = colony.autonomousFoundingPopulation;
        int households = Math.max(1, Math.min(12, (foundingPopulation + 3) / 4));
        Set<String> newlyPostedStock = new LinkedHashSet<>();
        try {
            // Capture only loaded terrain. Missing cells are not guessed and are retried later.
            colony.captureTerrain(level, -48, -48, 48, 48);
            boolean validStock = true;
            validStock &= ensureFoundingStock(colony, "wood", 35, "founding-core",
                    TreasuryLedger.Kind.IMPORT, newlyPostedStock);
            validStock &= ensureFoundingStock(colony, "food",
                    Math.max(80, Math.min(500, foundingPopulation * 4.0)), "founding-food",
                    TreasuryLedger.Kind.IMPORT, newlyPostedStock);
            validStock &= ensureFoundingStock(colony, "stone", 20, "founding-stone",
                    TreasuryLedger.Kind.IMPORT, newlyPostedStock);
            if (!validStock) {
                // Earlier resource postings may already have succeeded; persist them before
                // returning so a later retry cannot leave an in-memory-only ledger mutation.
                if (!newlyPostedStock.isEmpty() && !WorldForgeMod.saveTreasuryNow(server)) {
                    colony.actionPersistenceBlocked = true;
                    LOGGER.error("wf_settle: partial founding stock for {} could not be persisted; settlement frozen for recovery",
                            settlementId);
                }
                return false;
            }

            int[][] preferredHouses = {
                    {-12, -8}, {-4, -12}, {4, -12}, {12, -8},
                    {-12, 4}, {-4, 12}, {4, 12}, {12, 4},
                    {-20, -8}, {20, -8}, {-20, 8}, {20, 8}
            };
            boolean changed = false;
            // Establish the civic/storage spine first: dependent village facilities can
            // only be placed after the hall has a durable domain building record.
            changed |= placeAutonomousIdentityBuilding(colony, "wfworldgen.village-hall", "village_hall",
                    0, 0, 20, false, false);
            changed |= placeAutonomousIdentityBuilding(colony, "wfworldgen.village-granary", "village_granary",
                    12, 8, 30, false, false);
            changed |= placeAutonomousIdentityBuilding(colony, "wfworldgen.farm-district", "farm_district",
                    30, 16, 44, true, false);
            if (settlement.defs.buildings.containsKey("house")) {
                for (int i = 0; i < households; i++) {
                    String request = "wfworldgen.house." + i;
                    if (settlement.placement(request) != null) continue;
                    if (!ensureFoundingStock(colony, "wood", 20, "house-stock-" + i,
                            TreasuryLedger.Kind.IMPORT, newlyPostedStock)) continue;
                    changed |= placeAutonomousIdentityBuilding(colony, request, "house",
                            preferredHouses[i][0], preferredHouses[i][1], 24, false, false);
                }
            }
            changed |= placeAutonomousIdentityBuilding(colony, "wfworldgen.farm", "farm",
                    24, 0, 44, true, false);
            changed |= placeAutonomousIdentityBuilding(colony, "wfworldgen.lumber", "lumber_camp",
                    -24, 0, 44, false, true);

            // Demographic expansion uses canonical population and existing economy/housing definitions.
            changed |= ensureAutonomousHousingGrowth(colony);

            // Economic specialization responds to real stock pressure, not a fixed town template.
            changed |= ensureEconomicSpecialization(colony);

            // The A* road graph is authoritative; RoadSite materializes these routes only in local LOD.
            changed |= ensureAutonomousRoads(colony);

            if (!newlyPostedStock.isEmpty() && !WorldForgeMod.saveTreasuryNow(server)) {
                colony.actionPersistenceBlocked = true;
                LOGGER.error("wf_settle: autonomous founding stock for {} could not be persisted; settlement frozen for recovery",
                        settlementId);
                return false;
            }
            if (changed) {
                if (!saveDurably(server)) {
                    colony.actionPersistenceBlocked = true;
                    LOGGER.error("wf_settle: autonomous building plan for {} could not be persisted; settlement frozen for recovery",
                            settlementId);
                    return false;
                }
                List<String> realms = colony.realmId == null || colony.realmId.isBlank()
                        ? List.of() : List.of(colony.realmId);
                dev.worldforge.mod.api.WorldForgeApi.postEvent("settlement",
                        settlement.name + " begins terrain-adapted physical founding: housing, food production and timber work",
                        realms, dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute());
                LOGGER.info("wf_settle: terrain-adapted founding orders committed id={} population={} households={} houses={} farm={} timber={}",
                        settlementId, foundingPopulation, households, settlement.count("house", false),
                        settlement.count("farm", false), settlement.count("lumber_camp", false));
            }
            return changed || !newlyPostedStock.isEmpty();
        } catch (Settlement.OrderException | IllegalStateException failure) {
            LOGGER.debug("wf_settle: autonomous site {} is not ready for physical founding yet: {}",
                    settlementId, failure.getMessage());
            return false;
        }
    }

    /** Map title changes do not mutate settlement sovereignty; only canonical realm grants may do that. */

    /** In-memory owner binding used by WorldSim's per-map batch commit. */
    public static boolean bindEmergentRealmInMemory(String settlementId, String realmId) {
        Colony colony = COLONIES.get(settlementId);
        if (colony == null || realmId == null || realmId.isBlank()) return false;
        colony.realmId = realmId;
        return true;
    }

    public static boolean bindEmergentRealm(MinecraftServer server, String settlementId, String realmId) {
        Colony colony = COLONIES.get(settlementId);
        if (server == null || colony == null || realmId == null || realmId.isBlank()) return false;
        String before = colony.realmId;
        if (realmId.equals(before)) return true;
        if (!bindEmergentRealmInMemory(settlementId, realmId)) return false;
        if (saveDurably(server)) return true;
        colony.realmId = before;
        return false;
    }

    /** In-memory canonical resident binding; caller persists the complete settlement batch. */
    public static boolean linkCanonicalResidentsInMemory(String settlementId, Collection<String> globalCharacterIds) {
        Colony colony = COLONIES.get(settlementId);
        if (colony == null || globalCharacterIds == null) return false;
        for (String id : globalCharacterIds) if (id != null && !id.isBlank())
            colony.residentCharacterIds.add(id);
        return true;
    }

    /** Reconciles the complete living population projection, removing departed/deceased UUIDs. */
    public static boolean replaceCanonicalResidentsInMemory(String settlementId, Collection<String> globalCharacterIds) {
        Colony colony = COLONIES.get(settlementId);
        if (colony == null || globalCharacterIds == null) return false;
        colony.residentCharacterIds.clear();
        for (String id : globalCharacterIds) if (id != null && !id.isBlank())
            colony.residentCharacterIds.add(id);
        return true;
    }

    public static boolean linkCanonicalResidents(MinecraftServer server, String settlementId,
                                                   Collection<String> globalCharacterIds) {
        Colony colony = COLONIES.get(settlementId);
        if (server == null || colony == null || globalCharacterIds == null) return false;
        Set<String> before = new LinkedHashSet<>(colony.residentCharacterIds);
        if (!linkCanonicalResidentsInMemory(settlementId, globalCharacterIds)) return false;
        if (saveDurably(server)) return true;
        colony.residentCharacterIds.clear();
        colony.residentCharacterIds.addAll(before);
        return false;
    }

    /** Atomic settlement snapshot used when a domain action must persist before returning success. */
    public static boolean saveDurably(MinecraftServer server) {
        if (COLONIES.values().stream().anyMatch(colony -> colony != null && (colony.genesisProvisioningRuntimeBlocked || colony.actionPersistenceBlocked))) {
            LOGGER.error("wf_settle: durable save blocked because a settlement operation has uncommitted in-memory mutations");
            return false;
        }
        Path target = file(server);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            writeSnapshot(temporary);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_settle: не удалось атомарно сохранить поселения: {}", ex.toString(), ex);
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            return false;
        }
    }

    // ------------------------------------------------------------------ загрузка и сохранение

    static Defs loadDefs(MinecraftServer server) {
        Defs d = new Defs();
        dev.worldforge.settle.SettleTypes types = new dev.worldforge.settle.SettleTypes();
        dev.worldforge.settle.StructureCatalog structureCatalog = dev.worldforge.settle.StructureCatalog.empty();
        java.util.List<String> raw = new java.util.ArrayList<>();
        var found = new java.util.TreeMap<ResourceLocation, Resource>(server.getResourceManager().listResources("wf_settle", rl -> rl.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) {
                String txt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                d.add(txt);
                try { types.load(txt); } catch (RuntimeException ex) { LOGGER.error("wf_settle: виды в {}: {}", r.getKey(), ex.toString()); }
                if (r.getKey().getPath().endsWith("structure_catalog.json")) {
                    try { structureCatalog = dev.worldforge.settle.StructureCatalog.load(txt); }
                    catch (RuntimeException ex) { LOGGER.error("wf_settle: каталог структур {} не загружен: {}", r.getKey(), ex.toString()); }
                }
                raw.add(txt);
            } catch (Exception ex) {
                LOGGER.error("wf_settle: ошибка в {}: {}", r.getKey(), ex.toString());
            }
        }
        BASE_RAW = new java.util.ArrayList<>(raw);
        for (String line : SchemLibrary.loadAll(server, d, raw)) LOGGER.info("wf_settle: схема {}", line);
        DEFS_RAW = raw;
        for (String p : types.validate(d)) LOGGER.warn("wf_settle: {}", p);
        for (String p : structureCatalog.validate(d)) LOGGER.warn("wf_settle: каталог структур: {}", p);
        TYPES = types;
        STRUCTURE_CATALOG = structureCatalog;
        for (String p : d.validate()) LOGGER.warn("wf_settle: {}", p);
        LOGGER.info("wf_settle: зданий {}, профессий {}, видов поселений {}, классов структур {}, identity dimensions {}",
                d.buildings.size(), d.professions.size(), types.types.size(),
                structureCatalog.structureClasses().size(), structureCatalog.identityDimensions().size());
        return d;
    }

    private static Path file(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("wf_settle.json"); }

    /** Writes one colony projection at a time; never retains a world-sized JSON object/string. */
    private static void writeSnapshot(Path destination) throws java.io.IOException {
        try (java.io.BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {
            Iterator<Colony> source = COLONIES.values().iterator();
            Iterator<Map<String, Object>> projections = new Iterator<>() {
                @Override public boolean hasNext() { return source.hasNext(); }
                @Override public Map<String, Object> next() { return source.next().toMap(); }
            };
            dev.worldforge.settle.SettlementSnapshotWriter.write(writer, projections, NETWORK.toJson());
        }
    }

    @SuppressWarnings("unchecked")
    static void load(MinecraftServer server) {
        resetWorkerIndexes();
        COLONIES.clear();
        resetColonyRegistryIndex();
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
                    markColonyRegistryChanged(c);
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
        if (COLONIES.values().stream().anyMatch(colony -> colony != null && (colony.genesisProvisioningRuntimeBlocked || colony.actionPersistenceBlocked))) {
            LOGGER.error("wf_settle: periodic/shutdown save skipped to preserve the last durable settlement checkpoint");
            return;
        }
        saveDurably(server);
    }

    /** True while any settlement has an uncommitted mutation; all settlement writers must then fail closed. */
    public static boolean actionsBlocked() {
        return logisticsPersistenceBlocked || COLONIES.values().stream().anyMatch(colony -> colony != null
                && (colony.actionPersistenceBlocked || colony.genesisProvisioningRuntimeBlocked));
    }

    /** Persist a player-issued domain mutation before its ActionExecution receipt can succeed. */
    public static boolean persistActionMutation(MinecraftServer server, Colony colony) {
        if (colony == null || colony.actionPersistenceBlocked || colony.genesisProvisioningRuntimeBlocked) return false;
        if (saveDurably(server)) return true;
        colony.actionPersistenceBlocked = true;
        return false;
    }

    /** Persist one mixed RTS action that may have changed workers in several settlements. */
    public static boolean persistActionMutation(MinecraftServer server) {
        if (actionsBlocked()) return false;
        if (saveDurably(server)) return true;
        for (Colony colony : COLONIES.values()) {
            if (colony != null) colony.actionPersistenceBlocked = true;
        }
        return false;
    }

    // ------------------------------------------------------------------ события сервера

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) {
        ticks = 0;
        LAST_SIMULATED_WORLD_MINUTE.clear();
        LIFE_AI_CURSORS.clear();
        RESIDENT_LIFE_INDEXES.clear();
        COLONY_SIMULATION_PHASES = Map.of();
        colonySimulationIndexBuiltAt = Long.MIN_VALUE;
        colonySimulationIndexInterval = -1;
        professionsSynced = false;
        ColonyInterestIndex.clear();
        IMPORTED_BLUEPRINTS.clear();
        lastResidentAuditTick = Long.MIN_VALUE;
        REPORTED_RESIDENT_LINK_ERRORS.clear();
        Blueprints.clearCache();
        Bodies.LIVE.clear();
        DEFS = loadDefs(e.getServer());
        load(e.getServer());
        // The shared clock is restored by ClockService on the same lifecycle event.
        // Seed colony cursors on the first server tick, after every addon has loaded it.
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) {
        if (DEFS != null) save(e.getServer());
        Bodies.LIVE.clear();
        LAST_SIMULATED_WORLD_MINUTE.clear();
        COLONY_SIMULATION_PHASES = Map.of();
        colonySimulationIndexBuiltAt = Long.MIN_VALUE;
        colonySimulationIndexInterval = -1;
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent e) {
        resetWorkerIndexes();
        COLONIES.clear();
        resetColonyRegistryIndex();
        cachedTradeViews = new TradeViews(Map.of(), Map.of());
        tradeViewsRevision = Long.MIN_VALUE;
        colonySimulationIndexRevision = Long.MIN_VALUE;
        LAST_SIMULATED_WORLD_MINUTE.clear();
        COLONY_SIMULATION_PHASES = Map.of();
        colonySimulationIndexBuiltAt = Long.MIN_VALUE;
        colonySimulationIndexInterval = -1;
        ColonyInterestIndex.clear();
        professionsSynced = false;
        IMPORTED_BLUEPRINTS.clear();
        DEFS = null;
        STRUCTURE_CATALOG = dev.worldforge.settle.StructureCatalog.empty();
        Blueprints.clearCache();
    }

    /**
     * Audits persisted settlement resident UUIDs against the owning canonical wf-people registry.
     * This is a read-only integrity check: missing residents are reported, never cloned or silently rebound.
     */
    public static void validateCanonicalResidentLinks() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        Map<String, dev.worldforge.mod.MapData> mapsByName = new HashMap<>();
        for (dev.worldforge.mod.MapData map : WorldForgeMod.loadedMaps().values())
            if (map != null && map.name != null) mapsByName.put(map.name, map);
        for (Colony colony : COLONIES.values()) {
            if (colony == null || colony.sourceMapId == null || colony.sourceMapId.isBlank()
                    || colony.residentCharacterIds.isEmpty()) continue;
            dev.worldforge.mod.MapData map = mapsByName.get(colony.sourceMapId);
            if (map == null) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry == null) continue;
            Set<String> known = new HashSet<>();
            for (Character character : registry.all())
                if (character.globalId != null && !character.globalId.isBlank()) known.add(character.globalId);
            for (String residentId : colony.residentCharacterIds)
                if (!known.contains(residentId)) {
                    String issue = colony.s.id + "|" + residentId;
                    if (REPORTED_RESIDENT_LINK_ERRORS.add(issue))
                        LOGGER.error("wf_settle: orphan resident Character ID {} in colony {} (map {}); no duplicate Character was created",
                                residentId, colony.s.id, colony.sourceMapId);
                }
        }
    }

    /**
     * Bridges settlement workers to the authoritative person registry.
     * Worker.person is the durable foreign key; profession/building remain settlement-owned state.
     * The optional wf_people addon remains optional and no duplicate character registry is created here.
     */
    static void syncPeople(Colony colony, MinecraftServer server) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_people") || colony == null || colony.s == null) return;
        // Use the exact dimension's canonical registry: map-backed and vanilla settlements
        // follow the same Character lifecycle and never infer identity from map sovereignty.
        CharacterRegistry registry = registryForColony(colony);
        if (registry == null) return;
        String race = "";
        String culture = "";
        String place = "settlement:" + colony.s.id;
        List<Character> pendingProfessionTransitions = new ArrayList<>();

        // Consume persisted departures before reconciling current workers. A crash/reload cannot erase a death or migration.
        for (Settlement.WorkerDeparture departure : new ArrayList<>(colony.s.pendingDepartures())) {
            if (departure.personId().isBlank()) {
                colony.s.acknowledgeDeparture(departure); // legacy worker had no Character identity
                continue;
            }
            PersonBinding departedBinding = resolvePersonBinding(registry, departure.personId());
            Character departed = departedBinding == null ? null : departedBinding.character();
            if (departedBinding == null) {
                LOGGER.warn("wf_settle: departure references missing Character {}; retaining event for repair", departure.personId());
                continue;
            }
            if (departed.alive) {
                CharacterRegistry departedRegistry = departedBinding.registry();
                try {
                    if (departure.reason().startsWith("death:"))
                        PopulationSimulation.die(departedRegistry, departed.id, PeopleMod.YEAR, departure.reason().substring("death:".length()));
                    else if (departure.reason().startsWith("transfer:"))
                        departedRegistry.fire(departed.id, place, PeopleMod.YEAR);
                    else departedRegistry.migrate(departed.id, "outside:" + colony.s.id, PeopleMod.YEAR);
                } catch (RuntimeException ex) {
                    LOGGER.error("wf_settle: lifecycle transition failed for {}: {}", departed.id, ex.toString());
                    continue;
                }
            }
            colony.s.acknowledgeDeparture(departure);
        }

        for (dev.worldforge.settle.Worker worker : new ArrayList<>(colony.s.workers)) {
            boolean hasPersonId = worker.person != null && !worker.person.isBlank();
            PersonBinding personBinding = hasPersonId ? resolvePersonBinding(registry, worker.person) : null;
            Character person = personBinding == null ? null : personBinding.character();
            CharacterRegistry personRegistry = personBinding == null ? registry : personBinding.registry();
            if (hasPersonId && person == null) {
                LOGGER.warn("wf_settle: worker {} references missing Character {}; not creating a replacement", worker.id, worker.person);
                continue;
            }
            if (person != null && !person.alive) {
                colony.s.removeWorkerProjectionByPerson(person.globalId);
                continue;
            }
            if (person == null) {
                person = registry.create(worker.name, "", culture, "m", PeopleMod.YEAR - 20);
                personRegistry = registry;
            }
            String canonicalPersonId = person.globalId; // migrate legacy local cN reference to canonical global UUID
            colony.s.updateWorker(worker, current -> current.person = canonicalPersonId);
            if (!person.has("settlement_worker", place)) personRegistry.appoint(person.id, "settlement_worker", place, PeopleMod.YEAR);
            person.props.put("population.employer", place);
            person.props.put("population.location", colony.s.id);
            if (person.culture == null || person.culture.isBlank()) person.culture = culture;
            if (person.culture == null || person.culture.isBlank()) person.culture = culture;
            String profession = worker.profession == null || worker.profession.isBlank() ? "laborer" : worker.profession;
            // A cross-domain profession change writes the canonical Character first. If the
            // server stopped before the settlement projection was durable, this marker makes
            // the Character's committed target win over the stale Worker snapshot on reload.
            Object pendingRaw = person.props.get("population.pending_settlement_profession");
            if (pendingRaw instanceof Map<?, ?> pending
                    && colony.s.id.equals(pending.get("settlement"))
                    && worker.id == (pending.get("worker_id") instanceof Number n ? n.intValue() : -1)
                    && person.globalId.equals(pending.get("character_id"))
                    && pending.get("profession") instanceof String target
                    && colony.s.defs.professions.containsKey(target)) {
                if (!target.equals(worker.profession)) colony.s.setProfession(worker.id, target);
                profession = target;
                if (!pendingProfessionTransitions.contains(person)) pendingProfessionTransitions.add(person);
            }
            if (!profession.equals(PopulationSimulation.profession(person))) personRegistry.changeProfession(person.id, profession, PeopleMod.YEAR);
            personRegistry.setDuty(person.id, Duty.WORKER, true, PeopleMod.YEAR);
            boolean settlementSoldier = java.util.Set.of("guard", "soldier", "archer", "knight").contains(profession);
            if (settlementSoldier && !PopulationSimulation.hasDuty(person, Duty.SOLDIER)) {
                personRegistry.setDuty(person.id, Duty.SOLDIER, true, PeopleMod.YEAR);
                person.props.put("population.settlement_soldier_duty", true);
            } else if (!settlementSoldier && Boolean.TRUE.equals(person.props.remove("population.settlement_soldier_duty"))) {
                personRegistry.setDuty(person.id, Duty.SOLDIER, false, PeopleMod.YEAR);
            }
            boolean settlementSpecialist = java.util.Set.of("lumberjack", "sawyer", "farmer", "miner", "smith",
                    "builder", "blacksmith", "armorer", "trainer", "healer", "merchant", "scholar", "porter").contains(profession);
            if (settlementSpecialist && !PopulationSimulation.hasDuty(person, Duty.SPECIALIST)) {
                personRegistry.setDuty(person.id, Duty.SPECIALIST, true, PeopleMod.YEAR);
                person.props.put("population.settlement_specialist_duty", true);
            } else if (!settlementSpecialist && Boolean.TRUE.equals(person.props.remove("population.settlement_specialist_duty"))) {
                personRegistry.setDuty(person.id, Duty.SPECIALIST, false, PeopleMod.YEAR);
            }
            PopulationSimulation.updateIntent(person, PeopleMod.YEAR);
            person.props.put("wf_unit_kind", "worker");
            person.props.put("wf_settlement_id", colony.s.id);
            person.props.put("wf_worker_id", worker.id);
            person.props.put("wf_profession", worker.profession == null ? "laborer" : worker.profession);
            person.props.put("wf_assigned_profession", worker.assignedProfession == null ? "" : worker.assignedProfession);
            person.props.put("wf_building_uid", worker.building);
            person.props.put("wf_race_id", race);
            person.props.put("wf_culture_id", culture);
        }
        if (!pendingProfessionTransitions.isEmpty() && saveDurably(server)) {
            for (Character person : pendingProfessionTransitions) {
                Object marker = person.props.remove("population.pending_settlement_profession");
                if (!PeopleMod.persistCharacter(person, server) && marker != null)
                    person.props.put("population.pending_settlement_profession", marker);
            }
        }
    }

    /** Resolve the canonical Character registry for the settlement's actual physical dimension. */
    private static CharacterRegistry registryForColony(Colony colony) {
        if (colony == null || colony.s == null) return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : colony.level(server);
        return level == null ? null : PeopleMod.registryFor(level);
    }

    /** World-space proximity determines simulation detail; no player is required for remote progress. */
    private static LifeSimulation.LOD lifeLod(MinecraftServer server, Colony colony, dev.worldforge.settle.Worker worker) {
        double best = Double.POSITIVE_INFINITY;
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.serverLevel().dimension().location().toString().equals(colony.dim)) continue;
            double dx = player.getX() - (colony.cx + worker.x);
            double dz = player.getZ() - (colony.cz + worker.z);
            best = Math.min(best, dx * dx + dz * dz);
        }
        if (best <= 64 * 64) return LifeSimulation.LOD.ACTIVE;
        if (best <= 256 * 256) return LifeSimulation.LOD.SETTLEMENT;
        if (best <= 1024 * 1024) return LifeSimulation.LOD.REGIONAL;
        return LifeSimulation.LOD.REMOTE;
    }

    /** Residents meet for meals at the nearest finished food/civic building. */
    private static double[] residentMealPlace(Settlement settlement, dev.worldforge.settle.Worker worker) {
        Building best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Building building : settlement.buildings) {
            if (building.state != Building.State.DONE || building.paused) continue;
            String category = building.def.category == null ? "" : building.def.category;
            if (!category.equalsIgnoreCase("food") && !category.equalsIgnoreCase("civic")
                    && !building.def.id.contains("tavern") && !building.def.id.contains("inn")) continue;
            double dx = building.x - worker.x, dz = building.z - worker.z;
            double d = dx * dx + dz * dz;
            if (d < distance) { distance = d; best = building; }
        }
        return best == null ? new double[]{0, 0} : new double[]{best.x, best.z};
    }

    /** A completed house is the preferred safe/rest destination; the settlement centre is a fallback. */
    private static double[] residentShelter(Settlement settlement, dev.worldforge.settle.Worker worker) {
        Building best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Building building : settlement.buildings) {
            if (building.state != Building.State.DONE || !"housing".equalsIgnoreCase(building.def.category)) continue;
            double dx = building.x - worker.x, dz = building.z - worker.z;
            double d = dx * dx + dz * dz;
            if (d < distance) { distance = d; best = building; }
        }
        return best == null ? new double[]{0, 0} : new double[]{best.x, best.z};
    }

    /** Executes Character AI against authoritative settlement stock, work assignments and nearby population. */
    private static void runLifeAI(MinecraftServer server, Colony colony) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_people") || colony == null || colony.s == null) return;
        CharacterRegistry registry = registryForColony(colony);
        if (registry == null) return;
        Settlement settlement = colony.s;
        long worldMinute = settlement.worldMinute();
        List<dev.worldforge.settle.Worker> workers = settlement.workers;
        int workerCount = workers.size();
        if (workerCount == 0) {
            dev.worldforge.settle.ResidentLifeEvents.advanceYearly(registry, settlement.year(), PeopleMod.NAMES,
                    dev.worldforge.mod.MaqanPerformanceConfig.familyEventsResidentsPerTick(), ticks);
            return;
        }
        ResidentLifeIndex residentIndex = residentLifeIndex(colony, ticks);
        int previousCursor = LIFE_AI_CURSORS.getOrDefault(settlement.id, 0);
        int batchLimit = settlement.raidIncoming
                ? dev.worldforge.mod.MaqanPerformanceConfig.lifeAiUrgentResidentsPerPass()
                : dev.worldforge.mod.MaqanPerformanceConfig.lifeAiResidentsPerPass();
        ResidentLifeBatch.Window window = ResidentLifeBatch.next(workerCount, previousCursor, batchLimit);
        LIFE_AI_CURSORS.put(settlement.id, window.nextCursor());
        for (int offset = 0; offset < window.count(); offset++) {
            dev.worldforge.settle.Worker worker = workers.get((window.start() + offset) % workerCount);
            if (worker.person == null || worker.person.isBlank()) continue;
            Character person = resolvePerson(registry, worker.person);
            if (person == null || !person.alive) continue;
            if (!settlement.raidIncoming && !(person.props.get("population.ai.pending_action") instanceof String)) {
                Object nextThinkRaw = person.props.get("population.ai.next_think_minute");
                if (nextThinkRaw instanceof Number nextThink && worldMinute < nextThink.longValue()) continue;
            }

            // Explicit player/RTS orders always preempt autonomous life goals without granting their effects.
            if (worker.manualOrder || worker.manualFollow) {
                LifeSimulation.cancelPending(person, "preempted-by-player-order");
                settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                continue;
            }
            if ("fleeing".equals(worker.lifeAction) || "defending".equals(worker.lifeAction)) {
                if (settlement.raidIncoming) continue;
                settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
            }
            if (!worker.lifeAction.isBlank()
                    && !("travelling".equals(worker.lifeActionStage) || "performing".equals(worker.lifeActionStage))) {
                continue;
            }

            boolean brave = PopulationSimulation.hasDuty(person, Duty.SOLDIER)
                    || person.traits.contains("brave") || person.traits.contains("courageous")
                    || person.traits.contains("protective");
            if (worker.lifeAction.isBlank() && settlement.raidIncoming && brave) {
                settlement.updateWorkerKnownMember(worker, current -> {
                    current.lifeAction = "defending";
                    current.lifeTargetX = 0; current.lifeTargetZ = 0;
                    current.lifeActionUntil = 1.0e12;
                    current.status = "спешит на помощь поселению";
                });
                continue;
            }
            boolean hasTraining = !worker.generalist() && !PopulationSimulation.trainingTarget(person).isBlank();
            boolean hasLeisure = residentIndex.hasLeisure;
            boolean hasCompanion = residentIndex.hasCompanion;
            LifeSimulation.Context context = new LifeSimulation.Context(
                    worldMinute,
                    settlement.amount("food") >= 1.0,
                    settlement.housing() > 0,
                    !settlement.raidIncoming,
                    hasCompanion,
                    worker.building != 0,
                    hasTraining,
                    hasLeisure,
                    settlement.raidIncoming,
                    lifeLod(server, colony, worker));
            LifeSimulation.tick(person, context, (character, action, ignored) -> {
                // An existing pending action is polled here until its physical/abstract execution finishes.
                if (!worker.lifeAction.isBlank()) {
                    // Character and settlement snapshots are persisted independently. After a
                    // crash they can disagree; do not let an old sleep/eat/social animation
                    // complete a different pending Character action and grant the wrong effects.
                    if (!LifeSimulation.matchesPhysicalAction(action, worker.lifeAction)) {
                        LOGGER.warn("wf_settle: cancelling mismatched life action for Character {} (AI={}, worker={})",
                                character.globalId, action, worker.lifeAction);
                        settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                        return LifeSimulation.Outcome.BLOCKED;
                    }
                    if (!"performing".equals(worker.lifeActionStage)) return LifeSimulation.Outcome.IN_PROGRESS;
                    if (worldMinute < worker.lifeActionUntil) return LifeSimulation.Outcome.IN_PROGRESS;
                    // Settlement.eat(dt) is the authoritative ration ledger for every resident.
                    // Do not debit a second meal here; only ensure the stock has not collapsed to zero.
                    if (action == LifeSimulation.Action.EAT && settlement.amount("food") <= 0) {
                        settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                        return LifeSimulation.Outcome.BLOCKED;
                    }
                    if (action == LifeSimulation.Action.SOCIALIZE) {
                        dev.worldforge.settle.Worker companion = residentIndex.byPerson.get(worker.lifeSocialTarget);
                        Character companionPerson = companion == null ? null : resolvePerson(registry, companion.person);
                        if (companion == null || companionPerson == null || !companionPerson.alive) {
                            settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                            return LifeSimulation.Outcome.BLOCKED;
                        }
                        if (ignored.lod() == LifeSimulation.LOD.ACTIVE) {
                            double dx = worker.x - companion.x, dz = worker.z - companion.z;
                            if (dx * dx + dz * dz > 16.0) {
                                settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                                return LifeSimulation.Outcome.BLOCKED;
                            }
                        }
                        Object tokenRaw = character.props.get("population.ai.pending_token");
                        if (!(tokenRaw instanceof String operationId) || operationId.isBlank()) {
                            settlement.updateWorkerKnownMember(worker, SettleMod::clearLifeAction);
                            return LifeSimulation.Outcome.BLOCKED;
                        }
                        int opinion = dev.worldforge.settle.ResidentSocial.interactionDelta(character, companionPerson);
                        dev.worldforge.settle.ResidentSocial.socializeOnce(character, companionPerson, opinion, operationId);
                        // Both participants belong to this settlement's canonical registry.
                        // The durable receipt is part of each Character snapshot and is committed
                        // by PeopleMod's bounded registry checkpoint/shutdown save. Never serialize
                        // every world registry synchronously for each ordinary conversation:
                        // estate-transfer journals are reserved for actual ownership transactions.
                        settlement.updateWorkerKnownMember(companion, current -> current.status = "общался: " + character.name);
                    }
                    settlement.updateWorkerKnownMember(worker, current -> {
                        clearLifeAction(current);
                        current.status = current.building == 0 ? "свободен" : "работает";
                    });
                    return LifeSimulation.Outcome.SUCCESS;
                }

                // Start a local action. Its needs, food stock and social relations are not changed yet.
                switch (action) {
                    case EAT -> {
                        if (settlement.amount("food") < 1.0) return LifeSimulation.Outcome.BLOCKED;
                        double[] meal = residentMealPlace(settlement, worker);
                        settlement.updateWorkerKnownMember(worker, current -> {
                            beginLifeAction(current, "eating", meal[0], meal[1], 30, worldMinute, context.lod());
                            current.status = "идёт принимать пищу";
                        });
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case SLEEP -> {
                        double[] home = residentShelter(settlement, worker);
                        settlement.updateWorkerKnownMember(worker, current -> {
                            beginLifeAction(current, "sleeping", home[0], home[1], 8 * 60, worldMinute, context.lod());
                            current.status = "идёт отдыхать";
                        });
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case SEEK_SAFETY -> {
                        if (!settlement.raidIncoming) return LifeSimulation.Outcome.BLOCKED;
                        double[] shelter = residentShelter(settlement, worker);
                        settlement.updateWorkerKnownMember(worker, current -> {
                            current.lifeAction = "fleeing";
                            current.lifeTargetX = shelter[0]; current.lifeTargetZ = shelter[1];
                            current.lifeActionUntil = 1.0e12;
                            current.status = "бежит в укрытие";
                        });
                        return LifeSimulation.Outcome.SUCCESS;
                    }
                    case SOCIALIZE -> {
                        dev.worldforge.settle.Worker companion =
                                nearestResidentCompanion(worker, residentIndex, registry);
                        if (companion == null) return LifeSimulation.Outcome.BLOCKED;
                        String companionId = companion.person;
                        double companionX = companion.x, companionZ = companion.z;
                        settlement.updateWorkerKnownMember(worker, current -> {
                            current.lifeSocialTarget = companionId;
                            beginLifeAction(current, "socializing", companionX, companionZ, 3 * 60, worldMinute, context.lod());
                            current.status = "идёт на встречу";
                        });
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case WORK -> {
                        return worker.building != 0 ? LifeSimulation.Outcome.SUCCESS : LifeSimulation.Outcome.RETRY;
                    }
                    case TRAIN -> {
                        if (worker.generalist() || PopulationSimulation.trainingTarget(character).isBlank())
                            return LifeSimulation.Outcome.BLOCKED;
                        settlement.updateWorkerKnownMember(worker, current -> current.status = "обучается");
                        return LifeSimulation.Outcome.SUCCESS; // skill XP is granted once by LifeSimulation.applyEffects
                    }
                    case RECREATE -> {
                        if (!hasLeisure) return LifeSimulation.Outcome.BLOCKED;
                        settlement.updateWorkerKnownMember(worker, current -> current.status = "досуг");
                        return LifeSimulation.Outcome.SUCCESS;
                    }
                    case IDLE -> { return LifeSimulation.Outcome.SUCCESS; }
                }
                return LifeSimulation.Outcome.BLOCKED;
            });
        }
        dev.worldforge.settle.ResidentLifeEvents.advanceYearly(registry, settlement.year(), PeopleMod.NAMES,
                    dev.worldforge.mod.MaqanPerformanceConfig.familyEventsResidentsPerTick(), ticks);
    }

    private static void beginLifeAction(dev.worldforge.settle.Worker worker, String action,
                                        double targetX, double targetZ, double durationMinutes, double worldMinute,
                                        LifeSimulation.LOD lod) {
        worker.lifeAction = action;
        worker.lifeTargetX = targetX;
        worker.lifeTargetZ = targetZ;
        worker.lifeActionDuration = Math.max(1, Math.min(1440, durationMinutes));
        worker.lifeActionStage = lod == LifeSimulation.LOD.ACTIVE ? "travelling" : "performing";
        worker.lifeActionUntil = lod == LifeSimulation.LOD.ACTIVE ? 0 : worldMinute + worker.lifeActionDuration;
    }

    private static void clearLifeAction(dev.worldforge.settle.Worker worker) {
        worker.lifeAction = "";
        worker.lifeActionStage = "";
        worker.lifeSocialTarget = "";
        worker.lifeActionDuration = 0;
        worker.lifeActionUntil = 0;
    }

    private static void resetWorkerIndexes() {
        WORKER_COLONY_BY_PERSON.clear();
        workerOwnershipRevision = null;
        workerOwnershipIndexBuilt = false;
        MILITARY_DEPARTURE_COLONIES.clear();
        militaryDepartureIndexBuilt = false;
    }

    private static void refreshWorkerOwnershipIndex() {
        var revisions = dev.worldforge.mod.api.WorldForgeApi.revisions();
        var current = revisions.snapshot(dev.worldforge.mod.api.DomainMutationRevisions.Domain.SETTLEMENTS);
        if (workerOwnershipIndexBuilt && workerOwnershipRevision != null && current.isPresent()
                && revisions.isCurrent(workerOwnershipRevision,
                        dev.worldforge.mod.api.DomainMutationRevisions.Domain.SETTLEMENTS)) return;
        WORKER_COLONY_BY_PERSON.clear();
        for (Colony colony : COLONIES.values()) {
            if (colony == null || colony.s == null) continue;
            for (dev.worldforge.settle.Worker worker : colony.s.workers)
                if (worker.person != null && !worker.person.isBlank())
                    WORKER_COLONY_BY_PERSON.putIfAbsent(worker.person, colony.s.id);
        }
        workerOwnershipRevision = current.orElse(null);
        workerOwnershipIndexBuilt = current.isPresent();
    }

    private static void captureWorkerOwnershipRevision() {
        if (!workerOwnershipIndexBuilt) return;
        var current = dev.worldforge.mod.api.WorldForgeApi.revisions().snapshot(
                dev.worldforge.mod.api.DomainMutationRevisions.Domain.SETTLEMENTS);
        workerOwnershipRevision = current.orElse(null);
        workerOwnershipIndexBuilt = current.isPresent();
    }

    private static void refreshMilitaryDepartureIndex() {
        if (militaryDepartureIndexBuilt) return;
        MILITARY_DEPARTURE_COLONIES.clear();
        for (Colony colony : COLONIES.values()) {
            if (colony == null || colony.s == null) continue;
            for (dev.worldforge.settle.Settlement.WorkerDeparture departure : colony.s.pendingDepartures()) {
                if (!"transfer:military".equals(departure.reason()) || departure.personId().isBlank()) continue;
                MILITARY_DEPARTURE_COLONIES.computeIfAbsent(departure.personId(), ignored -> new HashSet<>())
                        .add(colony.s.id);
            }
        }
        militaryDepartureIndexBuilt = true;
    }

    /** Removes the existing worker projection for a person entering military service; the departure is persisted. */
    public static boolean detachWorkerForMilitary(String characterId) {
        if (characterId == null || characterId.isBlank()) return false;
        refreshWorkerOwnershipIndex();
        refreshMilitaryDepartureIndex();
        String colonyId = WORKER_COLONY_BY_PERSON.get(characterId);
        Colony colony = colonyId == null ? null : COLONIES.get(colonyId);
        if (colony == null || colony.s == null
                || !colony.s.removeWorkerByPerson(characterId, "transfer:military")) {
            WORKER_COLONY_BY_PERSON.remove(characterId);
            captureWorkerOwnershipRevision();
            return false;
        }
        WORKER_COLONY_BY_PERSON.remove(characterId);
        MILITARY_DEPARTURE_COLONIES.computeIfAbsent(characterId, ignored -> new HashSet<>()).add(colonyId);
        captureWorkerOwnershipRevision();
        return true;
    }

    /** Compatibility entry point for callers without a batch ownership projection. */
    public static boolean attachExistingCharacter(String colonyId, String characterId) {
        return attachExistingCharacter(colonyId, characterId, null);
    }

    /** Reinstates an existing living Character as a settlement worker; no new Character is allocated. */
    public static boolean attachExistingCharacter(String colonyId, String characterId,
                                                   Map<String, String> workerColonyByPerson) {
        Colony colony = COLONIES.get(colonyId);
        if (colony == null || colony.s == null || characterId == null || characterId.isBlank()
                || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return false;
        // Batch callers provide a projection built once for the whole materialization pass.
        // This keeps per-resident idempotency checks O(1), rather than rescanning all workers.
        if (workerColonyByPerson != null) {
            String assignedColony = workerColonyByPerson.get(characterId);
            if (assignedColony != null) return colonyId.equals(assignedColony);
        } else {
            refreshWorkerOwnershipIndex();
            String assignedColony = WORKER_COLONY_BY_PERSON.get(characterId);
            if (assignedColony != null) return colonyId.equals(assignedColony);
        }
        CharacterRegistry registry = null;
        Character person = PeopleMod.authority().getGlobal(characterId);
        if (person != null) {
            registry = PeopleMod.authority().registryForGlobal(characterId);
        } else {
            // Legacy local cN references are only meaningful inside the destination colony's map.
            registry = registryForColony(colony);
            person = registry == null ? null : registry.get(characterId);
        }
        if (person == null || !person.alive) return false;
        String profession = person.props.get("population.pre_military_profession") instanceof String p && !p.isBlank()
                ? p : PopulationSimulation.profession(person);
        if (profession == null || profession.isBlank() || DEFS == null || !DEFS.professions.containsKey(profession))
            profession = "laborer";
        dev.worldforge.settle.Worker worker = colony.s.addWorker(person.name);
        String restoredPersonId = person.globalId;
        String restoredProfession = profession;
        colony.s.updateWorkerKnownMember(worker, current -> {
            current.person = restoredPersonId;
            current.profession = restoredProfession;
        });
        // Keep the canonical Character's active profession in sync with the restored worker assignment.
        // This records the military service interval in the normal profession history.
        if (!profession.equals(PopulationSimulation.profession(person)))
            PopulationSimulation.assignProfession(person, profession, PeopleMod.YEAR);
        person.props.remove("population.pre_military_profession");
        registry.setDuty(person.id, Duty.SOLDIER, false, PeopleMod.YEAR);
        registry.setDuty(person.id, Duty.WORKER, true, PeopleMod.YEAR);
        String place = "settlement:" + colony.s.id;
        person.props.put("population.location", colony.s.id);
        person.props.put("population.employer", place);
        if (!person.has("settlement_worker", place)) registry.appoint(person.id, "settlement_worker", place, PeopleMod.YEAR);
        if (workerColonyByPerson != null) workerColonyByPerson.put(characterId, colonyId);
        if (workerOwnershipIndexBuilt) {
            WORKER_COLONY_BY_PERSON.put(person.globalId, colonyId);
            captureWorkerOwnershipRevision();
        }
        // Only settlements with a queued military departure for this Character are visited.
        refreshMilitaryDepartureIndex();
        Set<String> departureColonies = MILITARY_DEPARTURE_COLONIES.remove(person.globalId);
        if (departureColonies != null) for (String departureColonyId : departureColonies) {
            Colony formerColony = COLONIES.get(departureColonyId);
            if (formerColony != null && formerColony.s != null)
                formerColony.s.acknowledgeDepartureForPerson(person.globalId, "transfer:military");
        }
        return true;
    }

    /** Remit the configured settlement tax share to the owning realm through the global ledger. */
    private static void remitCrownTaxes(Colony colony) {
        if (colony == null || colony.s == null || colony.s.crownShare <= 0) return;
        String realmId = realmOf(colony);
        if (realmId == null || realmId.isBlank()) return;
        Settlement settlement = colony.s;
        String source = settlement.ledgerAccountId(Defs.SILVER);
        String target = "realm:" + realmId;
        TreasuryLedger ledger = WorldForgeMod.treasury();
        settlement.amount(Defs.SILVER); // ensures/migrates the settlement account
        ledger.open(target, realmId, Defs.SILVER, 0);
        // The financial journal is authoritative: recover remittances even if the settlement snapshot
        // was not saved after the ledger transfer (crash between the two world files).
        double remitted = ledger.totalDebits(source, TreasuryLedger.Kind.TAX, "crown-remittance");
        double due = Math.max(0, settlement.taxCollected * Math.max(0, Math.min(100, settlement.crownShare)) / 100.0
                - remitted);
        double payment = Math.min(due, ledger.balance(source));
        if (payment <= 1e-9) return;
        boolean transferred = ledger.transfer(source, target, payment, TreasuryLedger.Kind.TAX,
                "crown-remittance", settlement.id + ":" + Double.toHexString(settlement.remittedSilver),
                settlement.worldMinute(), "settlement tax remitted to realm treasury");
        if (transferred) {
            remitted = ledger.totalDebits(source, TreasuryLedger.Kind.TAX, "crown-remittance");
        }
        settlement.applyTreasuryProjection(remitted, ledger.balance(source));
    }

    /**
     * Materializes the image generator's named settlement anchors into the canonical Settlement/Colony
     * registry. The blueprint is immutable input; the Colony is the durable runtime projection.
     * Imports are batched and marked complete only after saveDurably succeeds, so retries are idempotent.
     */
    private static void importWorldBlueprintSettlements(MinecraftServer server) {
        if (DEFS == null || server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator)) continue;
            dev.worldforge.mod.MapData map;
            try { map = generator.data(); }
            catch (RuntimeException ex) {
                LOGGER.error("wf_settle: could not read map blueprint for dimension {}: {}",
                        level.dimension().location(), ex.toString());
                continue;
            }
            String dimension = level.dimension().location().toString();
            String importKey = dimension + "|" + map.name;
            if (IMPORTED_BLUEPRINTS.contains(importKey)) continue;
            try {
                int staged = 0;
                Set<String> blueprintIds = new HashSet<>();
                // Names are unique across the whole saved world, not just this blueprint batch.
                // Deterministically retain the first canonical owner and repair legacy collisions.
                Set<String> usedNames = new HashSet<>();
                Map<String, String> nameOwners = new TreeMap<>();
                List<Colony> namedColonies = COLONIES.values().stream()
                        .filter(existing -> existing != null && existing.s != null
                                && existing.s.name != null && !existing.s.name.isBlank())
                        .sorted(Comparator.comparing(existing -> existing.s.id)).toList();
                for (Colony existingColony : namedColonies) {
                    String normalized = existingColony.s.name.trim().replaceAll("\\s+", " ")
                            .toLowerCase(Locale.ROOT);
                    nameOwners.putIfAbsent(normalized, existingColony.s.id);
                    usedNames.add(existingColony.s.name);
                }

                for (dev.worldforge.mod.MapData.SettlementSeed seed : map.settlementSeeds) {
                    if (seed == null || seed.id() == null || !blueprintIds.add(seed.id()))
                        throw new IllegalStateException("Null or duplicate SettlementSeed ID in map " + map.name);
                    String id = Colony.blueprintColonyId(map.name, seed.id());
                    String operationId = Colony.blueprintOperationId(map.name, seed.id());
                    boolean existed = COLONIES.containsKey(id);
                    TitleData.Realm realm = map.titles == null ? null : map.titles.realms.get(seed.realmId());
                    TitleData.Title province = map.titles == null ? null : map.titles.get(seed.provinceId());
                    // Prefer the exact province's inherited demographic identity. Mixed-race realms
                    // therefore produce different local naming traditions instead of one realm-wide label.
                    String race = province == null ? "" : String.valueOf(province.props.getOrDefault("identity.race",
                            province.props.getOrDefault("race", "")));
                    String culture = province == null ? "" : String.valueOf(province.props.getOrDefault("identity.culture",
                            province.props.getOrDefault("culture", "")));
                    if (race.isBlank() && realm != null) race = String.valueOf(realm.props.getOrDefault("identity.race",
                            realm.props.getOrDefault("race", "")));
                    if (culture.isBlank() && realm != null) culture = String.valueOf(realm.props.getOrDefault("identity.culture",
                            realm.props.getOrDefault("culture", "")));
                    Colony existingColony = COLONIES.get(id);
                    String uniqueName;
                    if (existingColony != null && map.name.equals(existingColony.sourceMapId)
                            && seed.id().equals(existingColony.blueprintSettlementId)
                            && existingColony.s != null && existingColony.s.name != null
                            && !existingColony.s.name.isBlank()
                            && existingColony.s.id.equals(nameOwners.get(existingColony.s.name.trim()
                                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)))) {
                        uniqueName = existingColony.s.name;
                    } else {
                        // Names come exclusively from the race/culture datapack pools.
                        // Exhaustion is a hard preflight failure, never a fabricated suffix or image fallback.
                        uniqueName = PeopleMod.NAMES.generateUnique(race, culture,
                                NameBank.settlementCategory(seed.type()),
                                new java.util.Random(Objects.hash(map.name, seed.id())), usedNames);
                        if (existingColony != null && existingColony.s != null)
                            existingColony.s.name = uniqueName;
                        nameOwners.put(uniqueName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT), id);
                    }
                    Colony colony = foundEmergentInMemory(server, dimension, id, uniqueName,
                            seed.blockX(), seed.blockZ(), operationId);
                    // Preserve the blueprint's stable ID, type and exact political ownership
                    // on the canonical Colony record; name remains Settlement.s.name.
                    colony.applyBlueprintIdentity(map.name, seed, uniqueName, race, culture,
                            realm == null ? "" : String.valueOf(realm.props.getOrDefault("politics.government", "")));
                    // Verify that every seed became a real Colony and that its persisted form can be restored.
                    Colony restored = Colony.fromMap(colony.toMap(), DEFS);
                    if (!id.equals(restored.s.id) || !operationId.equals(restored.genesisOperationId)
                            || !map.name.equals(restored.sourceMapId)
                            || !seed.id().equals(restored.blueprintSettlementId)
                            || !uniqueName.equals(restored.s.name))
                        throw new IllegalStateException("Colony identity round-trip failed for SettlementSeed " + seed.id());
                    if (!existed) staged++;
                }
                if (!saveDurably(server)) {
                    LOGGER.error("wf_settle: blueprint import for map '{}' in {} remains pending; durable snapshot failed",
                            map.name, dimension);
                    continue;
                }

                // Run the same canonical create-or-get operation a second time against the
                // just-imported seeds. Existing Colony objects must be returned, never cloned.
                int colonyCountAfterFirstImport = COLONIES.size();
                CharacterRegistry people = net.minecraftforge.fml.ModList.get().isLoaded("wf_people")
                        ? PeopleMod.registry(map) : null;
                int characterCount = people == null ? -1 : people.all().size();
                Set<String> houseNames = new HashSet<>();
                if (people != null) for (Character character : people.all())
                    if (character.house != null && !character.house.isBlank()) houseNames.add(character.house);
                int houseCount = houseNames.size();
                int titleCount = map.titles == null ? 0 : map.titles.allTitles().size();
                for (dev.worldforge.mod.MapData.SettlementSeed seed : map.settlementSeeds) {
                    String id = Colony.blueprintColonyId(map.name, seed.id());
                    String operationId = Colony.blueprintOperationId(map.name, seed.id());
                    Colony existing = COLONIES.get(id);
                    if (existing == null) throw new IllegalStateException("Imported Colony disappeared before idempotence audit: " + id);
                    Colony repeated = foundEmergentInMemory(server, dimension, id, existing.s.name,
                            seed.blockX(), seed.blockZ(), operationId);
                    if (repeated != existing)
                        throw new IllegalStateException("Repeated blueprint import allocated a second Colony: " + id);
                }
                Set<String> housesAfter = new HashSet<>();
                if (people != null) for (Character character : people.all())
                    if (character.house != null && !character.house.isBlank()) housesAfter.add(character.house);
                if (COLONIES.size() != colonyCountAfterFirstImport
                        || (people != null && (people.all().size() != characterCount || !housesAfter.equals(houseNames)))
                        || (map.titles != null && map.titles.allTitles().size() != titleCount))
                    throw new IllegalStateException("Repeated map import changed Colony/Character/House/Title counts");

                IMPORTED_BLUEPRINTS.add(importKey);
                if (staged > 0 || !map.settlementSeeds.isEmpty())
                    LOGGER.info("wf_settle: imported map blueprint '{}' in {}: {} named settlement anchors, {} new Colony records; re-import idempotence verified",
                            map.name, dimension, map.settlementSeeds.size(), staged);
            } catch (RuntimeException ex) {
                LOGGER.error("wf_settle: blueprint settlement import failed for map '{}' in {}: {}",
                        map.name, dimension, ex.toString(), ex);
            }
        }
    }

    /** Synchronize the canonical People profession catalog into the settlement assignment vocabulary. */
    public static void syncCanonicalProfessions() {
        Defs definitions = DEFS;
        if (definitions == null || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return;
        try {
            for (dev.worldforge.people.ProfessionDef profession : PeopleMod.PROFESSIONS.all())
                definitions.registerCanonicalProfession(profession.id, profession.name);
        } catch (LinkageError | RuntimeException error) {
            LOGGER.error("wf_settle: canonical People profession synchronization failed: {}", error.toString(), error);
        }
    }

    /**
     * Settlements owns the integration with resident life events because it depends on wf-people,
     * while wf-people must not depend back on this addon. Work is split fairly across canonical
     * partitions and remains bounded even when one partition contains most of the population.
     */
    private static void advanceCanonicalResidentLife() {
        try {
            LinkedHashSet<CharacterRegistry> unique = new LinkedHashSet<>();
            unique.addAll(PeopleMod.registries().values());
            unique.addAll(PeopleMod.dimensionRegistries().values());
            unique.add(PeopleMod.worldRegistry());
            unique.add(PeopleMod.playerRegistry());
            List<CharacterRegistry> registries = new ArrayList<>(unique);
            if (registries.isEmpty()) return;
            int count = registries.size();
            int start = Math.floorMod(lifeRegistryCursor++, count);
            var clock = dev.worldforge.mod.api.WorldForgeApi.clock();
            long worldDay = clock.dayNumber();
            int year = clock.year();
            for (int i = 0; i < count; i++) {
                int share = 128 / count + (i < 128 % count ? 1 : 0);
                if (share <= 0) continue;
                CharacterRegistry registry = registries.get((start + i) % count);
                registry.processPendingBereavementBatch(share);
                ResidentLifeEvents.advanceDuePregnanciesBatch(
                        registry, year, worldDay, PeopleMod.NAMES, share);
                ResidentLifeEvents.advanceHealthBatch(
                        registry, worldDay, share);
            }
        } catch (LinkageError | RuntimeException error) {
            LOGGER.error("wf_settle: canonical resident life pass failed: {}", error.toString(), error);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        advanceCanonicalResidentLife();
        if (DEFS == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ticks++;
        if (!professionsSynced) {
            syncCanonicalProfessions();
            professionsSynced = true;
        }
        // Blueprint packages are immutable during normal play; check for newly mounted maps
        // on a coarse cadence instead of walking every ServerLevel on every server tick.
        if (ticks % 100L == 1L) importWorldBlueprintSettlements(server);
        int simulationInterval = dev.worldforge.mod.MaqanPerformanceConfig.settlementSimulationIntervalTicks();
        if (ticks % 1200 == 0 && ticks != lastResidentAuditTick) {
            lastResidentAuditTick = ticks;
            validateCanonicalResidentLinks();
        }
        if (ticks % (simulationInterval * 10L) == 0) VanillaVillageAdapter.reconcile(server);
        if (actionsBlocked()) return;
        {
            var clock = dev.worldforge.mod.api.WorldForgeApi.clock();
            long worldMinute = clock.worldMinute();
            int worldYear = clock.year();
            double speed = clock.multiplier();
            refreshColonySimulationIndex(simulationInterval, ticks, worldMinute);
            if (speed > 0) {
                for (Colony c : coloniesDueForSimulation(ticks, simulationInterval)) {
                    if (c == null || c.s == null || COLONIES.get(c.s.id) != c) continue;
                    try {
                        // Stable colony phases spread work across the interval. The shared calendar,
                        // not wall ticks, preserves exact speed changes and prevents pause catch-up.
                        long previousMinute = LAST_SIMULATED_WORLD_MINUTE.computeIfAbsent(c.s.id, ignored -> worldMinute);
                        double elapsedSteps = ColonySimulationCadence.elapsedSteps(previousMinute, worldMinute);
                        if (elapsedSteps <= 0) continue;
                        c.s.setWorldCalendar(worldMinute, worldYear);
                        c.s.tick(elapsedSteps);
                        // Commit the cursor immediately after the authoritative settlement step:
                        // optional follow-up failures must never replay this economic/life interval.
                        LAST_SIMULATED_WORLD_MINUTE.put(c.s.id, worldMinute);
                        remitCrownTaxes(c);
                        syncPeople(c, server);
                        if (c.genesisOperationId != null && c.genesisOperationId.startsWith("worldsim:settlement:"))
                            ensureAutonomousInfrastructure(server, c.s.id);
                        long colonyStep = ColonySimulationCadence.stepNumber(c.s.id, ticks, simulationInterval);
                        if (c.s.raidIncoming || colonyStep
                                % dev.worldforge.mod.MaqanPerformanceConfig.lifeAiIntervalSteps() == 0)
                            runLifeAI(server, c);
                        c.map.tick(c.s, speed); // strategic logistics remains independent of loaded chunks
                        if (colonyStep % 10L == 0L) {
                            c.s.types = TYPES;
                            String rid = realmOf(c);
                            c.s.refreshType(c.s.population(),
                                    rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.terrain(rid),
                                    rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.races(rid));
                        }
                    } catch (RuntimeException ex) {
                        LOGGER.error("wf_settle: ошибка симуляции поселения {}: {}", c.s.id, ex.toString());
                    }
                }
            }
            // The shared trade network remains a world-level transaction on its existing cadence.
            if (ticks % simulationInterval == 0) try {
                TradeViews views = tradeViews();
                Map<String, Settlement> all = views.settlements();
                if (speed > 0) {
                    Map<String, dev.worldforge.settle.TradeNetwork.Location> anchors = views.anchors();
                    int pendingTradeEvents = NETWORK.events.size();
                    NETWORK.tick(all, speed, anchors, SettleMod::planShipmentPath);
                    if (NETWORK.events.size() > pendingTradeEvents) {
                        // The ledger is authoritative. Persist it before the shipment projection; fail closed
                        // if either side cannot be checkpointed so a dispatched consignment is never replayed.
                        if (!WorldForgeMod.saveTreasuryNow(server) || !saveDurably(server)) {
                            logisticsPersistenceBlocked = true;
                            LOGGER.error("wf_settle: shipment checkpoint failed; trade simulation frozen for recovery");
                            return;
                        }
                        for (String event : new ArrayList<>(NETWORK.events))
                            dev.worldforge.mod.api.WorldForgeApi.postEvent("trade", event, List.of(),
                                    dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute());
                        NETWORK.events.clear();
                    }
                    // Checkpoint in-flight progress every 30 seconds of server ticks; cargo remains escrowed.
                    if (NETWORK.events.isEmpty() && ticks % (STEP_TICKS * 30) == 0
                            && NETWORK.shipments.values().stream().anyMatch(shipment ->
                            shipment.status.equals("в пути") || shipment.status.equals("ожидает места")
                                    || shipment.status.equals("ожидает расчёта"))) {
                        if (!saveDurably(server)) {
                            logisticsPersistenceBlocked = true;
                            LOGGER.error("wf_settle: in-flight shipment progress checkpoint failed; trade simulation frozen");
                            return;
                        }
                    }
                    SettlementCaravans.tick(server, NETWORK.shipments.values());
                    if (ticks % (simulationInterval * 10L) == 0) {
                        Set<Integer> routeIds = new LinkedHashSet<>(NETWORK.routes.keySet());
                        int created = NETWORK.ensureAutonomousRoutes(all, 2, Math.max(1, all.size() / 3), 2.0);
                        if (created > 0) {
                            if (!saveDurably(server)) {
                                NETWORK.routes.keySet().removeIf(id -> !routeIds.contains(id));
                                LOGGER.error("wf_settle: autonomous trade links could not be persisted; review deferred");
                            } else {
                                dev.worldforge.mod.api.WorldForgeApi.postEvent("trade",
                                        "Autonomous settlement supply network established " + created + " new route(s)",
                                        List.of(), dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute());
                                LOGGER.info("wf_settle: autonomous supply links created={} settlements={}", created, all.size());
                            }
                        }
                    }
                }
            } catch (RuntimeException ex) {
                LOGGER.error("wf_settle: ошибка торговых маршрутов: {}", ex.toString());
            }
        }
        if (ticks % SITE_TICKS == 0) {
            // The incremental spatial index is maintained by canonical registry mutations.
            for (Colony c : ColonyInterestIndex.nearby(server, SimulationLod.LOCAL_RADIUS)) {
                ServerLevel lv = c.level(server);
                if (lv == null) continue;
                try {
                    BuildingSite.tick(lv, c);
                    RoadSite.tick(lv, c);
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
        SettleMapCommands.register(e.getDispatcher());
        ResidentInteraction.register(e);
        SettlementStressLab.register(e.getDispatcher());
    }
}
