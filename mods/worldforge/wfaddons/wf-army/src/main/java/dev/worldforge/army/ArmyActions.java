package dev.worldforge.army;

import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;

import java.util.List;
import java.util.function.Function;

/** Действия карты для армий, экономики и торговых договоров. Права проверяются как для войн (WarEngine.authority). */
public final class ArmyActions {
    private ArmyActions() {}

    private static final java.util.Map<String, String> CAPS = new java.util.HashMap<>();

    /** Runtime persistence is injected by the Forge adapter; pure logic tests stay Forge-independent. */
    public interface PersistenceBridge {
        boolean actionsBlocked();
        boolean persist();
    }
    private static volatile PersistenceBridge persistence = new PersistenceBridge() {
        @Override public boolean actionsBlocked() { return false; }
        @Override public boolean persist() { return true; }
    };
    public static void installPersistenceBridge(PersistenceBridge bridge) {
        if (bridge == null) throw new IllegalArgumentException("army persistence bridge is required");
        persistence = bridge;
    }

    private static Function<TitleEditor, ArmyEngine> ARMIES;

    /** Действия армий: набор, наём, движение, остановка, слияние, роспуск. Права проверяются как для войн (способность "army"). */
    public static void registerArmies(Function<TitleEditor, ArmyEngine> armies) {
        ARMIES = armies;
        CAPS.put("econ_develop", "economy");
        CAPS.put("econ_tax", "economy");
        CAPS.put("econ_tribute", "economy");
        CAPS.put("trade_propose", "alliance");
        CAPS.put("trade_cancel", "alliance");
        CAPS.put("army_raise", "army");
        CAPS.put("army_hire", "army");
        CAPS.put("army_move", "army");
        CAPS.put("army_stop", "army");
        CAPS.put("army_merge", "army");
        CAPS.put("army_split", "army");
        CAPS.put("army_disband", "army");
        WorldForgeApi.addGuard((action, ctx) -> {
            String cap = CAPS.get(action.id);
            if (cap == null) return null;
            if (persistence.actionsBlocked()) return "Стратегические приказы заблокированы после ошибки сохранения; требуется перезапуск и сверка";
            ArmyEngine e = armies.apply(ctx.editor);
            if (e == null) return "Движок армий не запущен";
            if (ctx.realmId == null) return "Не выбрано государство";
            return e.war.authority.may(ctx.actor, ctx.operator, ctx.realmId, cap) ? null : "У вас нет полномочий: " + cap + " для этого государства";
        });
        areg("army_raise", "Набрать войско", List.of("тип", "число"), "Типы: levy, spear, archer, cavalry, knight, siege. Ополчение тратит запас людей, остальные - золото", (e, c) -> {
            Army a = recruitOnce(e, c, "raise");
            return ActionResult.ok("Армия #" + a.id + ": " + a.men() + " человек");
        });
        areg("army_hire", "Нанять наёмников", List.of("тип", "число"), "Дорого, но без ополчения", (e, c) -> {
            Army a = recruitOnce(e, c, "hire");
            return ActionResult.ok("Наёмники #" + a.id + ": " + a.men() + " человек");
        });
        areg("army_move", "Двинуть армию", List.of("номер армии", "id баронства"), "Армии ходят по баронствам", (e, c) -> {
            TitleData.Title t = c.data().findTitle(c.arg(1, "id баронства"));
            if (t == null) return ActionResult.fail("Нет титула " + c.args.get(1));
            e.moveOnce(c.requestId, num(c.arg(0, "номер армии")), t.id, c.realmId);
            return ActionResult.ok("Армия идёт в " + t.name);
        });
        areg("trade_propose", "Торговый договор", List.of("id государства"), "Оба получают золото каждый день, пока нет войны. Принимает, кому вы не противны", (e, c) -> {
            String other = c.arg(0, "государство");
            if (!c.data().realms.containsKey(other)) return ActionResult.fail("Нет государства " + other);
            String no = e.proposePact(c.realmId, other);
            return no == null ? ActionResult.ok("Договор заключён: +" + Math.round(e.pactIncome(c.realmId, other) * 100) / 100.0 + " золота в день каждому") : ActionResult.fail("Отказ: " + no);
        });
        areg("econ_develop", "Развить баронство", List.of("id баронства"), "Платит золото; каждый уровень даёт +25% дохода баронства (до 5)", (e, c) -> {
            TitleData.Title t = c.data().findTitle(c.arg(0, "id баронства"));
            if (t == null) return ActionResult.fail("Нет титула " + c.args.get(0));
            e.eco.develop(c.realmId, t.id);
            return ActionResult.ok(t.name + ": развитие " + e.eco.level(t.id));
        });
        areg("econ_tax", "Налоговая политика", List.of("0 низкие, 1 обычные, 2 высокие"), "Низкие -20% дохода, высокие +25%", (e, c) -> { e.eco.setTax(c.realmId, num(c.arg(0, "политика"))); return ActionResult.ok(Economy.TAX_NAMES[e.eco.taxPolicy(c.realmId)]); });
        areg("econ_tribute", "Дань вассалов", List.of("процент 0-30"), "Доля дохода каждого вассала, которую он отдаёт вам", (e, c) -> { e.eco.setTribute(c.realmId, num(c.arg(0, "процент"))); return ActionResult.ok("Дань: " + e.eco.tributeRate(c.realmId) + "%"); });
        areg("trade_cancel", "Расторгнуть договор", List.of("id государства"), "", (e, c) -> { e.cancelPact(c.realmId, c.arg(0, "государство")); return ActionResult.ok("Договор расторгнут"); });
        areg("army_stop", "Остановить армию", List.of("номер армии"), "", (e, c) -> {
            e.stopOnce(c.requestId, num(c.arg(0, "номер армии")), c.realmId);
            return ActionResult.ok("Армия остановлена");
        });
        areg("army_split", "Разделить армию", List.of("номер армии", "тип", "число"),
                "Отделяет часть выбранного типа в отдельную армию", (e, c) -> {
                    Army split = e.splitOnce(c.requestId, num(c.arg(0, "номер армии")),
                            c.arg(1, "тип"), num(c.arg(2, "число")), c.realmId);
                    return ActionResult.ok("Создана армия #" + split.id + ": " + split.men() + " человек");
                });
        areg("army_merge", "Слить армии", List.of("номер армии", "номер второй"), "Обе армии должны стоять в одном месте вне осады", (e, c) -> {
            Army merged = e.mergeOnce(c.requestId, num(c.arg(0, "номер армии")),
                    num(c.arg(1, "номер второй")), c.realmId);
            return ActionResult.ok("Армии слиты в #" + merged.id + ": " + merged.men() + " человек");
        });
        areg("army_disband", "Распустить армию", List.of("номер армии"), "Ополченцы возвращаются в запас", (e, c) -> {
            boolean changed = e.disbandOnce(c.requestId, num(c.arg(0, "номер армии")), c.realmId);
            return ActionResult.ok(changed ? "Армия распущена" : "Роспуск уже подтверждён");
        });
    }

