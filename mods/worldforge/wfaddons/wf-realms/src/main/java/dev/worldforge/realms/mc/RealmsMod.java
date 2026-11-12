package dev.worldforge.realms.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.AtomicFileBatch;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.DemoApi;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.realms.RealmDefs;
import dev.worldforge.realms.Realms;
import dev.worldforge.realms.Statecraft;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Склейка wf-realms: формы власти, уровни, доктрины, законы из датапаков (wf_realms). Состояние в мире, ежедневное накопление очков развития по игровым часам,
 * государства без игрока развиваются сами (ИИ). Состав рас и местностей - из общей шины демографии (свойства государства races/terrain: «human:60,elf:30»).
 * Команды: /wfrealm [info|gov|unlock|law|ai]. Менять строй государства может его правитель-игрок или оператор.
 */
@Mod("wf_realms")
public class RealmsMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final RealmDefs DEFS = new RealmDefs();
    public static final Realms REALMS = new Realms(DEFS);
    /** Persistent political state; Character IDs always resolve through wf-people. */
    public static final Statecraft POLITICS = new Statecraft();
    /** Runtime realm projections reconstructed from the persisted map-first emergence state. */
    private static final Map<String, TitleData.Realm> AUTONOMOUS_REALMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static long ticks;
    private static final Random RNG = new Random();

    public RealmsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            REALMS.publish();
            DemoApi.registerTerrain("realms", id -> { TitleData.Realm r = realm(id); return r == null ? Map.of() : DemoApi.shares(DemoApi.parse(r.props.get("terrain"))); });
            DemoApi.registerRaces("realm_props", id -> { TitleData.Realm r = realm(id); return r == null ? Map.of() : DemoApi.shares(DemoApi.parse(r.props.get("races"))); });
        }));
        MinecraftForge.EVENT_BUS.register(this);
        WorldForgeApi.addGuard((action, context) -> {
            if (!"war_declare".equals(action.id) || context.realmId == null || context.args.isEmpty()) return null;
            String defender = context.args.get(0);
            return POLITICS.hasTreaty(context.realmId, defender, Statecraft.Treaty.NON_AGGRESSION)
                    || POLITICS.hasTreaty(context.realmId, defender, Statecraft.Treaty.ALLIANCE)
                    ? "Дипломатический договор запрещает объявлять войну" : null;
        });
        if (WorldForgeApi.action("vassal_demands") == null) {
            WorldForgeApi.registerAction(new dev.worldforge.mod.api.MapAction(
                    "vassal_demands", "Уступить требованиям вассалов",
                    dev.worldforge.mod.api.MapAction.Scope.REALM, dev.worldforge.mod.api.MapAction.Perm.OP,
                    List.of("vassal_realm_ids"), "Пересматривает феодальные обязательства всех участников фракции.",
                    context -> {
                        if (context.realmId == null || context.args.isEmpty())
                            return dev.worldforge.mod.api.ActionResult.fail("Не указаны сюзерен или участники фракции");
                        List<String> members = Arrays.stream(context.args.get(0).split(","))
                                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
                        if (!POLITICS.concedeVassalDemands(context.realmId, members))
                            return dev.worldforge.mod.api.ActionResult.fail(POLITICS.lastError);
                        syncPoliticalMapState();
                        return dev.worldforge.mod.api.ActionResult.ok("Требования вассалов приняты");
                    }));
        }
        REALMS.hooks = new Realms.Hooks() {
            @Override public int provinces(String realm) {
                TitleData.Realm r = realm(realm);
                if (r == null) return 1;
                Object emergent = r.props.get("emergence.settlement_count");
                int emergentSettlements = emergent instanceof Number n ? n.intValue() : 0;
                return Math.max(1, Math.max(r.titleKeys.size(), emergentSettlements));
            }
            @Override public int prestige(String realm) { TitleData.Realm r = realm(realm); return r != null && r.props.get("prestige") instanceof Number n ? n.intValue() : 0; }
            @Override public boolean flag(String realm, String flag) { TitleData.Realm r = realm(realm); return r != null && Boolean.parseBoolean(String.valueOf(r.props.get("flag." + flag))); }
            @Override public Map<String, Double> policySignals(String realmId) {
                TitleData.Realm current = realm(realmId);
                if (current == null) return Map.of();
                Map<String, Double> signals = new LinkedHashMap<>();

                Object warState = current.props.get("war.state");
                String war = warState == null ? "" : String.valueOf(warState);
                signals.put("war", !war.isBlank() && !war.equalsIgnoreCase("peace") ? 1.0 : 0.0);

                Statecraft.Treasury treasury = POLITICS.treasury(realmId);
                double debt = POLITICS.realmDebt(realmId);
                double balance = Math.max(0, treasury.balance);
                double fiscalStress = debt <= 0 ? 0 : debt / (debt + balance + 1.0);
                if (POLITICS.realmBankrupt(realmId)) fiscalStress = 1.0;
                signals.put("fiscal_stress", Math.max(0, Math.min(1, fiscalStress)));

                List<Statecraft.VassalContract> vassals = new ArrayList<>(POLITICS.vassalsOf(realmId));
                double averageLoyalty = vassals.stream().mapToInt(v -> v.loyalty).average().orElse(100.0);
                signals.put("vassal_discontent", Math.max(0, Math.min(1, 1.0 - averageLoyalty / 100.0)));

                Map<String, Double> terrain = REALMS.hooks.terrain(realmId);
                signals.put("trade_access", Math.max(0, Math.min(1, terrain.getOrDefault("coast", 0.0))));

                Map<String, Double> races = REALMS.hooks.races(realmId);
                double totalPopulationShare = races.values().stream().filter(v -> v != null && Double.isFinite(v))
                        .mapToDouble(v -> Math.max(0, v)).sum();
                double dominantRaceShare = totalPopulationShare <= 0 ? 0 : races.values().stream()
                        .filter(v -> v != null && Double.isFinite(v)).mapToDouble(v -> Math.max(0, v)).max().orElse(0)
                        / totalPopulationShare;
                signals.put("cultural_diversity", totalPopulationShare <= 0 ? 0
                        : Math.max(0, Math.min(1, 1.0 - dominantRaceShare)));

                Object unrestValue = current.props.get("politics.unrest");
                double unrest = unrestValue instanceof Number n ? n.doubleValue() / 100.0 : 0.0;
                signals.put("unrest", Math.max(0, Math.min(1, unrest)));
                signals.put("faction_pressure", normalizedProp(current, "politics.faction_pressure"));
                signals.put("court_intrigue", normalizedProp(current, "politics.intrigue_pressure"));
                Object ties = current.props.get("politics.dynastic_ties");
                double tieCount = ties instanceof Number n ? n.doubleValue()
                        : current.props.get("people.ties") == null ? 0
                        : String.valueOf(current.props.get("people.ties")).isBlank() ? 0
                        : String.valueOf(current.props.get("people.ties")).split(",").length;
                signals.put("dynastic_ties", Math.max(0, Math.min(1, tieCount / 3.0)));
                Object councilAccept = current.props.get("council.ai_accept");
                double accept = councilAccept instanceof Number n ? (n.doubleValue() + 20.0) / 40.0 : 0.5;
                signals.put("council_ai_accept", Math.max(0, Math.min(1, accept)));

                // Ruler profile is published by CourtSim from the canonical Character Registry.
                // Missing values from legacy saves remain neutral instead of inventing traits.
                for (String attribute : List.of("diplomacy", "martial", "intrigue", "stewardship", "learning"))
                    signals.put("ruler_" + attribute, normalizedProp(current, "politics.ruler_" + attribute));
                for (String trait : List.of("greedy", "generous", "just", "cruel", "ambitious",
                        "content", "brave", "craven", "patient", "wroth", "scholar", "shrewd"))
                    signals.put("ruler_trait_" + trait,
                            normalizedProp(current, "politics.ruler_trait_" + trait));
                return signals;
            }
            @Override public int today() { return (int) WorldForgeApi.clock().dayNumber(); }
        };
    }

    /** All political actors, including polities that emerged without a pre-authored title overlay. */
    public static List<TitleData.Realm> realms() {
        List<TitleData.Realm> l = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var m : WorldForgeMod.loadedMaps().values()) if (m.titles != null)
            for (TitleData.Realm realm : m.titles.realmList()) if (seen.add(realm.id)) l.add(realm);
        for (TitleData.Realm realm : AUTONOMOUS_REALMS.values()) if (seen.add(realm.id)) l.add(realm);
        return l;
    }

    public static TitleData.Realm realm(String id) {
        for (TitleData.Realm r : realms()) if (r.id.equals(id)) return r;
        return null;
    }

    public static List<TitleData.Realm> emergentRealms() {
        return List.copyOf(AUTONOMOUS_REALMS.values());
    }

    /** Rebuilds a virtual realm projection without fabricating map provinces or title overlays. */
    public static TitleData.Realm registerEmergentRealm(String id, String name, String mapId,
            String rulerGlobalId, long population, int stability, int settlementCount,
            Collection<String> neighbors, String biomeSummary) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{3,96}"))
            throw new IllegalArgumentException("Invalid emergent realm id");
        TitleData.Realm realm = AUTONOMOUS_REALMS.computeIfAbsent(id, key -> new TitleData.Realm());
        realm.id = id;
        realm.name = name == null || name.isBlank() ? id : name;
        realm.ruler = rulerGlobalId == null ? "" : rulerGlobalId;
        realm.overlordId = null;
        realm.relation = "";
        realm.titleKeys.clear();
        realm.subjectIds.clear();
        realm.props.put("emergence.map_id", mapId == null ? "" : mapId);
        realm.props.put("emergence.settlement_count", Math.max(0, settlementCount));
        realm.props.put("emergence.neighbors", neighbors == null ? "" : String.join(",", new TreeSet<>(neighbors)));
        realm.props.put("emergence.biomes", biomeSummary == null ? "" : biomeSummary);
        realm.props.put("population", Math.max(0L, population));
        realm.props.put("stability", Math.max(0, Math.min(100, stability)));
        realm.props.put("prestige", (int) Math.min(100_000L, Math.max(0L, population / 10L)));
        realm.props.put("taxable_base", Math.max(0.0, population * 0.05));
        REALMS.state(id);
        POLITICS.treasury(id);
        return realm;
    }

    /** Remove runtime market actors whose polities have genuinely dissolved. Their historical records remain in Statecraft. */
    public static void reconcileEmergentRealms(Set<String> activeIds) {
        Set<String> keep = activeIds == null ? Set.of() : new HashSet<>(activeIds);
        AUTONOMOUS_REALMS.keySet().retainAll(keep);
    }

    private static double normalizedProp(TitleData.Realm realm, String key) {
        Object value = realm.props.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) return 0;
        return Math.max(0, Math.min(1, n.doubleValue()));
    }

    /** Statecraft owns feudal contract terms; the map owns the visible realm hierarchy and CourtSim reads it. */
    private static void syncPoliticalMapState() {
        for (var map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null) continue;
            // Statecraft is authoritative after startup import; this pass only projects it.
            TitleEditor editor = EditorManager.editorFor(map);
            if (editor == null) continue;
            for (TitleData.Realm realm : map.titles.realmList()) {
                Statecraft.VassalContract contract = POLITICS.vassalage(realm.id);
                String desiredLiege = contract == null ? null : contract.liegeRealm;
                if (!Objects.equals(realm.overlordId, desiredLiege)) {
                    try { editor.setOverlord(realm.id, desiredLiege, null); }
                    catch (RuntimeException ex) {
                        LOGGER.warn("wf_realms: cannot project vassal contract {} -> {} onto map {}: {}",
                                realm.id, desiredLiege, map.name, ex.getMessage());
                        continue;
                    }
                }
                if (contract == null) {
                    editor.setDerivedProp(realm.id, "tax_share", null);
                    editor.setDerivedProp(realm.id, "levy_share", null);
                    editor.setDerivedProp(realm.id, "politics.vassal_loyalty", null);
                    continue;
                }
                editor.setDerivedProp(realm.id, "tax_share", contract.taxShare);
                editor.setDerivedProp(realm.id, "levy_share", contract.levyShare);
                editor.setDerivedProp(realm.id, "politics.vassal_loyalty", contract.loyalty);
            }
        }
    }

    private static ActionResult runWarDomainAction(CommandSourceStack source, String realmId,
                                                   String actionId, List<String> args, String requestId) {
        for (var map : WorldForgeMod.loadedMaps().values()) {
            if (map.titles == null || !map.titles.realms.containsKey(realmId)) continue;
            TitleEditor editor = EditorManager.editorFor(map);
            if (editor == null) return ActionResult.fail("Редактор карты недоступен");
            String actor = source.getEntity() instanceof ServerPlayer player
                    ? player.getUUID().toString() : "console";
            return WorldForgeApi.run(actionId, new ActionContext(editor, actor, source.hasPermission(2),
                    realmId, 0, args, requestId));
        }
        return ActionResult.fail("Государство не найдено на загруженной карте: " + realmId);
    }

    static boolean rules(ServerPlayer p, String realm) {
        if (p.hasPermissions(2)) return true;
        TitleData.Realm r = realm(realm);
        Object who = r == null ? null : r.props.get("player");
        return who != null && (String.valueOf(who).equals(p.getGameProfile().getName()) || String.valueOf(who).equals(p.getUUID().toString()));
    }

    static String realmOfPlayer(ServerPlayer p) {
        for (TitleData.Realm r : realms()) { Object who = r.props.get("player"); if (who != null && (String.valueOf(who).equals(p.getGameProfile().getName()) || String.valueOf(who).equals(p.getUUID().toString()))) return r.id; }
        return "";
    }

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_realms.json"); }
    private static Path politicsFile(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_statecraft.json"); }

    private static void loadDefs(ResourceManager rm) {
        Map<ResourceLocation, Resource> found = rm.listResources("wf_realms", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { DEFS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_realms: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        LOGGER.info("wf_realms: форм власти {}, ветвей {}, законов {}", DEFS.govs.size(), DEFS.branches.size(), DEFS.laws.size());
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) RealmsMod::loadDefs); }
    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        AUTONOMOUS_REALMS.clear();
        try {
            Path f = file(e.getServer());
            if (Files.exists(f)) REALMS.fromJson(Files.readString(f));
            Path political = politicsFile(e.getServer());
            if (Files.exists(political)) POLITICS.fromJson(Files.readString(political));
            int importedTitles = 0;
            for (var map : WorldForgeMod.loadedMaps().values())
                if (map.titles != null) importedTitles += POLITICS.syncMapTitles(map.name, map.titles);
            syncPoliticalMapState();
            if (importedTitles > 0) LOGGER.info("wf_realms: imported {} political titles from world maps", importedTitles);
            int treasuryAccounts = 0;
            for (TitleData.Realm realm : realms()) {
                POLITICS.treasury(realm.id);
                treasuryAccounts++;
            }
            LOGGER.info("wf_realms: synchronized {} realm treasury accounts with WorldForge ledger", treasuryAccounts);
        } catch (Exception ex) { LOGGER.error("wf_realms: не удалось прочитать состояние: {}", ex.toString()); }
    }

    public static synchronized boolean persistNow(MinecraftServer server) {
        if (server == null) return false;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT).resolve("worldforge");
            Map<Path, byte[]> writes = new LinkedHashMap<>();
            writes.put(file(server), REALMS.toJson().getBytes(StandardCharsets.UTF_8));
            writes.put(politicsFile(server), POLITICS.toJson().getBytes(StandardCharsets.UTF_8));
            AtomicFileBatch.commit(root, writes);
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_realms: atomic political checkpoint failed: {}", ex.toString(), ex);
            return false;
        }
    }

    private static void save(MinecraftServer s) {
        if (!persistNow(s)) LOGGER.error("wf_realms: political checkpoint remains dirty");
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % 200 == 0) {
                int days = WorldForgeApi.clock().pull("wf_realms");
                syncPoliticalMapState();
                List<TitleData.Realm> currentRealms = realms();
                if (days > 0) {
                    Map<String, Integer> rulerOpinions = new LinkedHashMap<>();
                    for (TitleData.Realm r : currentRealms) {
                        Object rawOpinion = r.props.get("politics.liege_opinion");
                        rulerOpinions.put(r.id, rawOpinion instanceof Number n ? n.intValue() : 0);
                    }
                    POLITICS.advanceVassalPolitics((int) WorldForgeApi.clock().dayNumber(), rulerOpinions);
                    for (TitleData.Realm realm : currentRealms) {
                        Statecraft.VassalContract contract = POLITICS.vassalage(realm.id);
                        if (contract != null) realm.props.put("politics.vassal_loyalty", contract.loyalty);
                        realm.props.put("politics.succession_law", POLITICS.successionLawForPeople(realm.id));
                    }
                }
                for (TitleData.Realm r : currentRealms) {
                    Object taxableBase = r.props.get("taxable_base");
                    if (days > 0 && taxableBase instanceof Number base && base.doubleValue() > 0) {
                        POLITICS.collectTaxes(REALMS, r.id, base.doubleValue(), days);
                    }
                    if (days > 0 && !r.props.containsKey("player")) REALMS.autoDevelop(r.id, r.id.hashCode() + WorldForgeApi.clock().dayNumber());
                    else REALMS.tick(r.id);
                }
            }
            if (ticks % 6000 == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_realms: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        registerPolitics(d);
        d.register(Commands.literal("wfrealm")
                .executes(c -> info(c.getSource(), ""))
                .then(Commands.literal("info").then(Commands.argument("realm", StringArgumentType.word()).executes(c -> info(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("gov").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "gov", StringArgumentType.getString(c, "id"), ""))))
                .then(Commands.literal("unlock").then(Commands.argument("key", StringArgumentType.word()).executes(c -> act(c.getSource(), "unlock", StringArgumentType.getString(c, "key"), ""))))
                .then(Commands.literal("law").then(Commands.argument("law", StringArgumentType.word()).then(Commands.argument("option", StringArgumentType.word()).executes(c -> act(c.getSource(), "law", StringArgumentType.getString(c, "law"), StringArgumentType.getString(c, "option")))))));
    }

    /** Operator-only bootstrap surface for the political simulation. */
    private static void registerPolitics(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfpolitics").requires(s -> s.hasPermission(2))
                .then(Commands.literal("title")
                        .then(Commands.literal("create")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("realm", StringArgumentType.word())
                                                .then(Commands.argument("rank", StringArgumentType.word())
                                                        .then(Commands.argument("name", StringArgumentType.word())
                                                                .executes(c -> politics(c.getSource(), "title_create",
                                                                        StringArgumentType.getString(c, "id"),
                                                                        StringArgumentType.getString(c, "realm"),
                                                                        StringArgumentType.getString(c, "rank"),
                                                                        StringArgumentType.getString(c, "name"))))))))
                        .then(Commands.literal("grant")
                                .then(Commands.argument("title", StringArgumentType.word())
                                        .then(Commands.argument("character", StringArgumentType.word())
                                                .executes(c -> politics(c.getSource(), "title_grant",
                                                        StringArgumentType.getString(c, "title"),
                                                        StringArgumentType.getString(c, "character"), "", "")))))
                        .then(Commands.literal("claim")
                                .then(Commands.argument("title", StringArgumentType.word())
                                        .then(Commands.argument("character", StringArgumentType.word())
                                                .executes(c -> politics(c.getSource(), "title_claim",
                                                        StringArgumentType.getString(c, "title"),
                                                        StringArgumentType.getString(c, "character"), "", ""))))))
                .then(Commands.literal("vassal")
                        .then(Commands.argument("vassal", StringArgumentType.word())
                                .then(Commands.argument("liege", StringArgumentType.word())
                                        .then(Commands.argument("tax_share", DoubleArgumentType.doubleArg(0, 1))
                                                .then(Commands.argument("levy_share", DoubleArgumentType.doubleArg(0, 1))
                                                        .executes(c -> politics(c.getSource(), "vassal",
                                                                StringArgumentType.getString(c, "vassal"),
                                                                StringArgumentType.getString(c, "liege"),
                                                                Double.toString(DoubleArgumentType.getDouble(c, "tax_share")),
                                                                Double.toString(DoubleArgumentType.getDouble(c, "levy_share")))))))))
                .then(Commands.literal("tax")
                        .then(Commands.argument("realm", StringArgumentType.word())
                                .then(Commands.argument("rate", DoubleArgumentType.doubleArg(0, 1))
                                        .executes(c -> politics(c.getSource(), "tax",
                                                StringArgumentType.getString(c, "realm"),
                                                Double.toString(DoubleArgumentType.getDouble(c, "rate")), "", "")))))
                .then(Commands.literal("opinion")
                        .then(Commands.argument("realm_a", StringArgumentType.word())
                                .then(Commands.argument("realm_b", StringArgumentType.word())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(-100, 100))
                                                .executes(c -> politics(c.getSource(), "opinion",
                                                        StringArgumentType.getString(c, "realm_a"),
                                                        StringArgumentType.getString(c, "realm_b"),
                                                        Integer.toString(IntegerArgumentType.getInteger(c, "value")), ""))))))
                .then(Commands.literal("treaty")
                        .then(Commands.argument("realm_a", StringArgumentType.word())
                                .then(Commands.argument("realm_b", StringArgumentType.word())
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .then(Commands.argument("active", BoolArgumentType.bool())
                                                        .executes(c -> politics(c.getSource(), "treaty",
                                                                StringArgumentType.getString(c, "realm_a"),
                                                                StringArgumentType.getString(c, "realm_b"),
                                                                StringArgumentType.getString(c, "type"),
                                                                Boolean.toString(BoolArgumentType.getBool(c, "active")))))))))
                .then(Commands.literal("war")
                        .then(Commands.argument("attacker", StringArgumentType.word())
                                .then(Commands.argument("defender", StringArgumentType.word())
                                        .then(Commands.argument("casus_belli", StringArgumentType.word())
                                                .then(Commands.argument("ruler_character", StringArgumentType.word())
                                                        .executes(c -> politics(c.getSource(), "war",
                                                                StringArgumentType.getString(c, "attacker"),
                                                                StringArgumentType.getString(c, "defender"),
                                                                StringArgumentType.getString(c, "casus_belli"),
                                                                StringArgumentType.getString(c, "ruler_character"))))))))
                .then(Commands.literal("succession")
                        .then(Commands.argument("realm", StringArgumentType.word())
                                .then(Commands.argument("law", StringArgumentType.word())
                                        .executes(c -> politics(c.getSource(), "succession",
                                                StringArgumentType.getString(c, "realm"),
                                                StringArgumentType.getString(c, "law"), "", ""))))));
        d.register(Commands.literal("wfinherit").requires(s -> s.hasPermission(2))
                .then(Commands.argument("title", StringArgumentType.word())
                        .then(Commands.argument("deceased", StringArgumentType.word())
                                .then(Commands.argument("successor", StringArgumentType.word())
                                        .executes(ctx -> politics(ctx.getSource(), "inherit",
                                                StringArgumentType.getString(ctx, "title"),
                                                StringArgumentType.getString(ctx, "deceased"),
                                                StringArgumentType.getString(ctx, "successor"), ""))))));
        d.register(Commands.literal("wfdynasty").requires(s -> s.hasPermission(2))
                .then(Commands.literal("register")
                        .then(Commands.argument("house", StringArgumentType.word())
                                .then(Commands.argument("founder", StringArgumentType.word())
                                        .executes(ctx -> politics(ctx.getSource(), "dynasty", StringArgumentType.getString(ctx, "house"), StringArgumentType.getString(ctx, "founder"), "", "")))))
                .then(Commands.literal("member")
                        .then(Commands.argument("house", StringArgumentType.word())
                                .then(Commands.argument("character", StringArgumentType.word())
                                        .executes(ctx -> politics(ctx.getSource(), "dynasty_member", StringArgumentType.getString(ctx, "house"), StringArgumentType.getString(ctx, "character"), "", ""))))));
        d.register(Commands.literal("wfplot").requires(s -> s.hasPermission(2))
                .then(Commands.literal("create")
                        .then(Commands.argument("realm", StringArgumentType.word())
                                .then(Commands.argument("sponsor", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        .executes(ctx -> politics(ctx.getSource(), "plot",
                                                                StringArgumentType.getString(ctx, "realm"),
                                                                StringArgumentType.getString(ctx, "sponsor"),
                                                                StringArgumentType.getString(ctx, "target"),
                                                                StringArgumentType.getString(ctx, "kind")))))))));
    }

    private static int politics(CommandSourceStack source, String action, String a, String b, String c, String d) {
        String actor = source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : "console";
        String payload = String.join("|", action, a, b, c, d);
        return executePoliticalMutation(source, action, a, payload, requestId -> {
            try {
                switch (action) {
                    case "title_create" -> POLITICS.createTitle(a, b, Statecraft.Rank.valueOf(c.toUpperCase(Locale.ROOT)), d);
                    case "title_grant" -> { if (!POLITICS.grantTitle(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                    case "title_claim" -> { if (!POLITICS.addClaim(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                    case "vassal" -> { if (!POLITICS.setVassalage(a, b, Double.parseDouble(c), Double.parseDouble(d))) throw new IllegalArgumentException(POLITICS.lastError); }
                    case "tax" -> POLITICS.setTaxRate(a, Double.parseDouble(b));
                    case "opinion" -> POLITICS.setOpinion(a, b, Integer.parseInt(c));
                    case "treaty" -> {
                        Statecraft.Treaty treaty = Statecraft.Treaty.valueOf(c.toUpperCase(Locale.ROOT));
                        boolean active = Boolean.parseBoolean(d);
                        if (treaty == Statecraft.Treaty.ALLIANCE) {
                            ActionResult domain = runWarDomainAction(source, a, active ? "ally" : "ally_break",
                                    List.of(b), requestId);
                            if (!domain.ok) throw new IllegalArgumentException(domain.message);
                        } else POLITICS.setTreaty(a, b, treaty, active);
                    }
                    case "war" -> {
                        ActionResult domain = runWarDomainAction(source, a, "war_declare", List.of(b, c, ""), requestId);
                        if (!domain.ok) throw new IllegalArgumentException(domain.message);
                    }
                    case "succession" -> POLITICS.setInheritanceLaw(a, Statecraft.InheritanceLaw.valueOf(b.toUpperCase(Locale.ROOT)));
                    case "dynasty" -> POLITICS.registerDynasty(a, b);
                    case "dynasty_member" -> { if (!POLITICS.addDynastyMember(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                    case "plot" -> POLITICS.createPlot(a, b, c, Statecraft.PlotKind.valueOf(d.toUpperCase(Locale.ROOT)));
                    case "inherit" -> { if (!POLITICS.inheritTitle(a, b, c)) throw new IllegalArgumentException(POLITICS.lastError); }
                    default -> { return dev.worldforge.mod.api.ActionResult.fail("Неизвестная политическая операция"); }
                }
                syncPoliticalMapState();
                save(source.getServer());
                return dev.worldforge.mod.api.ActionResult.ok("Политическое состояние обновлено: " + action);
            } catch (Exception ex) {
                return dev.worldforge.mod.api.ActionResult.fail("Политическая операция отклонена: " + ex.getMessage());
            }
        }, () -> null, true);
    }

    private static int executePoliticalMutation(CommandSourceStack source, String action, String target,
                                                String payload,
                                                java.util.function.Function<String, dev.worldforge.mod.api.ActionResult> operation,
                                                java.util.function.Supplier<String> domainAuthorization,
                                                boolean operatorOnly) {
        String actor = source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : "console";
        long minute = WorldForgeApi.clock().worldMinute();
        String requestId = "politics:" + java.util.UUID.randomUUID();
        try {
            var task = WorldForgeApi.actionExecution().execute(
                    new dev.worldforge.mod.api.ActionExecutionService.Request(
                            requestId, "politics." + action, actor, target, List.of(payload), minute),
                    req -> {
                        if (source.getEntity() instanceof ServerPlayer player
                                && source.getServer().getPlayerList().getPlayer(player.getUUID()) == null)
                            return "Игрок отключился до политической операции";
                        if (operatorOnly && !source.hasPermission(2))
                            return "Нужны права оператора";
                        return domainAuthorization.get();
                    },
                    (req, attempt) -> operation.apply(req.requestId()), null, minute,
                    WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                source.sendSuccess(() -> Component.literal(task.message()), true);
                return 1;
            }
            source.sendFailure(Component.literal(task.message()));
            return 0;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось выполнить политическую операцию: " + ex.getMessage()));
            return 0;
        }
    }

    private static int info(CommandSourceStack s, String realm) {
        ServerPlayer sp = s.getPlayer();
        if (realm.isEmpty() && sp != null) realm = realmOfPlayer(sp);
        if (realm.isEmpty() || realm(realm) == null) { s.sendFailure(Component.literal("Государство не найдено. Укажите: /wfrealm info <id>")); return 0; }
        Realms.State st = REALMS.state(realm);
        StringBuilder sb = new StringBuilder(realm(realm).name + ": " + REALMS.levelDef(realm).name() + ", очков развития " + Math.round(st.points * 10) / 10.0);
        sb.append("\nФорма власти: ").append(st.gov.isEmpty() ? "не выбрана" : DEFS.govs.get(st.gov).name());
        Map<String, Double> races = DemoApi.races(realm);
        if (!races.isEmpty()) { sb.append("\nЖители: "); races.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(6).forEach(e -> sb.append(e.getKey()).append(' ').append(Math.round(e.getValue() * 100)).append("%  ")); }
        List<String> options = new ArrayList<>();
        for (RealmDefs.Gov g : DEFS.govs.values()) { String why = REALMS.why(realm, g); options.add(g.id() + (why == null || why.isEmpty() ? " (подходит " + Math.round(REALMS.fit(realm, g)) + ")" : " ✗ " + why)); }
        sb.append("\nФормы власти: ").append(String.join("; ", options));
        String m = sb.toString();
        s.sendSuccess(() -> Component.literal(m), false);
        return 1;
    }

    private static int act(CommandSourceStack source, String what, String a, String b) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        String realm = realmOfPlayer(player);
        if (realm.isEmpty()) { source.sendFailure(Component.literal("Вы не правите государством.")); return 0; }
        String actor = player.getUUID().toString();
        String payload = what + "|" + realm + "|" + a + "|" + b;
        int result = executePoliticalMutation(source, "realm_" + what, realm, payload, requestId -> {
            boolean ok = switch (what) {
                case "gov" -> REALMS.setGov(realm, a, false);
                case "unlock" -> REALMS.unlock(realm, a);
                default -> REALMS.setLaw(realm, a, b);
            };
            if (!ok) return dev.worldforge.mod.api.ActionResult.fail(REALMS.lastError);
            return dev.worldforge.mod.api.ActionResult.ok("Готово.");
        }, () -> rules(player, realm) ? null : "Это решение правителя.", false);
        if (result == 0) return 0;
        return info(source, realm);
    }
}
