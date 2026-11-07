package dev.worldforge.people.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Courts;
import dev.worldforge.people.Role;
import dev.worldforge.people.Succession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** /wfpeople court <государство> | org | who | kill <id> (права 2). */
final class PeopleCommands {
    private PeopleCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfpeople").requires(s -> s.hasPermission(2))
                .then(Commands.literal("court").then(Commands.argument("realm", StringArgumentType.greedyString()).executes(c -> court(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("org").then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("org", StringArgumentType.word()).executes(c -> setOrg(c.getSource(), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "org"))))))
                .then(Commands.literal("who").executes(c -> who(c.getSource())))
                .then(Commands.literal("kill").then(Commands.argument("id", StringArgumentType.word()).executes(c -> kill(c.getSource(), StringArgumentType.getString(c, "id"))))));
    }

    private static void say(CommandSourceStack s, String line) {
        s.sendSuccess(() -> Component.literal(line), false);
    }

    private static MapData map(CommandSourceStack s) {
        MapData m = EditorManager.dataFor(s.getLevel());
        if (m == null || m.titles == null) {
            s.sendFailure(Component.literal("В этом измерении нет политической карты"));
            return null;
        }
        return m;
    }

    private static int court(CommandSourceStack s, String q) {
        MapData m = map(s);
        if (m == null) return 0;
        CharacterRegistry reg = PeopleMod.registry(m);
        TitleData.Realm r = m.titles.findRealm(q);
        if (r == null) {
            s.sendFailure(Component.literal("Нет государства '" + q + "'"));
            return 0;
        }
        say(s, "Двор: " + r.name);
        int n = 0;
        for (Role role : PeopleMod.ROLES.all()) {
            if (!role.per.equals("realm")) continue;
            for (Character c : reg.holders(role.id, Courts.realmPlace(r.id))) {
                n++;
                String extra = "";
                if (!c.spouse.isEmpty() && reg.get(c.spouse) != null) extra += ", супруг(а): " + PeopleMod.label(reg.get(c.spouse));
                if (!c.children.isEmpty()) extra += ", детей: " + c.children.size();
                say(s, role.name + ": " + c.name + (c.house.isEmpty() ? "" : " " + c.house) + " [" + c.id + "], " + (PeopleMod.YEAR - c.birthYear) + " лет" + extra);
            }
        }
        if (n == 0) say(s, "(пусто)");
        return n;
    }

    private static int who(CommandSourceStack s) throws CommandSyntaxException {
        MapData m = map(s);
        if (m == null) return 0;
        ServerPlayer p = s.getPlayerOrException();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        WFNpc best = null;
        double bestScore = -1e9;
        for (WFNpc n : p.serverLevel().getEntitiesOfClass(WFNpc.class, p.getBoundingBox().inflate(32))) {
            Vec3 to = n.position().add(0, 1, 0).subtract(eye);
            double dist = to.length();
            double dot = dist < 1e-6 ? 1 : look.dot(to.scale(1 / dist));
            double score = (dot > 0.95 ? 1000 : 0) - dist;               // сначала тот, на кого смотрим, иначе ближайший
            if (score > bestScore) { bestScore = score; best = n; }
        }
        if (best == null) {
            s.sendFailure(Component.literal("Рядом нет людей (32 блока)"));
            return 0;
        }
        CharacterRegistry reg = PeopleMod.registry(m);
        Character c = reg.get(best.characterId());
        if (c == null) {
            s.sendFailure(Component.literal("Тело без персонажа (id '" + best.characterId() + "')"));
            return 0;
        }
        describe(s, m.titles, reg, c);
        return 1;
    }

    private static void describe(CommandSourceStack s, TitleData d, CharacterRegistry reg, Character c) {
        say(s, PeopleMod.label(c) + " [" + c.id + "]" + (c.alive ? "" : " (мёртв)"));
        say(s, "Пол: " + c.gender + ", возраст: " + (PeopleMod.YEAR - c.birthYear) + ", культура: " + c.culture + ", дом: " + (c.house.isEmpty() ? "-" : c.house));
        for (Character.Post po : c.posts) {
            Role r = PeopleMod.ROLES.get(po.role);
            say(s, "Должность: " + (r == null ? po.role : r.name) + " @ " + placeName(d, po.place));
        }
        if (!c.spouse.isEmpty() && reg.get(c.spouse) != null) say(s, "Супруг(а): " + PeopleMod.label(reg.get(c.spouse)));
        if (!c.children.isEmpty()) say(s, "Детей: " + c.children.size());
        if (!c.traits.isEmpty()) say(s, "Черты: " + String.join(", ", c.traits));
    }

    private static String placeName(TitleData d, String place) {
        try {
            if (place.startsWith("realm:")) {
                TitleData.Realm r = d.realms.get(place.substring(6));
                if (r != null) return r.name;
            } else if (place.startsWith("title:")) {
                TitleData.Title t = d.get(Integer.parseInt(place.substring(6)));
                if (t != null) return t.name;
            }
        } catch (NumberFormatException ignored) { }
        return place;
    }

    private static int kill(CommandSourceStack s, String id) {
        MapData m = map(s);
        if (m == null) return 0;
        CharacterRegistry reg = PeopleMod.registry(m);
        Character c = reg.get(id);
        if (c == null || !c.alive) {
            s.sendFailure(Component.literal(c == null ? "Нет персонажа " + id : c.name + " уже мёртв"));
            return 0;
        }
        String name = PeopleMod.label(c);
        List<Succession.Result> res;
        try {
            res = Succession.onDeath(reg, PeopleMod.ROLES, m.titles, EditorManager.editorFor(m), PeopleMod.NAMES, PeopleMod.seed(m), id, PeopleMod.YEAR, PeopleMod.LAW);
        } catch (RuntimeException ex) {
            s.sendFailure(Component.literal("Ошибка: " + ex.getMessage()));
            return 0;
        }
        say(s, name + " умер(ла)");
        List<String> heirs = new ArrayList<>();
        for (Succession.Result r : res) {
            heirs.add(r.heir.name + (r.heir.house.isEmpty() ? "" : " " + r.heir.house) + " [" + r.heir.id + "] -> " + placeName(m.titles, r.place) + (r.usurper ? " (узурпатор)" : ""));
        }
        for (String h : heirs) say(s, "Наследник: " + h);
        int refill = Courts.ensure(m.titles, EditorManager.editorFor(m), reg, PeopleMod.ROLES, PeopleMod.NAMES, PeopleMod.seed(m), PeopleMod.YEAR);
        if (refill > 0) say(s, "Освободившиеся должности заполнены: новых людей " + refill);
        return 1;
    }

    /** /wfpeople org <id> <организация|none>: делает персонажа представителем организации (диалог wf_orders:master). */
    private static int setOrg(CommandSourceStack s, String id, String org) {
        Character c = PeopleMod.findNpc(id);
        if (c == null) { s.sendFailure(Component.literal("Нет персонажа " + id)); return 0; }
        if (org.equals("none")) c.props.remove("org"); else c.props.put("org", org);
        s.sendSuccess(() -> Component.literal(c.name + (org.equals("none") ? ": больше не представитель" : ": представитель " + org)), true);
        return 1;
    }
}
