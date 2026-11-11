package dev.worldforge.war;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.Objects;

/** Регистрирует действия карты: объявить войну, мир, союз. Права проверяет {@link WarEngine.Authority} (по умолчанию - оператор или правитель). */
public final class WarActions {
    private WarActions() {}

    public interface PersistenceBridge {
        boolean actionsBlocked();
        boolean persist();
    }
    private static volatile PersistenceBridge persistenceBridge;

    public static void installPersistenceBridge(PersistenceBridge bridge) {
        persistenceBridge = Objects.requireNonNull(bridge, "war persistence bridge");
    }

    /** engine: по редактору карты возвращает движок войн этой карты (null - не запущен). */
    public static void register(Function<TitleEditor, WarEngine> engine) {
        reg("war_declare", "Объявить войну", List.of("id цели", "повод", "id титулов-целей через запятую"), "Повод: conquest, subjugation, tribute_war, humiliation, betrayal. Для conquest укажите id титулов цели",
                "war", (e, c) -> {
                    List<Integer> goals = new ArrayList<>();
                    if (c.args.size() > 2 && !c.args.get(2).isBlank()) {
                        for (String p : c.args.get(2).split("[,;\\s]+")) {
                            if (p.isBlank()) continue;
                            TitleData.Title t = c.data().findTitle(p);
                            if (t == null) return ActionResult.fail("Нет титула " + p);
                            goals.add(t.id);
                        }
                    }
                    String target = c.arg(0, "id цели"), cb = c.args.size() > 1 && !c.args.get(1).isBlank() ? c.args.get(1).trim() : "conquest";
                    TitleData.Realm t = c.data().findRealm(target);
                    if (t == null) return ActionResult.fail("Нет государства " + target);
                    War w = e.declareOnce(c.requestId, c.realmId, t.id, cb, goals);
                    return ActionResult.ok("Война #" + w.id + ": " + c.realm().name + " против " + t.name);
                });
        reg("war_independence", "Война за независимость", List.of(), "Подданный восстаёт против сюзерена", "war", (e, c) -> {
            TitleData.Realm r = c.realm();
            if (r.overlordId == null) return ActionResult.fail(r.name + " и так независимо");
            War w = e.declareOnce(c.requestId, r.id, r.overlordId, "independence", List.of());
            return ActionResult.ok("Война #" + w.id + ": восстание " + r.name);
        });
        reg("war_peace_white", "Предложить белый мир", List.of("номер войны"), "Мир без условий; противник должен принять", "peace", (e, c) -> {
            e.offerPeaceOnce(c.requestId, num(c.arg(0, "номер войны")), c.realmId, true);
            return ActionResult.ok("Белый мир предложен");
        });
        reg("war_peace_demand", "Выдвинуть условия мира", List.of("номер войны"), "Нужно достаточно очков войны для целей", "peace", (e, c) -> {
            e.offerPeaceOnce(c.requestId, num(c.arg(0, "номер войны")), c.realmId, false);
            return ActionResult.ok("Условия предъявлены");
        });
        reg("war_peace_accept", "Принять мир", List.of("номер войны"), "Принять предложение противника", "peace", (e, c) -> {
            e.acceptPeaceOnce(c.requestId, num(c.arg(0, "номер войны")), c.realmId);
            return ActionResult.ok("Мир заключён");
        });
        reg("war_enforce", "Навязать мир", List.of("номер войны"), "Если очков хватает на цели, мир заключается сразу", "peace", (e, c) -> {
            e.enforceOnce(c.requestId, num(c.arg(0, "номер войны")), c.realmId);
            return ActionResult.ok("Мир навязан");
        });
        reg("war_surrender", "Капитулировать", List.of("номер войны"), "Противник получает цели войны", "peace", (e, c) -> {
            e.surrenderOnce(c.requestId, num(c.arg(0, "номер войны")), c.realmId);
            return ActionResult.ok("Капитуляция");
        });
        reg("ally", "Заключить союз", List.of("id государства"), "Союзники вступают в войны друг друга", "alliance", (e, c) -> {
            TitleData.Realm t = c.data().findRealm(c.arg(0, "id государства"));
            if (t == null) return ActionResult.fail("Нет такого государства");
            e.allyOnce(c.requestId, c.realmId, t.id);
            return ActionResult.ok("Союз: " + c.realm().name + " и " + t.name);
        });
        reg("ally_break", "Разорвать союз", List.of("id государства"), "", "alliance", (e, c) -> {
            TitleData.Realm t = c.data().findRealm(c.arg(0, "id государства"));
            if (t == null) return ActionResult.fail("Нет такого государства");
            e.breakAllianceOnce(c.requestId, c.realmId, t.id);
            return ActionResult.ok("Союз разорван");
        });
        WorldForgeApi.addGuard((action, ctx) -> {
            String cap = CAPS.get(action.id);
            if (cap == null) return null;
            WarEngine e = engine.apply(ctx.editor);
            if (e == null) return "Движок войн не запущен";
            if (ctx.realmId == null) return "Не выбрано государство";
            PersistenceBridge bridge = persistenceBridge;
            if (bridge != null && bridge.actionsBlocked()) return "Политические сохранения заблокированы до восстановления";
            return e.authority.may(ctx.actor, ctx.operator, ctx.realmId, cap) ? null : "У вас нет полномочий: " + cap + " для этого государства";
        });
        ENGINE = engine;
    }

    private interface Body { ActionResult run(WarEngine e, dev.worldforge.mod.api.ActionContext c); }

    private static final java.util.Map<String, String> CAPS = new java.util.HashMap<>();
    private static Function<TitleEditor, WarEngine> ENGINE;

    private static void reg(String id, String label, List<String> params, String hint, String cap, Body body) {
        CAPS.put(id, cap);
        WorldForgeApi.registerAction(new MapAction(id, label, MapAction.Scope.REALM, MapAction.Perm.ANY, params, hint, ctx -> {
            WarEngine e = ENGINE == null ? null : ENGINE.apply(ctx.editor);
            if (e == null) return ActionResult.fail("Движок войн не запущен");
            try {
                ActionResult result = body.run(e, ctx);
                if (!result.ok) return result;
                PersistenceBridge bridge = persistenceBridge;
                if (bridge != null && !bridge.persist())
                    return ActionResult.fail("Политическое действие применено в памяти, но сохранение войны/карты не подтверждено; повторные приказы заблокированы до сверки");
                return result;
            } catch (TitleEditor.EditException ex) {
                return ActionResult.fail(ex.getMessage());
            } catch (RuntimeException ex) {
                return ActionResult.fail("Политическое действие не завершено: " + ex.getMessage());
            }
        }));
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim().replace("#", "")); } catch (NumberFormatException e) { throw new TitleEditor.EditException("Нужен номер войны: " + s); }
    }
}
