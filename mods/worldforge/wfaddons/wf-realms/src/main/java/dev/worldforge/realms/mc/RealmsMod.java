package dev.worldforge.realms.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.DemoApi;
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
    private static long ticks;
    private static final Random RNG = new Random();

    public RealmsMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) -> e.enqueueWork(() -> {
            REALMS.publish();
            DemoApi.registerTerrain("realms", id -> { TitleData.Realm r = realm(id); return r == null ? Map.of() : DemoApi.shares(DemoApi.parse(r.props.get("terrain"))); });
            DemoApi.registerRaces("realm_props", id -> { TitleData.Realm r = realm(id); return r == null ? Map.of() : DemoApi.shares(DemoApi.parse(r.props.get("races"))); });
        }));
        MinecraftForge.EVENT_BUS.register(this);
        REALMS.hooks = new Realms.Hooks() {
            @Override public int provinces(String realm) { TitleData.Realm r = realm(realm); return r == null ? 1 : Math.max(1, r.titleKeys.size()); }
            @Override public int prestige(String realm) { TitleData.Realm r = realm(realm); return r != null && r.props.get("prestige") instanceof Number n ? n.intValue() : 0; }
            @Override public boolean flag(String realm, String flag) { TitleData.Realm r = realm(realm); return r != null && Boolean.parseBoolean(String.valueOf(r.props.get("flag." + flag))); }
            @Override public int today() { return (int) WorldForgeApi.clock().dayNumber(); }
        };
    }

    static List<TitleData.Realm> realms() {
        List<TitleData.Realm> l = new ArrayList<>();
        for (var m : WorldForgeMod.loadedMaps().values()) if (m.titles != null) l.addAll(m.titles.realmList());
        return l;
    }

    static TitleData.Realm realm(String id) { for (TitleData.Realm r : realms()) if (r.id.equals(id)) return r; return null; }

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
        try {
            Path f = file(e.getServer());
            if (Files.exists(f)) REALMS.fromJson(Files.readString(f));
            Path political = politicsFile(e.getServer());
            if (Files.exists(political)) POLITICS.fromJson(Files.readString(political));
        } catch (Exception ex) { LOGGER.error("wf_realms: не удалось прочитать состояние: {}", ex.toString()); }
    }

    private static void save(MinecraftServer s) {
        try {
            Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, REALMS.toJson());
            Files.writeString(politicsFile(s), POLITICS.toJson());
        } catch (Exception ex) { LOGGER.error("wf_realms: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % 200 == 0) {
                int days = WorldForgeApi.clock().pull("wf_realms");
                for (TitleData.Realm r : realms()) {
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
                                                StringArgumentType.getString(ctx, "successor"), "")))))
        d.register(Commands.literal("wfdynasty").requires(s -> s.hasPermission(2))
                .then(Commands.literal("register")
                        .then(Commands.argument("house", StringArgumentType.word())
                                .then(Commands.argument("founder", StringArgumentType.word())
                                        .executes(ctx -> politics(ctx.getSource(), "dynasty", StringArgumentType.getString(ctx, "house"), StringArgumentType.getString(ctx, "founder"), "", "")))))
                .then(Commands.literal("member")
                        .then(Commands.argument("house", StringArgumentType.word())
                                .then(Commands.argument("character", StringArgumentType.word())
                                        .executes(ctx -> politics(ctx.getSource(), "dynasty_member", StringArgumentType.getString(ctx, "house"), StringArgumentType.getString(ctx, "character"), "", "")))))
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
        try {
            switch (action) {
                case "title_create" -> POLITICS.createTitle(a, b, Statecraft.Rank.valueOf(c.toUpperCase(Locale.ROOT)), d);
                case "title_grant" -> { if (!POLITICS.grantTitle(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                case "title_claim" -> { if (!POLITICS.addClaim(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                case "vassal" -> { if (!POLITICS.setVassalage(a, b, Double.parseDouble(c), Double.parseDouble(d))) throw new IllegalArgumentException(POLITICS.lastError); }
                case "tax" -> POLITICS.setTaxRate(a, Double.parseDouble(b));
                case "opinion" -> POLITICS.setOpinion(a, b, Integer.parseInt(c));
                case "treaty" -> POLITICS.setTreaty(a, b, Statecraft.Treaty.valueOf(c.toUpperCase(Locale.ROOT)), Boolean.parseBoolean(d));
                case "war" -> POLITICS.declareWar(a, b, c, d, (int) WorldForgeApi.clock().dayNumber());
                case "succession" -> POLITICS.setInheritanceLaw(a, Statecraft.InheritanceLaw.valueOf(b.toUpperCase(Locale.ROOT)));
                case "dynasty" -> POLITICS.registerDynasty(a, b);
                case "dynasty_member" -> { if (!POLITICS.addDynastyMember(a, b)) throw new IllegalArgumentException(POLITICS.lastError); }
                case "plot" -> POLITICS.createPlot(a, b, c, Statecraft.PlotKind.valueOf(d.toUpperCase(Locale.ROOT)));
                case "inherit" -> { if (!POLITICS.inheritTitle(a, b, c)) throw new IllegalArgumentException(POLITICS.lastError); }
                default -> throw new IllegalArgumentException("Неизвестная политическая операция");
            }
            save(source.getServer());
            source.sendSuccess(() -> Component.literal("Политическое состояние обновлено: " + action), true);
            return 1;
        } catch (Exception ex) {
            source.sendFailure(Component.literal("Политическая операция отклонена: " + ex.getMessage()));
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

    private static int act(CommandSourceStack s, String what, String a, String b) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String realm = realmOfPlayer(sp);
        if (realm.isEmpty()) { s.sendFailure(Component.literal("Вы не правите государством.")); return 0; }
        if (!rules(sp, realm)) { s.sendFailure(Component.literal("Это решение правителя.")); return 0; }
        boolean ok = switch (what) {
            case "gov" -> REALMS.setGov(realm, a, false);
            case "unlock" -> REALMS.unlock(realm, a);
            default -> REALMS.setLaw(realm, a, b);
        };
        if (!ok) { s.sendFailure(Component.literal(REALMS.lastError)); return 0; }
        s.sendSuccess(() -> Component.literal("Готово."), false);
        return info(s, realm);
    }
}
