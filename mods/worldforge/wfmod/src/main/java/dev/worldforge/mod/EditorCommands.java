package dev.worldforge.mod;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.worldforge.mod.api.WorldForgeApi;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** /worldforge editor ... — режим редактора для оператора в творческом режиме. */
public final class EditorCommands {
    private EditorCommands() {}

    private interface Action {
        int run(ServerPlayer p, MapData m, TitleEditor ed, EditorManager.Session s) throws TitleEditor.EditException;
    }

    private static final class Failure extends RuntimeException {
        Failure(String m) { super(m); }
    }

    private static int exec(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean needsOn, Action a) throws CommandSyntaxException {
        if (!needsOn) return execDirect(c, needsOn, a);
        ServerPlayer player = c.getSource().getPlayerOrException();
        long minute = WorldForgeApi.clock().worldMinute();
        String actor = player.getUUID().toString();
        String requestId = "editor:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + player.server.getTickCount() + ":" + c.getInput())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String target = player.serverLevel().dimension().location().toString();
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "worldforge.editor_mutation", actor, target, List.of(c.getInput()), minute);
        try {
            var task = WorldForgeApi.actionExecution().execute(request,
                    req -> !EditorManager.canEdit(player) || !EditorManager.session(player).on
                            ? "Полномочия редактора отозваны до исполнения" : null,
                    (req, attempt) -> {
                        try {
                            return execDirect(c, needsOn, a) == 1
                                    ? dev.worldforge.mod.api.ActionResult.ok("Изменение карты выполнено")
                                    : dev.worldforge.mod.api.ActionResult.fail("Изменение карты отклонено");
                        } catch (CommandSyntaxException ex) {
                            return dev.worldforge.mod.api.ActionResult.fail(ex.getMessage());
                        }
                    }, null, minute, WorldForgeMod.actionExecutionFile(player.server));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                c.getSource().sendFailure(Component.literal("Команда не выполнена: " + task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            c.getSource().sendFailure(Component.literal("Не удалось принять команду: " + ex.getMessage()));
            return 0;
        }
    }

    private static int execDirect(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean needsOn, Action a) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        ServerPlayer p = src.getPlayerOrException();
        if (!EditorManager.canEdit(p)) {
            src.sendFailure(Component.literal("Редактор доступен операторам (уровень 2) в творческом режиме"));
            return 0;
        }
        EditorManager.Session s = EditorManager.session(p);
        if (needsOn && !s.on) {
            src.sendFailure(Component.literal("Сначала включите режим: /worldforge editor on"));
            return 0;
        }
        MapData m = EditorManager.dataFor(p.serverLevel());
        TitleEditor ed = EditorManager.editorFor(m);
        if (ed == null) {
            src.sendFailure(Component.literal("В этом измерении нет карты с титулами (нужны provinces.png и titles.json)"));
            return 0;
        }
        if (needsOn && EditorManager.mutationsBlocked(m)) {
            src.sendFailure(Component.literal("Правки этой карты заблокированы после ошибки сохранения; требуется перезапуск и сверка"));
            return 0;
        }
        try {
            int before = ed.modCount();
            int r = a.run(p, m, ed, s);
            if (ed.modCount() != before) {
                EditorManager.touch(m);
                EditorManager.saveDirty(p.server);
                if (ed.isDirty()) {
                    EditorManager.blockMutations(m);
                    throw new Failure("Изменение карты не удалось надёжно сохранить; операция отмечена как неуспешная");
                }
            }
            return r;
        } catch (TitleEditor.EditException | Failure e) {
            src.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static void ok(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GREEN));
    }

    private static int tierOf(MapData m, String q) {
        for (TitleData.Tier t : m.titles.tiers) {
            if (t.key.equalsIgnoreCase(q) || t.name.equalsIgnoreCase(q) || Integer.toString(t.index).equals(q)) return t.index;
        }
        throw new Failure("Неизвестный уровень '" + q + "'. Есть: " + tierKeys(m));
    }

