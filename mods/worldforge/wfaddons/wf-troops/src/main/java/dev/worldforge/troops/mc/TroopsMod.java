package dev.worldforge.troops.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.api.TreasuryApi;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.PopulationSimulation;
import dev.worldforge.people.PopulationSimulation.Duty;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Forts;
import dev.worldforge.troops.Muster;
import dev.worldforge.troops.Wars;
import dev.worldforge.troops.Squads;
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
    private static final String PROTO = "1";
    public static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(new ResourceLocation(MOD_ID, "main"), () -> PROTO,
            NetworkRegistry.acceptMissingOr(PROTO), NetworkRegistry.acceptMissingOr(PROTO));

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<EntityType<TroopEntity>> TROOP = ENTITIES.register("troop",
            () -> EntityType.Builder.<TroopEntity>of(TroopEntity::new, MobCategory.MISC).sized(0.6f, 1.95f).clientTrackingRange(12).build(MOD_ID + ":troop"));

    public static volatile TroopDefs DEFS = load(null);
    public static final Squads SQUADS = new Squads();
    public static final Forts FORTS = new Forts();
    public static final Map<String, TroopEntity> LIVE = new ConcurrentHashMap<>();
    private static MinecraftServer server;
    private static int ticks;

    public TroopsMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(TROOP.get(), TroopEntity.attributes().build()));
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                NET.registerMessage(0, OrderPacket.class, OrderPacket::encode, OrderPacket::decode, OrderPacket::handle));
        MinecraftForge.EVENT_BUS.register(this);
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
        server = e.getServer();
        DEFS = load(dir(server).resolve("wf_troops_units.json"));
        try {
            Path f = dir(server).resolve("wf_squads.json");
            if (Files.exists(f)) SQUADS.fromJson(Files.readString(f));
            Path ff = dir(server).resolve("wf_forts.json");
            if (Files.exists(ff)) FORTS.fromJson(Files.readString(ff));
        } catch (Exception ex) {
            LOGGER.error("wf_squads.json: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent e) { save(); LIVE.clear(); server = null; }

    static void save() {
        if (server == null) return;
        try {
            Files.createDirectories(dir(server));
            Files.writeString(dir(server).resolve("wf_squads.json"), SQUADS.toJson());
            Files.writeString(dir(server).resolve("wf_forts.json"), FORTS.toJson());
        } catch (Exception ex) {
            LOGGER.error("save squads: {}", ex.toString());
        }
    }

    @SubscribeEvent
    public void join(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof TroopEntity t)) return;
        LIVE.put(t.getUUID().toString(), t);
        syncPerson(t);
        if (SQUADS.squadOf(t.getUUID().toString()) == null) {                       // солдат без отряда (отряд потерян): свой отряд
            Squads.Squad s = SQUADS.create(t.ownerId(), null, List.of(t.getUUID().toString()));
            s.realm = t.realm; s.cx = t.getX(); s.cz = t.getZ();
            t.setSquadId(s.id);
        } else t.setSquadId(SQUADS.squadOf(t.getUUID().toString()).id);
    }

    private record PersonBinding(CharacterRegistry registry, Character character) {}

    /** Resolves a physical soldier to the one authoritative Character in the map owning its realm. */
    private static PersonBinding bindPerson(TroopEntity troop, boolean create) {
        if (troop == null || !ModList.get().isLoaded("wf_people")) return null;
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
            Character person = troop.characterId == null || troop.characterId.isBlank() ? null : registry.get(troop.characterId);
            if (person == null || !person.alive) {
                if (!create) return null;
                String culture = realm.props == null || realm.props.get("culture") == null ? "" : String.valueOf(realm.props.get("culture"));
                String name = troop.def() == null ? troop.unitId() : troop.def().name();
                person = registry.create(name + " " + troop.getUUID().toString().substring(0, 4), "", culture, "m", PeopleMod.YEAR - 20);
                troop.characterId = person.id;
            }
            return new PersonBinding(registry, person);
        }
        return null;
    }

    /** Shared character identity, soldier duty, profession, combat XP and skill ranks. */
    static void syncPerson(TroopEntity troop) {
        PersonBinding binding = bindPerson(troop, true);
        if (binding == null) return;
        Character person = binding.character();
        String profession = troop.unitId() == null || troop.unitId().isBlank() ? "soldier" : troop.unitId();
        if (!profession.equals(PopulationSimulation.profession(person)))
            PopulationSimulation.assignProfession(person, profession, PeopleMod.YEAR);
        PopulationSimulation.addDuty(person, Duty.SOLDIER);
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
        PopulationSimulation.updateIntent(person, PeopleMod.YEAR);
    }

    @SubscribeEvent
    public void leave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof TroopEntity t)) return;
        LIVE.remove(t.getUUID().toString());
    }

    static void onDeath(TroopEntity t) {
        PersonBinding binding = bindPerson(t, false);
        if (binding != null && binding.character().alive) {
            try { PopulationSimulation.die(binding.registry(), binding.character().id, PeopleMod.YEAR, "combat"); }
            catch (RuntimeException ex) { LOGGER.error("wf_troops: failed to record soldier death {}: {}", t.characterId, ex.toString()); }
        }
        String id = t.getUUID().toString();
        LIVE.remove(id);
        SQUADS.remove(id);
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || server == null) return;
        ticks++;
        if (ticks % 10 == 0) TroopController.step(server);
        if (ticks % 1200 == 0) for (TroopEntity troop : LIVE.values()) syncPerson(troop);
        if (ticks % 100 == 0) payroll();
        if (ticks % 20 == 0) FortService.step(server, ticks);
        if (ticks % 6000 == 0) save();
    }

    // ---- жалованье ----
    private static final Random RNG = new Random();

    /** Раз в игровой день: государства платят солдатам из казны; не заплатили - четверть разбегается. */
    private static void payroll() {
        int days = dev.worldforge.mod.api.WorldForgeApi.clock().pull("wf_troops");
        if (days <= 0) return;
        Map<String, List<TroopEntity>> byRealm = new LinkedHashMap<>();
        for (TroopEntity t : LIVE.values()) if (t.isAlive() && !t.realm.isEmpty()) byRealm.computeIfAbsent(t.realm, k -> new ArrayList<>()).add(t);
        for (var e : byRealm.entrySet()) {
            Map<String, Integer> counts = new HashMap<>();
            for (TroopEntity t : e.getValue()) counts.merge(t.unitId(), 1, Integer::sum);
            double cost = Muster.upkeep(DEFS, counts) * days;
            if (cost <= 0 || TreasuryApi.spend(e.getKey(), cost, "Жалованье солдат")) continue;
            List<TroopEntity> list = e.getValue();
            for (int i : Muster.deserters(list.size(), RNG)) {
                TroopEntity t = list.get(i);
                onDeath(t);
                t.discard();
            }
        }
    }

    // ---- приказы от клиента ----
    static void handleOrder(ServerPlayer p, OrderPacket o) {
        String owner = p.getUUID().toString();
        switch (o.action()) {
            case OrderPacket.ORDER -> {
                Squads.Order ord = o.kind() == Squads.Kind.ATTACK.ordinal() ? new Squads.Order(Squads.Kind.ATTACK, 0, 0, o.target())
                        : new Squads.Order(Squads.Kind.values()[Math.max(0, Math.min(o.kind(), Squads.Kind.values().length - 1))], o.x(), o.z(), o.target());
                for (String id : troopIds(o)) {
                    Squads.Squad rs = SQUADS.squadOf(id);
                    if (rs != null && rs.owner.equals(owner) && rs.routed && ord.kind() != Squads.Kind.STOP) {
                        p.displayClientMessage(Component.literal("Отряд «" + rs.name + "» бежит и не слушает приказов"), true);
                        return;
                    }
                }
                Squads.Squad s = SQUADS.commandUnits(owner, troopIds(o), ord, o.queue());
                if (s != null) p.displayClientMessage(Component.literal(s.name + " (" + s.members.size() + "): " + ord.kind().title), true);
            }
            case OrderPacket.FORMATION, OrderPacket.STANCE -> {
                Set<Squads.Squad> hit = new LinkedHashSet<>();
                for (String id : troopIds(o)) { Squads.Squad s = SQUADS.squadOf(id); if (s != null && s.owner.equals(owner)) hit.add(s); }
                for (Squads.Squad s : hit) {
                    if (o.action() == OrderPacket.FORMATION) s.formation = Formations.Type.values()[Math.floorMod(o.arg(), Formations.Type.values().length)];
                    else s.stance = Squads.Stance.values()[Math.floorMod(o.arg(), Squads.Stance.values().length)];
                }
                if (!hit.isEmpty()) {
                    Squads.Squad f = hit.iterator().next();
                    p.displayClientMessage(Component.literal(o.action() == OrderPacket.FORMATION ? "Строй: " + f.formation.title : "Поза: " + f.stance.title), true);
                }
            }
            case OrderPacket.GROUP -> {
                List<String> ids = new ArrayList<>();
                for (String id : troopIds(o)) { Squads.Squad s = SQUADS.squadOf(id); if (s != null && s.owner.equals(owner)) ids.add(id); }
                if (!ids.isEmpty()) {
                    Squads.Squad n = SQUADS.create(owner, null, ids);
                    for (String id : ids) { TroopEntity t = LIVE.get(id); if (t != null) t.setSquadId(n.id); }
                    p.displayClientMessage(Component.literal("Собран " + n.name + " (" + n.members.size() + ")"), true);
                }
            }
            case OrderPacket.WORK, OrderPacket.RELEASE, OrderPacket.WORK_STOP, OrderPacket.WORK_HOLD -> {
                if (!ModList.get().isLoaded("wf_settle") || !WorkerControl.anyWorker(p, o)) return;
                // WorkerControl проверяет владельца каждого поселения; глобальный операторский gate здесь неверен.
                p.displayClientMessage(Component.literal(WorkerControl.command(p, o)), true);
            }
            case OrderPacket.BUILD_LIST -> {
                if (!ModList.get().isLoaded("wf_settle")) { p.displayClientMessage(Component.literal("Нужен мод wf_settle"), true); return; }
                BuildControl.sendList(p, o.x(), o.z());
            }
            case OrderPacket.BUILD -> {
                if (!ModList.get().isLoaded("wf_settle")) return;
                // BuildControl validates the target colony owner and every assigned worker.
                p.displayClientMessage(Component.literal(BuildControl.place(p, o.target(), (int) Math.floor(o.x()), (int) Math.floor(o.z()), o.ids())), true);
            }
            default -> {}
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
                .then(Commands.literal("list").executes(c -> {
                    try {
                        String json = SQUADS.viewJson(c.getSource().getPlayerOrException().getUUID().toString());
                        c.getSource().sendSuccess(() -> Component.literal(json), false);
                    } catch (Exception ex) {
                        c.getSource().sendFailure(Component.literal("Только для игрока"));
                    }
                    return 1;
                }))
                .then(Commands.literal("disband").executes(c -> {
                    try {
                        ServerPlayer p = c.getSource().getPlayerOrException();
                        int n = 0;
                        for (Squads.Squad s : new ArrayList<>(SQUADS.of(p.getUUID().toString()))) {
                            for (String id : new ArrayList<>(s.members)) { TroopEntity t = LIVE.get(id); if (t != null) { t.discard(); n++; } SQUADS.remove(id); }
                        }
                        int cnt = n;
                        c.getSource().sendSuccess(() -> Component.literal("Распущено солдат: " + cnt), false);
                    } catch (Exception ex) {
                        c.getSource().sendFailure(Component.literal("Только для игрока"));
                    }
                    return 1;
                })));
    }

    /** Наём только из завершённого и укомплектованного профильного здания; списывает снабжение поселения и золото игрока/казны. */
    private static int raise(CommandSourceStack src, String type, int n, String realm) {
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
        src.sendSuccess(() -> Component.literal("Из «" + site.building().def.name + "» выпущен отряд «"
                + squad.name + "»: " + recruits.size() + " × " + unit.name() + "; золото "
                + goldCost + ", снабжение " + totalSupply), false);
        return recruits.size();
    }

    /** Призыв из армии wf-army: люди снимаются с армии, 1 солдат = Muster.SCALE человек; государство армии платит жалованье. */
    private static int muster(CommandSourceStack src, int armyId, int n) {
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

        int recruited = total;
        src.sendSuccess(() -> Component.literal("Из военных зданий поселения призвано " + recruited
                + " солдат армии " + armyId + " (оставшиеся люди: " + found.army().men() + ")"), false);
        return recruited;
    }

    /** Выжившие призванные солдаты игрока возвращаются в свои армии; павшие потеряны. */
    private static int returnToArmy(CommandSourceStack src) {
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Только для игрока"));
            return 0;
        }
        if (!ModList.get().isLoaded("wf_army")) { src.sendFailure(Component.literal("Нужен мод wf_army")); return 0; }
        Map<Integer, Map<String, Integer>> byArmy = new HashMap<>();
        List<TroopEntity> gone = new ArrayList<>();
        for (TroopEntity t : LIVE.values()) if (t.isAlive() && t.armyId > 0 && t.ownerId().equals(p.getUUID().toString())) {
            byArmy.computeIfAbsent(t.armyId, k -> new HashMap<>()).merge(t.unitId(), 1, Integer::sum);
            gone.add(t);
        }
        int back = 0;
        for (var e : byArmy.entrySet()) {
            Map<String, Integer> men = Muster.back(e.getValue(), DEFS.armyTypes());
            if (ArmyLink.give(e.getKey(), men)) back += men.values().stream().mapToInt(Integer::intValue).sum();
            else gone.removeIf(t -> t.armyId == e.getKey());                                    // армии больше нет: солдаты остаются
        }
        for (TroopEntity t : gone) { onDeath(t); t.discard(); }
        int men = back;
        src.sendSuccess(() -> Component.literal("Вернулось в армии людей: " + men), false);
        return gone.size();
    }

    private static void discardPrepared(List<TroopEntity> troops) {
        for (TroopEntity troop : troops) {
            onDeath(troop);
            troop.discard();
        }
    }

    static Entity find(UUID id) {
        if (server == null) return null;
        for (ServerLevel lv : server.getAllLevels()) { Entity e = lv.getEntity(id); if (e != null) return e; }
        return null;
    }
}
