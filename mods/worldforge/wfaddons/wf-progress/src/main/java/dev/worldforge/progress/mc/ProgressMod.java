package dev.worldforge.progress.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WFPerm;
import dev.worldforge.progress.Profile;
import dev.worldforge.progress.Progress;
import dev.worldforge.progress.ProgressDefs;
import dev.worldforge.progress.Threat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Склейка wf-progress: определения из датапаков (wf_progress), профили игроков в мире, опыт за убийства, применение статистик к игроку
 * (здоровье, урон, скорость, броня, удача), команды /wfprog и окно (клавиша P).
 */
@Mod("wf_progress")
public class ProgressMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static volatile ProgressDefs DEFS = new ProgressDefs();
    public static final Progress PROGRESS = new Progress(DEFS);
    private static final UUID MOD_ID = UUID.fromString("5c0f4a3e-6a54-4f38-9b6f-2f2d8d3c0001");
    private static long ticks;
    private static final int SAVE_EVERY = 6000, APPLY_EVERY = 40;

    public ProgressMod() { MinecraftForge.EVENT_BUS.register(this); PROGRESS.publish(); }

    public static String owner(ServerPlayer p) { return "p:" + p.getUUID(); }

    private static ServerPlayer find(String owner) {
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || !owner.startsWith("p:")) return null;
        try { return s.getPlayerList().getPlayer(UUID.fromString(owner.substring(2))); } catch (IllegalArgumentException e) { return null; }
    }

    private static void loadDefs(ResourceManager rm) {
        ProgressDefs d = new ProgressDefs();
        Map<ResourceLocation, Resource> found = rm.listResources("wf_progress", rl -> rl.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { d.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_progress: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        Threat th = new Threat();
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { th.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_progress: угрозы в {}: {}", r.getKey(), ex.toString()); }
        }
        ThreatMc.THREAT.defs.clear(); ThreatMc.THREAT.defs.putAll(th.defs);
        ThreatMc.THREAT.hpPerLevel = th.hpPerLevel; ThreatMc.THREAT.dmgPerLevel = th.dmgPerLevel; ThreatMc.THREAT.blocksPerLevel = th.blocksPerLevel;
        ThreatMc.THREAT.overPlayer = th.overPlayer; ThreatMc.THREAT.underPlayer = th.underPlayer; ThreatMc.THREAT.baseLevel = th.baseLevel;
        for (String p : d.validate()) LOGGER.warn("wf_progress: {}", p);
        DEFS = d;
        PROGRESS.defs.attrs.clear(); PROGRESS.defs.skills.clear(); PROGRESS.defs.nodes.clear(); PROGRESS.defs.archetypes.clear(); PROGRESS.defs.stats.clear();
        PROGRESS.defs.attrs.putAll(d.attrs); PROGRESS.defs.skills.putAll(d.skills); PROGRESS.defs.nodes.putAll(d.nodes); PROGRESS.defs.archetypes.putAll(d.archetypes); PROGRESS.defs.stats.putAll(d.stats);
        PROGRESS.defs.perLevel.clear(); PROGRESS.defs.perLevel.putAll(d.perLevel); PROGRESS.defs.milestones.clear(); PROGRESS.defs.milestones.putAll(d.milestones);
        PROGRESS.defs.maxLevel = d.maxLevel; PROGRESS.defs.xpBase = d.xpBase; PROGRESS.defs.xpGrowth = d.xpGrowth;
        PROGRESS.defs.attrPoints = d.attrPoints; PROGRESS.defs.skillPoints = d.skillPoints; PROGRESS.defs.passivePoints = d.passivePoints;
        LOGGER.info("wf_progress: узлов {}, навыков {}, атрибутов {}", d.nodes.size(), d.skills.size(), d.attrs.size());
    }

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_progress.json"); }

    @SubscribeEvent
    public void onStarting(ServerStartingEvent e) { loadDefs(e.getServer().getResourceManager()); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        PROGRESS.hooks = new Progress.Hooks() {
            @Override public int gold(String o) { ServerPlayer p = find(o); if (p == null) return 0; int n = 0; for (ItemStack s : p.getInventory().items) if (s.is(Items.GOLD_NUGGET)) n += s.getCount(); return n; }
            @Override public boolean takeGold(String o, int n) {
                ServerPlayer p = find(o);
                if (p == null || gold(o) < n) return false;
                for (ItemStack s : p.getInventory().items) { if (n <= 0) break; if (s.is(Items.GOLD_NUGGET)) { int d = Math.min(n, s.getCount()); s.shrink(d); n -= d; } }
                return true;
            }
        };
        try { Path f = file(e.getServer()); if (Files.exists(f)) PROGRESS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_progress: не удалось прочитать профили: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) ProgressMod::loadDefs); }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    private static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, PROGRESS.toJson()); }
        catch (Exception ex) { LOGGER.error("wf_progress: не удалось сохранить: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        ticks++;
        try {
            if (ticks % APPLY_EVERY == 0) for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) apply(p);
            if (ticks % SAVE_EVERY == 0) save(e.getServer());
        } catch (Exception ex) { LOGGER.error("wf_progress: ошибка тика: {}", ex.toString()); }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) { if (e.getEntity() instanceof ServerPlayer p) apply(p); }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent e) { if (e.getEntity() instanceof ServerPlayer p) apply(p); }

    // ------------------------------------------------------------------ применение статистик

    private static void mod(LivingEntity p, Attribute a, double v, AttributeModifier.Operation op, String name) {
        AttributeInstance i = p.getAttribute(a);
        if (i == null) return;
        UUID id = new UUID(MOD_ID.getMostSignificantBits(), MOD_ID.getLeastSignificantBits() + name.hashCode());
        i.removeModifier(id);
        if (Math.abs(v) > 1e-9) i.addPermanentModifier(new AttributeModifier(id, "wf_progress_" + name, v, op));
    }

    public static void apply(ServerPlayer p) {
        Map<String, Double> s = PROGRESS.sheet(owner(p));
        mod(p, Attributes.MAX_HEALTH, s.getOrDefault("max_health", 20.0) - 20, AttributeModifier.Operation.ADDITION, "health");
        mod(p, Attributes.ATTACK_DAMAGE, (s.getOrDefault("melee_dmg", 1.0) - 1) * (1 + s.getOrDefault("melee_dmg_pct", 0.0) / 100.0), AttributeModifier.Operation.ADDITION, "damage");
        mod(p, Attributes.MOVEMENT_SPEED, s.getOrDefault("move_speed_pct", 0.0) / 100.0, AttributeModifier.Operation.MULTIPLY_TOTAL, "speed");
        mod(p, Attributes.ARMOR, s.getOrDefault("armor", 0.0), AttributeModifier.Operation.ADDITION, "armor");
        mod(p, Attributes.LUCK, s.getOrDefault("luck", 0.0), AttributeModifier.Operation.ADDITION, "luck");
        double regen = s.getOrDefault("health_regen", 0.0);
        if (regen > 0 && p.getHealth() < p.getMaxHealth() && p.tickCount % 40 == 0) p.heal((float) (regen * 2));   // раз в 2 секунды, regen - в сердцах/сек
    }

    // ------------------------------------------------------------------ опыт

    /** Уровень врага: из метки wf_level (её ставит система угроз), иначе из здоровья. */
    public static int mobLevel(LivingEntity e) {
        if (e.getPersistentData().contains("wf_level")) return e.getPersistentData().getInt("wf_level");
        return Math.max(1, Math.round(e.getMaxHealth() / 3f));
    }

    @SubscribeEvent
    public void onKill(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || e.getEntity() instanceof ServerPlayer) return;
        LivingEntity mob = e.getEntity();
        String o = owner(p);
        Profile pr = PROGRESS.profile(o);
        double base = mob instanceof Enemy ? 10 : 2;
        int up = PROGRESS.award(o, PROGRESS.killXp(pr.level, mobLevel(mob), base + mob.getMaxHealth() * 0.5));
        if (up > 0) levelUp(p, pr);
    }

    public static void levelUp(ServerPlayer p, Profile pr) {
        p.sendSystemMessage(Component.literal("§6Уровень " + pr.level + "! §7Очки: атрибуты " + pr.attrFree + ", навыки " + pr.skillFree + ", умения " + pr.passiveFree + ". Клавиша P."));
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        apply(p);
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfprog")
                .executes(c -> open(c.getSource(), true))
                .then(Commands.literal("attr").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "attr", StringArgumentType.getString(c, "id"))))) 
                .then(Commands.literal("skill").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "skill", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("node").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "node", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("refund").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "refund", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("archetype").then(Commands.argument("id", StringArgumentType.word()).executes(c -> act(c.getSource(), "archetype", StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("respec").executes(c -> act(c.getSource(), "respec", "")))
                .then(Commands.literal("xp").requires(WFPerm::trusted).then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    int up = PROGRESS.award(owner(t), IntegerArgumentType.getInteger(c, "n"));
                    if (up > 0) levelUp(t, PROGRESS.profile(owner(t)));
                    return 1;
                }))))
                .then(Commands.literal("level").requires(WFPerm::trusted).then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("n", IntegerArgumentType.integer(1, 200)).executes(c -> {
                    ServerPlayer t = EntityArgument.getPlayer(c, "player");
                    Profile pr = PROGRESS.profile(owner(t));
                    int target = IntegerArgumentType.getInteger(c, "n");
                    while (pr.level < Math.min(target, PROGRESS.defs.maxLevel)) PROGRESS.award(owner(t), PROGRESS.defs.xpFor(pr.level) - pr.xp);
                    levelUp(t, pr);
                    return 1;
                })))));
    }

    private static int open(CommandSourceStack s, boolean withDefs) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(Component.literal("Только для игрока")); return 0; }
        if (withDefs) WFNet.sendView(sp, "wf_progress:defs", PROGRESS.defsJson());
        WFNet.sendView(sp, "wf_progress:profile", PROGRESS.viewJson(owner(sp)));
        return 1;
    }

    private static int act(CommandSourceStack s, String what, String id) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        String o = owner(sp);
        boolean ok = switch (what) {
            case "attr" -> PROGRESS.addAttr(o, id, 1);
            case "skill" -> PROGRESS.learnSkill(o, id);
            case "node" -> PROGRESS.allocate(o, id);
            case "refund" -> PROGRESS.refund(o, id);
            case "archetype" -> PROGRESS.chooseArchetype(o, id);
            case "respec" -> PROGRESS.respec(o);
            default -> false;
        };
        if (!ok) s.sendFailure(Component.literal(PROGRESS.lastError));
        apply(sp);
        return open(s, false);
    }
}
