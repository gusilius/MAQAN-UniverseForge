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
