package dev.worldforge.court.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** /wfcourt [государство] | play <id> | play realm <государство> | release. */
final class CourtCommands {
    private CourtCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfcourt")
                .executes(c -> openCourt(c.getSource(), ""))
                .then(Commands.literal("play").requires(dev.worldforge.mod.WFPerm::trusted)
                        .then(Commands.literal("realm").then(Commands.argument("realm", StringArgumentType.word()).executes(c -> play(c.getSource(), StringArgumentType.getString(c, "realm"), true))))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(c -> play(c.getSource(), StringArgumentType.getString(c, "id"), false))))
                .then(Commands.literal("release").executes(c -> release(c.getSource())))
                .then(Commands.argument("realm", StringArgumentType.word()).executes(c -> openCourt(c.getSource(), StringArgumentType.getString(c, "realm")))));
    }

    private static void say(CommandSourceStack s, String line) { s.sendSuccess(() -> Component.literal(line), false); }

    private static MapData map(CommandSourceStack s) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null || m.titles == null) { s.sendFailure(Component.literal("В этом измерении нет политической карты")); return null; }
        return m;
    }

    /** Окно двора: государство по аргументу или то, на чьей земле стоит игрок. */
    private static int openCourt(CommandSourceStack s, String realm) {
        net.minecraft.server.level.ServerPlayer sp = s.getPlayer();
        if (sp == null) { s.sendFailure(net.minecraft.network.chat.Component.literal("Только для игрока")); return 0; }
        dev.worldforge.mod.MapData m = dev.worldforge.mod.EditorManager.dataFor(s.getLevel());
        dev.worldforge.court.CourtSim sim = m == null || m.titles == null ? null : CourtMod.court(m);
        if (sim == null) { s.sendFailure(net.minecraft.network.chat.Component.literal("В этом измерении нет политической карты")); return 0; }
        if (realm.isEmpty()) {
            var pr = m.provinceAt(sp.getX(), sp.getZ());
            var re = pr == null ? null : m.titles.realmOf(pr);
            if (re != null) realm = re.id;
        }
        if (m.titles.realms.get(realm) == null) { s.sendFailure(net.minecraft.network.chat.Component.literal("Нет государства: " + realm)); return 0; }
        String[] v = sim.viewJson(realm, sp.getGameProfile().getName());
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("realm", realm); p.put("start", sim.startYear);
        p.put("realm_projections", dev.worldforge.mod.RealmProjectionCodec.encode(m.titles));
        p.put("roles", new java.util.ArrayList<Object>(dev.worldforge.people.mc.PeopleMod.ROLES_RAW));
        p.put("events", new java.util.ArrayList<Object>(sim.eventSources()));
        p.put("reg", v[0]); p.put("sim", v[1]);
        dev.worldforge.mod.WFNet.sendView(sp, "wf_court:court", dev.worldforge.mod.Json.write(p));
        return 1;
    }

    private static int play(CommandSourceStack s, String q, boolean realm) {
        ServerPlayer player = s.getPlayer();
        if (player == null) return 0;
        String actor = player.getUUID().toString();
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "court-play:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + s.getServer().getTickCount() + ":" + q + ":" + realm)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "court.play_character", actor, q, java.util.List.of(Boolean.toString(realm)), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> dev.worldforge.mod.WFPerm.trusted(s)
                            && s.getServer().getPlayerList().getPlayer(player.getUUID()) != null
                            ? null : "Недостаточно полномочий или игрок отключился",
                    (req, attempt) -> playDirect(s, q, realm) == 1
                            ? dev.worldforge.mod.api.ActionResult.ok("Персонаж выбран")
                            : dev.worldforge.mod.api.ActionResult.fail("Не удалось выбрать персонажа"),
                    null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(s.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                s.sendFailure(Component.literal(task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось выполнить приказ: " + ex.getMessage()));
            return 0;
        }
    }

    private static int playDirect(CommandSourceStack s, String q, boolean realm) {
        MapData m = map(s);
        ServerPlayer sp = s.getPlayer();
        if (m == null || sp == null) return 0;
        dev.worldforge.court.CourtSim sim = CourtMod.court(m);
        try {
            String id = q;
            if (realm) {
                TitleData.Realm r = m.titles.findRealm(q);
                Character k = r == null ? null : sim.ruler(r.id);
                if (k == null) { s.sendFailure(Component.literal("У государства '" + q + "' нет правителя")); return 0; }
                id = k.id;
            }
            Character c = sim.play(sp.getGameProfile().getName(), id);
            say(s, "Вы ведёте " + c.name + " [" + c.id + "]");
            return 1;
        } catch (IllegalArgumentException ex) {
            s.sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
    }

    private static int release(CommandSourceStack s) {
        ServerPlayer player = s.getPlayer();
        if (player == null) return 0;
        String actor = player.getUUID().toString();
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "court-release:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + s.getServer().getTickCount())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, "court.release_character", actor, "player-session", java.util.List.of(), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> s.getServer().getPlayerList().getPlayer(player.getUUID()) != null
                            ? null : "Игрок отключился до исполнения команды",
                    (req, attempt) -> releaseDirect(s) == 1
                            ? dev.worldforge.mod.api.ActionResult.ok("Управление персонажем завершено")
                            : dev.worldforge.mod.api.ActionResult.fail("Не удалось завершить управление персонажем"),
                    null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(s.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                s.sendFailure(Component.literal(task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Не удалось выполнить команду: " + ex.getMessage()));
            return 0;
        }
    }

    private static int releaseDirect(CommandSourceStack s) {
        MapData m = map(s);
        ServerPlayer sp = s.getPlayer();
        if (m == null || sp == null) return 0;
        CourtMod.court(m).release(sp.getGameProfile().getName());
        say(s, "Вы больше никого не ведёте");
        return 1;
    }
}
