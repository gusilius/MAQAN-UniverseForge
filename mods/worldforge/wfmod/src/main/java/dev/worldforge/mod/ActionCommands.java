package dev.worldforge.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.worldforge.mod.api.ActionContext;
import dev.worldforge.mod.api.ActionResult;
import dev.worldforge.mod.api.MapAction;
import dev.worldforge.mod.api.WorldForgeApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /wfaction list и /wfaction run <id> <цель> [аргументы через |]. Отдельный корень с доступом для всех игроков:
 * права проверяет само действие (Perm.OP требует оператора). Именно эти команды отправляют кнопки панелей карты.
 */
public final class ActionCommands {
    private ActionCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("wfaction")
                .then(Commands.literal("list").executes(c -> {
                    CommandSourceStack s = c.getSource();
                    for (MapAction a : WorldForgeApi.actions()) {
                        s.sendSuccess(() -> Component.literal(a.id + " [" + a.scope.name().toLowerCase() + (a.perm == MapAction.Perm.OP ? ", оператор" : "") + "] " + a.label
                                + (a.params.isEmpty() ? "" : " (" + String.join(" | ", a.params) + ")")), false);
                    }
                    return WorldForgeApi.actions().size();
                }))
                .then(Commands.literal("submit")
                        .then(Commands.argument("requestId", StringArgumentType.word())
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(c -> submit(c.getSource(),
                                                        StringArgumentType.getString(c, "requestId"),
                                                        StringArgumentType.getString(c, "id"),
                                                        StringArgumentType.getString(c, "target"), ""))
                                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                                        .executes(c -> submit(c.getSource(),
                                                                StringArgumentType.getString(c, "requestId"),
                                                                StringArgumentType.getString(c, "id"),
                                                                StringArgumentType.getString(c, "target"),
                                                                StringArgumentType.getString(c, "args"))))))))
                .then(Commands.literal("run").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("target", StringArgumentType.word())
                                .executes(c -> run(c.getSource(), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "target"), ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(c -> run(c.getSource(), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "target"),
                                                StringArgumentType.getString(c, "args")))))));
    }

    private static int submit(CommandSourceStack s, String requestId, String id, String target, String rawArgs) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null || m.titles == null) {
            s.sendFailure(Component.literal("В этом измерении нет политической карты"));
            return 0;
        }
        MapAction action = WorldForgeApi.action(id);
        if (action == null) {
            s.sendFailure(Component.literal("Нет такого действия: " + id));
            return 0;
        }
        String realmId = null;
        int titleId = 0;
        if (action.scope == MapAction.Scope.REALM) realmId = target;
        else if (action.scope == MapAction.Scope.TITLE) {
            try { titleId = Integer.parseInt(target); }
            catch (NumberFormatException ex) {
                s.sendFailure(Component.literal("Для этого действия цель - номер титула"));
                return 0;
            }
        }
        List<String> args = new ArrayList<>();
        if (!rawArgs.isBlank()) for (String part : rawArgs.split("\\|", -1)) args.add(part.trim());
        ActionContext context = new ActionContext(EditorManager.editorFor(m), s.getTextName(),
                WFPerm.trusted(s), realmId, titleId, args);
        try {
            var task = WorldForgeApi.submitAction(requestId, id, context,
                    WorldForgeMod.actionExecutionFile(s.getServer()), WorldForgeApi.clock().worldMinute());
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                EditorManager.touch(m);
                s.sendSuccess(() -> Component.literal(task.message()), false);
                return 1;
            }
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.QUEUED) {
                s.sendSuccess(() -> Component.literal("Приказ поставлен в очередь: " + task.requestId()), false);
                return 1;
            }
            s.sendFailure(Component.literal(task.message()));
            return 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось принять приказ: " + ex.getMessage()));
            return 0;
        }
    }

    private static int run(CommandSourceStack s, String id, String target, String rawArgs) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null || m.titles == null) {
            s.sendFailure(Component.literal("В этом измерении нет политической карты"));
            return 0;
        }
        MapAction a = WorldForgeApi.action(id);
        if (a == null) {
            s.sendFailure(Component.literal("Нет такого действия: " + id));
            return 0;
        }
        String realmId = null;
        int titleId = 0;
        if (a.scope == MapAction.Scope.REALM) {
            realmId = target;
        } else if (a.scope == MapAction.Scope.TITLE) {
            try {
                titleId = Integer.parseInt(target);
            } catch (NumberFormatException e) {
                s.sendFailure(Component.literal("Для этого действия цель - номер титула"));
                return 0;
            }
        }
        List<String> args = new ArrayList<>();
        if (!rawArgs.isBlank()) for (String part : rawArgs.split("\\|", -1)) args.add(part.trim());
        String who = s.getTextName();
        ActionContext ctx = new ActionContext(EditorManager.editorFor(m), who, WFPerm.trusted(s), realmId, titleId, args);
        ActionResult r = WorldForgeApi.run(id, ctx);
        if (!r.ok) {
            s.sendFailure(Component.literal(r.message));
            return 0;
        }
        EditorManager.touch(m);
        s.sendSuccess(() -> Component.literal(r.message.isEmpty() ? a.label + ": готово" : r.message), false);
        return 1;
    }
}
