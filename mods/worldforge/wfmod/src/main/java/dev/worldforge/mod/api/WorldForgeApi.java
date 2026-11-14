package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleEditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.nio.file.Path;

/**
 * Точки расширения для аддонов (дипломатия, войны, NPC):
 * <ul>
 *   <li>действия - {@link #registerAction}, их показывают панели карты и запускает {@code /worldforge action};</li>
 *   <li>проверки до действия - {@link #addGuard} (можно запретить с сообщением);</li>
 *   <li>уведомления об изменениях - {@link #addChangeListener};</li>
 *   <li>данные - свойства титулов и государств ({@code props}) попадают клиентам и веб-карте и сами становятся режимами карты;
 *       описание значений (название, цвета) задаётся в {@code prop_defs} файла realms.json.</li>
 * </ul>
 * Без зависимостей от Minecraft: тестируется отдельно.
 */
public final class WorldForgeApi {
    /** Проверка перед действием: вернуть текст отказа или null, чтобы разрешить. */
    @FunctionalInterface
    public interface Guard {
        String check(MapAction action, ActionContext ctx);
    }

    private static final DomainMutationRevisions REVISIONS = new DomainMutationRevisions();
    private static final Map<String, MapAction> ACTIONS = new LinkedHashMap<>();
    private static final List<Guard> GUARDS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MapChange>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<String, ActionExecutionService.Replanner> REPLANNERS = new LinkedHashMap<>();
    private static final EventLog EVENTS = new EventLog();
    private static ActionExecutionService ACTION_EXECUTION = new ActionExecutionService();
    private static boolean builtinsDone;

    private WorldForgeApi() {}

    public static synchronized void registerAction(MapAction a) {
        if (ACTIONS.containsKey(a.id)) throw new IllegalStateException("Действие уже зарегистрировано: " + a.id);
        ACTIONS.put(a.id, a);
    }

    public static synchronized MapAction action(String id) {
        ensureBuiltins();
        return ACTIONS.get(id);
    }

    public static synchronized Collection<MapAction> actions() {
        ensureBuiltins();
        return new ArrayList<>(ACTIONS.values());
    }

    private static final GameClock CLOCK = new GameClock();

    /** Общие часы мира: пауза, скорость, календарь; все симуляции берут дни отсюда (GameClock.pull). */
    public static GameClock clock() { return CLOCK; }

    /** Shared canonical-domain revision vector; lanes are advanced by authoritative writers only. */
    public static DomainMutationRevisions revisions() { return REVISIONS; }

    /** Shared durable action queue. WorldForgeMod loads/saves it with the active world. */
    public static ActionExecutionService actionExecution() { return ACTION_EXECUTION; }
    public static synchronized void resetActionExecution() { ACTION_EXECUTION = new ActionExecutionService(); }

    public static ActionExecutionService.Task submitAction(String requestId, String id, ActionContext base,
            Path journalFile, long worldMinute) {
        return submitAction(requestId, id, base, journalFile, worldMinute, null);
    }

    public static ActionExecutionService.Task submitAction(String requestId, String id, ActionContext base,
            Path journalFile, long worldMinute, ActionExecutionService.Replanner replanner) {
        MapAction initial = action(id);
        if (initial == null) throw new IllegalArgumentException("Нет такого действия: " + id);
        String target = initial.scope == MapAction.Scope.REALM ? base.realmId
                : initial.scope == MapAction.Scope.TITLE ? Integer.toString(base.titleId) : "map";
        if (target == null || target.equals("0")) throw new IllegalArgumentException("Для действия не выбрана цель");
        ActionExecutionService.Request request = new ActionExecutionService.Request(requestId, id,
                base.actor, target, base.args, worldMinute);
        ActionExecutionService service = ACTION_EXECUTION;
        if (replanner == null) {
            synchronized (WorldForgeApi.class) { replanner = REPLANNERS.get(id); }
        }
        ActionExecutionService.Task queued = service.enqueue(request, worldMinute, journalFile);
        if (queued.status() != ActionExecutionService.Status.QUEUED) return queued;
        return service.process(requestId, req -> {
            MapAction candidate = action(req.actionId());
            if (candidate == null) return "Действие больше не зарегистрировано: " + req.actionId();
            if (candidate.perm == MapAction.Perm.OP && !base.operator) return "Нужны права оператора";
            ActionContext context = actionContext(base, candidate, req.targetId(), req.args(), req.requestId());
            for (Guard guard : GUARDS) {
                String denied = guard.check(candidate, context);
                if (denied != null) return denied;
            }
            return null;
        }, (req, attempt) -> {
            MapAction candidate = action(req.actionId());
            if (candidate == null) return ActionResult.fail("Действие не найдено: " + req.actionId());
            ActionContext context = actionContext(base, candidate, req.targetId(), req.args(), req.requestId());
            ActionResult result = candidate.handler.run(context);
            if (result.ok) fireChange(new MapChange(context.data(), "action", candidate.id));
            return result;
        }, replanner, worldMinute, journalFile);
    }

