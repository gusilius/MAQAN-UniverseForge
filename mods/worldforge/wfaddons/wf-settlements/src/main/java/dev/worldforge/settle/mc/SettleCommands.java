package dev.worldforge.settle.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.worldforge.settle.Building;
import dev.worldforge.settle.Settlement;
import dev.worldforge.settle.Worker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;

/** /wfsettle found|list|info|build|cancel|assign|hauler|move|unassign|profession|priority|auto|disband. Приказы действуют на ближайшее к игроку поселение. */
public final class SettleCommands {
    private SettleCommands() {}

    private interface Order {
        String run(Colony c) throws Settlement.OrderException;
    }

    /** Оператор, владелец одиночного мира или правитель какого-либо поселения (его поселение проверяется в {@link #order}). */
    private static boolean op(CommandSourceStack s) {
        if (dev.worldforge.mod.WFPerm.trusted(s)) return true;
        return s.getEntity() instanceof net.minecraft.server.level.ServerPlayer p && SettleMod.ownsAny(p.getUUID().toString());
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfsettle")
                .then(Commands.literal("found").requires(SettleCommands::op)
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> found(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("schem")
                        .then(Commands.literal("list").executes(c -> {
                            var names = SchemLibrary.names();
                            c.getSource().sendSuccess(() -> Component.literal("Схем: " + names.size() + (names.isEmpty() ? ". Положите .litematic/.schem/.nbt в " + SchemLibrary.worldDir(c.getSource().getServer()) + " и выполните /wfsettle schem reload" : " " + names)), false);
                            return names.size();
                        }))
                        .then(Commands.literal("reload").requires(SettleCommands::op).executes(c -> {
                            var rep = SettleMod.reloadSchematics(c.getSource().getServer());
                            c.getSource().sendSuccess(() -> Component.literal("Схемы перечитаны: " + (rep.isEmpty() ? "файлов нет" : "\n" + String.join("\n", rep))), true);
                            return rep.size();
                        })))
                .then(Commands.literal("build").requires(SettleCommands::op)
                        .then(Commands.argument("def", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(SettleMod.DEFS == null ? java.util.List.of() : SettleMod.DEFS.buildings.keySet(), b))
                                .executes(c -> build(c, null, null))
                                .then(Commands.argument("dx", IntegerArgumentType.integer())
                                        .then(Commands.argument("dz", IntegerArgumentType.integer())
                                                .executes(c -> build(c, IntegerArgumentType.getInteger(c, "dx"), IntegerArgumentType.getInteger(c, "dz")))))))
                .then(Commands.literal("cancel").requires(SettleCommands::op)
                        .then(Commands.argument("uid", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                            int uid = IntegerArgumentType.getInteger(c, "uid");
                            col.s.cancel(uid);
                            col.map.removeBuilding(uid);
                            return "Отменено здание #" + uid;
                        }))))
                .then(Commands.literal("assign").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                .then(Commands.argument("uid", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                                    int w = IntegerArgumentType.getInteger(c, "worker"), uid = IntegerArgumentType.getInteger(c, "uid");
                                    col.s.assign(w, uid);
                                    return "Рабочий #" + w + " отправлен в здание #" + uid;
                                })))))
                .then(Commands.literal("hauler").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                .then(Commands.argument("uid", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                                    int w = IntegerArgumentType.getInteger(c, "worker");
                                    int uid = IntegerArgumentType.getInteger(c, "uid");
                                    col.s.assignHauler(w, uid);
                                    return "Носильщик #" + w + " назначен на доставку ресурсов из здания #" + uid;
                                })))))
                .then(Commands.literal("move").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                .then(Commands.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-100000, 100000))
                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(-100000, 100000)).executes(c -> order(c, col -> {
                                            int w = IntegerArgumentType.getInteger(c, "worker");
                                            double x = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "x");
                                            double z = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "z");
                                            col.s.moveTo(w, x, z);
                                            return "Рабочий #" + w + " получил приказ перемещения";
                                        }))))))
                .then(Commands.literal("unassign").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                            int w = IntegerArgumentType.getInteger(c, "worker");
                            col.s.unassign(w);
                            return "Рабочий #" + w + " свободен";
                        }))))
                .then(Commands.literal("profession").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                .then(Commands.argument("prof", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(SettleMod.DEFS == null ? java.util.List.of() : SettleMod.DEFS.professions.keySet(), b))
                                        .executes(c -> order(c, col -> {
                                            int w = IntegerArgumentType.getInteger(c, "worker");
                                            String p = StringArgumentType.getString(c, "prof");
                                            col.s.setProfession(w, p);
                                            return "Рабочий #" + w + " теперь " + col.s.defs.professionName(p);
                                        })))))
                .then(Commands.literal("priority").requires(SettleCommands::op)
                        .then(Commands.argument("uid", IntegerArgumentType.integer(1))
                                .then(Commands.argument("n", IntegerArgumentType.integer(1, 9)).executes(c -> order(c, col -> {
                                    int uid = IntegerArgumentType.getInteger(c, "uid"), n = IntegerArgumentType.getInteger(c, "n");
                                    col.s.setPriority(uid, n);
                                    return "Приоритет здания #" + uid + ": " + n;
                                })))))
                .then(Commands.literal("pause").requires(SettleCommands::op)
                        .then(Commands.argument("uid", IntegerArgumentType.integer(1))
                                .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool()).executes(c -> order(c, col -> {
                                    int uid = IntegerArgumentType.getInteger(c, "uid");
                                    boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "on");
                                    col.s.setPaused(uid, on);
                                    return "Здание #" + uid + (on ? ": пауза" : ": работает");
                                })))))
                .then(Commands.literal("view")
                        .then(Commands.argument("kind", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("panel", "hud"), b)).executes(c -> {
                            CommandSourceStack src = c.getSource();
                            Colony col = SettleMod.nearest(src.getLevel(), src.getPosition().x, src.getPosition().z);
                            if (col == null) { src.sendFailure(Component.literal("Рядом нет поселения")); return 0; }
                            net.minecraft.server.level.ServerPlayer sp = src.getPlayer();
                            if (sp == null) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
                            String kind = StringArgumentType.getString(c, "kind");
                            SettleViews.send(sp, col, kind.equals("hud") ? SettleViews.HUD : SettleViews.PANEL);
                            return 1;
                        }).then(Commands.argument("city", StringArgumentType.word()).executes(c -> {
                            CommandSourceStack src = c.getSource();
                            Colony col = SettleMod.colony(StringArgumentType.getString(c, "city"));
                            if (col == null) { src.sendFailure(Component.literal("Нет такого поселения")); return 0; }
                            net.minecraft.server.level.ServerPlayer sp = src.getPlayer();
                            if (sp == null) { src.sendFailure(Component.literal("Только для игрока")); return 0; }
                            SettleViews.send(sp, col, StringArgumentType.getString(c, "kind").equals("hud") ? SettleViews.HUD : SettleViews.PANEL);
                            return 1;
                        }))))
                .then(Commands.literal("route").requires(SettleCommands::op)
                        .then(Commands.literal("add")
                                .then(Commands.argument("from", StringArgumentType.word())
                                        .then(Commands.argument("to", StringArgumentType.word())
                                                .then(Commands.argument("res", StringArgumentType.word())
                                                        .then(Commands.argument("rate", IntegerArgumentType.integer(1, 30)).executes(c -> {
                                                            try {
                                                                java.util.Map<String, dev.worldforge.settle.Settlement> all = new java.util.LinkedHashMap<>();
                                                                for (Colony x : SettleMod.COLONIES.values()) all.put(x.s.id, x.s);
                                                                var r = SettleMod.NETWORK.add(all, StringArgumentType.getString(c, "from"), StringArgumentType.getString(c, "to"), StringArgumentType.getString(c, "res"), IntegerArgumentType.getInteger(c, "rate"));
                                                                c.getSource().sendSuccess(() -> Component.literal("Маршрут #" + r.id + " открыт"), false);
                                                                return 1;
                                                            } catch (dev.worldforge.settle.TradeNetwork.TradeException ex) { c.getSource().sendFailure(Component.literal(ex.getMessage())); return 0; }
                                                        }))))))
                        .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            try { SettleMod.NETWORK.remove(IntegerArgumentType.getInteger(c, "id")); c.getSource().sendSuccess(() -> Component.literal("Маршрут закрыт"), false); return 1; }
                            catch (dev.worldforge.settle.TradeNetwork.TradeException ex) { c.getSource().sendFailure(Component.literal(ex.getMessage())); return 0; }
                        }))))
                .then(Commands.literal("priority-work").requires(SettleCommands::op)
                        .then(Commands.argument("worker", IntegerArgumentType.integer(1))
                                .then(Commands.argument("prof", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 4)).executes(c -> order(c, col -> {
                                            col.s.setWorkPriority(IntegerArgumentType.getInteger(c, "worker"), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "prof"), IntegerArgumentType.getInteger(c, "n"));
                                            return "Приоритет работы обновлён";
                                        }))))))
                .then(Commands.literal("tax").requires(SettleCommands::op)
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 3)).executes(c -> order(c, col -> {
                            col.s.setTax(IntegerArgumentType.getInteger(c, "level"));
                            return "Налог: " + Settlement.TAX_NAMES[col.s.taxLevel].toLowerCase();
                        }))))
                .then(Commands.literal("buy").requires(SettleCommands::op)
                        .then(Commands.argument("res", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                                    col.s.buy(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "res"), IntegerArgumentType.getInteger(c, "n"));
                                    return "Куплено";
                                })))))
                .then(Commands.literal("sell").requires(SettleCommands::op)
                        .then(Commands.argument("res", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(c -> order(c, col -> {
                                    col.s.sell(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "res"), IntegerArgumentType.getInteger(c, "n"));
                                    return "Продано";
                                })))))
                .then(Commands.literal("trade-auto").requires(SettleCommands::op)
                        .then(Commands.argument("res", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(Commands.argument("n", IntegerArgumentType.integer(0)).executes(c -> order(c, col -> {
                                            col.s.setAutoTrade(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "res"), com.mojang.brigadier.arguments.StringArgumentType.getString(c, "mode"), IntegerArgumentType.getInteger(c, "n"));
                                            return "Правило торговли обновлено";
                                        }))))))
                .then(Commands.literal("auto").requires(SettleCommands::op)
                        .then(Commands.literal("on").executes(c -> order(c, col -> { col.s.auto = true; return "Автоназначение включено"; })))
                        .then(Commands.literal("off").executes(c -> order(c, col -> { col.s.auto = false; return "Автоназначение выключено"; }))))
                .then(Commands.literal("disband").requires(SettleCommands::op).executes(c -> order(c, col -> {
                    SettleMod.COLONIES.remove(col.s.id);
                    return "Поселение " + col.s.name + " распущено";
                }))));
    }

    private static int order(CommandContext<CommandSourceStack> c, Order o) {
        CommandSourceStack source = c.getSource();
        Colony colony = SettleMod.nearest(source.getLevel(), source.getPosition().x, source.getPosition().z);
        if (colony == null) {
            source.sendFailure(Component.literal("Рядом нет поселения. Основать: /wfsettle found <имя>"));
            return 0;
        }
        String actorId = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                ? player.getUUID().toString() : "console";
        boolean operator = dev.worldforge.mod.WFPerm.trusted(source);
        if (!operator && !actorId.equals(colony.owner)) {
            source.sendFailure(Component.literal("Это поселение не ваше: приказы отдаёт его правитель"));
            return 0;
        }

        String input = c.getInput();
        String requestId = "settlement:" + actorId + ":" + source.getServer().getTickCount() + ":"
                + Integer.toUnsignedString(java.util.Objects.hash(colony.s.id, input), 16);
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "settlement.command", actorId, colony.s.id, java.util.List.of(input), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> {
                        Colony current = SettleMod.colony(req.targetId());
                        if (current == null) return "Поселение больше не существует";
                        if (!operator && !req.actorId().equals(current.owner))
                            return "Полномочия на это поселение изменились";
                        return null;
                    },
                    (req, attempt) -> {
                        Colony current = SettleMod.colony(req.targetId());
                        if (current == null) return dev.worldforge.mod.api.ActionResult.fail("Поселение больше не существует");
                        try { return dev.worldforge.mod.api.ActionResult.ok(o.run(current)); }
                        catch (Settlement.OrderException ex) { return dev.worldforge.mod.api.ActionResult.fail(ex.getMessage()); }
                    }, null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED) {
                String message = task.message();
                source.sendSuccess(() -> Component.literal(message), true);
                return 1;
            }
            source.sendFailure(Component.literal(task.message()));
            return 0;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось выполнить приказ: " + ex.getMessage()));
            return 0;
        }
    }

    private static int found(CommandSourceStack s, String name) {
        if (SettleMod.DEFS == null) {
            s.sendFailure(Component.literal("Сервер ещё не готов"));
            return 0;
        }
        ServerLevel lv = s.getLevel();
        BlockPos p = BlockPos.containing(s.getPosition());
        Colony c = SettleMod.found(lv, name.trim(), p);
        if (s.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) c.owner = player.getUUID().toString();
        s.sendSuccess(() -> Component.literal("Основано поселение " + c.s.name + " (" + c.s.id + ") в " + c.cx + ", " + c.cz + ": 4 рабочих, дерево 60, еда 40, камень 10"), true);
        return 1;
    }

    private static int list(CommandSourceStack s) {
        String dim = s.getLevel().dimension().location().toString();
        int n = 0;
        for (Colony c : SettleMod.COLONIES.values()) {
            if (!c.dim.equals(dim)) continue;
            n++;
            String line = c.s.id + ": " + c.s.name + " | центр " + c.cx + ", " + c.cz + " | рабочих " + c.s.workers.size() + " | зданий " + c.s.buildings.size();
            s.sendSuccess(() -> Component.literal(line), false);
        }
        if (n == 0) s.sendSuccess(() -> Component.literal("В этом измерении нет поселений"), false);
        return n;
    }

    private static int info(CommandSourceStack s) {
        Colony c = SettleMod.nearest(s.getLevel(), s.getPosition().x, s.getPosition().z);
        if (c == null) {
            s.sendFailure(Component.literal("Рядом нет поселения"));
            return 0;
        }
        Settlement st = c.s;
        send(s, "== " + st.name + " (" + st.id + ") | центр " + c.cx + ", " + c.cz + " | жилья " + st.housing() + " | авто " + (st.auto ? "да" : "нет"));
        StringBuilder sb = new StringBuilder("Склад:");
        for (Map.Entry<String, Double> e : st.stock.entrySet()) {
            sb.append(' ').append(st.defs.resourceNames.getOrDefault(e.getKey(), e.getKey())).append(' ').append(Math.round(e.getValue())).append('/').append(Math.round(st.capacity(e.getKey()))).append(';');
        }
        send(s, sb.toString());
        for (Building b : st.buildings) {
            send(s, "  здание #" + b.uid + " " + b.def.name + " [" + b.state.name().toLowerCase() + "] смещение " + b.x + ", " + b.z + " приоритет " + b.priority + " - " + b.status);
        }
        for (Worker w : st.workers) {
            send(s, "  рабочий #" + w.id + " " + w.name + " (" + st.defs.professionName(w.profession) + ") " + (w.building == 0 ? "" : "-> #" + w.building + " ") + w.status);
        }
        int from = Math.max(0, st.log.size() - 5);
        for (int i = from; i < st.log.size(); i++) send(s, "  > " + st.log.get(i));
        return 1;
    }

    private static void send(CommandSourceStack s, String line) { s.sendSuccess(() -> Component.literal(line), false); }

    private static int build(CommandContext<CommandSourceStack> c, Integer dx, Integer dz) {
        String def = StringArgumentType.getString(c, "def");
        CommandSourceStack s = c.getSource();
        int px = (int) Math.floor(s.getPosition().x), pz = (int) Math.floor(s.getPosition().z);
        return order(c, col -> {
            int x = dx != null ? dx : px - col.cx, z = dz != null ? dz : pz - col.cz;   // без аргументов - там, где стоит игрок
            ServerLevel lv = col.level(c.getSource().getServer());
            if (lv != null) col.captureTerrain(lv, x - 16, z - 16, x + 16, z + 16);
            dev.worldforge.settle.Defs.Building definition = SettleMod.DEFS.buildings.get(def);
            if (definition != null && !col.map.cells().isEmpty())
                col.map.validatePlacement(definition.category, x, z, definition.sizeX, definition.sizeZ);
            Building b = col.s.place(def, x, z);
            if (!col.map.cells().isEmpty()) col.map.planBuilding(b);
            return "Заложено: " + b.def.name + " #" + b.uid + " (смещение " + x + ", " + z + ")";
        });
    }
}