    private static Army recruitOnce(ArmyEngine engine, dev.worldforge.mod.api.ActionContext context, String kind) {
        String operationId = engine.stageRecruitment(context.requestId, kind, context.realmId,
                context.arg(0, "тип"), num(context.arg(1, "число")));
        // The intent is written before the operation can debit TreasuryLedger or change the roster.
        if (!persistence.persist())
            throw new TitleEditor.EditException("Не удалось сохранить намерение набора армии; ресурсы не изменены");
        Army result = engine.completePendingRecruitment(operationId);
        // The enclosing action wrapper checkpoints treasury and army state once.
        // If that checkpoint fails, the persisted intent and treasury reference make restart replay safe.
        return result;
    }

    private interface ABody { ActionResult run(ArmyEngine e, dev.worldforge.mod.api.ActionContext c); }

    private static void areg(String id, String label, List<String> params, String hint, ABody body) {
        WorldForgeApi.registerAction(new MapAction(id, label, MapAction.Scope.REALM, MapAction.Perm.ANY, params, hint, ctx -> {
            ArmyEngine e = ARMIES == null ? null : ARMIES.apply(ctx.editor);
            if (e == null) return ActionResult.fail("Движок армий не запущен");
            try {
                ActionResult result = body.run(e, ctx);
                if (!result.ok) return result;
                if (!persistence.persist())
                    return ActionResult.fail("Стратегический приказ применён в памяти, но его сохранение не подтверждено; дальнейшие приказы заблокированы до восстановления");
                return result;
            } catch (TitleEditor.EditException ex) {
                return ActionResult.fail(ex.getMessage());
            } catch (RuntimeException ex) {
                return ActionResult.fail("Стратегический приказ не завершён: " + ex.getMessage());
            }
        }));
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim().replace("#", "")); } catch (NumberFormatException e) { throw new TitleEditor.EditException("Нужен номер: " + s); }
    }
}
