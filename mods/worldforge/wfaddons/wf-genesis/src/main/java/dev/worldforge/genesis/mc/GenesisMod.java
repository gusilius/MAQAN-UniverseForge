package dev.worldforge.genesis.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import dev.worldforge.genesis.Draft;
import dev.worldforge.genesis.Genesis;
import dev.worldforge.genesis.GenesisDefs;
import dev.worldforge.genesis.GenesisActionIds;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.mc.PeopleMod;
import dev.worldforge.mod.WFNet;
import dev.worldforge.mod.WFPerm;
import dev.worldforge.mod.WorldForgeMod;
import dev.worldforge.mod.api.ActionExecutionService;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.origins.Origins;
import dev.worldforge.origins.mc.OriginsMod;
import dev.worldforge.progress.Profile;
import dev.worldforge.progress.mc.ProgressMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Начало игры: мастер создания персонажа. При первом входе открывается окно: раса и подраса, внешность, происхождение (старт в поселении,
 * городе, столице, разбойником, скитальцем или сразу правителем с основанием поселения и панелью управления), профессии, атрибуты, класс и навыки.
 * Состояние выбора хранится на сервере (черновик), окно лишь показывает его и шлёт команды /wfgenesis ... Облик завершённых персонажей рассылается всем игрокам.
 */
@Mod("wf_genesis")
public class GenesisMod {
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final GenesisDefs DEFS = new GenesisDefs();
    public static final Genesis GENESIS = new Genesis(DEFS, OriginsMod.DEFS, ProgressMod.PROGRESS.defs);
    public static final String VIEW = "wf_genesis:wizard", LOOKS = "wf_genesis:looks";
    /** Клиент подставляет сюда чтение облика по игроку (рост и ширина в долях от 1). */
    public static volatile java.util.function.BiFunction<Player, String, Double> CLIENT_SCALE = (p, k) -> 1.0;
    private static final String WAIT = "wf_genesis_wait";
    /** Changes once per server process so a restart can resume an interrupted, idempotent Genesis operation. */
    private static volatile String ACTION_SESSION_ID = "";

    public GenesisMod() { MinecraftForge.EVENT_BUS.register(this); }

    public static String owner(ServerPlayer p) { return "p:" + p.getUUID(); }

    private static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_genesis.json"); }

    // ------------------------------------------------------------------ данные и сохранение

