package dev.worldforge.settle.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.mod.Json;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Defs;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.LifeSimulation;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.MapChunkGenerator;
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
    /** Fail-closed gate when an army logistics transfer cannot persist its ledger/settlement checkpoint. */
    private static volatile boolean logisticsPersistenceBlocked;

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

    /** Государство, на чьей земле стоит поселение (по провинции под центром), или "". */
    /** Resolve a settlement's realm only against the map generator in its actual dimension. */
    private static String realmOf(Colony colony, ServerLevel level) {
        if (colony == null || level == null
                || !colony.dim.equals(level.dimension().location().toString())) return "";
        if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator generator)) return "";
        dev.worldforge.mod.MapData map = generator.data();
        if (map == null || map.titles == null) return "";
        dev.worldforge.mod.TitleData.Title province = map.provinceAt(colony.cx, colony.cz);
        dev.worldforge.mod.TitleData.Realm realm = province == null ? null : map.titles.realmOf(province);
        return realm == null || realm.id == null ? "" : realm.id;
    }

    public static String realmOf(Colony c) {
        if (c != null && c.realmId != null && !c.realmId.isBlank()) return c.realmId;
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
            String sourceAccount = colony.s.ledgerAccountId("food");
            long previousLegs = TreasuryService.current().journal().stream()
                    .filter(entry -> entry.accountId().equals(sourceAccount) && !entry.credit()
                            && entry.kind() == TreasuryLedger.Kind.LOGISTICS
                            && entry.category().equals("army-field-rations")
                            && entry.reference().startsWith(referenceStem + ":"))
                    .map(TreasuryLedger.Entry::reference).distinct().count();
            String reference = referenceStem + ":" + previousLegs;
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
                if (plan == null || physicalSite.placed < plan.blocks().size()) continue;
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

    /** Закладывает поселение: 4 рабочих, склад дерево 60 / еда 40 / камень 10. */
    public static Colony found(ServerLevel lv, String name, BlockPos p) {
        return found(lv, name, p, "");
    }

    /** Idempotent Genesis-aware variant. The same operation always resolves to the same colony. */
    public static Colony found(ServerLevel lv, String name, BlockPos p, String genesisOperationId) {
        String dimension = lv.dimension().location().toString();
        if (genesisOperationId != null && !genesisOperationId.isBlank()) {
            for (Colony existing : COLONIES.values()) {
                if (!genesisOperationId.equals(existing.genesisOperationId)) continue;
                if (dimension.equals(existing.dim)) return existing;
                throw new IllegalStateException("Genesis operation " + genesisOperationId
                        + " is already bound to dimension " + existing.dim + " and cannot create a second colony in " + dimension);
            }
        }
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
        return c;
    }

    /** Creates a map-first settlement without fabricated workers, stock or buildings. */
    public static Colony foundEmergent(MinecraftServer server, String dimension, String id, String name,
                                       int x, int z, String operationId) {
        Colony existing = COLONIES.get(id);
        Colony colony = foundEmergentInMemory(server, dimension, id, name, x, z, operationId);
        if (existing == null && !saveDurably(server)) {
            COLONIES.remove(id, colony);
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
        Settlement settlement = new Settlement(id, name == null || name.isBlank() ? id : name, DEFS);
        settlement.setLedgerScope(dim);
        settlement.types = TYPES;
        Colony colony = new Colony(settlement, dim, x, z);
        colony.genesisOperationId = operationId == null ? "" : operationId;
        COLONIES.put(id, colony);
        LOGGER.info("wf_settle: map-first settlement staged id={} dim={} x={} z={} (no synthetic workers or stock)",
                id, dim, x, z);
        return colony;
    }

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
            List<Object> colonies = new ArrayList<>();
            for (Colony colony : COLONIES.values()) colonies.add(colony.toMap());
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("format", "wf-settle-world/1");
            root.put("colonies", colonies);
            root.put("trade", NETWORK.toJson());
            Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
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
        if (COLONIES.values().stream().anyMatch(colony -> colony != null && (colony.genesisProvisioningRuntimeBlocked || colony.actionPersistenceBlocked))) {
            LOGGER.error("wf_settle: periodic/shutdown save skipped to preserve the last durable settlement checkpoint");
            return;
        }
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

    /**
     * Bridges settlement workers to the authoritative person registry.
     * Worker.person is the durable foreign key; profession/building remain settlement-owned state.
     * The optional wf_people addon remains optional and no duplicate character registry is created here.
     */
    private static void syncPeople(Colony colony, MinecraftServer server) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("wf_people") || colony == null || colony.s == null) return;
        dev.worldforge.mod.MapData ownerMap = null;
        dev.worldforge.mod.TitleData.Title province = null;
        for (dev.worldforge.mod.MapData map : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            dev.worldforge.mod.TitleData.Title candidate = map.provinceAt(colony.cx, colony.cz);
            if (candidate != null) { ownerMap = map; province = candidate; break; }
        }
        if (ownerMap == null) return;
        CharacterRegistry registry = PeopleMod.registry(ownerMap);
        if (registry == null) return;
        dev.worldforge.mod.TitleData.Realm realm = ownerMap.titles.realmOf(province);
        String race = realm == null ? "" : propertyString(realm.props, "race");
        String culture = realm == null ? "" : propertyString(realm.props, "culture");
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
                colony.s.workers.remove(worker);
                continue;
            }
            if (person == null) {
                person = registry.create(worker.name, "", culture, "m", PeopleMod.YEAR - 20);
                personRegistry = registry;
                worker.person = person.globalId;
            }
            worker.person = person.globalId; // migrate legacy local cN reference to canonical global UUID
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

    /** Resolve the Character registry which owns this settlement's province. */
    private static CharacterRegistry registryForColony(Colony colony) {
        if (colony == null || colony.s == null) return null;
        for (dev.worldforge.mod.MapData map : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null || map.provinceAt(colony.cx, colony.cz) == null) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry != null) return registry;
        }
        return null;
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
        for (dev.worldforge.settle.Worker worker : new ArrayList<>(settlement.workers)) {
            if (worker.person == null || worker.person.isBlank()) continue;
            Character person = resolvePerson(registry, worker.person);
            if (person == null || !person.alive) continue;

            // Explicit player/RTS orders always preempt autonomous life goals without granting their effects.
            if (worker.manualOrder || worker.manualFollow) {
                LifeSimulation.cancelPending(person, "preempted-by-player-order");
                clearLifeAction(worker);
                continue;
            }
            if ("fleeing".equals(worker.lifeAction) || "defending".equals(worker.lifeAction)) {
                if (settlement.raidIncoming) continue;
                clearLifeAction(worker);
            }
            if (!worker.lifeAction.isBlank()
                    && !("travelling".equals(worker.lifeActionStage) || "performing".equals(worker.lifeActionStage))) {
                continue;
            }

            boolean brave = PopulationSimulation.hasDuty(person, Duty.SOLDIER)
                    || person.traits.contains("brave") || person.traits.contains("courageous")
                    || person.traits.contains("protective");
            if (worker.lifeAction.isBlank() && settlement.raidIncoming && brave) {
                worker.lifeAction = "defending";
                worker.lifeTargetX = 0; worker.lifeTargetZ = 0;
                worker.lifeActionUntil = 1.0e12;
                worker.status = "спешит на помощь поселению";
                continue;
            }
            boolean hasTraining = !worker.generalist() && !PopulationSimulation.trainingTarget(person).isBlank();
            boolean hasLeisure = settlement.buildings.stream().anyMatch(b -> b.state == Building.State.DONE
                    && !b.paused && b.def.category != null
                    && (b.def.category.equalsIgnoreCase("leisure") || b.def.id.contains("tavern") || b.def.id.contains("inn")));
            boolean hasCompanion = settlement.workers.stream().anyMatch(other -> other != worker);
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
                        clearLifeAction(worker);
                        return LifeSimulation.Outcome.BLOCKED;
                    }
                    if (!"performing".equals(worker.lifeActionStage)) return LifeSimulation.Outcome.IN_PROGRESS;
                    if (worldMinute < worker.lifeActionUntil) return LifeSimulation.Outcome.IN_PROGRESS;
                    // Settlement.eat(dt) is the authoritative ration ledger for every resident.
                    // Do not debit a second meal here; only ensure the stock has not collapsed to zero.
                    if (action == LifeSimulation.Action.EAT && settlement.amount("food") <= 0) {
                        clearLifeAction(worker);
                        return LifeSimulation.Outcome.BLOCKED;
                    }
                    if (action == LifeSimulation.Action.SOCIALIZE) {
                        dev.worldforge.settle.Worker companion = settlement.workers.stream()
                                .filter(other -> other.person != null && other.person.equals(worker.lifeSocialTarget))
                                .findFirst().orElse(null);
                        Character companionPerson = companion == null ? null : resolvePerson(registry, companion.person);
                        if (companion == null || companionPerson == null || !companionPerson.alive) {
                            clearLifeAction(worker);
                            return LifeSimulation.Outcome.BLOCKED;
                        }
                        if (ignored.lod() == LifeSimulation.LOD.ACTIVE) {
                            double dx = worker.x - companion.x, dz = worker.z - companion.z;
                            if (dx * dx + dz * dz > 16.0) {
                                clearLifeAction(worker);
                                return LifeSimulation.Outcome.BLOCKED;
                            }
                        }
                        Object tokenRaw = character.props.get("population.ai.pending_token");
                        if (!(tokenRaw instanceof String operationId) || operationId.isBlank()) {
                            clearLifeAction(worker);
                            return LifeSimulation.Outcome.BLOCKED;
                        }
                        int opinion = dev.worldforge.settle.ResidentSocial.interactionDelta(character, companionPerson);
                        dev.worldforge.settle.ResidentSocial.socializeOnce(character, companionPerson, opinion, operationId);
                        // Persist both canonical Character partitions before completing the
                        // physical action. If the second write fails, keep the same pending
                        // token: the first participant's receipt makes the retry idempotent.
                        if (!PeopleMod.persistCharacter(character, server)
                                || !PeopleMod.persistCharacter(companionPerson, server))
                            return LifeSimulation.Outcome.IN_PROGRESS;
                        companion.status = "общался: " + character.name;
                    }
                    clearLifeAction(worker);
                    worker.status = worker.building == 0 ? "свободен" : "работает";
                    return LifeSimulation.Outcome.SUCCESS;
                }

                // Start a local action. Its needs, food stock and social relations are not changed yet.
                switch (action) {
                    case EAT -> {
                        if (settlement.amount("food") < 1.0) return LifeSimulation.Outcome.BLOCKED;
                        double[] meal = residentMealPlace(settlement, worker);
                        beginLifeAction(worker, "eating", meal[0], meal[1], 30, worldMinute, context.lod());
                        worker.status = "идёт принимать пищу";
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case SLEEP -> {
                        double[] home = residentShelter(settlement, worker);
                        beginLifeAction(worker, "sleeping", home[0], home[1], 8 * 60, worldMinute, context.lod());
                        worker.status = "идёт отдыхать";
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case SEEK_SAFETY -> {
                        if (!settlement.raidIncoming) return LifeSimulation.Outcome.BLOCKED;
                        double[] shelter = residentShelter(settlement, worker);
                        worker.lifeAction = "fleeing";
                        worker.lifeTargetX = shelter[0]; worker.lifeTargetZ = shelter[1];
                        worker.lifeActionUntil = 1.0e12;
                        worker.status = "бежит в укрытие";
                        return LifeSimulation.Outcome.SUCCESS;
                    }
                    case SOCIALIZE -> {
                        dev.worldforge.settle.Worker companion = null;
                        double nearest = 32 * 32;
                        for (dev.worldforge.settle.Worker other : settlement.workers) {
                            if (other == worker || other.person == null || other.person.isBlank()) continue;
                            double dx = worker.x - other.x, dz = worker.z - other.z;
                            double distance = dx * dx + dz * dz;
                            if (distance < nearest && resolvePerson(registry, other.person) != null) {
                                nearest = distance; companion = other;
                            }
                        }
                        if (companion == null) return LifeSimulation.Outcome.BLOCKED;
                        worker.lifeSocialTarget = companion.person;
                        beginLifeAction(worker, "socializing", companion.x, companion.z, 3 * 60, worldMinute, context.lod());
                        worker.status = "идёт на встречу";
                        return LifeSimulation.Outcome.IN_PROGRESS;
                    }
                    case WORK -> {
                        return worker.building != 0 ? LifeSimulation.Outcome.SUCCESS : LifeSimulation.Outcome.RETRY;
                    }
                    case TRAIN -> {
                        if (worker.generalist() || PopulationSimulation.trainingTarget(character).isBlank())
                            return LifeSimulation.Outcome.BLOCKED;
                        worker.status = "обучается";
                        return LifeSimulation.Outcome.SUCCESS; // skill XP is granted once by LifeSimulation.applyEffects
                    }
                    case RECREATE -> {
                        if (!hasLeisure) return LifeSimulation.Outcome.BLOCKED;
                        worker.status = "досуг";
                        return LifeSimulation.Outcome.SUCCESS;
                    }
                    case IDLE -> { return LifeSimulation.Outcome.SUCCESS; }
                }
                return LifeSimulation.Outcome.BLOCKED;
            });
        }
        dev.worldforge.settle.ResidentLifeEvents.yearly(registry, settlement.year(), PeopleMod.NAMES);
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

    /** Removes the existing worker projection for a person entering military service; the departure is persisted. */
    public static boolean detachWorkerForMilitary(String characterId) {
        if (characterId == null || characterId.isBlank()) return false;
        for (Colony colony : COLONIES.values()) {
            if (colony != null && colony.s != null
                    && colony.s.removeWorkerByPerson(characterId, "transfer:military")) return true;
        }
        return false;
    }

    /** Reinstates an existing living Character as a settlement worker; no new Character is allocated. */
    public static boolean attachExistingCharacter(String colonyId, String characterId) {
        Colony colony = COLONIES.get(colonyId);
        if (colony == null || colony.s == null || characterId == null || characterId.isBlank()
                || !net.minecraftforge.fml.ModList.get().isLoaded("wf_people")) return false;
        // Recovery may replay a transition after the destination settlement was already saved.
        // Treat the exact existing assignment as success; never create a duplicate worker.
        for (Colony other : COLONIES.values()) {
            if (other == null || other.s == null) continue;
            for (dev.worldforge.settle.Worker worker : other.s.workers) {
                if (!characterId.equals(worker.person)) continue;
                return other == colony;
            }
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
        worker.person = person.globalId;
        worker.profession = profession;
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
        // Reattachment supersedes the queued transfer departure in the old settlement.
        // A later sync must not interpret that stale event as a second firing.
        for (Colony other : COLONIES.values()) {
            if (other != null && other.s != null)
                other.s.acknowledgeDepartureForPerson(person.globalId, "transfer:military");
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
        settlement.remittedSilver = ledger.totalDebits(source, TreasuryLedger.Kind.TAX, "crown-remittance");
        double due = Math.max(0, settlement.taxCollected * Math.max(0, Math.min(100, settlement.crownShare)) / 100.0
                - settlement.remittedSilver);
        double payment = Math.min(due, ledger.balance(source));
        if (payment <= 1e-9) return;
        boolean transferred = ledger.transfer(source, target, payment, TreasuryLedger.Kind.TAX,
                "crown-remittance", settlement.id + ":" + Double.toHexString(settlement.remittedSilver),
                settlement.worldMinute(), "settlement tax remitted to realm treasury");
        if (transferred) {
            settlement.remittedSilver = ledger.totalDebits(source, TreasuryLedger.Kind.TAX, "crown-remittance");
            settlement.stock.put(Defs.SILVER, ledger.balance(source));
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || DEFS == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ticks++;
        if (actionsBlocked()) return;
        if (ticks % STEP_TICKS == 0) {
            double speed = dev.worldforge.mod.api.WorldForgeApi.clock().multiplier();
            for (Colony c : COLONIES.values()) {
                if (speed <= 0) break;
                try {
                    c.s.setWorldCalendar(dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute(),
                            dev.worldforge.mod.api.WorldForgeApi.clock().year());
                    c.s.tick(speed);                                   // 1 симуляционная секунда = 20 тиков, 24 мировые минуты
                    remitCrownTaxes(c);
                    syncPeople(c, server);
                    runLifeAI(server, c);
                    c.map.tick(c.s, speed);                             // глобальная логистика не зависит от загруженности чанков
                    if (ticks % (STEP_TICKS * 10) == 0) { c.s.types = TYPES; String rid = realmOf(c); c.s.refreshType(c.s.population(), rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.terrain(rid), rid.isEmpty() ? java.util.Map.of() : dev.worldforge.mod.api.DemoApi.races(rid)); }
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
    }
}
