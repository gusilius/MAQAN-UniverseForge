package dev.worldforge.mod.api;

import dev.worldforge.mod.TitleEditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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

    private static final Map<String, MapAction> ACTIONS = new LinkedHashMap<>();
    private static final List<Guard> GUARDS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MapChange>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final EventLog EVENTS = new EventLog();
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

    public static void addChangeListener(Consumer<MapChange> l) { LISTENERS.add(l); }

    /** Для тестов: убрать всё, что добавлено. */
    public static synchronized void resetForTests() {
        ACTIONS.clear();
        GUARDS.clear();
        LISTENERS.clear();
        EVENTS.clear();
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
