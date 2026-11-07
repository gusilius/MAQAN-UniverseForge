package dev.worldforge.court;

import dev.worldforge.people.Character;

import dev.worldforge.mod.TitleEditor;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;

import java.util.List;
import java.util.function.Function;

/** Действия карты для двора: козни, умиротворение и подавление фракций, ответы на события. Работают от имени выбранного государства. */
public final class CourtActions {
    private CourtActions() {}

    private static Function<TitleEditor, CourtSim> SIMS;

    private interface Body { ActionResult run(CourtSim s, ActionContext c); }

    public static void register(Function<TitleEditor, CourtSim> sims) {
        SIMS = sims;
        reg("scheme_start", "Начать козни", List.of("вид: befriend|sway|murder", "id цели (персонаж)"), "Правитель плетёт козни против персонажа. Не более двух одновременно", (s, c) -> {
            Character me = s.ruler(c.realmId);
            if (me == null) return ActionResult.fail("У государства нет правителя");
            String k = c.arg(0, "вид").toUpperCase();
            Scheme.Kind kind;
            try { kind = Scheme.Kind.valueOf(k); } catch (IllegalArgumentException e) { return ActionResult.fail("Вид козней: befriend, sway, murder"); }
            Scheme sc = s.startScheme(me.id, c.arg(1, "цель"), kind);
            return ActionResult.ok("Козни #" + sc.id + " начаты: шанс " + Math.round(s.schemeChance(sc) * 100) + "%");
        });
        reg("scheme_cancel", "Прекратить козни", List.of("номер козней"), "", (s, c) -> { s.cancelScheme(num(c.arg(0, "номер"))); return ActionResult.ok("Козни прекращены"); });
        reg("faction_appease", "Одарить вассала", List.of("id вассала"), "Мнение вассала о вас растёт на 5 лет", (s, c) -> { s.appease(c.realmId, c.arg(0, "вассал")); return ActionResult.ok("Вассал одарён"); });
        reg("faction_crush", "Подавить заговор", List.of("номер фракции"), "Шанс зависит от интриги совета и силы фракции; провал злит вассалов", (s, c) -> {
            boolean ok = s.crush(c.realmId, num(c.arg(0, "номер")));
            return ok ? ActionResult.ok("Заговор подавлен") : ActionResult.fail("Не удалось: заговор окреп");
        });
        reg("marriage_propose", "Предложить брак", List.of("наш персонаж", "их персонаж"), "Оценивает правитель другого государства: мнение, советники, возраст. Отказ объясняется", (s, c) -> {
            Character mine = s.reg.get(c.arg(0, "наш персонаж"));
            if (mine == null || !Marriages.family(s, mine).equals(c.realmId)) return ActionResult.fail("Это не член вашего дома");
            Marriages.Offer o = Marriages.propose(s, mine.id, c.arg(1, "их персонаж"));
            String why = o.problems.isEmpty() ? String.join("; ", o.reasons) : String.join("; ", o.problems);
            return o.ok ? ActionResult.ok("Брак заключён (" + o.score + "): " + why) : ActionResult.fail("Отказ (" + o.score + "): " + why);
        });
        reg("event_choose", "Ответить на событие", List.of("номер события", "номер ответа (с 1)"), "", (s, c) -> {
            CourtSim.Pending p = null;
            for (CourtSim.Pending x : s.pending) if (x.id == num(c.arg(0, "номер")) && x.realm.equals(c.realmId)) p = x;
            if (p == null) return ActionResult.fail("Нет такого события у этого государства");
            s.choose(p.id, num(c.arg(1, "ответ")) - 1);
            return ActionResult.ok("Выбор сделан");
        });
    }

    private static void reg(String id, String label, List<String> params, String hint, Body body) {
        WorldForgeApi.registerAction(new MapAction(id, label, MapAction.Scope.REALM, MapAction.Perm.ANY, params, hint, ctx -> {
            CourtSim s = SIMS == null ? null : SIMS.apply(ctx.editor);
            if (s == null) return ActionResult.fail("Двор не запущен");
            try { return body.run(s, ctx); } catch (TitleEditor.EditException | IllegalArgumentException ex) { return ActionResult.fail(ex.getMessage()); }
        }));
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.trim().replace("#", "")); } catch (NumberFormatException e) { throw new TitleEditor.EditException("Нужно число: " + s); }
    }
}
