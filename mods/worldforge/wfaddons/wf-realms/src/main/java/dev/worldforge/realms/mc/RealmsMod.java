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
        d.register(Commands.literal("wfrealm")
                .executes(c -> info(c.getSource(), ""))
                .then(Commands.literal("info").then(Commands.argument("realm", StringArgumentType.word()).executes(c -> info(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("gov").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "gov", StringArgumentType.getString(c, "id"), ""))))
                .then(Commands.literal("unlock").then(Commands.argument("key", StringArgumentType.word()).executes(c -> act(c.getSource(), "unlock", StringArgumentType.getString(c, "key"), ""))))
                .then(Commands.literal("law").then(Commands.argument("law", StringArgumentType.word()).then(Commands.argument("option", StringArgumentType.word()).executes(c -> act(c.getSource(), "law", StringArgumentType.getString(c, "law"), StringArgumentType.getString(c, "option")))))));
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