    private static void loadDefs(ResourceManager rm) {
        DEFS.reset();
        Map<ResourceLocation, Resource> found = new TreeMap<>(rm.listResources("wf_genesis", rl -> rl.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> r : found.entrySet()) {
            try (InputStream in = r.getValue().open()) { DEFS.load(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            catch (Exception ex) { LOGGER.error("wf_genesis: ошибка в {}: {}", r.getKey(), ex.toString()); }
        }
        List<String> items = new ArrayList<>();
        for (String p : DEFS.validate(OriginsMod.DEFS.races.keySet(), ProgressMod.PROGRESS.defs.attrs.keySet(), OriginsMod.DEFS.professions.keySet(), List.of(), items)) LOGGER.warn("wf_genesis: {}", p);
        LOGGER.info("wf_genesis: стартов {}, ползунков {}, особенностей {}", DEFS.starts.size(), DEFS.sliders.size(), DEFS.features.size());
    }

    @SubscribeEvent public void onStarting(ServerStartingEvent e) {
        ACTION_SESSION_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        loadDefs(e.getServer().getResourceManager());
    }
    @SubscribeEvent public void onReload(AddReloadListenerEvent e) { e.addListener((ResourceManagerReloadListener) GenesisMod::loadDefs); }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent e) {
        try { Path f = file(e.getServer()); if (Files.exists(f)) GENESIS.fromJson(Files.readString(f)); }
        catch (Exception ex) { LOGGER.error("wf_genesis: не удалось прочитать персонажей: {}", ex.toString()); }
    }

    @SubscribeEvent public void onStopping(ServerStoppingEvent e) { save(e.getServer()); }

    private static boolean save(MinecraftServer s) {
        Path f = file(s);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(tmp, GENESIS.toJson(), StandardCharsets.UTF_8);
            try { Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_genesis: не удалось сохранить персонажей: {}", ex.toString(), ex);
            return false;
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }
    }

    // ------------------------------------------------------------------ вход игрока

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp)) return;
        sendAllLooks(sp);
        if (GENESIS.isDone(owner(sp))) {
            Draft finished = GENESIS.finished(owner(sp));
            try {
                if (finished != null) ensurePlayerCharacter(sp, finished);
            } catch (RuntimeException ex) {
                LOGGER.error("wf_genesis: cannot resolve canonical Character for player {}: {}", sp.getName().getString(), ex.toString(), ex);
                sp.sendSystemMessage(Component.literal("Персонаж загружен, но его WorldForge Character не удалось восстановить. Сохранение не перезаписано; обратитесь к администратору."));
                return;
            }
            broadcastLook(sp); sp.refreshDimensions(); return;
        }
        // игроки с уже выбранной расой (из старых миров) мастер не видят: они уже играют
        Origins.Build b = OriginsMod.ORIGINS.peek(owner(sp));
        if (b != null && !b.race.isEmpty()) return;
        begin(sp);
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent e) { if (e.getEntity() instanceof ServerPlayer sp && sp.getPersistentData().getBoolean(WAIT)) e.setCanceled(true); }

    static void begin(ServerPlayer sp) {
        sp.getPersistentData().putBoolean(WAIT, true);
        Draft d = GENESIS.draft(owner(sp));
        if (d.race.isEmpty()) GENESIS.setRace(d, OriginsMod.DEFS.races.containsKey("human") ? "human" : OriginsMod.DEFS.races.keySet().iterator().next());
        push(sp, d);
    }

    static void push(ServerPlayer sp, Draft d) { WFNet.sendView(sp, VIEW, GENESIS.viewJson(d)); }

    // ------------------------------------------------------------------ облик для всех

    private static void sendAllLooks(ServerPlayer to) {
        MinecraftServer s = to.getServer();
        if (s == null) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) { String l = GENESIS.lookString(owner(p)); if (!l.isEmpty()) WFNet.sendView(to, LOOKS, p.getUUID() + "\n" + l); }
    }

    static void broadcastLook(ServerPlayer sp) {
        String l = GENESIS.lookString(owner(sp));
        if (l.isEmpty() || sp.getServer() == null) return;
        for (ServerPlayer p : sp.getServer().getPlayerList().getPlayers()) WFNet.sendView(p, LOOKS, sp.getUUID() + "\n" + l);
    }

    // ------------------------------------------------------------------ размер тела по внешности

    @SubscribeEvent
    public void onSize(EntityEvent.Size e) {
        if (!(e.getEntity() instanceof Player p)) return;
        double sy, sx;
        if (p.level().isClientSide) { sy = CLIENT_SCALE.apply(p, "height"); sx = CLIENT_SCALE.apply(p, "width"); }
        else {
            Draft d = GENESIS.finished(owner0(p));
            if (d == null) return;
            sy = scale(d, "height"); sx = scale(d, "width");
        }
        if (sy == 1.0 && sx == 1.0) return;
        e.setNewSize(e.getNewSize().scale((float) sx, (float) sy));
        e.setNewEyeHeight((float) (e.getNewEyeHeight() * sy));
    }

    private static String owner0(Player p) { return "p:" + p.getUUID(); }

    private static double scale(Draft d, String key) {
        try { return Math.max(0.5, Math.min(1.5, Double.parseDouble(d.look.getOrDefault(key, "100")) / 100.0)); } catch (NumberFormatException ex) { return 1.0; }
    }

    // ------------------------------------------------------------------ команды

    @SubscribeEvent public void onCommands(RegisterCommandsEvent e) { register(e.getDispatcher()); }

    private interface Step { String run(Draft d); }

    private static void register(CommandDispatcher<CommandSourceStack> dp) {
        LiteralArgumentBuilder<CommandSourceStack> r = Commands.literal("wfgenesis").executes(c -> open(c.getSource()));
        r.then(Commands.literal("race").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.setRace(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("look").then(Commands.argument("key", StringArgumentType.word()).then(Commands.argument("value", StringArgumentType.greedyString())
                .executes(c -> step(c, d -> GENESIS.setLook(d, StringArgumentType.getString(c, "key"), StringArgumentType.getString(c, "value").trim()))))));
        r.then(Commands.literal("shift").then(Commands.argument("key", StringArgumentType.word()).then(Commands.argument("h", IntegerArgumentType.integer(-180, 180)).then(Commands.argument("s", IntegerArgumentType.integer(-100, 100)).then(Commands.argument("b", IntegerArgumentType.integer(-100, 100))
                .executes(c -> step(c, d -> GENESIS.shiftColor(d, StringArgumentType.getString(c, "key"), IntegerArgumentType.getInteger(c, "h"), IntegerArgumentType.getInteger(c, "s"), IntegerArgumentType.getInteger(c, "b")))))))));
        r.then(Commands.literal("feature").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.toggleFeature(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("start").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.setStart(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("settle").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> step(c, d -> GENESIS.setSettle(d, StringArgumentType.getString(c, "name"))))));
        r.then(Commands.literal("randomname").executes(c -> step(c, d -> { d.settleName = GENESIS.randomName(d, new Random()); return ""; })));
        r.then(Commands.literal("focus").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.setFocus(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("tax").then(Commands.argument("n", IntegerArgumentType.integer(0, 3)).executes(c -> step(c, d -> GENESIS.setTax(d, IntegerArgumentType.getInteger(c, "n"))))));
        r.then(Commands.literal("raids").executes(c -> step(c, d -> { d.raids = !d.raids; return ""; })));
        r.then(Commands.literal("auto").executes(c -> step(c, d -> { d.auto = !d.auto; return ""; })));
        r.then(Commands.literal("prof").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.toggleProf(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("attr").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("n", IntegerArgumentType.integer(-8, 8))
                .executes(c -> step(c, d -> GENESIS.adjustAttr(d, StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "n")))))));
        r.then(Commands.literal("attrreset").executes(c -> step(c, GENESIS::resetAttrs)));
        r.then(Commands.literal("class").then(Commands.argument("id", StringArgumentType.word()).executes(c -> step(c, d -> GENESIS.pickClass(d, StringArgumentType.getString(c, "id"))))));
        r.then(Commands.literal("skill").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("n", IntegerArgumentType.integer(-3, 3))
                .executes(c -> step(c, d -> GENESIS.adjustSkill(d, StringArgumentType.getString(c, "id"), IntegerArgumentType.getInteger(c, "n")))))));
        r.then(Commands.literal("random").executes(c -> step(c, d -> { d.race = ""; d.start = ""; GENESIS.randomize(d, new Random()); return ""; })));
        r.then(Commands.literal("confirm").executes(c -> confirm(c.getSource())));
        r.then(Commands.literal("reset").requires(WFPerm::trusted).then(Commands.argument("player", EntityArgument.player()).executes(c -> reset(c.getSource(), EntityArgument.getPlayer(c, "player")))));
        dp.register(r);
    }

    private static int open(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        if (GENESIS.isDone(owner(sp))) { s.sendFailure(Component.literal("Персонаж уже создан. Оператор может сбросить: /wfgenesis reset <игрок>")); return 0; }
        begin(sp);
        return 1;
    }

    private static int step(CommandContext<CommandSourceStack> c, Step st) {
        CommandSourceStack s = c.getSource();
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        if (GENESIS.isDone(owner(sp))) { s.sendFailure(Component.literal("Персонаж уже создан.")); return 0; }
        Draft d = GENESIS.draft(owner(sp));
        String err = st.run(d);
        push(sp, d);
        if (!err.isEmpty()) { s.sendFailure(Component.literal(err)); return 0; }
        return 1;
    }

    private static int reset(CommandSourceStack s, ServerPlayer t) {
        String actor = s.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : s.getTextName();
        String target = t.getUUID().toString();
        long minute = WorldForgeApi.clock().worldMinute();
        String draftFingerprint = UUID.nameUUIDFromBytes(
                (target + "|" + GENESIS.viewJson(GENESIS.draft(owner(t)))).getBytes(StandardCharsets.UTF_8)).toString();
        String requestId = GenesisActionIds.resetDraft(actor, target, draftFingerprint);
        ActionExecutionService.Request request = new ActionExecutionService.Request(
                requestId, "genesis.reset_draft", actor, target, List.of(target), minute);
        try {
            ActionExecutionService.Task task = WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (!WFPerm.trusted(s)) return "Для сброса черновика нужны права оператора";
                        if (s.getServer().getPlayerList().getPlayer(t.getUUID()) != t)
                            return "Игрок больше не подключён";
                        boolean hasCanonicalCharacter = !t.getPersistentData()
                                .getString("worldforge_character_global_id").isBlank();
                        return GENESIS.isDone(owner(t)) || hasCanonicalCharacter
                                ? "Сброс завершённого или частично применённого персонажа запрещён без согласованной миграции всех доменов"
                                : null;
                    },
                    (req, attempt) -> {
                        GENESIS.forget(owner(t));
                        t.refreshDimensions();
                        broadcastLook(t);
                        begin(t);
                        return ActionResult.ok("Создание персонажа сброшено для " + t.getName().getString());
                    }, null, minute, WorldForgeMod.actionExecutionFile(s.getServer()));
            if (task.status() == ActionExecutionService.Status.SUCCEEDED) {
                s.sendSuccess(() -> Component.literal(task.message()), true);
                return 1;
            }
            s.sendFailure(Component.literal(task.message()));
            return 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось сбросить черновик Genesis: " + ex.getMessage()));
            return 0;
        }
    }

    // ------------------------------------------------------------------ применение

    private static int confirm(CommandSourceStack s) {
        ServerPlayer sp = s.getPlayer();
        if (sp == null) return 0;
        Draft d = GENESIS.draft(owner(sp));
        List<String> problems = GENESIS.problems(d);
        if (!problems.isEmpty()) {
            push(sp, d);
            s.sendFailure(Component.literal(problems.get(0)));
            return 0;
        }
        try {
            // Genesis uses the one shared durable action journal; it does not own a second executor.
            long worldMinute = WorldForgeApi.clock().worldMinute();
            String draftFingerprint = UUID.nameUUIDFromBytes((sp.getUUID() + "|" + GENESIS.viewJson(d)).getBytes(StandardCharsets.UTF_8)).toString();
            String actorId = "player:" + sp.getUUID();
            int recoveryAttempt = 0;
            for (ActionExecutionService.Task previous : WorldForgeApi.actionExecution().tasks()) {
                if (!"genesis.confirm".equals(previous.actionId()) || !actorId.equals(previous.actorId())
                        || previous.status() != ActionExecutionService.Status.FAILED || previous.args().isEmpty()) continue;
                String previousFingerprint = previous.args().get(previous.args().size() - 1);
                if (!draftFingerprint.equals(previousFingerprint)) {
                    s.sendFailure(Component.literal("Предыдущее создание персонажа завершилось частично. Изменение черновика заблокировано до восстановления предыдущей операции."));
                    return 0;
                }
                recoveryAttempt++;
            }
            if (GENESIS.isDone(owner(sp)) && recoveryAttempt == 0) {
                s.sendFailure(Component.literal("Персонаж уже создан."));
                return 0;
            }
            // Re-applying this exact draft is safe: Origins/Progress are rebuilt, starter grants
            // carry stable grant IDs, and the settlement bridge resumes its durable phase cursor.
            String requestId = GenesisActionIds.confirm(
                    sp.getUUID().toString(), ACTION_SESSION_ID, draftFingerprint, recoveryAttempt);
            ActionExecutionService.Request request = new ActionExecutionService.Request(
                    requestId, "genesis.confirm", actorId, sp.getUUID().toString(),
                    List.of(d.race, d.start, d.cls, draftFingerprint), worldMinute);
            ActionExecutionService.Task task = WorldForgeApi.actionExecution().execute(
                    request,
                    req -> sp.getServer() != null
                            && sp.getServer().getPlayerList().getPlayer(sp.getUUID()) == sp
                            && req.actorId().equals("player:" + sp.getUUID())
                            && req.targetId().equals(sp.getUUID().toString()) && !sp.isRemoved()
                            ? null : "Genesis confirmation actor/target is no longer authorized",
                    (req, attempt) -> {
                        // Do not publish the draft as completed until every application step succeeds.
                        apply(sp, d);
                        String err = GENESIS.confirm(d);
                        if (!err.isEmpty()) return ActionResult.fail(err);
                        if (!save(sp.getServer())) {
                            return ActionResult.fail("Genesis state could not be saved; confirmation remains blocked for reconciliation");
                        }
                        return ActionResult.ok("Genesis character created");
                    },
                    null, worldMinute, WorldForgeMod.actionExecutionFile(sp.getServer()));
            if (task.status() != ActionExecutionService.Status.SUCCEEDED) {
                s.sendFailure(Component.literal("Создание персонажа не завершено: " + task.message()));
                return 0;
            }
        } catch (RuntimeException ex) {
            LOGGER.error("wf_genesis: ошибка применения персонажа {}: {}", sp.getName().getString(), ex.toString(), ex);
            s.sendFailure(Component.literal("Ошибка применения. Мастер создания оставлен открытым: " + ex.getMessage()));
            return 0;
        }
        sp.getPersistentData().remove(WAIT);
        broadcastLook(sp);
        sp.sendSystemMessage(Component.literal("§6Персонаж создан. §7Окно происхождения - K, прокачка - P."));
        WFNet.sendView(sp, VIEW + "_close", "");
        return 1;
    }

    private static void apply(ServerPlayer sp, Draft d) {
        Character person = ensurePlayerCharacter(sp, d);
        String o = owner(sp);
        GenesisDefs.Start st = DEFS.starts.get(d.start);
        Origins og = OriginsMod.ORIGINS;
        // раса, профессии и класс заново
        og.resetRace(o);
        Origins.Build b = og.build(o);
        b.prof.clear(); b.history.clear(); b.cls = "";
        if (!og.setRace(o, d.race)) {
            throw new IllegalStateException("Не удалось применить расу " + d.race + ": " + og.lastError);
        }
        // Очки прокачки применяются строго: Genesis не может подтвердить частично собранный профиль.
        var pg = ProgressMod.PROGRESS;
        Profile pr = pg.profile(o);
        pr.attrs.clear(); pr.skills.clear(); pr.nodes.clear(); pr.archetype = "";
        pr.attrFree = GENESIS.budget(d);
        for (var e : d.attrs.entrySet()) {
            if (e.getValue() > 0 && !pg.addAttr(o, e.getKey(), e.getValue())) {
                throw new IllegalStateException("Не удалось применить атрибут " + e.getKey() + ": " + pg.lastError);
            }
            if (pr.attr(e.getKey()) != e.getValue()) {
                throw new IllegalStateException("Атрибут " + e.getKey() + " применён не полностью");
            }
        }
        pr.skillFree = GENESIS.skillBudget(d);
        Map<String, Integer> want = new LinkedHashMap<>(d.skills);
        for (int guard = 0; guard < 200 && !want.isEmpty(); guard++) {
            boolean any = false;
            for (var it = want.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (pr.skill(e.getKey()) >= e.getValue()) { it.remove(); continue; }
                if (pg.learnSkill(o, e.getKey())) any = true;
            }
            if (!any) break;
        }
        for (var e : want.entrySet()) {
            if (pr.skill(e.getKey()) < e.getValue()) {
                throw new IllegalStateException("Не удалось полностью применить навык " + e.getKey()
                        + ": " + pg.canSkill(o, e.getKey()));
            }
        }
        // Профессии и класс обязательны: ошибка должна остановить подтверждение.
        for (String p : d.profs) {
            if (!og.learn(o, p)) {
                throw new IllegalStateException("Не удалось применить профессию " + p + ": " + og.lastError);
            }
            if (st != null && st.profXp() > 0) og.work(o, p, st.profXp());
            if (!og.build(o).prof.containsKey(p)) {
                throw new IllegalStateException("Профессия " + p + " не сохранилась в профиле Origins");
            }
        }
        if (!d.cls.isEmpty()) {
            if (!og.chooseClass(o, d.cls)) {
                throw new IllegalStateException("Не удалось применить класс " + d.cls + ": " + og.lastError);
            }
            if (!d.cls.equals(og.build(o).cls)) {
                throw new IllegalStateException("Класс " + d.cls + " не сохранился в профиле Origins");
            }
        }
        person.props.put("genesis.race", d.race);
        person.props.put("genesis.start", d.start);
        person.props.put("genesis.class", d.cls);
        person.props.put("genesis.professions", new ArrayList<>(d.profs));
        person.props.put("genesis.attributes", new LinkedHashMap<>(d.attrs));
        person.props.put("genesis.skills", new LinkedHashMap<>(d.skills));
        person.props.put("genesis.features", new ArrayList<>(d.features));
        person.props.put("genesis.appearance", new LinkedHashMap<>(d.look));
        person.props.put("genesis.profile_owner", o);
        ProgressMod.apply(sp);
        sp.setHealth(sp.getMaxHealth());
        // Стартовые предметы помечаются стабильным Genesis grant ID; повторный вызов
        // учитывает уже выданные стопки и не создаёт дубликаты.
        if (st != null) {
            String grantBase = "genesis:" + sp.getUUID();
            give(sp, Items.GOLD_NUGGET, st.gold() * 10, grantBase + ":gold");
            for (var e : st.items().entrySet()) {
                Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(e.getKey().contains(":") ? e.getKey() : "minecraft:" + e.getKey()));
                if (it != Items.AIR) give(sp, it, e.getValue(), grantBase + ":item:" + e.getKey());
                else LOGGER.warn("wf_genesis: нет предмета {}", e.getKey());
            }
        }
        // место старта
        place(sp, d, st);
        sp.refreshDimensions();
    }

    /**
     * Genesis creates the player's one canonical Character. Player UUID and progression owner
     * remain external account/profile keys; they point to this same world-wide person record.
     */
    private static Character ensurePlayerCharacter(ServerPlayer sp, Draft draft) {
        if (!ModList.get().isLoaded("wf_people"))
            throw new IllegalStateException("Genesis requires wf_people to create the canonical player Character");
        if (!PeopleMod.playerRegistryAvailable())
            throw new IllegalStateException("Player Character registry is corrupt; refusing to create a duplicate or overwrite its save");
        String playerUuid = sp.getUUID().toString();
        String savedGlobalId = sp.getPersistentData().getString("worldforge_character_global_id");
        String name = sp.getGameProfile().getName();
        CharacterRegistry playerRegistry = PeopleMod.playerRegistry();
        if (playerRegistry == null || !PeopleMod.playerRegistryAvailable())
            throw new IllegalStateException("Dedicated player Character partition is unavailable");
        Character person = dev.worldforge.genesis.PlayerCharacterBinding.resolveOrCreate(
                playerUuid, savedGlobalId, PeopleMod.authority(),
                PeopleMod.registries().values(), playerRegistry,
                name, draft.race, "m", PeopleMod.YEAR - 20);
        boolean firstGenesisSync = !Boolean.TRUE.equals(person.props.get("genesis.initialized"));
        person.props.put("genesis.player_uuid", playerUuid);
        person.props.put("genesis.profile_owner", owner(sp));
        person.props.put("genesis.race", draft.race);
        person.props.put("genesis.start", draft.start);
        person.props.put("genesis.class", draft.cls);
        person.props.put("genesis.professions", new ArrayList<>(draft.profs));
        person.props.put("genesis.attributes", new LinkedHashMap<>(draft.attrs));
        person.props.put("genesis.skills", new LinkedHashMap<>(draft.skills));
        person.props.put("genesis.features", new ArrayList<>(draft.features));
        person.props.put("genesis.appearance", new LinkedHashMap<>(draft.look));
        if (person.culture == null || person.culture.isBlank()) person.culture = draft.race;
        if (firstGenesisSync && !draft.profs.isEmpty()) {
            CharacterRegistry ownerRegistry = PeopleMod.authority().registryForGlobal(person.globalId);
            if (ownerRegistry != null)
                ownerRegistry.changeProfession(person.id, draft.profs.iterator().next(), PeopleMod.YEAR);
        }
        person.props.put("genesis.initialized", true);
        sp.getPersistentData().putString("worldforge_character_global_id", person.globalId);
        if (!PeopleMod.persistCharacter(person, sp.getServer()))
            throw new IllegalStateException("Canonical player Character could not be durably saved");
        return person;
    }

    private static void give(ServerPlayer sp, Item it, int n, String grantId) {
        if (it == Items.AIR || n <= 0) return;
        net.minecraft.nbt.CompoundTag playerData = sp.getPersistentData();
        net.minecraft.nbt.CompoundTag grantLedger = playerData.getCompound("wf_genesis_grants");
        int ledgerGranted = Math.max(0, grantLedger.getInt(grantId));
        int inventoryGranted = 0;
        for (int slot = 0; slot < sp.getInventory().getContainerSize(); slot++) {
            ItemStack stack = sp.getInventory().getItem(slot);
            if (stack.is(it) && stack.hasTag() && grantId.equals(stack.getTag().getString("wf_genesis_grant"))) {
                inventoryGranted += stack.getCount();
            }
        }
        // The player NBT ledger follows the inventory into chests, containers and later sessions.
        // Marked inventory stacks recover the narrow crash window before that ledger is saved.
        int alreadyGranted = Math.max(ledgerGranted, inventoryGranted);
        int remaining = Math.max(0, n - alreadyGranted);
        if (remaining == 0) {
            if (ledgerGranted < n) {
                grantLedger.putInt(grantId, n);
                playerData.put("wf_genesis_grants", grantLedger);
            }
            return;
        }
        int freeCapacity = 0;
        for (int slot = 0; slot < sp.getInventory().getContainerSize(); slot++) {
            ItemStack stack = sp.getInventory().getItem(slot);
            if (stack.isEmpty()) freeCapacity += it.getMaxStackSize();
            else if (stack.is(it) && stack.hasTag()
                    && grantId.equals(stack.getTag().getString("wf_genesis_grant"))) {
                freeCapacity += Math.max(0, it.getMaxStackSize() - stack.getCount());
            }
        }
        if (freeCapacity < remaining) {
            throw new IllegalStateException("Not enough inventory space for Genesis grant " + grantId
                    + "; clear " + (remaining - freeCapacity) + " item capacity and retry after server reconciliation");
        }
        while (remaining > 0) {
            ItemStack stack = new ItemStack(it, Math.min(remaining, it.getMaxStackSize()));
            stack.getOrCreateTag().putString("wf_genesis_grant", grantId);
            int count = stack.getCount();
            if (!sp.getInventory().add(stack)) {
                throw new IllegalStateException("Could not complete Genesis grant " + grantId);
            }
            remaining -= count;
        }
        grantLedger.putInt(grantId, Math.max(ledgerGranted, n));
        playerData.put("wf_genesis_grants", grantLedger);
    }

    // ------------------------------------------------------------------ место старта

    private static boolean settleLoaded() { return ModList.get().isLoaded("wf_settle"); }

    private static void place(ServerPlayer sp, Draft d, GenesisDefs.Start st) {
        if (st == null) return;
        ServerLevel lv = sp.serverLevel();
        Random rng = new Random(sp.getUUID().getMostSignificantBits() ^ sp.getUUID().getLeastSignificantBits());
        BlockPos spawn = lv.getSharedSpawnPos();
        boolean settle = settleLoaded() && SettleBridge.ready();
        switch (st.spawn()) {
            case "here" -> {
                BlockPos at = BlockPos.containing(sp.position());
                if (st.leader() && settle) foundFor(sp, d, st, lv, at);
                else if (st.leader()) sp.sendSystemMessage(Component.literal("Поселения недоступны: нет мода wf_settle. Вы просто начинаете здесь."));
            }
            case "settlement" -> {
                if (!settle) { teleport(sp, lv, scatter(lv, spawn, 150, 500, rng)); break; }
                var c = SettleBridge.nearest(lv, sp.getX(), sp.getZ(), 4000);
                BlockPos at;
                if (c != null) at = new BlockPos(SettleBridge.cx(c), lv.getSharedSpawnPos().getY(), SettleBridge.cz(c));
                else {
                    BlockPos where = scatter(lv, spawn, 120, 400, rng);
                    var tier = DEFS.tiers.get(st.tier().isEmpty() ? "village" : st.tier());
                    String name = GENESIS.randomName(d, rng);
                    var col = SettleBridge.found(lv, where, name, tier, null, null, d, "genesis:" + sp.getUUID() + ":origin-settlement");
                    at = new BlockPos(SettleBridge.cx(col), where.getY(), SettleBridge.cz(col));
                    sp.sendSystemMessage(Component.literal("§7Вы родом из поселения «" + name + "»."));
                }
                BlockPos s = surface(lv, at.getX() + 4, at.getZ() + 4);
                teleport(sp, lv, s == null ? at : s);
            }
            case "camp" -> {
                BlockPos where = scatter(lv, spawn, 300, 900, rng);
                teleport(sp, lv, where);
                if (lv.getBlockState(where.below()).isSolid()) lv.setBlock(where, Blocks.CAMPFIRE.defaultBlockState(), 3);
            }
            default -> teleport(sp, lv, scatter(lv, spawn, 150, 800, rng));
        }
        if (st.wanted()) sp.sendSystemMessage(Component.literal("§cВы вне закона: стража знает ваше лицо."));
    }

    private static void foundFor(ServerPlayer sp, Draft d, GenesisDefs.Start st, ServerLevel lv, BlockPos at) {
        var tier = DEFS.tiers.get(st.tier());
        var focus = DEFS.focus.get(d.focus);
        var col = SettleBridge.found(lv, at, d.settleName, tier, focus, sp, d, "genesis:" + sp.getUUID() + ":ruler-settlement");
        sp.sendSystemMessage(Component.literal("§6" + GENESIS.rulerTitle(d, st.tier()) + " " + d.settleName + "§7: основано (" + GENESIS.settleNoun(d, st.tier()) + "). Панель правителя открыта, дальше - J."));
        if (st.panel()) SettleBridge.panel(sp, col);
    }

    private static void teleport(ServerPlayer sp, ServerLevel lv, BlockPos p) {
        if (p == null) return;
        sp.teleportTo(lv, p.getX() + 0.5, p.getY(), p.getZ() + 0.5, sp.getYRot(), sp.getXRot());
    }

    /** Точка на поверхности на расстоянии [min, max] от центра; воды и пустоты избегаем, при неудаче - сам центр. */
    private static BlockPos scatter(ServerLevel lv, BlockPos centre, int min, int max, Random rng) {
        for (int i = 0; i < 30; i++) {
            double ang = rng.nextDouble() * Math.PI * 2, r = min + rng.nextDouble() * (max - min);
            BlockPos p = surface(lv, centre.getX() + (int) (Math.cos(ang) * r), centre.getZ() + (int) (Math.sin(ang) * r));
            if (p != null) return p;
        }
        BlockPos p = surface(lv, centre.getX(), centre.getZ());
        return p == null ? centre : p;
    }

    private static BlockPos surface(ServerLevel lv, int x, int z) {
        lv.getChunk(x >> 4, z >> 4);
        int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= lv.getMinBuildHeight() + 1) return null;
        BlockPos below = new BlockPos(x, y - 1, z);
        if (!lv.getBlockState(below).getFluidState().isEmpty()) return null;
        return new BlockPos(x, y, z);
    }
}