    private static ActionContext actionContext(ActionContext base, MapAction action, String target,
                                               List<String> args, String requestId) {
        String realmId = action.scope == MapAction.Scope.REALM ? target : null;
        int titleId = 0;
        if (action.scope == MapAction.Scope.TITLE) {
            try { titleId = Integer.parseInt(target); }
            catch (NumberFormatException ex) { throw new TitleEditor.EditException("Для действия нужен корректный ID титула"); }
        }
        return new ActionContext(base.editor, base.actor, base.operator, realmId, titleId, args, requestId);
    }

    /** Общий журнал событий мира (аддоны пишут сюда войны, браки, стройки; клиент показывает всплывающие сообщения и окно журнала). */
    public static EventLog events() { return EVENTS; }

    /** Дописывает событие в журнал. category: war, diplomacy, people, settlement, system (иное = system). */
    public static EventLog.Event postEvent(String category, String text, List<String> realmIds, long time) {
        return EVENTS.post(category, text, realmIds, time);
    }

    public static EventLog.Event postEvent(String category, String text, List<String> realmIds) {
        return EVENTS.post(category, text, realmIds, System.currentTimeMillis());
    }

    public static void addGuard(Guard g) { GUARDS.add(g); }

    /** Domain addon registers a bounded alternative-plan strategy for its action family. */
    public static synchronized void registerReplanner(String actionId, ActionExecutionService.Replanner replanner) {
        if (actionId == null || !actionId.matches("[a-z0-9_.:-]{1,48}"))
            throw new IllegalArgumentException("Invalid replanner action id");
        if (REPLANNERS.putIfAbsent(actionId, java.util.Objects.requireNonNull(replanner)) != null)
            throw new IllegalStateException("Replanner already registered: " + actionId);
    }

    public static void addChangeListener(Consumer<MapChange> l) { LISTENERS.add(l); }

    /** Для тестов: убрать всё, что добавлено. */
    public static synchronized void resetForTests() {
        ACTIONS.clear();
        GUARDS.clear();
        LISTENERS.clear();
        REPLANNERS.clear();
        EVENTS.clear();
        REVISIONS.resetForTests();
        builtinsDone = false;
    }

    private static void ensureBuiltins() {
        if (builtinsDone) return;
        builtinsDone = true;
        BuiltinActions.register();
    }

    public static void fireChange(MapChange c) {
        for (Consumer<MapChange> l : LISTENERS) {
            try {
                l.accept(c);
            } catch (RuntimeException e) {
                System.err.println("WorldForge: ошибка слушателя изменений: " + e);
            }
        }
    }

    /** Запускает действие: права, проверки аддонов, обработчик. Ошибки правки превращаются в отказ, а не в исключение. */
    public static ActionResult run(String id, ActionContext ctx) {
        MapAction a = action(id);
        if (a == null) return ActionResult.fail("Нет такого действия: " + id);
        if (a.perm == MapAction.Perm.OP && !ctx.operator) return ActionResult.fail("Нужны права оператора");
        for (Guard g : GUARDS) {
            String no = g.check(a, ctx);
            if (no != null) return ActionResult.fail(no);
        }
        try {
            ActionResult r = a.handler.run(ctx);
            if (r.ok) fireChange(new MapChange(ctx.data(), "action", a.id));
            return r;
        } catch (TitleEditor.EditException e) {
            return ActionResult.fail(e.getMessage());
        }
    }

    /** JSON со списком действий для клиентов. */
    public static String actionsJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (MapAction a : actions()) {
            if (!first) sb.append(',');
            sb.append(a.toJson());
            first = false;
        }
        return sb.append(']').toString();
    }
}