    private static List<String> tierKeys(MapData m) {
        List<String> out = new ArrayList<>();
        for (TitleData.Tier t : m.titles.tiers) out.add(t.key);
        return out;
    }

    /** Провинция, с которой работаем: выбранная, иначе под ногами. */
    private static TitleData.Title province(ServerPlayer p, MapData m, EditorManager.Session s) {
        TitleData.Title t = m.titles.get(s.province);
        if (t == null) t = m.provinceAt(p.getX(), p.getZ());
        if (t == null) throw new Failure("Выберите провинцию (Shift+ПКМ жезлом или /worldforge editor select)");
        return t;
    }

    private static TitleData.Title target(ServerPlayer p, MapData m, EditorManager.Session s, String tierName) {
        int tier = tierOf(m, tierName);
        TitleData.Title prov = province(p, m, s);
        TitleData.Title t = m.titles.ancestor(prov, tier);
        if (t == null) throw new Failure("У " + prov.name + " нет титула уровня " + tierName);
        return t;
    }

    private static int parseColor(String s) {
        String h = s.startsWith("#") ? s.substring(1) : s;
        try {
            if (h.length() != 6) throw new NumberFormatException();
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            throw new Failure("Цвет задаётся как #rrggbb, например #c0392b");
        }
    }

    private static Object parseValue(String v) {
        if (v.equals("-") || v.equalsIgnoreCase("null")) return null;
        if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static final SuggestionProvider<CommandSourceStack> TIERS = (c, b) -> {
        try {
            MapData m = EditorManager.dataFor(c.getSource().getPlayerOrException().serverLevel());
            if (m != null && m.titles != null) for (String k : tierKeys(m)) if (k.startsWith(b.getRemaining().toLowerCase())) b.suggest(k);
        } catch (CommandSyntaxException ignored) {
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> REALMS = (c, b) -> {
        try {
            MapData m = EditorManager.dataFor(c.getSource().getPlayerOrException().serverLevel());
            if (m != null && m.titles != null) for (TitleData.Realm r : m.titles.realmList()) if (r.id.startsWith(b.getRemaining().toLowerCase())) b.suggest(r.id);
        } catch (CommandSyntaxException ignored) {
        }
        return b.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> RELATIONS = (c, b) -> {
        try {
            MapData m = EditorManager.dataFor(c.getSource().getPlayerOrException().serverLevel());
            if (m != null && m.titles != null) for (String k : m.titles.relationTypes.keySet()) b.suggest(k);
        } catch (CommandSyntaxException ignored) {
        }
        return b.buildFuture();
    };

    private static RequiredArgumentBuilder<CommandSourceStack, String> tier() {
        return Commands.argument("tier", StringArgumentType.word()).suggests(TIERS);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> realmId(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests(REALMS);
    }

    private static String str(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, String n) {
        return StringArgumentType.getString(c, n);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("editor")
                .then(Commands.literal("on").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    s.on = true;
                    ok(p, "Редактор включён. Жезл: /worldforge editor wand. Уровень границ: " + m.titles.tiers.get(Math.min(s.tier, m.titles.tiers.size() - 1)).key);
                    return 1;
                })))
                .then(Commands.literal("off").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    s.on = false;
                    int saved = EditorManager.saveDirty(ServerLifecycleHooks.getCurrentServer());
                    ok(p, "Редактор выключен" + (saved > 0 ? ", правки сохранены" : ""));
                    return 1;
                })))
                .then(Commands.literal("wand").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    if (!p.getInventory().add(EditorManager.makeWand())) p.drop(EditorManager.makeWand(), false);
                    ok(p, "Выдан жезл картографа. ПКМ: закрасить; Shift+ПКМ/ЛКМ: выбрать провинцию");
                    return 1;
                })))
                .then(Commands.literal("level").then(tier().executes(c -> exec(c, false, (p, m, ed, s) -> {
                    s.tier = tierOf(m, str(c, "tier"));
                    ok(p, "Показываются границы уровня: " + m.titles.tiers.get(s.tier).name);
                    return 1;
                }))))
                .then(Commands.literal("brush").then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1, 400)).executes(c -> exec(c, false, (p, m, ed, s) -> {
                    s.brush = DoubleArgumentType.getDouble(c, "blocks");
                    ok(p, "Радиус кисти: " + s.brush + " блоков (разрешение карты ≈ " + String.format("%.1f", m.bpp) + " блока на пиксель)");
                    return 1;
                }))))
                .then(Commands.literal("select").executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = m.provinceAt(p.getX(), p.getZ());
                    if (t == null) throw new Failure("Под вами нет провинции");
                    s.province = t.id;
                    p.sendSystemMessage(EditorEvents.describe(m, t));
                    return 1;
                })).then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = m.titles.findTitle(str(c, "name"));
                    if (t == null || t.tier != 0) throw new Failure("Нет провинции '" + str(c, "name") + "'");
                    s.province = t.id;
                    p.sendSystemMessage(EditorEvents.describe(m, t));
                    return 1;
                }))))
                .then(Commands.literal("at")                       // то же, что жезл, но по координатам: так редактор работает из окна карты
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .then(Commands.literal("pick").executes(c -> exec(c, true, (p, m, ed, s) -> {
                                    EditorEvents.select(p, s, m, DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "z"));
                                    return 1;
                                })))
                                .then(Commands.literal("apply").executes(c -> exec(c, true, (p, m, ed, s) -> {
                                    return EditorEvents.applyTool(p, s, m, ed,
                                            DoubleArgumentType.getDouble(c, "x"), DoubleArgumentType.getDouble(c, "z")) ? 1 : 0;
                                }))))))
                .then(Commands.literal("info").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    TitleData.Title t = province(p, m, s);
                    p.sendSystemMessage(EditorEvents.describe(m, t));
                    TitleData.Realm r = m.titles.realmOf(t);
                    p.sendSystemMessage(Component.literal("Государство: " + (r == null ? "нет" : r.name) + "; площадь провинции " + t.areaBlocks / 1000 + " тыс. блоков²; соседей " + t.neighbors.length
                            + "; отмен доступно " + ed.undoDepth() + (ed.isDirty() ? "; есть несохранённые правки" : "")));
                    return 1;
                })))
                .then(Commands.literal("undo").executes(c -> exec(c, true, (p, m, ed, s) -> {
                    if (!ed.undo()) throw new Failure("Нечего отменять");
                    ok(p, "Отменено (осталось отмен: " + ed.undoDepth() + ")");
                    return 1;
                })))
                .then(Commands.literal("redo").executes(c -> exec(c, true, (p, m, ed, s) -> {
                    if (!ed.redo()) throw new Failure("Нечего повторять");
                    ok(p, "Повторено (осталось повторов: " + ed.redoDepth() + ")");
                    return 1;
                })))
                .then(Commands.literal("validate").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    List<String> issues = TitleValidator.check(m.titles);
                    if (issues.isEmpty()) ok(p, "Проблем не найдено");
                    else {
                        p.sendSystemMessage(Component.literal("Найдено замечаний: " + issues.size()).withStyle(ChatFormatting.YELLOW));
                        for (int i = 0; i < Math.min(15, issues.size()); i++) p.sendSystemMessage(Component.literal(" - " + issues.get(i)).withStyle(ChatFormatting.GRAY));
                        if (issues.size() > 15) p.sendSystemMessage(Component.literal(" ... и ещё " + (issues.size() - 15)).withStyle(ChatFormatting.DARK_GRAY));
                    }
                    return issues.size();
                })))
                .then(Commands.literal("tool")
                        .then(Commands.literal("brush").executes(c -> exec(c, true, (p, m, ed, s) -> { s.tool = "brush"; ok(p, "Инструмент: кисть (закрасить выбранной провинцией)"); return 1; })))
                        .then(Commands.literal("smooth").executes(c -> exec(c, true, (p, m, ed, s) -> { s.tool = "smooth"; ok(p, "Инструмент: сглаживание границ (ПКМ)"); return 1; })))
                        .then(Commands.literal("assign").then(tier().executes(c -> exec(c, true, (p, m, ed, s) -> {
                            TitleData.Title t = target(p, m, s, str(c, "tier"));
                            if (t.tier == 0) throw new Failure("Для назначения выберите уровень выше баронства");
                            s.tool = "assign";
                            s.assignTitle = t.id;
                            ok(p, "Инструмент: назначение. ПКМ по любой провинции переносит её под " + t.name);
                            return 1;
                        })))))
                .then(Commands.literal("save").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    int n = EditorManager.saveDirty(ServerLifecycleHooks.getCurrentServer());
                    ok(p, n > 0 ? "Правки сохранены в папку мира" : "Нет несохранённых правок");
                    return 1;
                })))
                .then(Commands.literal("refresh").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    EditorManager.touch(m);
                    EditorManager.refreshMaps(m);
                    ok(p, "Карты-предметы перерисованы");
                    return 1;
                })))
                .then(Commands.literal("export").executes(c -> exec(c, false, (p, m, ed, s) -> {
                    try {
                        TitleStore.save(m, EditorManager.exportDir(m));
                    } catch (IOException e) {
                        throw new Failure("Не удалось записать: " + e.getMessage());
                    }
                    ok(p, "Выгружено в " + EditorManager.exportDir(m) + " (импорт: worldforge import-edits)");
                    return 1;
                })))
                .then(Commands.literal("rename").then(tier().then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    ed.rename(t.id, str(c, "name"));
                    ok(p, "Переименовано: " + str(c, "name"));
                    return 1;
                })))))
                .then(Commands.literal("color").then(tier().then(Commands.argument("hex", StringArgumentType.word()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    ed.recolor(t.id, parseColor(str(c, "hex")));
                    ok(p, "Цвет " + t.name + " изменён");
                    return 1;
                })))))
                .then(Commands.literal("move").then(tier().then(Commands.argument("parent", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    TitleData.Title np = m.titles.findTitle(str(c, "parent"));
                    if (np == null) throw new Failure("Нет титула '" + str(c, "parent") + "'");
                    ed.setParent(t.id, np.id);
                    ok(p, t.name + " теперь входит в " + np.name);
                    return 1;
                })))))
                .then(Commands.literal("new").then(tier().then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    int tier = tierOf(m, str(c, "tier"));
                    TitleData.Title cur = province(p, m, s);
                    TitleData.Title up = tier + 1 < m.titles.tiers.size() ? m.titles.ancestor(cur, tier + 1) : null;
                    int rgb = 0x808080 | (int) (Math.random() * 0x7F7F7F);
                    TitleData.Title t = ed.createTitle(tier, str(c, "name"), up == null ? 0 : up.id, rgb);
                    if (tier == 0) s.province = t.id;
                    ok(p, "Создан титул " + t.name + (tier == 0 ? " (пустая провинция, закрасьте её жезлом)" : " внутри " + (up == null ? "никого" : up.name)));
                    return 1;
                })))))
                .then(Commands.literal("delete").then(tier().executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    String name = t.name;
                    if (t.tier == 0) s.province = 0;
                    ed.deleteTitle(t.id);
                    ok(p, "Удалено: " + name);
                    return 1;
                }))))
                .then(Commands.literal("capital").then(tier().executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    ed.setCapitalAtBlock(t.id, p.getX(), p.getZ());
                    ok(p, "Столица " + t.name + " перенесена сюда");
                    return 1;
                }))))
                .then(Commands.literal("prop").then(tier().then(Commands.argument("key", StringArgumentType.word()).then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(c -> exec(c, true, (p, m, ed, s) -> {
                            TitleData.Title t = target(p, m, s, str(c, "tier"));
                            ed.setProp(t.id, str(c, "key"), parseValue(str(c, "value")));
                            ok(p, "Свойство " + str(c, "key") + " задано для " + t.name + " (значение '-' удаляет)");
                            return 1;
                        }))))))
                .then(realm());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> realm() {
        return Commands.literal("realm")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Realm r = ed.createRealm(str(c, "name"), 0x808080 | (int) (Math.random() * 0x7F7F7F));
                    ok(p, "Создано государство '" + r.id + "'. Передайте ему титул: /worldforge editor realm give " + r.id + " <уровень>");
                    return 1;
                }))))
                .then(Commands.literal("delete").then(realmId("id").executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.deleteRealm(str(c, "id"));
                    ok(p, "Государство удалено");
                    return 1;
                }))))
                .then(Commands.literal("rename").then(realmId("id").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setRealmName(str(c, "id"), str(c, "name"));
                    ok(p, "Переименовано");
                    return 1;
                })))))
                .then(Commands.literal("ruler").then(realmId("id").then(Commands.argument("who", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setRuler(str(c, "id"), str(c, "who"));
                    ok(p, "Правитель задан");
                    return 1;
                })))))
                .then(Commands.literal("color").then(realmId("id").then(Commands.argument("hex", StringArgumentType.word()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.recolorRealm(str(c, "id"), parseColor(str(c, "hex")));
                    ok(p, "Цвет изменён");
                    return 1;
                })))))
                .then(Commands.literal("give").then(realmId("id").then(tier().executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    ed.give(str(c, "id"), t.id);
                    ok(p, t.name + " передан государству " + str(c, "id"));
                    return 1;
                })))))
                .then(Commands.literal("take").then(realmId("id").then(tier().executes(c -> exec(c, true, (p, m, ed, s) -> {
                    TitleData.Title t = target(p, m, s, str(c, "tier"));
                    ed.take(str(c, "id"), t.id);
                    ok(p, t.name + " отобран у государства " + str(c, "id"));
                    return 1;
                })))))
                .then(Commands.literal("overlord").then(realmId("id").then(Commands.literal("none").executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setOverlord(str(c, "id"), null, null);
                    ok(p, "Государство стало независимым");
                    return 1;
                }))).then(Commands.argument("lord", StringArgumentType.word()).suggests(REALMS).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setOverlord(str(c, "id"), str(c, "lord"), null);
                    ok(p, "Сюзерен назначен");
                    return 1;
                })).then(Commands.argument("relation", StringArgumentType.word()).suggests(RELATIONS).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setOverlord(str(c, "id"), str(c, "lord"), str(c, "relation"));
                    ok(p, "Сюзерен назначен (" + m.titles.relationLabel(str(c, "relation")) + ")");
                    return 1;
                }))))))
                .then(Commands.literal("relation").then(realmId("id").then(Commands.argument("type", StringArgumentType.word()).suggests(RELATIONS).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setRelation(str(c, "id"), str(c, "type"));
                    ok(p, "Тип отношений изменён");
                    return 1;
                })))))
                .then(Commands.literal("relparam").then(Commands.argument("type", StringArgumentType.word()).suggests(RELATIONS)
                        .then(Commands.argument("param", StringArgumentType.word()).then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                            ed.setRelationParam(str(c, "type"), str(c, "param"), parseValue(str(c, "value")));
                            ok(p, "Параметр " + str(c, "param") + " типа " + str(c, "type") + " изменён");
                            return 1;
                        }))))))
                .then(Commands.literal("prop").then(realmId("id").then(Commands.argument("key", StringArgumentType.word()).then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> exec(c, true, (p, m, ed, s) -> {
                    ed.setRealmProp(str(c, "id"), str(c, "key"), parseValue(str(c, "value")));
                    ok(p, "Свойство задано");
                    return 1;
                }))))));
    }
}
