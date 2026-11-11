package dev.worldforge.troops.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.economy.TreasuryLedger;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.PopulationSimulation.Duty;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Forts;
import dev.worldforge.troops.Muster;
import dev.worldforge.troops.MixedOrders;
import dev.worldforge.troops.Wars;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.ArmyHierarchy;
import dev.worldforge.troops.MilitaryDeathLedger;
import dev.worldforge.troops.OperationalSupply;
import dev.worldforge.troops.TroopDefs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** wf-troops: физические солдаты и командный режим (клавиша R): выделить мышкой, приказать, держать строй. */
@Mod(TroopsMod.MOD_ID)
public class TroopsMod {
    public static final String MOD_ID = "wf_troops";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTO = "2";
    public static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(new ResourceLocation(MOD_ID, "main"), () -> PROTO,
            PROTO::equals, PROTO::equals);

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<EntityType<TroopEntity>> TROOP = ENTITIES.register("troop",
            () -> EntityType.Builder.<TroopEntity>of(TroopEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(12).build(MOD_ID + ":troop"));

    public static volatile TroopDefs DEFS = load(null);
    public static final Squads SQUADS = new Squads();
    public static final ArmyHierarchy HIERARCHY = new ArmyHierarchy();
    public static final Forts FORTS = new Forts();
    public static final Map<String, TroopEntity> LIVE = new ConcurrentHashMap<>();
    /** Durable death outbox; a soldier body is retained until the canonical Character estate is committed. */
    private static final MilitaryDeathLedger DEATHS = new MilitaryDeathLedger();
    /** Durable field rations/readiness; ration quantities themselves live in the shared TreasuryLedger. */
    private static final OperationalSupply.Store SUPPLY = new OperationalSupply.Store();
    private static volatile boolean militaryLoadFailed;
    /** Fail-closed guard after a player action cannot durably commit its military snapshot. */
    private static volatile boolean actionPersistenceBlocked;
    private static MinecraftServer server;
    private static int ticks;

    public TroopsMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(TROOP.get(), TroopEntity.attributes().build()));
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                NET.registerMessage(0, OrderPacket.class, OrderPacket::encode, OrderPacket::decode, OrderPacket::handle));
        MinecraftForge.EVENT_BUS.register(this);
        WorldForgeModSnapshotBridge.register();
    }

    private static final class WorldForgeModSnapshotBridge {
        private static void register() {
            dev.worldforge.mod.WorldForgeSnapshotExporter.registerContributor(TroopsMod::appendAtlasTroops);
        }
    }

    /** Physical soldiers and army aggregates are exported in the same dimension/block frame as settlements. */
    private static void appendAtlasTroops(MinecraftServer server, ServerLevel level, MapData map, List<Object> features) {
        String dimension=level.dimension().location().toString();
        Map<String,List<TroopEntity>> armies=new LinkedHashMap<>();
        for(TroopEntity troop:LIVE.values()){
            if(troop==null||!troop.isAlive()||troop.level()!=level)continue;
            String realm=troop.realm==null?"":troop.realm;
            int armyId=troop.armyId;
            String groupKey=realm+"|"+(armyId>0?"army:"+armyId:"garrison");
            armies.computeIfAbsent(groupKey,ignored->new ArrayList<>()).add(troop);
            Map<String,String> attrs=new LinkedHashMap<>();
            attrs.put("realm_id",realm);
            attrs.put("army_id",Integer.toString(armyId));
            attrs.put("squad_id",troop.squadId()==null?"":troop.squadId());
            attrs.put("character_id",troop.characterId==null?"":troop.characterId);
            attrs.put("owner_id",troop.ownerId());
            attrs.put("unit_id",troop.unitId());
            attrs.put("morale",Integer.toString(troop.moralePct()));
            attrs.put("health",Double.toString(troop.getHealth()));
            Map<String,Object> feature=new LinkedHashMap<>();
            feature.put("id","worldforge:army-unit:"+dimension+":"+troop.getUUID());
            feature.put("kind","army_unit");
            feature.put("name",troop.unitId()==null||troop.unitId().isBlank()?"Soldier":troop.unitId());
            feature.put("dimension_id",dimension);
            feature.put("x",troop.getX());feature.put("y",troop.getY());feature.put("z",troop.getZ());
            feature.put("realm_id",realm);feature.put("subtype",troop.unitId());
            feature.put("color",0xC65A55);feature.put("attributes",attrs);
            features.add(feature);
        }
        for(Map.Entry<String,List<TroopEntity>> entry:armies.entrySet()){
            List<TroopEntity> units=entry.getValue();
            if(units.isEmpty())continue;
            double x=0,y=0,z=0;
            for(TroopEntity troop:units){x+=troop.getX();y+=troop.getY();z+=troop.getZ();}
            x/=units.size();y/=units.size();z/=units.size();
            TroopEntity sample=units.get(0);
            String realm=sample.realm==null?"":sample.realm;
            Map<String,String> attrs=new LinkedHashMap<>();
            attrs.put("realm_id",realm);attrs.put("army_id",Integer.toString(sample.armyId));
            attrs.put("unit_count",Integer.toString(units.size()));
            attrs.put("composition",units.stream().collect(java.util.stream.Collectors.groupingBy(
                    TroopEntity::unitId,TreeMap::new,java.util.stream.Collectors.counting())).toString());
            Map<String,Object> feature=new LinkedHashMap<>();
            feature.put("id","worldforge:army:"+dimension+":"+entry.getKey().replace('|',':'));
            feature.put("kind","army");feature.put("name",sample.armyId>0?"Army "+sample.armyId:"Garrison · "+realm);
            feature.put("dimension_id",dimension);feature.put("x",x);feature.put("y",y);feature.put("z",z);
            feature.put("realm_id",realm);feature.put("subtype","military_force");
            feature.put("color",0xD95A55);feature.put("attributes",attrs);
            features.add(feature);
        }
    }

    static TroopDefs load(Path override) {
        TroopDefs d = new TroopDefs();
        try (InputStream in = TroopsMod.class.getResourceAsStream("/data/wf_troops/wf_troops/units.json")) {
            if (in != null) d.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (override != null && Files.exists(override)) d.load(Files.readString(override));      // файл мира дополняет и заменяет типы
        } catch (Exception e) {
            LOGGER.error("units.json: {}", e.toString());
        }
        for (String err : d.validate()) LOGGER.warn("wf-troops: {}", err);
        return d;
    }

    private static Path dir(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge"); }

    @SubscribeEvent
    public void started(ServerStartedEvent e) {
        actionPersistenceBlocked = false;
        militaryLoadFailed = false;
        server = e.getServer();
        DEFS = load(dir(server).resolve("wf_troops_units.json"));
        try {
            DEATHS.clear();
            SUPPLY.clear();
            Path f = dir(server).resolve("wf_squads.json");
            if (Files.exists(f)) SQUADS.fromJson(Files.readString(f));
            Path hf = dir(server).resolve("wf_army_hierarchy.json");
            if (Files.exists(hf)) HIERARCHY.fromJson(Files.readString(hf));
            Path ff = dir(server).resolve("wf_forts.json");
            if (Files.exists(ff)) FORTS.fromJson(Files.readString(ff));
            Path df = dir(server).resolve("wf_military_deaths.json");
            if (Files.exists(df)) DEATHS.fromJson(Files.readString(df));
            Path sf = dir(server).resolve("wf_operational_supply.json");
            if (Files.exists(sf)) SUPPLY.fromJson(Files.readString(sf));
            syncArmyHierarchy();
            processPendingDeaths();
        } catch (Exception ex) {
            militaryLoadFailed = true;
            actionPersistenceBlocked = true;
            LOGGER.error("wf_troops: military snapshots, death outbox or supply state are corrupt; preserving originals and blocking mutations: {}",
                    ex.toString(), ex);
        }
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent e) { save(); LIVE.clear(); server = null; }

    static void save() {
        if (!saveDurably()) LOGGER.error("wf_troops: durable save failed; latest military mutations must not be acknowledged as committed");
    }

    /** Writes all three military snapshots as one staged transaction and restores prior files on failure. */
    static boolean saveDurably() {
        if (server == null || militaryLoadFailed) return false;
        Map<Path, String> snapshots = new LinkedHashMap<>();
        Path root = dir(server);
        snapshots.put(root.resolve("wf_squads.json"), SQUADS.toJson());
        snapshots.put(root.resolve("wf_army_hierarchy.json"), HIERARCHY.toJson());
        snapshots.put(root.resolve("wf_forts.json"), FORTS.toJson());
        snapshots.put(root.resolve("wf_military_deaths.json"), DEATHS.toJson());
        snapshots.put(root.resolve("wf_operational_supply.json"), SUPPLY.toJson());
        Map<Path, Path> staged = new LinkedHashMap<>(), backups = new LinkedHashMap<>();
        Set<Path> replaced = new LinkedHashSet<>();
        try {
            Files.createDirectories(root);
            for (Map.Entry<Path, String> entry : snapshots.entrySet()) {
                Path target = entry.getKey();
                Path temp = target.resolveSibling(target.getFileName() + ".action.tmp");
                Path backup = target.resolveSibling(target.getFileName() + ".action.bak");
                Files.deleteIfExists(temp);
                Files.deleteIfExists(backup);
                Files.writeString(temp, entry.getValue(), StandardCharsets.UTF_8);
                staged.put(target, temp);
                if (Files.isRegularFile(target)) {
                    Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    backups.put(target, backup);
                }
            }
            for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                Files.move(entry.getValue(), entry.getKey(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                replaced.add(entry.getKey());
            }
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_troops: atomic military snapshot failed: {}", ex.toString());
            for (Path target : replaced) {
                try {
                    Path backup = backups.get(target);
                    if (backup != null && Files.exists(backup))
                        Files.copy(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    else Files.deleteIfExists(target);
                } catch (Exception rollback) {
                    LOGGER.error("wf_troops: military snapshot rollback failed for {}: {}", target, rollback.toString());
                }
            }
            return false;
        } finally {
            for (Path temp : staged.values()) try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            for (Path backup : backups.values()) try { Files.deleteIfExists(backup); } catch (Exception ignored) {}
        }
    }

    /** Reconciles the physical squad projection with the strategic wf-army roster. */
    private static void syncArmyHierarchy() {
        if (server == null) return;
        Set<String> reconciledSquads = new HashSet<>();
        for (TroopEntity troop : LIVE.values()) {
            if (troop == null || !troop.isAlive() || troop.armyId <= 0) continue;
            Squads.Squad squad = SQUADS.squadOf(troop.getUUID().toString());
            if (squad == null || !reconciledSquads.add(squad.id)) continue;
            boolean oneStrategicArmy = true;
            for (String memberId : squad.members) {
                TroopEntity member = LIVE.get(memberId);
                if (member != null && member.isAlive() && member.armyId != troop.armyId) {
                    oneStrategicArmy = false;
                    break;
                }
            }
            if (!oneStrategicArmy) {
                HIERARCHY.unassignSquad(squad.id);
                LOGGER.warn("wf-troops: squad {} contains units from multiple strategic armies; hierarchy link withheld", squad.id);
                continue;
            }
            ArmyLink.Found strategic = ArmyLink.find(troop.armyId, troop.realm);
            String name = strategic == null ? "Армия " + troop.armyId : strategic.army().name;
            String realm = strategic == null ? troop.realm : strategic.army().realm;
            try {
                HIERARCHY.ensureArmy(troop.armyId, name, troop.ownerId(), realm);
                if (strategic != null && !strategic.army().commander.isBlank())
                    HIERARCHY.appointArmyCommander(troop.armyId, strategic.army().commander);
                ArmyHierarchy.Corps assigned = HIERARCHY.corpsForSquad(squad.id);
                if (assigned == null || assigned.armyId != troop.armyId) {
                    ArmyHierarchy.Corps corps = HIERARCHY.defaultCorps(troop.armyId);
                    HIERARCHY.assignSquad(corps.id, squad.id, SQUADS);
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("wf-troops: hierarchy reconcile failed for army {}: {}", troop.armyId, ex.toString());
            }
        }
        HIERARCHY.pruneSquads(SQUADS);
    }

    @SubscribeEvent
    public void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof TroopEntity t)) return;
        String entityId = t.getUUID().toString();
        if (ModList.get().isLoaded("wf_people") && t.characterId != null && !t.characterId.isBlank()) {
            Character existingCharacter = PeopleMod.authority().getGlobal(t.characterId);
            if (existingCharacter != null && !existingCharacter.alive) {
                LIVE.remove(entityId);
                SQUADS.remove(entityId);
                t.discard();
                return; // A previously committed death must never be resurrected by a late chunk load.
            }
        }
        MilitaryDeathLedger.Pending pending = t.characterId == null || t.characterId.isBlank()
                ? null : DEATHS.get(t.characterId);
        if (pending != null && pending.troopEntityId().equals(entityId)) {
            t.setInvulnerable(true);
            completePendingDeath(pending, true);
            return;
        }
        LIVE.put(entityId, t);
        if (!t.deferCharacterBinding) syncPerson(t); // legacy saved entities migrate once; new recruits bind after payment commits
        if (SQUADS.squadOf(entityId) == null) {
            Squads.Squad squad = SQUADS.create(t.ownerId(), null, List.of(entityId));
            squad.realm = t.realm; squad.cx = t.getX(); squad.cz = t.getZ();
            t.setSquadId(squad.id);
        } else t.setSquadId(SQUADS.squadOf(entityId).id);
        applySupplyReadiness(t);
    }

    private record PersonBinding(CharacterRegistry registry, Character character) {}

    private static Character resolvePerson(CharacterRegistry registry, String id) {
        if (registry == null || id == null || id.isBlank()) return null;
        Character global = PeopleMod.authority().getGlobal(id);
        if (global != null) return global; // physical troop bodies may travel away from the Character's storage map
        return registry.get(id); // compatibility with pre-global-UUID troop NBT in its original map
    }

    /** Resolves a physical soldier to the one authoritative Character in the map owning its realm. */
    private static PersonBinding bindPerson(TroopEntity troop, boolean create) {
        if (troop == null || troop.level().isClientSide || !ModList.get().isLoaded("wf_people")) return null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            TitleData.Realm realm = troop.realm == null || troop.realm.isBlank() ? null : map.titles.realms.get(troop.realm);
            if (realm == null && (troop.realm == null || troop.realm.isBlank()) && troop.level() instanceof ServerLevel level) {
                TitleData.Title province = map.provinceAt(troop.getBlockX(), troop.getBlockZ());
                realm = province == null ? null : map.titles.realmOf(province);
                if (realm != null) troop.realm = realm.id;
            }
            if (realm == null) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry == null) continue;
            boolean hasIdentity = troop.characterId != null && !troop.characterId.isBlank();
            Character person = hasIdentity ? resolvePerson(registry, troop.characterId) : null;
            CharacterRegistry ownerRegistry = registry;
            if (hasIdentity && person != null && PeopleMod.authority().getGlobal(troop.characterId) != null) {
                ownerRegistry = PeopleMod.authority().registryForGlobal(troop.characterId);
                if (ownerRegistry == null) return null;
            }
            if (hasIdentity && (person == null || !person.alive)) {
                // A missing/dead foreign key is a data-integrity fault, not permission to invent a replacement person.
                return null;
            }
            if (!hasIdentity) {
                if (!create) return null;
                String culture = realm.props == null || realm.props.get("culture") == null ? "" : String.valueOf(realm.props.get("culture"));
                String name = troop.def() == null ? troop.unitId() : troop.def().name();
                person = registry.create(name + " " + troop.getUUID().toString().substring(0, 4), "", culture, "m", PeopleMod.YEAR - 20);
                troop.setCharacterId(person.globalId);
                ownerRegistry = registry;
            }
            troop.setCharacterId(person.globalId); // canonicalize legacy local ID before saving the entity
            if (!PeopleMod.authority().bindEntity(person.globalId, "troop", troop.getUUID().toString())) return null;
            return new PersonBinding(ownerRegistry, person);
        }
        return null;
    }

    /** Reassigns an existing settlement Character to a physical soldier without allocating a new identity. */
    public static boolean assignExistingCharacter(TroopEntity troop, String characterId) {
        if (troop == null || troop.level().isClientSide || characterId == null || characterId.isBlank()
                || !ModList.get().isLoaded("wf_people")) return false;
        if (!troop.deferCharacterBinding && troop.characterId != null && !troop.characterId.isBlank()
                && !troop.characterId.equals(characterId)) return false;
        if (!troop.deferCharacterBinding && (troop.characterId == null || troop.characterId.isBlank())) return false;
        String previousId = troop.characterId;
        troop.setCharacterId(characterId);
        PersonBinding binding = bindPerson(troop, false);
        if (binding == null) { troop.setCharacterId(previousId); return false; }
        Character person = binding.character();
        boolean workerDuty = PopulationSimulation.hasDuty(person, Duty.WORKER);
        // Preserve the civilian profession for every recruit, not only current settlement workers.
        // A specialist without a worker duty must also be able to return to their former life.
        boolean hadSavedProfession = person.props.containsKey("population.pre_military_profession");
        if (!hadSavedProfession) person.props.put("population.pre_military_profession",
                PopulationSimulation.profession(person));
        if (workerDuty) {
            if (!ModList.get().isLoaded("wf_settle")
                    || !dev.worldforge.settle.mc.SettleMod.detachWorkerForMilitary(person.globalId)) {
                binding.registry().unbindEntity(person.id, "troop", troop.getUUID().toString());
                troop.characterId = previousId;
                if (!hadSavedProfession) person.props.remove("population.pre_military_profession");
                return false;
            }
            binding.registry().setDuty(person.id, Duty.WORKER, false, PeopleMod.YEAR);
        }
        binding.registry().setDuty(person.id, Duty.SOLDIER, true, PeopleMod.YEAR);
        syncPerson(troop);
        return true;
    }

    /** Demobilizes a physical soldier into a settlement worker using the same Character ID. */
    public static boolean demobilizeToSettlement(TroopEntity troop, String colonyId) {
        if (troop == null || troop.level().isClientSide || !ModList.get().isLoaded("wf_settle")) return false;
        PersonBinding binding = bindPerson(troop, false);
        if (binding == null) return false;
        Character person = binding.character();
        if (!dev.worldforge.settle.mc.SettleMod.attachExistingCharacter(colonyId, person.globalId)) return false;
        binding.registry().unbindEntity(person.id, "troop", troop.getUUID().toString());
        person.props.remove("wf_troop_uuid");
        person.props.remove("wf_troop_unit");
        person.props.remove("wf_army_id");
        person.props.remove("wf_combat_xp");
        person.props.remove("wf_veteran_rank");
        person.props.remove("wf_unit_kind");
        Object lastMorale = person.props.remove("military.morale");
        if (lastMorale instanceof Number n)
            person.props.put("military.last_service_morale", Math.max(0, Math.min(100, n.intValue())));
        LIVE.remove(troop.getUUID().toString());
        SQUADS.remove(troop.getUUID().toString());
        troop.discard();
        return true;
    }

    /** Records wounds on the canonical Character before a lethal hit can close the entity binding. */
    static void recordInjury(TroopEntity troop,int severity,String cause) {
        PersonBinding binding=bindPerson(troop,false);
        if(binding==null||!binding.character().alive)return;
        CharacterDevelopment.recordInjury(binding.character(),severity,cause,
                Math.max(0,troop.level().getDayTime()));
    }

    static double injuryCombatMultiplier(TroopEntity troop) {
        PersonBinding binding=bindPerson(troop,false);
        return binding==null?1.0:CharacterDevelopment.injuryCombatMultiplier(binding.character());
    }

    /** Pushes live squad morale to the canonical Character without rebuilding profession or XP state. */
    static void syncMilitaryMorale(TroopEntity troop) {
        PersonBinding binding = bindPerson(troop, false);
        if (binding == null) return;
        CharacterDevelopment.setMilitaryMorale(binding.character(), troop.moralePct());
    }

    /** Shared character identity, soldier duty, profession, combat XP and skill ranks. */
    static void syncPerson(TroopEntity troop) {
        PersonBinding binding = bindPerson(troop, true);
        if (binding == null) return;
        Character person = binding.character();
        String profession = troop.unitId() == null || troop.unitId().isBlank() ? "soldier" : troop.unitId();
        if (!profession.equals(PopulationSimulation.profession(person)))
            binding.registry().changeProfession(person.id, profession, PeopleMod.YEAR);
        binding.registry().setDuty(person.id, Duty.SOLDIER, true, PeopleMod.YEAR);
        long characterXp = PopulationSimulation.trainingXp(person, "combat");
        if (characterXp > troop.combatXp()) troop.setCombatXpFromCharacter(characterXp);
        else PopulationSimulation.setTrainingXp(person, "combat", troop.combatXp());
        PopulationSimulation.setSkillRank(person, "combat", troop.veteranRank());
        person.props.put("wf_unit_kind", "soldier");
        person.props.put("wf_troop_uuid", troop.getUUID().toString());
        person.props.put("wf_troop_unit", troop.unitId());
        person.props.put("wf_army_id", troop.armyId);
        person.props.put("wf_combat_xp", troop.combatXp());
        person.props.put("wf_veteran_rank", troop.veteranRank());
        CharacterDevelopment.setMilitaryMorale(person, troop.moralePct());
        PopulationSimulation.updateIntent(person, PeopleMod.YEAR);
    }

    @SubscribeEvent
    public void leave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof TroopEntity t)) return;
        LIVE.remove(t.getUUID().toString());
    }

    /**
     * Write the death intent before mutating Character state. On any storage/identity error
     * the physical body remains present and invulnerable; it cannot silently disappear.
     */
    static boolean onDeath(TroopEntity troop, net.minecraft.world.damagesource.DamageSource source) {
        if (troop == null) return false;
        String entityId = troop.getUUID().toString();
        if (!ModList.get().isLoaded("wf_people")) {
            LIVE.remove(entityId);
            SQUADS.remove(entityId);
            return true;
        }

        String characterId = troop.characterId == null ? "" : troop.characterId.trim();
        if (characterId.isBlank()) {
            PersonBinding binding = bindPerson(troop, false);
            if (binding == null || binding.character() == null) {
                actionPersistenceBlocked = true;
                LOGGER.error("wf_troops: refusing to remove soldier {} because its canonical Character cannot be resolved",
                        entityId);
                return false;
            }
            characterId = binding.character().globalId;
        }

        String cause = source == null ? "combat" : source.getMsgId();
        if (cause == null || cause.isBlank()) cause = "combat";
        MilitaryDeathLedger.Pending pending;
        try {
            pending = new MilitaryDeathLedger.Pending(characterId, entityId,
                    Math.max(1, PeopleMod.YEAR), cause);
            DEATHS.stage(pending);
        } catch (RuntimeException error) {
            actionPersistenceBlocked = true;
            LOGGER.error("wf_troops: death outbox rejected Character {}: {}", characterId, error.toString());
            return false;
        }

        if (!saveDurably()) {
            actionPersistenceBlocked = true;
            LOGGER.error("wf_troops: death intent for Character {} could not be committed; preserving soldier body",
                    characterId);
            return false;
        }
        boolean completed = completePendingDeath(pending, false);
        if (!completed) actionPersistenceBlocked = true;
        return completed;
    }

    /** Resolves and commits a pending Character death/estate, then acknowledges the outbox. */
    private static boolean completePendingDeath(MilitaryDeathLedger.Pending pending, boolean discardPhysicalBody) {
        if (pending == null || server == null || militaryLoadFailed || !ModList.get().isLoaded("wf_people")) return false;
        try {
            Character person = PeopleMod.authority().getGlobal(pending.characterGlobalId());
            CharacterRegistry owner = PeopleMod.authority().registryForGlobal(pending.characterGlobalId());
            if (person == null || owner == null || !pending.characterGlobalId().equals(person.globalId)) {
                LOGGER.debug("wf_troops: pending death {} waits for the owning Character partition",
                        pending.characterGlobalId());
                return false;
            }

            owner.unbindEntity(person.id, "troop", pending.troopEntityId());
            if (person.alive) {
                PopulationSimulation.die(owner, person.id, pending.year(), pending.cause());
            } else {
                // CharacterRegistry.kill also finishes an interrupted estate transfer and lifecycle receipt.
                person.props.putIfAbsent("population.death_year", pending.year());
                person.props.putIfAbsent("population.death_cause", pending.cause());
                owner.kill(person.id);
            }

            if (!PeopleMod.persistCharacter(person, server)) {
                LOGGER.error("wf_troops: Character death/estate {} is not durable; outbox remains pending",
                        pending.characterGlobalId());
                return false;
            }

            DEATHS.complete(pending.characterGlobalId());
            if (!saveDurably()) {
                DEATHS.stage(pending);
                LOGGER.error("wf_troops: cannot acknowledge persisted death {}; retry remains staged",
                        pending.characterGlobalId());
                return false;
            }

            LIVE.remove(pending.troopEntityId());
            SQUADS.remove(pending.troopEntityId());
            if (discardPhysicalBody) discardPendingPhysicalBody(pending.troopEntityId());
            return true;
        } catch (RuntimeException error) {
            LOGGER.error("wf_troops: pending Character death {} failed and will retry: {}",
                    pending.characterGlobalId(), error.toString(), error);
            return false;
        }
    }

    private static void discardPendingPhysicalBody(String entityId) {
        if (server == null) return;
        try {
            java.util.UUID id = java.util.UUID.fromString(entityId);
            for (ServerLevel level : server.getAllLevels()) {
                Entity body = level.getEntity(id);
                if (body instanceof TroopEntity troop) {
                    troop.discard();
                    return;
                }
            }
        } catch (IllegalArgumentException error) {
            LOGGER.error("wf_troops: invalid pending troop entity UUID {}", entityId);
        }
    }

    private static void processPendingDeaths() {
        if (server == null || militaryLoadFailed || DEATHS.size() == 0 || !ModList.get().isLoaded("wf_people")) return;
        for (MapData map : WorldForgeMod.loadedMaps().values())
            if (map != null && map.titles != null) PeopleMod.registry(map);
        for (MilitaryDeathLedger.Pending pending : DEATHS.pending())
            completePendingDeath(pending, true);
    }

    /** Unpaid soldiers desert; preserve the living Character and demobilize into a nearby settlement when possible. */
    private static void onDesertion(TroopEntity troop) {
        PersonBinding binding = bindPerson(troop, false);
        if (binding != null) {
            Character person = binding.character();
            binding.registry().unbindEntity(person.id, "troop", troop.getUUID().toString());
            boolean settled = false;
            if (ModList.get().isLoaded("wf_settle") && troop.level() instanceof ServerLevel level) {
                dev.worldforge.settle.mc.Colony nearest = dev.worldforge.settle.mc.SettleMod.nearest(level, troop.getX(), troop.getZ());
                if (nearest != null) settled = dev.worldforge.settle.mc.SettleMod.attachExistingCharacter(nearest.s.id, person.globalId);
            }
            if (!settled) {
                binding.registry().setDuty(person.id, Duty.SOLDIER, false, PeopleMod.YEAR);
                person.props.put("population.status", "deserted");
                person.props.put("population.employer", "");
            }
            person.props.remove("wf_unit_kind");
            person.props.remove("wf_troop_uuid");
            person.props.remove("wf_troop_unit");
            person.props.remove("wf_army_id");
            person.props.remove("wf_combat_xp");
            person.props.remove("wf_veteran_rank");
        }
        String id = troop.getUUID().toString();
        LIVE.remove(id);
        SQUADS.remove(id);
        troop.discard();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || server == null) return;
        ticks++;
        if (ticks % 10 == 0) TroopController.step(server);
        if (ticks % 100 == 0) {
            syncArmyHierarchy();
            processPendingDeaths();
        }
        if (ticks % 1200 == 0) for (TroopEntity troop : LIVE.values()) syncPerson(troop);
        if (ticks % 100 == 0) payroll();
        if (ticks % 20 == 0) FortService.step(server, ticks);
        if (ticks % 6000 == 0) save();
    }

    private record SupplyKey(String realm, int armyId, String dimension) {
        String id() { return realm + ":" + armyId + ":" + dimension; }
        String accountId() { return "army-supply:" + realm + ":" + armyId + ":" + dimension; }
        String ownerId() { return "army-supply:" + realm + ":" + armyId; }
    }

    /** Uses the saved field-supply state to apply combat/mobility effects when a soldier loads. */
    private static void applySupplyReadiness(TroopEntity troop) {
        if (troop == null || troop.realm == null || troop.realm.isBlank()) return;
        String dimension = troop.level().dimension().location().toString();
        OperationalSupply.State state = SUPPLY.state(new SupplyKey(troop.realm, troop.armyId, dimension).id());
        troop.setSupplyReadiness(OperationalSupply.combatMultiplier(state),
                OperationalSupply.movementMultiplier(state));
    }

    /**
     * Settles daily rations against the actual settlement food store and persistent army account.
     * The TreasuryLedger debit is committed before the readiness checkpoint. If the server stops
     * between these two writes, the journal reference lets the next boot reconstruct the exact ration
     * amount and finish the readiness checkpoint without charging the army twice.
     */
    private static void fieldSupply(Map<PayrollKey, List<TroopEntity>> payrollGroups, int days, long day, long worldMinute) {
        if (days <= 0 || day < 0) return;
        TreasuryLedger ledger = WorldForgeMod.treasury();
        for (Map.Entry<PayrollKey, List<TroopEntity>> armyEntry : payrollGroups.entrySet()) {
            Map<String, List<TroopEntity>> dimensions = new LinkedHashMap<>();
            for (TroopEntity troop : armyEntry.getValue()) {
                String dimension = troop.level().dimension().location().toString();
                dimensions.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(troop);
            }
            for (Map.Entry<String, List<TroopEntity>> dimensionEntry : dimensions.entrySet()) {
                PayrollKey army = armyEntry.getKey();
                String dimension = dimensionEntry.getKey();
                List<TroopEntity> soldiers = dimensionEntry.getValue();
                SupplyKey key = new SupplyKey(army.realm(), army.armyId(), dimension);
                OperationalSupply.State supplyState = SUPPLY.state(key.id());
                if (day <= supplyState.lastProcessedWorldDay) {
                    for (TroopEntity soldier : soldiers) applySupplyReadiness(soldier);
                    continue;
                }

                String accountId = key.accountId();
                ledger.open(accountId, key.ownerId(), "food", 0);
                String consumptionReference = "army-rations:" + key.id() + ":" + day;

                // Recovery after a ledger commit but before the supply snapshot commit.
                TreasuryLedger.Entry committedConsumption = ledger.journal().stream()
                        .filter(entry -> entry.accountId().equals(accountId) && !entry.credit()
                                && entry.kind() == TreasuryLedger.Kind.CONSUMPTION
                                && entry.reference().equals(consumptionReference))
                        .reduce((first, second) -> {
                            if (Math.abs(first.amount() - second.amount()) > 1e-8)
                                throw new IllegalStateException("Conflicting committed army ration postings");
                            return first;
                        }).orElse(null);

                double consumed;
                double availableBeforeConsumption;
                if (committedConsumption != null) {
                    consumed = committedConsumption.amount();
                    availableBeforeConsumption = ledger.balance(accountId) + consumed;
                } else {
                    int headcount = soldiers.size();
                    double demand = OperationalSupply.demand(headcount, days);
                    double available = ledger.balance(accountId);
                    double shortfall = Math.max(0, demand - available);

                    if (shortfall > 1e-9 && ModList.get().isLoaded("wf_settle")
                            && !dev.worldforge.settle.mc.SettleMod.actionsBlocked()) {
                        double centerX = soldiers.stream().mapToDouble(TroopEntity::getX).average().orElse(0);
                        double centerZ = soldiers.stream().mapToDouble(TroopEntity::getZ).average().orElse(0);
                        ServerLevel level = soldiers.get(0).level() instanceof ServerLevel serverLevel
                                ? serverLevel : null;
                        String deliveryReference = "army-resupply:" + key.id() + ":" + day;
                        if (level != null) {
                            dev.worldforge.settle.mc.SettleMod.transferFoodToArmy(level, army.realm(),
                                    accountId, key.ownerId(), shortfall, centerX, centerZ, deliveryReference);
                        }
                        available = ledger.balance(accountId);
                    }

                    consumed = Math.min(available, demand);
                    if (consumed > 1e-9 && !ledger.withdrawOnce(accountId, consumed,
                            TreasuryLedger.Kind.CONSUMPTION, "field-rations", consumptionReference,
                            worldMinute, "daily army food consumption")) {
                        actionPersistenceBlocked = true;
                        LOGGER.error("wf_troops: ration consumption failed for {}; readiness was not advanced: {}",
                                key.id(), ledger.lastError());
                        continue;
                    }
                    availableBeforeConsumption = available;
                }

                // First persist the economic posting. The readiness checkpoint is a recoverable second phase.
                if (!WorldForgeMod.saveTreasuryNow(server)) {
                    actionPersistenceBlocked = true;
                    LOGGER.error("wf_troops: ration ledger for {} could not be saved; blocking military mutations",
                            key.id());
                    continue;
                }
                OperationalSupply.Report report;
                try {
                    report = SUPPLY.advance(key.id(), soldiers.size(), days,
                            availableBeforeConsumption, consumed, day);
                } catch (RuntimeException error) {
                    actionPersistenceBlocked = true;
                    LOGGER.error("wf_troops: cannot advance supply checkpoint {}: {}", key.id(), error.toString());
                    continue;
                }
                if (report != null && report.shortage() > 1e-9)
                    LOGGER.warn("wf_troops: army {} lacks {} food; readiness {}",
                            key.id(), report.shortage(), report.readiness());
                for (TroopEntity soldier : soldiers) applySupplyReadiness(soldier);
                if (!saveDurably()) {
                    actionPersistenceBlocked = true;
                    LOGGER.error("wf_troops: field supply checkpoint {} could not be saved", key.id());
                }
            }
        }
    }

    // ---- жалованье ----
    private static final Random RNG = new Random();

    private record PayrollKey(String realm, int armyId) {}

    /** Daily payroll is funded through the same realm and army accounts used by Statecraft. */
    private static void payroll() {
        int days = WorldForgeApi.clock().pull("wf_troops");
        if (days <= 0) return;
        long day = Math.max(0, WorldForgeApi.clock().dayNumber());
        long worldMinute = day * 1440L;
        TreasuryLedger ledger = WorldForgeMod.treasury();
        Map<PayrollKey, List<TroopEntity>> groups = new LinkedHashMap<>();
        for (TroopEntity troop : LIVE.values()) {
            if (!troop.isAlive() || troop.realm == null || troop.realm.isBlank()) continue;
            groups.computeIfAbsent(new PayrollKey(troop.realm, troop.armyId), ignored -> new ArrayList<>()).add(troop);
        }
        fieldSupply(groups, days, day, worldMinute);
        if (actionPersistenceBlocked) return;
        for (Map.Entry<PayrollKey, List<TroopEntity>> entry : groups.entrySet()) {
            PayrollKey key = entry.getKey();
            Map<String, Integer> counts = new HashMap<>();
            for (TroopEntity troop : entry.getValue()) counts.merge(troop.unitId(), 1, Integer::sum);
            // Unit upkeep is authored in gold; the canonical world ledger stores silver (5:1).
            double costSilver = Muster.upkeep(DEFS, counts) * days * 5.0;
            if (!Double.isFinite(costSilver) || costSilver <= 0) continue;

            String realmAccount = "realm:" + key.realm();
            ledger.open(realmAccount, key.realm(), "silver", 0);
            String armyAccount = key.armyId() > 0 ? "army:" + key.armyId() : "army:garrison:" + key.realm();
            String armyOwner = key.armyId() > 0 ? Integer.toString(key.armyId()) : "garrison:" + key.realm();
            ledger.open(armyAccount, armyOwner, "silver", 0);

            String wageReference = "payroll:" + key.realm() + ":" + key.armyId() + ":" + day;
            if (ledger.hasDebit(armyAccount, TreasuryLedger.Kind.WAGE, wageReference)) continue;
            double topUp = Math.max(0, costSilver - ledger.balance(armyAccount));
            boolean funded = topUp <= 1e-9 || ledger.transferOnce(
                    realmAccount, armyAccount, topUp, TreasuryLedger.Kind.ARMY_UPKEEP,
                    "payroll-funding", "payroll-fund:" + key.realm() + ":" + key.armyId() + ":" + day,
                    worldMinute, "daily military payroll appropriation");
            boolean paid = funded && ledger.withdrawOnce(
                    armyAccount, costSilver, TreasuryLedger.Kind.WAGE,
                    "soldier-payroll", wageReference,
                    worldMinute, "daily soldier wages");
            if (paid) continue;

            List<TroopEntity> soldiers = entry.getValue();
            for (int index : Muster.deserters(soldiers.size(), RNG)) {
                TroopEntity troop = soldiers.get(index);
                onDesertion(troop);
            }
        }
    }

    // ---- приказы от клиента ----
    static void handleOrder(ServerPlayer p, OrderPacket o) {
        // BUILD_LIST is a read-only UI query and intentionally does not create a mutation receipt.
        if (o.action() == OrderPacket.BUILD_LIST) {
            handleOrderDirect(p, o);
            return;
        }
        if (actionPersistenceBlocked) {
            p.displayClientMessage(Component.literal("Военные приказы заблокированы после ошибки сохранения; требуется перезапуск и сверка"), true);
            return;
        }
        if (ModList.get().isLoaded("wf_settle") && WorkerControl.anyWorker(p, o)
                && dev.worldforge.settle.mc.SettleMod.actionsBlocked()) {
            p.displayClientMessage(Component.literal("Изменения поселений заблокированы после ошибки сохранения; требуется перезапуск и сверка"), true);
            return;
        }
        String actor = p.getUUID().toString();
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "troop:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + o.requestId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "military.client_order", actor, "selection", List.of(o.toString()), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (p.server.getPlayerList().getPlayer(p.getUUID()) == null)
                            return "Игрок отключился до исполнения приказа";
                        if (actionPersistenceBlocked)
                            return "Военные приказы заблокированы после ошибки сохранения; требуется перезапуск и сверка";
                        if (ModList.get().isLoaded("wf_settle") && WorkerControl.anyWorker(p, o)
                                && dev.worldforge.settle.mc.SettleMod.actionsBlocked())
                            return "Изменения поселений заблокированы после ошибки сохранения; требуется перезапуск и сверка";
                        return null;
                    },
                    (req, attempt) -> {
                        ActionResult result;
                        try { result = handleOrderDirect(p, o); }
                        catch (RuntimeException ex) {
                            actionPersistenceBlocked = true;
                            return ActionResult.fail("Военный приказ завершился исключением; дальнейшие приказы заблокированы до сверки: " + ex.getMessage());
                        }
                        if (!result.ok) return result;
                        if (!saveDurably()) {
                            actionPersistenceBlocked = true;
                            return ActionResult.fail("Приказ применён в памяти, но военные снимки не сохранены; дальнейшие приказы заблокированы до сверки");
                        }
                        if (ModList.get().isLoaded("wf_settle")
                                && (o.action() == OrderPacket.BUILD || WorkerControl.anyWorker(p, o))
                                && !dev.worldforge.settle.mc.SettleMod.persistActionMutation(p.server)) {
                            actionPersistenceBlocked = true;
                            return ActionResult.fail("Приказ изменил состояние поселения в памяти, но снимок не сохранён; военные и поселения заблокированы до сверки");
                        }
                        return result;
                    }, null, minute,
                    dev.worldforge.mod.WorldForgeMod.actionExecutionFile(p.server));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                p.displayClientMessage(Component.literal("Приказ отклонён: " + task.message()), true);
        } catch (RuntimeException ex) {
            p.displayClientMessage(Component.literal("Не удалось принять приказ: " + ex.getMessage()), true);
        }
    }

    private static ActionResult handleOrderDirect(ServerPlayer p, OrderPacket o) {
        String owner = p.getUUID().toString();
        switch (o.action()) {
            case OrderPacket.ORDER -> {
                Squads.Order ord = o.kind() == Squads.Kind.ATTACK.ordinal()
                        ? new Squads.Order(Squads.Kind.ATTACK, 0, 0, o.target())
                        : new Squads.Order(Squads.Kind.values()[Math.max(0, Math.min(o.kind(), Squads.Kind.values().length - 1))],
                                o.x(), o.z(), o.target());
                List<String> selectedTroops = troopIds(o);
                boolean selectedWorkers = ModList.get().isLoaded("wf_settle") && WorkerControl.anyWorker(p, o);
                MixedOrders.Plan mixed = MixedOrders.plan(ord.kind(), selectedWorkers, !selectedTroops.isEmpty());
                ActionResult workerResult = null;
                if (mixed.workers() != MixedOrders.WorkerAction.NONE) {
                    int workerAction = switch (mixed.workers()) {
                        case MOVE -> OrderPacket.WORK;
                        case FOLLOW -> OrderPacket.WORK_FOLLOW;
                        case HOLD -> OrderPacket.WORK_HOLD;
                        case STOP -> OrderPacket.WORK_STOP;
                        default -> -1;
                    };
                    if (workerAction >= 0) {
                        OrderPacket workerOrder = new OrderPacket(workerAction, o.ids(), 0, o.x(), o.z(), "", o.queue(), 0);
                        workerResult = WorkerControl.command(p, workerOrder);
                        p.displayClientMessage(Component.literal(workerResult.message), true);
                    }
                }
                if (!mixed.commandTroops())
                    return workerResult != null ? workerResult : ActionResult.fail("Не выбраны подходящие рабочие или военные подразделения");
                for (String id : selectedTroops) {
                    Squads.Squad routed = SQUADS.squadOf(id);
                    if (routed != null && routed.owner.equals(owner) && routed.routed && ord.kind() != Squads.Kind.STOP) {
                        String reason = "Отряд «" + routed.name + "» бежит и не слушает приказов";
                        p.displayClientMessage(Component.literal(reason), true);
                        return workerResult != null && workerResult.ok
                                ? ActionResult.ok("Приказ рабочим выполнен частично; " + reason)
                                : ActionResult.fail(reason);
                    }
                }
                Squads.Squad squad = SQUADS.commandUnits(owner, selectedTroops, ord, o.queue());
                if (squad != null) {
                    String message = squad.name + " (" + squad.members.size() + "): " + ord.kind().title;
                    p.displayClientMessage(Component.literal(message), true);
                    return ActionResult.ok(workerResult != null && !workerResult.ok
                            ? message + "; приказ рабочим не выполнен: " + workerResult.message : message);
                }
                return workerResult != null && workerResult.ok ? workerResult
                        : ActionResult.fail(workerResult == null
                                ? "Не найдено принадлежащих игроку подразделений для приказа" : workerResult.message);
            }
            case OrderPacket.FORMATION, OrderPacket.STANCE -> {
                Set<Squads.Squad> hit = new LinkedHashSet<>();
                for (String id : troopIds(o)) {
                    Squads.Squad squad = SQUADS.squadOf(id);
                    if (squad != null && squad.owner.equals(owner)) hit.add(squad);
                }
                for (Squads.Squad squad : hit) {
                    if (o.action() == OrderPacket.FORMATION)
                        squad.formation = Formations.Type.values()[Math.floorMod(o.arg(), Formations.Type.values().length)];
                    else squad.stance = Squads.Stance.values()[Math.floorMod(o.arg(), Squads.Stance.values().length)];
                }
                if (hit.isEmpty()) return ActionResult.fail("Не выбрано принадлежащих игроку отрядов");
                Squads.Squad first = hit.iterator().next();
                String message = o.action() == OrderPacket.FORMATION ? "Строй: " + first.formation.title : "Поза: " + first.stance.title;
                p.displayClientMessage(Component.literal(message), true);
                return ActionResult.ok(message + " · отрядов: " + hit.size());
            }
            case OrderPacket.APPOINT -> {
                Map<String, TroopEntity> leaders = new LinkedHashMap<>();
                for (String id : troopIds(o)) {
                    TroopEntity troop = LIVE.get(id);
                    if (troop == null || !owner.equals(troop.ownerId())) continue;
                    Squads.Squad squad = SQUADS.squadOf(id);
                    boolean hasIdentity = troop.characterId != null && !troop.characterId.isBlank();
                    if (squad != null && owner.equals(squad.owner) && (o.arg() < 0 || hasIdentity))
                        leaders.putIfAbsent(squad.id, troop);
                }
                int changed = 0;
                for (Map.Entry<String, TroopEntity> entry : leaders.entrySet()) {
                    TroopEntity leader = entry.getValue();
                    String character = o.arg() < 0 ? "" : leader.characterId;
                    if (o.arg() >= 0 && (character == null || character.isBlank())) continue;
                    if (SQUADS.appointLeader(entry.getKey(), character, false, Math.max(0, o.arg()))) changed++;
                }
                String message = o.arg() < 0 ? "Командиры сняты: " + changed
                        : "Назначено командиров: " + changed + " · V — назначить, Ctrl+V — снять";
                p.displayClientMessage(Component.literal(message), true);
                return changed > 0 ? ActionResult.ok(message)
                        : ActionResult.fail("Не удалось изменить назначение командиров: подходящие отряды не выбраны");
            }
            case OrderPacket.GROUP -> {
                List<String> ids = new ArrayList<>();
                for (String id : troopIds(o)) {
                    Squads.Squad squad = SQUADS.squadOf(id);
                    if (squad != null && squad.owner.equals(owner)) ids.add(id);
                }
                if (ids.isEmpty()) return ActionResult.fail("Не выбраны принадлежащие игроку солдаты для новой группы");
                Squads.Squad created = SQUADS.create(owner, null, ids);
                for (String id : ids) {
                    TroopEntity troop = LIVE.get(id);
                    if (troop != null) troop.setSquadId(created.id);
                }
                String message = "Собран " + created.name + " (" + created.members.size() + ")";
                p.displayClientMessage(Component.literal(message), true);
                return ActionResult.ok(message);
            }
            case OrderPacket.WORK, OrderPacket.RELEASE, OrderPacket.WORK_STOP, OrderPacket.WORK_HOLD, OrderPacket.WORK_FOLLOW -> {
                if (!ModList.get().isLoaded("wf_settle")) return ActionResult.fail("Для управления рабочими нужен мод wf_settle");
                if (!WorkerControl.anyWorker(p, o)) return ActionResult.fail("В выборе нет рабочих");
                ActionResult result = WorkerControl.command(p, o);
                p.displayClientMessage(Component.literal(result.message), true);
                return result;
            }
            case OrderPacket.WORK_AREA -> {
                if (!ModList.get().isLoaded("wf_settle")) return ActionResult.fail("Для рабочих зон нужен мод wf_settle");
                if (!WorkerControl.anyWorker(p, o)) return ActionResult.fail("В выборе нет рабочих");
                ActionResult result = WorkerControl.workArea(p, o);
                p.displayClientMessage(Component.literal(result.message), true);
                return result;
            }
            case OrderPacket.BUILD -> {
                if (!ModList.get().isLoaded("wf_settle")) return ActionResult.fail("Для строительства нужен мод wf_settle");
                ActionResult result = BuildControl.place(p, o.target(), (int) Math.floor(o.x()), (int) Math.floor(o.z()), o.ids());
                p.displayClientMessage(Component.literal(result.message), true);
                return result;
            }
            default -> { return ActionResult.fail("Неизвестный тип военного приказа: " + o.action()); }
        }
    }

    private static final Map<String, Boolean> WAR_CACHE = new ConcurrentHashMap<>();
    private static int warCacheTick = -1000;

    private static Object enemiesOf(String realm) {
        for (dev.worldforge.mod.MapData m : dev.worldforge.mod.WorldForgeMod.loadedMaps().values()) {
            if (m.titles == null) continue;
            dev.worldforge.mod.TitleData.Realm r = m.titles.realms.get(realm);
            if (r != null) return r.props.get("enemies");
        }
        return null;
    }

    /** Воюют ли государства солдат (кэш на 5 секунд: вызывается на каждый поиск цели). */
    static boolean atWar(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) return false;
        if (ticks - warCacheTick > 100) { WAR_CACHE.clear(); warCacheTick = ticks; }
        return WAR_CACHE.computeIfAbsent(a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a, k -> Wars.atWar(a, enemiesOf(a), b, enemiesOf(b)));
    }

    /** Защитники крепости и чужие солдаты, для которых она вражеская, враждуют сами, без приказов. */
    static boolean fortHostile(TroopEntity a, TroopEntity b) {
        if (a.fortId > 0 && b.fortId != a.fortId) { Forts.Fort f = FORTS.get(a.fortId); if (f != null && f.hostileTo(b.ownerId(), b.realm)) return true; }
        if (b.fortId > 0 && a.fortId != b.fortId) { Forts.Fort f = FORTS.get(b.fortId); if (f != null && f.hostileTo(a.ownerId(), a.realm)) return true; }
        return false;
    }

    /** Операторы и владелец одиночного мира: стройкой и рабочими управляют они (как командами /wfsettle). */
    static boolean may(ServerPlayer p) { return p.hasPermissions(2) || p.server.isSingleplayerOwner(p.getGameProfile()); }

    private static List<String> troopIds(OrderPacket o) {
        List<String> r = new ArrayList<>();
        for (String id : o.ids()) if (LIVE.containsKey(id)) r.add(id);
        return r;
    }

    // ---- команды ----
    @SubscribeEvent
    public void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("wftroops")
                .then(Commands.literal("types").executes(c -> {
                    for (TroopDefs.Unit u : DEFS.units.values())
                        c.getSource().sendSuccess(() -> Component.literal(u.id() + ": " + u.name() + " [" + u.role() + "] " + u.cost() + " зол. | здание: " + DEFS.recruitmentBuilding(u)), false);
                    return 1;
                }))
                .then(Commands.literal("raise")
                        .then(Commands.argument("type", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(DEFS.units.keySet(), b))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, Squads.MAX_SIZE)).executes(c -> raise(c.getSource(), StringArgumentType.getString(c, "type"), IntegerArgumentType.getInteger(c, "n"), ""))
                                        .then(Commands.argument("realm", StringArgumentType.word()).requires(s -> s.hasPermission(2))
                                                .executes(c -> raise(c.getSource(), StringArgumentType.getString(c, "type"), IntegerArgumentType.getInteger(c, "n"), StringArgumentType.getString(c, "realm")))))))
                .then(FortService.commands())
                .then(Commands.literal("muster").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, Squads.MAX_SIZE)).executes(c -> muster(c.getSource(), IntegerArgumentType.getInteger(c, "army"), IntegerArgumentType.getInteger(c, "n"))))))
                .then(Commands.literal("return").requires(s -> s.hasPermission(2)).executes(c -> returnToArmy(c.getSource())))
                .then(Commands.literal("appoint")
                        .then(Commands.argument("squad", StringArgumentType.word())
                                .then(Commands.argument("character", StringArgumentType.word())
                                        .then(Commands.argument("hero", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .then(Commands.argument("rank", IntegerArgumentType.integer(0, 10))
                                                        .executes(ctx -> appointCommander(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "squad"),
                                                                StringArgumentType.getString(ctx, "character"),
                                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "hero"),
                                                                IntegerArgumentType.getInteger(ctx, "rank"))))))))
                .then(Commands.literal("corps")
                        .then(Commands.literal("create")
                                .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(ctx -> createCorps(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "army"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("assign")
                                .then(Commands.argument("corps", StringArgumentType.word())
                                        .then(Commands.argument("squad", StringArgumentType.word())
                                                .executes(ctx -> assignSquadToCorps(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "corps"),
                                                        StringArgumentType.getString(ctx, "squad"))))))
                        .then(Commands.literal("order")
                                .then(Commands.argument("corps", StringArgumentType.word())
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .then(Commands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-30000000, 30000000))
                                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-30000000, 30000000))
                                                                .executes(ctx -> orderCorps(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "corps"),
                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"), false))
                                                                .then(Commands.argument("queue", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                                        .executes(ctx -> orderCorps(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "corps"),
                                                                                StringArgumentType.getString(ctx, "kind"),
                                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                                                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "queue"))))))))))
                .then(Commands.literal("order")
                        .then(Commands.literal("army")
                                .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("kind", StringArgumentType.word())
                                                .then(Commands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-30000000, 30000000))
                                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-30000000, 30000000))
                                                                .executes(ctx -> orderArmy(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "army"),
                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"), false))
                                                                .then(Commands.argument("queue", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                                        .executes(ctx -> orderArmy(ctx.getSource(),
                                                                                IntegerArgumentType.getInteger(ctx, "army"),
                                                                                StringArgumentType.getString(ctx, "kind"),
                                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "x"),
                                                                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "z"),
                                                                                com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "queue"))))))))))
                .then(Commands.literal("hierarchy")
                        .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                .executes(ctx -> showHierarchy(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "army")))))
                .then(Commands.literal("commander")
                        .then(Commands.literal("army")
                                .then(Commands.argument("army", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("character", StringArgumentType.word())
                                                .executes(ctx -> appointArmyCommander(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "army"),
                                                        StringArgumentType.getString(ctx, "character"))))))
                        .then(Commands.literal("corps")
                                .then(Commands.argument("corps", StringArgumentType.word())
                                        .then(Commands.argument("character", StringArgumentType.word())
                                                .executes(ctx -> appointCorpsCommander(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "corps"),
                                                        StringArgumentType.getString(ctx, "character")))))))
                .then(Commands.literal("list").executes(c -> {
                    try {
                        String json = SQUADS.viewJson(c.getSource().getPlayerOrException().getUUID().toString());
                        c.getSource().sendSuccess(() -> Component.literal(json), false);
                    } catch (Exception ex) {
                        c.getSource().sendFailure(Component.literal("Только для игрока"));
                    }
                    return 1;
                }))
                .then(Commands.literal("disband").executes(c -> disband(c.getSource()))));
    }

    static int executeMilitaryCommand(CommandSourceStack source, String actionId, String target, String payload,
                                       java.util.function.IntSupplier action) {
        String actor = source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : source.getTextName();
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "military:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + source.getServer().getTickCount() + ":" + actionId + ":" + target + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, actionId, actor, target, List.of(payload == null ? "" : payload), minute);
        try {
            var task = WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (source.getEntity() instanceof ServerPlayer player
                                && source.getServer().getPlayerList().getPlayer(player.getUUID()) == null)
                            return "Игрок отключился до исполнения приказа";
                        if (actionPersistenceBlocked)
                            return "Военные приказы заблокированы после ошибки сохранения; требуется перезапуск и сверка";
                        boolean operatorRequired = actionId.equals("military.muster")
                                || actionId.equals("military.return_to_army") || actionId.startsWith("military.fort_");
                        if (operatorRequired && !source.hasPermission(2))
                            return "Для этого военного приказа нужны права оператора";
                        return null;
                    },
                    (req, attempt) -> {
                        int outcome;
                        try { outcome = action.getAsInt(); }
                        catch (RuntimeException ex) {
                            actionPersistenceBlocked = true;
                            return dev.worldforge.mod.api.ActionResult.fail(
                                    "Военный приказ завершился исключением; дальнейшие приказы заблокированы до сверки: " + ex.getMessage());
                        }
                        if (outcome <= 0)
                            return dev.worldforge.mod.api.ActionResult.fail("Военный приказ отклонён");
                        if (!saveDurably()) {
                            actionPersistenceBlocked = true;
                            return dev.worldforge.mod.api.ActionResult.fail(
                                    "Военный приказ применён в памяти, но военные снимки не сохранены; дальнейшие приказы заблокированы до сверки");
                        }
                        return dev.worldforge.mod.api.ActionResult.ok("Военный приказ выполнен");
                    },
                    null, minute, WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                source.sendFailure(Component.literal(task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось выполнить военный приказ: " + ex.getMessage()));
            return 0;
        }
    }

    private static int disband(CommandSourceStack source) {
        String actor = source.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "console";
        return executeMilitaryCommand(source, "military.disband", actor, "disband", () -> {
            try {
                ServerPlayer player = source.getPlayerOrException();
                int n = 0;
                for (Squads.Squad squad : new ArrayList<>(SQUADS.of(player.getUUID().toString()))) {
                    for (String id : new ArrayList<>(squad.members)) {
                        TroopEntity troop = LIVE.get(id);
                        if (troop != null) { releaseBody(troop); troop.discard(); n++; }
                        SQUADS.remove(id);
                    }
                }
                if (n == 0) {
                    source.sendFailure(Component.literal("У вас нет солдат для расформирования."));
                    return 0;
                }
                int count = n;
                source.sendSuccess(() -> Component.literal("Распущено солдат: " + count), false);
                return count;
            } catch (Exception ex) {
                source.sendFailure(Component.literal("Только для игрока"));
                return 0;
            }
        });
    }

    private static int createCorps(CommandSourceStack src, int armyId, String name) {
        return executeMilitaryCommand(src, "military.create_corps", Integer.toString(armyId), name, () -> createCorpsDirect(src, armyId, name));
    }

    private static int createCorpsDirect(CommandSourceStack src, int armyId, String name) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        syncArmyHierarchy();
        ArmyHierarchy.ArmyNode army = HIERARCHY.army(armyId);
        if (army == null || !army.owner.equals(player.getUUID().toString())) {
            src.sendFailure(Component.literal("Армия не найдена или принадлежит другому игроку"));
            return 0;
        }
        ArmyHierarchy.Corps corps = HIERARCHY.createCorps(armyId, name);
        save();
        src.sendSuccess(() -> Component.literal("Создан корпус «" + corps.name + "» в составе " + army.name), false);
        return 1;
    }

    private static int assignSquadToCorps(CommandSourceStack src, String corpsId, String squadId) {
        return executeMilitaryCommand(src, "military.assign_squad_to_corps", corpsId, squadId, () -> assignSquadToCorpsDirect(src, corpsId, squadId));
    }

    private static int assignSquadToCorpsDirect(CommandSourceStack src, String corpsId, String squadId) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        ArmyHierarchy.Corps corps = HIERARCHY.corps(corpsId);
        Squads.Squad squad = SQUADS.get(squadId);
        ArmyHierarchy.ArmyNode army = corps == null ? null : HIERARCHY.army(corps.armyId);
        if (corps == null || squad == null || army == null
                || !army.owner.equals(player.getUUID().toString()) || !squad.owner.equals(army.owner)
                || !HIERARCHY.assignSquad(corpsId, squadId, SQUADS)) {
            src.sendFailure(Component.literal("Корпус или отряд не найден, либо нет прав на управление"));
            return 0;
        }
        save();
        src.sendSuccess(() -> Component.literal("Отряд «" + squad.name + "» назначен в корпус «" + corps.name + "»"), false);
        return 1;
    }

    private static Squads.Order parseArmyOrder(String kind, double x, double z) {
        try {
            Squads.Kind value = Squads.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
            if (value == Squads.Kind.ATTACK || value == Squads.Kind.FOLLOW || value == Squads.Kind.GUARD)
                throw new IllegalArgumentException("Этот приказ требует явную цель; используйте RTS-приказ по выбранной цели");
            return value == Squads.Kind.STOP || value == Squads.Kind.HOLD
                    || value == Squads.Kind.RETREAT ? Squads.Order.of(value)
                    : new Squads.Order(value, x, z, "");
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Неизвестный приказ: " + kind);
        }
    }

    private static int orderArmy(CommandSourceStack src, int armyId, String kind, double x, double z, boolean queue) {
        return executeMilitaryCommand(src, "military.order_army", Integer.toString(armyId), kind + "|" + x + "|" + z + "|" + queue, () -> orderArmyDirect(src, armyId, kind, x, z, queue));
    }

    private static int orderArmyDirect(CommandSourceStack src, int armyId, String kind, double x, double z, boolean queue) {
        syncArmyHierarchy();
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        ArmyHierarchy.ArmyNode army = HIERARCHY.army(armyId);
        if (army == null || !army.owner.equals(player.getUUID().toString())) {
            src.sendFailure(Component.literal("Армия не найдена или принадлежит другому игроку")); return 0;
        }
        try {
            int count = HIERARCHY.commandArmy(armyId, SQUADS, parseArmyOrder(kind, x, z), queue);
            save();
            int issued = count;
            src.sendSuccess(() -> Component.literal("Приказ передан корпусам и отрядам: " + issued), false);
            return issued;
        } catch (RuntimeException ex) {
            src.sendFailure(Component.literal(ex.getMessage())); return 0;
        }
    }

    private static int orderCorps(CommandSourceStack src, String corpsId, String kind, double x, double z, boolean queue) {
        return executeMilitaryCommand(src, "military.order_corps", corpsId, kind + "|" + x + "|" + z + "|" + queue, () -> orderCorpsDirect(src, corpsId, kind, x, z, queue));
    }

    private static int orderCorpsDirect(CommandSourceStack src, String corpsId, String kind, double x, double z, boolean queue) {
        syncArmyHierarchy();
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        ArmyHierarchy.Corps corps = HIERARCHY.corps(corpsId);
        ArmyHierarchy.ArmyNode army = corps == null ? null : HIERARCHY.army(corps.armyId);
        if (corps == null || army == null || !army.owner.equals(player.getUUID().toString())) {
            src.sendFailure(Component.literal("Корпус не найден или принадлежит другому игроку")); return 0;
        }
        try {
            int count = HIERARCHY.commandCorps(corpsId, SQUADS, parseArmyOrder(kind, x, z), queue);
            save();
            int issued = count;
            src.sendSuccess(() -> Component.literal("Приказ передан отрядам корпуса: " + issued), false);
            return issued;
        } catch (RuntimeException ex) {
            src.sendFailure(Component.literal(ex.getMessage())); return 0;
        }
    }

    private static int showHierarchy(CommandSourceStack src, int armyId) {
        syncArmyHierarchy();
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        ArmyHierarchy.ArmyNode army = HIERARCHY.army(armyId);
        if (army == null || !army.owner.equals(player.getUUID().toString())) {
            src.sendFailure(Component.literal("Армия не найдена или принадлежит другому игроку")); return 0;
        }
        src.sendSuccess(() -> Component.literal(HIERARCHY.viewJson(armyId)), false);
        return 1;
    }

    private static Character findArmyCharacter(ArmyHierarchy.ArmyNode army, String characterId) {
        if (!ModList.get().isLoaded("wf_people") || army == null) return null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null || !army.realm.isBlank() && !map.titles.realms.containsKey(army.realm)) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            Character candidate = resolvePerson(registry, characterId);
            if (candidate != null && candidate.alive) return candidate;
        }
        return null;
    }

    private static int appointArmyCommander(CommandSourceStack src, int armyId, String characterId) {
        return executeMilitaryCommand(src, "military.appoint_army_commander", Integer.toString(armyId), characterId, () -> appointArmyCommanderDirect(src, armyId, characterId));
    }

    private static int appointArmyCommanderDirect(CommandSourceStack src, int armyId, String characterId) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        syncArmyHierarchy();
        ArmyHierarchy.ArmyNode army = HIERARCHY.army(armyId);
        Character character = findArmyCharacter(army, characterId);
        if (army == null || !army.owner.equals(player.getUUID().toString()) || character == null
                || !HIERARCHY.appointArmyCommander(armyId, characterId)) {
            src.sendFailure(Component.literal("Живой Character из государства армии не найден или нет прав")); return 0;
        }
        ArmyLink.Found strategic = ArmyLink.find(armyId);
        if (strategic != null) strategic.army().commander = characterId;
        save();
        src.sendSuccess(() -> Component.literal("Командующий армией «" + army.name + "»: " + character.name), false);
        return 1;
    }

    private static int appointCorpsCommander(CommandSourceStack src, String corpsId, String characterId) {
        return executeMilitaryCommand(src, "military.appoint_corps_commander", corpsId, characterId, () -> appointCorpsCommanderDirect(src, corpsId, characterId));
    }

    private static int appointCorpsCommanderDirect(CommandSourceStack src, String corpsId, String characterId) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        syncArmyHierarchy();
        ArmyHierarchy.Corps corps = HIERARCHY.corps(corpsId);
        ArmyHierarchy.ArmyNode army = corps == null ? null : HIERARCHY.army(corps.armyId);
        Character character = findArmyCharacter(army, characterId);
        if (corps == null || army == null || !army.owner.equals(player.getUUID().toString())
                || character == null || !HIERARCHY.appointCorpsCommander(corpsId, characterId)) {
            src.sendFailure(Component.literal("Живой Character из государства армии не найден или нет прав")); return 0;
        }
        save();
        src.sendSuccess(() -> Component.literal("Командир корпуса «" + corps.name + "»: " + character.name), false);
        return 1;
    }

    /** Appoints a living canonical wf-people Character who is physically present in the squad. */
    private static int appointCommander(CommandSourceStack src, String squadId, String characterId, boolean hero, int rank) {
        return executeMilitaryCommand(src, "military.appoint_squad_commander", squadId, characterId + "|" + hero + "|" + rank, () -> appointCommanderDirect(src, squadId, characterId, hero, rank));
    }

    private static int appointCommanderDirect(CommandSourceStack src, String squadId, String characterId, boolean hero, int rank) {
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        Squads.Squad squad = SQUADS.get(squadId);
        if (squad == null || !squad.owner.equals(player.getUUID().toString())) {
            src.sendFailure(Component.literal("Отряд не найден или принадлежит другому игроку"));
            return 0;
        }
        if (!ModList.get().isLoaded("wf_people")) {
            src.sendFailure(Component.literal("Для назначения командира требуется wf_people"));
            return 0;
        }
        if (squad.realm.isEmpty()) {
            src.sendFailure(Component.literal("У отряда не определено государство/регион для разрешения Character ID"));
            return 0;
        }
        boolean member = false;
        for (String unitId : squad.members) {
            TroopEntity troop = LIVE.get(unitId);
            if (troop != null && characterId.equals(troop.characterId)) { member = true; break; }
        }
        if (!member) {
            src.sendFailure(Component.literal("Персонаж должен состоять в этом отряде"));
            return 0;
        }
        Character person = null;
        for (MapData map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            if (!squad.realm.isEmpty() && !map.titles.realms.containsKey(squad.realm)) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry == null) continue;
            Character candidate = resolvePerson(registry, characterId);
            if (candidate != null) { person = candidate; break; }
        }
        if (person == null || !person.alive) {
            src.sendFailure(Component.literal("Живой персонаж с таким Character ID не найден"));
            return 0;
        }
        if (hero && !PopulationSimulation.hasDuty(person, Duty.HERO)) {
            src.sendFailure(Component.literal("Герой должен иметь роль HERO в общей симуляции населения"));
            return 0;
        }
        if (!SQUADS.appointLeader(squadId, characterId, hero, rank)) {
            src.sendFailure(Component.literal("Не удалось назначить командира"));
            return 0;
        }
        Character appointed = person;
        src.sendSuccess(() -> Component.literal("Командир отряда «" + squad.name + "»: " + appointed.name
                + (hero ? " (герой)" : "") + ", навык лидерства " + rank), false);
        save();
        return 1;
    }

    /** Наём только из завершённого и укомплектованного профильного здания; списывает снабжение поселения и золото игрока/казны. */
    private static int raise(CommandSourceStack src, String type, int n, String realm) {
        return executeMilitaryCommand(src, "military.raise", type, n + "|" + realm, () -> raiseDirect(src, type, n, realm));
    }

    private static int raiseDirect(CommandSourceStack src, String type, int n, String realm) {
        ServerPlayer p;
        try { p = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }

        TroopDefs.Unit unit = DEFS.units.get(type);
        if (unit == null) { src.sendFailure(Component.literal("Нет такого типа: " + type)); return 0; }
        if (!ModList.get().isLoaded("wf_settle")) {
            src.sendFailure(Component.literal("Для найма требуется wf_settle и построенное военное здание."));
            return 0;
        }

        String owner = p.getUUID().toString();
        ServerLevel level = p.serverLevel();
        String requiredBuilding = DEFS.recruitmentBuilding(unit);
        dev.worldforge.settle.mc.SettleMod.RecruitmentSite site =
                dev.worldforge.settle.mc.SettleMod.recruitmentSite(level, owner, requiredBuilding, p.getX(), p.getZ());
        if (site == null) {
            src.sendFailure(Component.literal("Нет доступного готового здания «" + requiredBuilding
                    + "» с назначенным военным инструктором в радиусе 128 блоков."));
            return 0;
        }

        Map<String, Integer> totalSupply = new LinkedHashMap<>();
        for (var entry : DEFS.recruitmentCost(unit).entrySet()) {
            long total = (long) entry.getValue() * n;
            if (total > Integer.MAX_VALUE) { src.sendFailure(Component.literal("Слишком большой заказ")); return 0; }
            totalSupply.put(entry.getKey(), (int) total);
        }
        if (!site.colony().s.canPay(totalSupply)) {
            src.sendFailure(Component.literal("В поселении «" + site.colony().s.name
                    + "» не хватает материалов и припасов для найма: " + totalSupply));
            return 0;
        }

        int goldCost = Math.multiplyExact(unit.cost(), n);
        if (realm.isEmpty() && !p.isCreative()) {
            int have = 0;
            for (var stack : p.getInventory().items) if (stack.is(Items.GOLD_NUGGET)) have += stack.getCount();
            if (have < goldCost) {
                src.sendFailure(Component.literal("Нужно " + goldCost + " золотых самородков, есть " + have));
                return 0;
            }
        }

        List<Formations.Slot> slots = Formations.slots(Formations.Type.BLOCK, n, 1.6);
        List<double[]> positions = Formations.place(slots, site.spawn().getX() + 0.5, site.spawn().getZ() + 0.5, p.getYRot() + 180);
        List<TroopEntity> recruits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            TroopEntity troop = TROOP.get().create(level);
            if (troop == null) {
                src.sendFailure(Component.literal("Не удалось подготовить всех новобранцев; ресурсы не списаны."));
                return 0;
            }
            int x = (int) Math.floor(positions.get(i)[0]), z = (int) Math.floor(positions.get(i)[1]);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            troop.moveTo(positions.get(i)[0], y, positions.get(i)[1], p.getYRot(), 0);
            troop.setup(unit, owner);
            troop.realm = realm.isEmpty() ? dev.worldforge.settle.mc.SettleMod.realmOf(site.colony()) : realm;
            troop.deferCharacterBinding = true;
            recruits.add(troop);
        }

        List<String> ids = new ArrayList<>();
        for (TroopEntity troop : recruits) ids.add(troop.getUUID().toString());
        Squads.Squad squad = SQUADS.create(owner, null, ids);
        squad.realm = recruits.get(0).realm;
        squad.cx = site.spawn().getX();
        squad.cz = site.spawn().getZ();
        for (TroopEntity troop : recruits) {
            troop.setSquadId(squad.id);
            if (!level.addFreshEntity(troop)) {
                discardPrepared(recruits);
                src.sendFailure(Component.literal("Мир отклонил создание новобранцев; платежи не списаны."));
                return 0;
            }
        }
        if (!dev.worldforge.settle.mc.SettleMod.payRecruitment(site, totalSupply)) {
            discardPrepared(recruits);
            src.sendFailure(Component.literal("Склад изменился во время найма; отряд удалён, ресурсы не списаны."));
            return 0;
        }
        if (!realm.isEmpty() && !TreasuryApi.spend(realm, goldCost, "Наём: " + unit.name())) {
            dev.worldforge.settle.mc.SettleMod.refundRecruitment(site, totalSupply);
            discardPrepared(recruits);
            src.sendFailure(Component.literal("В казне " + realm + " не хватает золота: нужно " + goldCost));
            return 0;
        }
        if (realm.isEmpty() && !p.isCreative()) {
            int left = goldCost;
            for (var stack : p.getInventory().items) if (left > 0 && stack.is(Items.GOLD_NUGGET)) {
                int take = Math.min(left, stack.getCount());
                stack.shrink(take);
                left -= take;
            }
        }
        for (TroopEntity troop : recruits) { troop.deferCharacterBinding = false; syncPerson(troop); }
        src.sendSuccess(() -> Component.literal("Из «" + site.building().def.name + "» выпущен отряд «"
                + squad.name + "»: " + recruits.size() + " × " + unit.name() + "; золото "
                + goldCost + ", снабжение " + totalSupply), false);
        return recruits.size();
    }

    /** Призыв из армии wf-army: люди снимаются с армии, 1 солдат = Muster.SCALE человек; государство армии платит жалованье. */
    private static int muster(CommandSourceStack src, int armyId, int n) {
        return executeMilitaryCommand(src, "military.muster", Integer.toString(armyId), Integer.toString(n), () -> musterDirect(src, armyId, n));
    }

    private static int musterDirect(CommandSourceStack src, int armyId, int n) {
        if (!ModList.get().isLoaded("wf_army") || !ModList.get().isLoaded("wf_settle")) {
            src.sendFailure(Component.literal("Для призыва нужны wf_army и wf_settle."));
            return 0;
        }
        ServerPlayer player;
        try { player = src.getPlayerOrException(); }
        catch (Exception ex) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
        ArmyLink.Found found = ArmyLink.find(armyId);
        if (found == null) { src.sendFailure(Component.literal("Нет армии " + armyId)); return 0; }

        Muster.Plan plan = Muster.plan(found.army().units, n, DEFS.armyTypes());
        if (plan.total() == 0) {
            src.sendFailure(Component.literal("В армии недостаточно людей для призыва; на одного солдата нужно "
                    + Muster.SCALE + " человек."));
            return 0;
        }

        String owner = player.getUUID().toString();
        ServerLevel level = player.serverLevel();
        Map<String, dev.worldforge.settle.mc.SettleMod.RecruitmentSite> sites = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> supplies = new LinkedHashMap<>();
        Map<String, List<TroopEntity>> batches = new LinkedHashMap<>();

        for (var entry : plan.soldiers().entrySet()) {
            TroopDefs.Unit unit = DEFS.units.get(entry.getKey());
            if (unit == null) {
                src.sendFailure(Component.literal("В army_map указан неизвестный тип войска: " + entry.getKey()));
                return 0;
            }
            String buildingId = DEFS.recruitmentBuilding(unit);
            var site = sites.computeIfAbsent(buildingId, id ->
                    dev.worldforge.settle.mc.SettleMod.recruitmentSite(level, owner, id, player.getX(), player.getZ()));
            if (site == null) {
                src.sendFailure(Component.literal("Для призыва «" + unit.name() + "» требуется готовое здание «"
                        + buildingId + "» с инструктором в радиусе 128 блоков."));
                return 0;
            }
            Map<String, Integer> required = supplies.computeIfAbsent(buildingId, id -> new LinkedHashMap<>());
            for (var resource : DEFS.recruitmentCost(unit).entrySet()) {
                long amount = (long) resource.getValue() * entry.getValue();
                if (amount > Integer.MAX_VALUE) { src.sendFailure(Component.literal("Слишком большой заказ")); return 0; }
                required.merge(resource.getKey(), (int) amount, Integer::sum);
            }
            List<TroopEntity> batch = batches.computeIfAbsent(buildingId, id -> new ArrayList<>());
            for (int i = 0; i < entry.getValue(); i++) {
                TroopEntity troop = TROOP.get().create(level);
                if (troop == null) {
                    src.sendFailure(Component.literal("Не удалось подготовить весь призыв; армия и склады не изменены."));
                    return 0;
                }
                troop.setup(unit, owner);
                troop.realm = found.army().realm;
                troop.armyId = armyId;
                troop.deferCharacterBinding = true;
                batch.add(troop);
            }
        }

        for (var entry : supplies.entrySet()) {
            var site = sites.get(entry.getKey());
            if (!site.colony().s.canPay(entry.getValue())) {
                src.sendFailure(Component.literal("В поселении «" + site.colony().s.name
                        + "» не хватает снабжения для " + entry.getKey() + ": " + entry.getValue()));
                return 0;
            }
        }

        // Prepare all physical soldiers before touching army manpower or settlement stock.
        List<TroopEntity> allPrepared = new ArrayList<>();
        for (List<TroopEntity> batch : batches.values()) allPrepared.addAll(batch);
        for (var entry : batches.entrySet()) {
            var site = sites.get(entry.getKey());
            List<TroopEntity> batch = entry.getValue();
            List<Formations.Slot> slots = Formations.slots(Formations.Type.BLOCK, batch.size(), 1.6);
            List<double[]> positions = Formations.place(slots, site.spawn().getX() + 0.5,
                    site.spawn().getZ() + 0.5, player.getYRot() + 180);
            for (int i = 0; i < batch.size(); i++) {
                TroopEntity troop = batch.get(i);
                int x = (int) Math.floor(positions.get(i)[0]), z = (int) Math.floor(positions.get(i)[1]);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                troop.moveTo(positions.get(i)[0], y, positions.get(i)[1], player.getYRot(), 0);
            }
        }

        int total = 0;
        for (var entry : batches.entrySet()) {
            var site = sites.get(entry.getKey());
            List<TroopEntity> batch = entry.getValue();
            List<String> ids = new ArrayList<>();
            for (TroopEntity troop : batch) ids.add(troop.getUUID().toString());
            Squads.Squad squad = SQUADS.create(owner, found.army().name, ids);
            squad.realm = found.army().realm;
            squad.cx = site.spawn().getX();
            squad.cz = site.spawn().getZ();
            for (TroopEntity troop : batch) {
                troop.setSquadId(squad.id);
                if (!level.addFreshEntity(troop)) {
                    discardPrepared(allPrepared);
                    src.sendFailure(Component.literal("Мир отклонил создание призванных солдат; армия и склады не изменены."));
                    return 0;
                }
            }
            total += batch.size();
        }

        ArmyLink.take(found.army(), plan.menTaken());
        List<String> paidBuildings = new ArrayList<>();
        for (var entry : supplies.entrySet()) {
            if (!dev.worldforge.settle.mc.SettleMod.payRecruitment(sites.get(entry.getKey()), entry.getValue())) {
                for (String paid : paidBuildings)
                    dev.worldforge.settle.mc.SettleMod.refundRecruitment(sites.get(paid), supplies.get(paid));
                ArmyLink.give(armyId, plan.menTaken());
                discardPrepared(allPrepared);
                src.sendFailure(Component.literal("Склад изменился во время призыва; люди возвращены в армию, отряд удалён."));
                return 0;
            }
            paidBuildings.add(entry.getKey());
        }

        for (TroopEntity troop : allPrepared) { troop.deferCharacterBinding = false; syncPerson(troop); }
        int recruited = total;
        src.sendSuccess(() -> Component.literal("Из военных зданий поселения призвано " + recruited
                + " солдат армии " + armyId + " (оставшиеся люди: " + found.army().men() + ")"), false);
        return recruited;
    }

    /** Выжившие призванные солдаты игрока возвращаются в свои армии; павшие потеряны. */
    private static int returnToArmy(CommandSourceStack src) {
        return executeMilitaryCommand(src, "military.return_to_army", src.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "console", "return", () -> returnToArmyDirect(src));
    }

    private static int returnToArmyDirect(CommandSourceStack src) {
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Только для игрока"));
            return 0;
        }
        if (!ModList.get().isLoaded("wf_army")) { src.sendFailure(Component.literal("Нужен мод wf_army")); return 0; }

        record ArmyReturnKey(int armyId, String realm) {}
        Map<ArmyReturnKey, List<TroopEntity>> byArmy = new TreeMap<>(
                Comparator.comparing(ArmyReturnKey::realm).thenComparingInt(ArmyReturnKey::armyId));
        for (TroopEntity troop : LIVE.values()) {
            if (troop.isAlive() && troop.armyId > 0 && troop.ownerId().equals(p.getUUID().toString())) {
                String realm = troop.realm == null ? "" : troop.realm;
                byArmy.computeIfAbsent(new ArmyReturnKey(troop.armyId, realm), ignored -> new ArrayList<>()).add(troop);
            }
        }

        // Commit every strategic roster credit before releasing any physical soldier.
        List<TroopEntity> stagedReturns = new ArrayList<>();
        for (Map.Entry<ArmyReturnKey, List<TroopEntity>> entry : byArmy.entrySet()) {
            ArmyReturnKey key = entry.getKey();
            ArmyLink.Found found = ArmyLink.find(key.armyId(), key.realm());
            if (found == null) continue; // missing/ambiguous map-local army: soldiers remain physical and playable
            try {
                if (!ArmyLink.returnToArmy(found, entry.getValue(), DEFS.armyTypes(), p.server)) {
                    actionPersistenceBlocked = true;
                    src.sendFailure(Component.literal("Не удалось надёжно сохранить численность армии; физические солдаты оставлены в мире"));
                    return 0;
                }
                stagedReturns.addAll(entry.getValue());
            } catch (RuntimeException ex) {
                actionPersistenceBlocked = true;
                LOGGER.error("wf_troops: strategic return failed for army {} in realm {}: {}",
                        key.armyId(), key.realm(), ex.toString(), ex);
                src.sendFailure(Component.literal("Возврат в армию остановлен; физические солдаты сохранены для сверки"));
                return 0;
            }
        }

        int returnedBodies = 0;
        int returnedMen = 0;
        for (TroopEntity troop : stagedReturns) {
            if (!releaseBodyDurably(troop)) {
                actionPersistenceBlocked = true;
                src.sendFailure(Component.literal("Не удалось сохранить жизненный статус персонажа; его физическое тело оставлено в мире"));
                continue;
            }
            troop.discard();
            returnedBodies++;
            Map<String, Integer> one = Muster.back(Map.of(troop.unitId(), 1), DEFS.armyTypes());
            returnedMen += one.values().stream().mapToInt(Integer::intValue).sum();
        }

        int men = returnedMen;
        int bodies = returnedBodies;
        src.sendSuccess(() -> Component.literal("Возвращено в стратегические армии: " + men
                + " человек · физических солдат: " + bodies), false);
        return returnedBodies;
    }

    /** Removes a physical troop body without killing the underlying person (return, rollback or disband). */
    private static void releaseBody(TroopEntity troop) {
        PersonBinding binding = bindPerson(troop, false);
        if (binding != null) {
            Character person = binding.character();
            binding.registry().unbindEntity(person.id, "troop", troop.getUUID().toString());
            person.props.remove("wf_troop_uuid");
            person.props.remove("wf_troop_unit");
            person.props.remove("wf_army_id");
            person.props.remove("wf_combat_xp");
            person.props.remove("wf_veteran_rank");
        }
        String id = troop.getUUID().toString();
        LIVE.remove(id);
        SQUADS.remove(id);
    }

    /** Unbinds and persists the canonical Character before its physical troop body is discarded. */
    private static boolean releaseBodyDurably(TroopEntity troop) {
        if (troop == null) return false;
        if (ModList.get().isLoaded("wf_people")) {
            PersonBinding binding = bindPerson(troop, false);
            if (binding == null) return false;
            Character person = binding.character();
            binding.registry().unbindEntity(person.id, "troop", troop.getUUID().toString());
            person.props.remove("wf_troop_uuid");
            person.props.remove("wf_troop_unit");
            person.props.remove("wf_army_id");
            person.props.remove("wf_combat_xp");
            person.props.remove("wf_veteran_rank");
            if (server == null || !PeopleMod.persistCharacter(person, server)) return false;
        }
        String id = troop.getUUID().toString();
        LIVE.remove(id);
        SQUADS.remove(id);
        return true;
    }

    private static void discardPrepared(List<TroopEntity> troops) {
        for (TroopEntity troop : troops) {
            releaseBody(troop);
            troop.discard();
        }
    }

    static Entity find(UUID id) {
        if (server == null) return null;
        for (ServerLevel lv : server.getAllLevels()) { Entity e = lv.getEntity(id); if (e != null) return e; }
        return null;
    }
}
