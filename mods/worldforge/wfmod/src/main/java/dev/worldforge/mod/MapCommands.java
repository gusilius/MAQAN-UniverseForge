package dev.worldforge.mod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** /worldforge marker ..., /worldforge web, /worldforge tpxz. */
public final class MapCommands {
    private MapCommands() {}

    private static MapData data(CommandSourceStack s) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null) s.sendFailure(Component.literal("В этом измерении нет карты worldforge"));
        return m;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> marker() {
        return Commands.literal("marker")
                .then(Commands.literal("add").then(Commands.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> { for (String t : Markers.TYPES) b.suggest(t); return b.buildFuture(); })
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                            CommandSourceStack s = c.getSource();
                            MapData m = data(s);
                            if (m == null) return 0;
                            try {
                                Markers.Marker mk = EditorManager.markers(s.getServer(), m).add(StringArgumentType.getString(c, "name"),
                                        StringArgumentType.getString(c, "type"), (int) s.getPosition().x, (int) s.getPosition().z, 0xFFFFFF);
                                s.sendSuccess(() -> Component.literal("Метка '" + mk.name + "' (" + mk.id + ") добавлена"), true);
                                return 1;
                            } catch (IllegalArgumentException e) {
                                s.sendFailure(Component.literal(e.getMessage()));
                                return 0;
                            }
                        }))))
                .then(Commands.literal("addwith").then(Commands.argument("type", StringArgumentType.word())
                        .suggests((c, b) -> { for (String t : Markers.TYPES) b.suggest(t); return b.buildFuture(); })
                        .then(Commands.argument("visual", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                    CommandSourceStack s = c.getSource();
                                    MapData m = data(s);
                                    if (m == null) return 0;
                                    String type = StringArgumentType.getString(c, "type");
                                    String visual = StringArgumentType.getString(c, "visual");
                                    try {
                                        Markers.Marker mk = EditorManager.markers(s.getServer(), m).add(
                                                StringArgumentType.getString(c, "name"), type,
                                                (int) s.getPosition().x, (int) s.getPosition().z, 0xFFFFFF);
                                        EditorManager.markers(s.getServer(), m).setVisualProfile(mk.id,
                                                "auto".equalsIgnoreCase(visual) ? "" : visual);
                                        s.sendSuccess(() -> Component.literal("Метка '" + mk.name
                                                + "' добавлена · визуальный профиль: "
                                                + (mk.visualProfile.isBlank() ? "автоматический" : mk.visualProfile)), true);
                                        return 1;
                                    } catch (IllegalArgumentException e) {
                                        s.sendFailure(Component.literal(e.getMessage()));
                                        return 0;
                                    }
                                })))))
                .then(Commands.literal("visual").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("profile", StringArgumentType.word()).executes(c -> {
                            CommandSourceStack s = c.getSource();
                            MapData m = data(s);
                            if (m == null) return 0;
                            String markerId = StringArgumentType.getString(c, "id");
                            String profile = StringArgumentType.getString(c, "profile");
                            if (!"auto".equalsIgnoreCase(profile)
                                    && net.minecraft.resources.ResourceLocation.tryParse(profile) == null) {
                                s.sendFailure(Component.literal("Профиль должен иметь формат namespace:id или auto"));
                                return 0;
                            }
                            boolean updated = EditorManager.markers(s.getServer(), m).setVisualProfile(markerId,
                                    "auto".equalsIgnoreCase(profile) ? "" : profile);
                            if (!updated) {
                                s.sendFailure(Component.literal("Не удалось сохранить визуальный профиль объекта"));
                                return 0;
                            }
                            s.sendSuccess(() -> Component.literal("Визуальный профиль объекта " + markerId
                                    + " обновлён: " + ("auto".equalsIgnoreCase(profile) ? "автоматический" : profile)), true);
                            return 1;
                        }))))
                .then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.greedyString()).executes(c -> {
                    CommandSourceStack s = c.getSource();
                    MapData m = data(s);
                    if (m == null) return 0;
                    boolean ok = EditorManager.markers(s.getServer(), m).remove(StringArgumentType.getString(c, "id"));
                    if (!ok) s.sendFailure(Component.literal("Нет такой метки (список: /worldforge marker list)"));
                    else s.sendSuccess(() -> Component.literal("Метка удалена"), true);
                    return ok ? 1 : 0;
                })))
                .then(Commands.literal("list").executes(c -> {
                    CommandSourceStack s = c.getSource();
                    MapData m = data(s);
                    if (m == null) return 0;
                    Markers ms = EditorManager.markers(s.getServer(), m);
                    for (Markers.Marker mk : ms.list()) s.sendSuccess(() -> Component.literal(mk.id + ": " + mk.name + " [" + mk.type + "] " + mk.x + " " + mk.z), false);
                    if (ms.list().isEmpty()) s.sendSuccess(() -> Component.literal("Меток нет"), false);
                    return ms.list().size();
                }))
                .then(Commands.literal("tp").then(Commands.argument("id", StringArgumentType.greedyString()).executes(c -> {
                    CommandSourceStack s = c.getSource();
                    MapData m = data(s);
                    if (m == null) return 0;
                    Markers.Marker mk = EditorManager.markers(s.getServer(), m).find(StringArgumentType.getString(c, "id"));
                    if (mk == null) { s.sendFailure(Component.literal("Нет такой метки")); return 0; }
                    return teleport(s, mk.x, mk.z);
                })));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> web() {
        return Commands.literal("web").executes(c -> {
            String u = WebService.url();
            c.getSource().sendSuccess(() -> Component.literal(u.isEmpty() ? "Веб-карта выключена (config/worldforge/web.json)" : "Веб-карта: " + u), false);
            return 1;
        });
    }

    public static LiteralArgumentBuilder<CommandSourceStack> tpxz() {
        return Commands.literal("tpxz").then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("z", IntegerArgumentType.integer())
                .executes(c -> teleport(c.getSource(), IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")))));
    }

    private static int teleport(CommandSourceStack s, int x, int z) throws CommandSyntaxException {
        ServerPlayer p = s.getPlayerOrException();
        ServerLevel level = s.getLevel();
        if (!(level.getChunkSource().getGenerator() instanceof MapChunkGenerator g)) return 0;
        BlockPos pos = g.surfaceAt(x, z);
        p.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p.getYRot(), p.getXRot());
        s.sendSuccess(() -> Component.literal("Телепорт: " + x + " " + z).withStyle(Style.EMPTY), false);
        return 1;
    }
}
