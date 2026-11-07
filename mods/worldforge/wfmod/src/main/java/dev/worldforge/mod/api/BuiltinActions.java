package dev.worldforge.mod.api;

import java.util.List;

/** Базовые действия над государствами: они же пример для аддонов. */
final class BuiltinActions {
    private BuiltinActions() {}

    static void register() {
        WorldForgeApi.registerAction(new MapAction("set_ruler", "Сменить правителя", MapAction.Scope.REALM, MapAction.Perm.OP,
                List.of("имя"), "Имя правителя государства", ctx -> {
            ctx.editor.setRuler(ctx.realmId, ctx.arg(0, "имя"));
            return ActionResult.ok("Правитель " + ctx.realm().name + ": " + ctx.realm().ruler);
        }));
        WorldForgeApi.registerAction(new MapAction("set_overlord", "Подчинить государство", MapAction.Scope.REALM, MapAction.Perm.OP,
                List.of("id сюзерена", "отношение"), "Тип: vassal, tributary, protectorate, puppet, personal_union", ctx -> {
            String rel = ctx.args.size() > 1 && !ctx.args.get(1).isBlank() ? ctx.args.get(1).trim() : null;
            ctx.editor.setOverlord(ctx.realmId, ctx.arg(0, "id сюзерена"), rel);
            return ActionResult.ok(ctx.realm().name + " теперь подчинено");
        }));
        WorldForgeApi.registerAction(new MapAction("release", "Дать независимость", MapAction.Scope.REALM, MapAction.Perm.OP,
                List.of(), "Снимает подчинение", ctx -> {
            ctx.editor.setOverlord(ctx.realmId, null, null);
            return ActionResult.ok(ctx.realm().name + " независимо");
        }));
        WorldForgeApi.registerAction(new MapAction("set_prop", "Задать свойство", MapAction.Scope.REALM, MapAction.Perm.OP,
                List.of("ключ", "значение"), "Свойство государства; пустое значение удаляет", ctx -> {
            String v = ctx.args.size() > 1 ? ctx.args.get(1).trim() : "";
            ctx.editor.setRealmProp(ctx.realmId, ctx.arg(0, "ключ"), v.isEmpty() ? null : v);
            return ActionResult.ok("Свойство " + ctx.args.get(0) + (v.isEmpty() ? " удалено" : " = " + v));
        }));
    }
}
