package dev.worldforge.dialogue.mc;

import com.mojang.logging.LogUtils;
import dev.worldforge.dialogue.Ctx;
import dev.worldforge.dialogue.Dialogue;
import dev.worldforge.dialogue.DialogueMemory;
import dev.worldforge.dialogue.Session;
import dev.worldforge.mod.Json;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Публичное API диалогов для других модов: {@link #open}. Сессии хранятся по игроку (одна на игрока), закрываются при выходе,
 * смерти, смене измерения и (если задана позиция говорящего) при удалении игрока дальше maxDistance.
 * Флаги игрока (set_flag / flag) живут в памяти и сохраняются в worldforge/wf_dialogue_flags.json мира.
 */
public final class DialogueMc {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final double DEFAULT_MAX_DISTANCE = 8.0;
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<String>> FLAGS = new ConcurrentHashMap<>();
    private static final List<BiConsumer<ServerPlayer, Ctx>> PROVIDERS = new CopyOnWriteArrayList<>();

    private DialogueMc() {}

    private static final class Active {
        final long id;
        final Session session;
        final Vec3 pos;
        final double maxDist;

        Active(long id, Session session, Vec3 pos, double maxDist) { this.id = id; this.session = session; this.pos = pos; this.maxDist = maxDist; }
    }

    /** Другие аддоны добавляют сюда сервисы и переменные в контекст каждого разговора (например, репутация кладёт ReputationStore). */
    public static void addContextProvider(BiConsumer<ServerPlayer, Ctx> provider) { PROVIDERS.add(provider); }

    /** Открывает диалог без привязки к позиции. Возвращает false, если диалога нет или он не стартовал. */
    public static boolean open(ServerPlayer player, String dialogueId, Map<String, String> vars) {
        return open(player, dialogueId, vars, null, DEFAULT_MAX_DISTANCE);
    }

    /** Opens a character conversation with durable memory stored in the canonical Character.props map. */
    public static boolean openCharacter(ServerPlayer player, String dialogueId, Map<String, String> vars,
                                        Map<String, Object> characterProps) {
        return openCharacterAt(player, dialogueId, vars, characterProps, null, DEFAULT_MAX_DISTANCE);
    }

    /** Character-backed dialogue that is physically bound to the speaker's position. */
    public static boolean openCharacterAt(ServerPlayer player, String dialogueId, Map<String, String> vars,
                                          Map<String, Object> characterProps, Vec3 speakerPos, double maxDistance) {
        if (maxDistance <= 0 || !Double.isFinite(maxDistance)) return false;
        return openInternal(player, dialogueId, vars, speakerPos, maxDistance, new DialogueMemory(characterProps));
    }

    /** Открывает диалог; speakerPos != null: разговор прервётся, если игрок отойдёт дальше maxDistance блоков. */
    public static boolean open(ServerPlayer player, String dialogueId, Map<String, String> vars, Vec3 speakerPos, double maxDistance) {
        return openInternal(player, dialogueId, vars, speakerPos, maxDistance, null);
    }

    private static boolean openInternal(ServerPlayer player, String dialogueId, Map<String, String> vars,
                                        Vec3 speakerPos, double maxDistance, DialogueMemory memory) {
        Dialogue d = DialogueRegistry.get(dialogueId);
        if (d == null) {
            LOGGER.warn("wf_dialogue: нет диалога '{}'", dialogueId);
            return false;
        }
        UUID uid = player.getUUID();
        Active old = ACTIVE.remove(uid);
        if (old != null) DialogueNet.sendTo(player, new DialogueNet.Close(old.id));
        try {
            Ctx ctx = new Ctx(flagsOf(uid), memory);
            ctx.var("player", player.getGameProfile().getName());
            if (vars != null) for (Map.Entry<String, String> e : vars.entrySet()) ctx.var(e.getKey(), e.getValue());
            ctx.var("player_id", uid.toString());
            for (BiConsumer<ServerPlayer, Ctx> p : PROVIDERS) p.accept(player, ctx);
            Session s = new Session(d, ctx, true);
            long sessionId = UUID.randomUUID().getMostSignificantBits();
            String actor = uid.toString();
            long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
            String requestId = "dialogue:start:" + UUID.nameUUIDFromBytes(
                    (actor + ":" + sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                    requestId, "dialogue.start", actor, dialogueId, List.of(dialogueId), minute);
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> player.server.getPlayerList().getPlayer(uid) == null
                            ? "Игрок отключился до начала диалога" : null,
                    (req, attempt) -> {
                        s.start();
                        return s.view() == null
                                ? dev.worldforge.mod.api.ActionResult.fail("Диалог не содержит доступной стартовой реплики")
                                : dev.worldforge.mod.api.ActionResult.ok("Диалог начат: " + dialogueId);
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(player.server));
            if (task.status() != dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                LOGGER.warn("wf_dialogue: start rejected for '{}': {}", dialogueId, task.message());
                return false;
            }
            Active a = new Active(sessionId, s, speakerPos, maxDistance);
            ACTIVE.put(uid, a);
            sendView(player, a);
            return true;
        } catch (Exception ex) {
            LOGGER.error("wf_dialogue: не удалось открыть '{}': {}", dialogueId, ex.toString());
            return false;
        }
    }

    public static boolean hasSession(ServerPlayer player) { return ACTIVE.containsKey(player.getUUID()); }

    /** Закрывает разговор игрока, если он идёт; клиенту уходит Close. */
    public static void close(ServerPlayer player) {
        Active a = ACTIVE.remove(player.getUUID());
        if (a != null) DialogueNet.sendTo(player, new DialogueNet.Close(a.id));
    }

    public static Set<String> flags(ServerPlayer player) { return flagsOf(player.getUUID()); }

    private static Set<String> flagsOf(UUID id) { return FLAGS.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()); }

    private static void sendView(ServerPlayer player, Active a) {
        Session.View v = a.session.view();
        if (v == null) {
            ACTIVE.remove(player.getUUID(), a);
            DialogueNet.sendTo(player, new DialogueNet.Close(a.id));
            return;
        }
        byte mode = v.cont ? DialogueNet.MODE_CONTINUE : v.end ? DialogueNet.MODE_END : DialogueNet.MODE_CHOICES;
        DialogueNet.sendTo(player, new DialogueNet.OpenView(a.id, v.revision, v.speaker, v.text, mode, new ArrayList<>(v.choices)));
    }

    // ---- вызывается из пакетов (главный поток сервера)

    static void onChoose(ServerPlayer sp, long sessionId, int revision, int index) {
        Active a = ACTIVE.get(sp.getUUID());
        if (a == null || a.id != sessionId) return;
        String actor = sp.getUUID().toString();
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "dialogue:choose:" + UUID.nameUUIDFromBytes(
                (actor + ":" + sessionId + ":" + revision + ":" + index)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "dialogue.choose", actor, Long.toString(sessionId),
                List.of(Integer.toString(revision), Integer.toString(index)), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        if (sp.server.getPlayerList().getPlayer(sp.getUUID()) == null)
                            return "Игрок отключился до выбора ответа";
                        if (ACTIVE.get(sp.getUUID()) != a || a.id != sessionId)
                            return "Диалог уже закрыт или заменён";
                        if (a.session.revision() != revision)
                            return "Ответ устарел: откройте актуальный вариант диалога";
                        Session.View view = a.session.view();
                        if (view == null) return "Диалог уже завершён";
                        if (view.choices.isEmpty()) {
                            if (index != 0) return "Недопустимый индекс продолжения диалога";
                        } else if (index < 0 || index >= view.choices.size()) {
                            return "Недопустимый номер ответа";
                        }
                        return null;
                    },
                    (req, attempt) -> {
                        a.session.choose(index, revision);
                        return dev.worldforge.mod.api.ActionResult.ok("Выбор диалога обработан");
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(sp.server));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("Выбор не выполнен: " + task.message()));
                if (ACTIVE.get(sp.getUUID()) == a) {
                    if (a.session.ended()) {
                        ACTIVE.remove(sp.getUUID(), a);
                        DialogueNet.sendTo(sp, new DialogueNet.Close(a.id));
                    } else sendView(sp, a);
                }
                return;
            }
        } catch (RuntimeException ex) {
            LOGGER.error("wf_dialogue: ошибка выбора ответа: {}", ex.toString());
            ACTIVE.remove(sp.getUUID(), a);
            DialogueNet.sendTo(sp, new DialogueNet.Close(a.id));
            return;
        }
        if (a.session.ended()) {
            ACTIVE.remove(sp.getUUID(), a);
            DialogueNet.sendTo(sp, new DialogueNet.Close(a.id));
        } else sendView(sp, a);
    }

    static void onCloseRequest(ServerPlayer sp, long sessionId) {
        Active a = ACTIVE.get(sp.getUUID());
        if (a != null && a.id == sessionId) ACTIVE.remove(sp.getUUID(), a);   // клиент уже закрыл экран, Close не нужен
    }

    // ---- события жизненного цикла

    static void drop(UUID id) { ACTIVE.remove(id); }

    static void clearSessions() { ACTIVE.clear(); }

    /** Раз в секунду: сбрасывает разговоры, если игрок ушёл от говорящего. */
    static void checkDistances(MinecraftServer server) {
        for (Map.Entry<UUID, Active> e : ACTIVE.entrySet()) {
            Active a = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) { ACTIVE.remove(e.getKey(), a); continue; }
            if (a.pos != null && p.position().distanceToSqr(a.pos) > a.maxDist * a.maxDist) {
                ACTIVE.remove(e.getKey(), a);
                DialogueNet.sendTo(p, new DialogueNet.Close(a.id));
            }
        }
    }

    // ---- флаги игроков в мире

    private static Path flagsFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_dialogue_flags.json");
    }

    @SuppressWarnings("unchecked")
    static void loadFlags(MinecraftServer server) {
        FLAGS.clear();
        Path f = flagsFile(server);
        try {
            if (!Files.exists(f)) return;
            Object root = Json.parse(Files.readString(f));
            if (!(root instanceof Map)) return;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
                if (!(e.getValue() instanceof List)) continue;
                Set<String> s = flagsOf(UUID.fromString(e.getKey()));
                for (Object o : (List<Object>) e.getValue()) s.add(String.valueOf(o));
            }
        } catch (Exception ex) {
            LOGGER.error("wf_dialogue: не удалось прочитать {}: {}", f, ex.toString());
        }
    }

    static void saveFlags(MinecraftServer server) {
        Path f = flagsFile(server);
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            for (Map.Entry<UUID, Set<String>> e : FLAGS.entrySet()) if (!e.getValue().isEmpty()) root.put(e.getKey().toString(), new ArrayList<>(new HashSet<>(e.getValue())));
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(root));
        } catch (Exception ex) {
            LOGGER.error("wf_dialogue: не удалось сохранить {}: {}", f, ex.toString());
        }
    }
}
