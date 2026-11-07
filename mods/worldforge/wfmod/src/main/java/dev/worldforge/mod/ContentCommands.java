package dev.worldforge.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.worldforge.mod.api.ContentApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * /wfcontent: общий редактор пользовательского содержимого (организации, квесты, навыки, угрозы...). Доступен всем игрокам, но каждое
 * действие проверяет {@link ContentApi#permission}: оператору можно всё, остальным - по праву и в пределах лимита.
 */
final class ContentCommands {
    private ContentCommands() {}

    static Path file(MinecraftServer s) { return s.getWorldPath(LevelResource.ROOT).resolve("worldforge").resolve("wf_custom.json"); }

    static void load(MinecraftServer s) {
        try { Path f = file(s); if (Files.exists(f)) ContentApi.fromJson(Files.readString(f)); }
        catch (Exception e) { WorldForgeMod.LOGGER.error("WorldForge: не удалось прочитать wf_custom.json", e); }
        for (String bad : ContentApi.reapply()) WorldForgeMod.LOGGER.warn("WorldForge: пользовательский контент пропущен: {}", bad);
    }

    static void save(MinecraftServer s) {
        try { Path f = file(s); Files.createDirectories(f.getParent()); Files.writeString(f, ContentApi.toJson()); }
        catch (Exception e) { WorldForgeMod.LOGGER.error("WorldForge: не удалось сохранить wf_custom.json", e); }
    }

    private static String who(CommandSourceStack s) { return s.getPlayer() == null ? "console" : s.getPlayer().getUUID().toString(); }

    private static int result(CommandSourceStack s, String err, String ok) {
        if (err != null) { s.sendFailure(Component.literal(err)); return 0; }
        save(s.getServer());
        s.sendSuccess(() -> Component.literal(ok), false);
        return 1;
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("wfcontent")
                .then(Commands.literal("kinds").executes(c -> { c.getSource().sendSuccess(() -> Component.literal("Виды: " + ContentApi.kinds()), false); return 1; }))
                .then(Commands.literal("list").then(Commands.argument("kind", StringArgumentType.word()).executes(c -> {
                    List<ContentApi.Entry> l = ContentApi.list(StringArgumentType.getString(c, "kind"));
                    StringBuilder sb = new StringBuilder("Записей: " + l.size());
                    for (ContentApi.Entry e : l) sb.append("\n ").append(e.id()).append(e.owner().equals("console") ? "" : " (автор " + e.owner().substring(0, Math.min(8, e.owner().length())) + ")");
                    c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                })))
                .then(Commands.literal("show").then(Commands.argument("kind", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word()).executes(c -> {
                    ContentApi.Entry e = ContentApi.get(StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"));
                    if (e == null) { c.getSource().sendFailure(Component.literal("Нет такой записи")); return 0; }
                    c.getSource().sendSuccess(() -> Component.literal(e.json()), false);
                    return 1;
                }))))
                .then(Commands.literal("new").then(Commands.argument("kind", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> create(c.getSource(), StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"), ""))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "name")))))))
                .then(Commands.literal("json").then(Commands.argument("kind", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("json", StringArgumentType.greedyString()).executes(c ->
                        result(c.getSource(), ContentApi.put(StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "json"), who(c.getSource()), WFPerm.trusted(c.getSource())), "Сохранено"))))))
                .then(Commands.literal("set").then(Commands.argument("kind", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("field", StringArgumentType.word()).then(Commands.argument("value", StringArgumentType.greedyString()).executes(c ->
                        result(c.getSource(), ContentApi.set(StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "field"), StringArgumentType.getString(c, "value"), who(c.getSource()), WFPerm.trusted(c.getSource())), "Изменено")))))))
                .then(Commands.literal("remove").then(Commands.argument("kind", StringArgumentType.word()).then(Commands.argument("id", StringArgumentType.word()).executes(c ->
                        result(c.getSource(), ContentApi.remove(StringArgumentType.getString(c, "kind"), StringArgumentType.getString(c, "id"), who(c.getSource()), WFPerm.trusted(c.getSource())), "Удалено")))))
                .then(Commands.literal("reload").requires(WFPerm::trusted).executes(c -> {
                    List<String> bad = ContentApi.reapply();
                    c.getSource().sendSuccess(() -> Component.literal("Применено заново" + (bad.isEmpty() ? "" : ", с ошибками: " + bad)), false);
                    return 1;
                }));
    }

    private static int create(CommandSourceStack s, String kind, String id, String name) {
        return result(s, ContentApi.create(kind, id, name.isEmpty() ? id : name, who(s), WFPerm.trusted(s)), "Создано " + kind + "/" + id + ". Правьте: /wfcontent set " + kind + " " + id + " <поле> <значение> или /wfcontent json ...");
    }
}
