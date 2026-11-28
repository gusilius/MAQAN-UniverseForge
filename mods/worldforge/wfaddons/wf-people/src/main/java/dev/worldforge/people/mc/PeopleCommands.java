package dev.worldforge.people.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** /wfpeople court <государство> | org | who | kill <id> (права 2). */
final class PeopleCommands {
    private PeopleCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("wfpeople")
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                        .executes(c -> spawnOwned(c.getSource(),
                                                StringArgumentType.getString(c, "name"),
                                                IntegerArgumentType.getInteger(c, "count"))))))
                .then(Commands.literal("court").requires(s -> s.hasPermission(2)).then(Commands.argument("realm", StringArgumentType.greedyString()).executes(c -> court(c.getSource(), StringArgumentType.getString(c, "realm")))))
                .then(Commands.literal("org").requires(s -> s.hasPermission(2)).then(Commands.argument("id", StringArgumentType.word()).then(Commands.argument("org", StringArgumentType.word()).executes(c -> setOrg(c.getSource(), StringArgumentType.getString(c, "id"), StringArgumentType.getString(c, "org"))))))
                .then(Commands.literal("who").requires(s -> s.hasPermission(2)).executes(c -> who(c.getSource())))
                .then(Commands.literal("kill").requires(s -> s.hasPermission(2)).then(Commands.argument("id", StringArgumentType.word()).executes(c -> kill(c.getSource(), StringArgumentType.getString(c, "id"))))));
    }

    /**
     * Creative NPC authoring: creates canonical Characters and their physical WFNpc bodies.
     * No political title, settlement or pre-existing village is required. The same Character
     * registry/WAL and body binding path is used as for generated people.
     */
    private static int spawnOwned(CommandSourceStack source,String requestedName,int count) throws CommandSyntaxException {
        ServerPlayer player=source.getPlayerOrException();
        if(!player.isCreative()){
            source.sendFailure(Component.literal("Создание произвольных NPC доступно в творческом режиме."));
            return 0;
        }
        String baseName=requestedName==null?"":requestedName.trim();
        if(baseName.isBlank()||baseName.length()>64){
            source.sendFailure(Component.literal("Имя NPC должно содержать от 1 до 64 символов."));
            return 0;
        }
        var level=player.serverLevel();
        CharacterRegistry registry=PeopleMod.registryFor(level);
        if(registry==null||!PeopleMod.playerRegistryAvailable()){
            source.sendFailure(Component.literal("Реестр персонажей этого измерения не готов; NPC не создавались."));
            return 0;
        }
        List<WFNpc> prepared=new ArrayList<>(count);
        List<Character> characters=new ArrayList<>(count);
        String owner=player.getUUID().toString();
        for(int i=0;i<count;i++){
            int ring=i/12;
            double angle=(i%12)*Math.PI/6.0+ring*0.37;
            double radius=2.5+ring*2.0;
            int x=(int)Math.floor(player.getX()+Math.cos(angle)*radius);
            int z=(int)Math.floor(player.getZ()+Math.sin(angle)*radius);
            if(!level.hasChunkAt(new BlockPos(x,level.getMinBuildHeight()+1,z))){
                for(WFNpc body:prepared)body.discard();
                source.sendFailure(Component.literal("Создание отменено: зона появления выходит за загруженные чанки."));
                return 0;
            }
            int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            if(y<=level.getMinBuildHeight()+1||y>=level.getMaxBuildHeight()-2){
                for(WFNpc body:prepared)body.discard();
                source.sendFailure(Component.literal("Не найдена безопасная поверхность для NPC."));
                return 0;
            }
            String name=count==1?baseName:baseName+" "+(i+1);
            Character character=registry.create(name,"","","m",PeopleMod.YEAR);
            character.props.put("npc.owner_uuid",owner);
            character.props.put("npc.origin","creative_spawn");
            CharacterRegistry.recordCharacterMutation(character);
            WFNpc body=PeopleMod.NPC.get().create(level);
            if(body==null){
                character.alive=false;
                PeopleMod.persistCharacter(character,source.getServer());
                for(WFNpc old:prepared)old.discard();
                source.sendFailure(Component.literal("Не удалось создать тело NPC; операция остановлена."));
                return 0;
            }
            body.moveTo(x+0.5,y,z+0.5,player.getYRot(),0);
            body.bind(character);
            body.setPost(x,z);
            if(!registry.bindEntity(character.id,"npc",body.getUUID().toString())){
                body.discard();
                character.alive=false;
                PeopleMod.persistCharacter(character,source.getServer());
                for(WFNpc old:prepared)old.discard();
                source.sendFailure(Component.literal("Не удалось закрепить тело за Character; операция остановлена."));
                return 0;
            }
            characters.add(character);
            prepared.add(body);
        }
        int spawned=0;
        for(int i=0;i<prepared.size();i++){
            WFNpc body=prepared.get(i);
            Character character=characters.get(i);
            if(!level.addFreshEntity(body)){
                body.discard();
                registry.unbindEntity(character.id,"npc",body.getUUID().toString());
                character.alive=false;
                CharacterRegistry.recordCharacterMutation(character);
                for(int j=i+1;j<prepared.size();j++){
                    prepared.get(j).discard();
                    Character pending=characters.get(j);
                    registry.unbindEntity(pending.id,"npc",prepared.get(j).getUUID().toString());
                    pending.alive=false;
                    CharacterRegistry.recordCharacterMutation(pending);
                }
                for(int j=0;j<characters.size();j++)PeopleMod.persistCharacter(characters.get(j),source.getServer());
                source.sendFailure(Component.literal("Мир отклонил создание NPC; незаспавненные записи помечены недействующими."));
                return spawned;
            }
            spawned++;
        }
        boolean durable=true;
        for(Character character:characters)durable&=PeopleMod.persistCharacter(character,source.getServer());
        if(!durable){
            source.sendFailure(Component.literal("NPC появились, но WAL не подтвердил все записи. Не продолжайте массовое создание до проверки сохранения."));
            return spawned;
        }
        final int spawnedCount=spawned;
        source.sendSuccess(()->Component.literal("Создано NPC: "+spawnedCount+" · Character ID и тела связаны · владелец: "+player.getGameProfile().getName()),true);
        return spawnedCount;
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
        return mutate(s, "people.character_death", id, "kill:" + id, () -> killDirect(s, id));
    }

    private static int killDirect(CommandSourceStack s, String id) {
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
    private static int mutate(CommandSourceStack source, String actionId, String target, String payload,
                               java.util.function.IntSupplier action) {
        String actor = source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : source.getTextName();
        long minute = dev.worldforge.mod.api.WorldForgeApi.clock().worldMinute();
        String requestId = "people:" + java.util.UUID.nameUUIDFromBytes(
                (actor + ":" + source.getServer().getTickCount() + ":" + actionId + ":" + target + ":" + payload)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = new dev.worldforge.mod.api.ActionExecutionService.Request(
                requestId, actionId, actor, target, List.of(payload), minute);
        try {
            var task = dev.worldforge.mod.api.WorldForgeApi.actionExecution().execute(request,
                    req -> source.hasPermission(2) ? null : "Недостаточно полномочий для изменения персонажа",
                    (req, attempt) -> action.getAsInt() == 1
                            ? dev.worldforge.mod.api.ActionResult.ok("Изменение персонажа выполнено")
                            : dev.worldforge.mod.api.ActionResult.fail("Изменение персонажа отклонено"),
                    null, minute, dev.worldforge.mod.WorldForgeMod.actionExecutionFile(source.getServer()));
            if (task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.FAILED)
                source.sendFailure(Component.literal(task.message()));
            return task.status() == dev.worldforge.mod.api.ActionExecutionService.Status.SUCCEEDED ? 1 : 0;
        } catch (RuntimeException ex) {
            source.sendFailure(Component.literal("Не удалось выполнить изменение персонажа: " + ex.getMessage()));
            return 0;
        }
    }

    private static int setOrg(CommandSourceStack s, String id, String org) {
        return mutate(s, "people.assign_organization", id, "org:" + org, () -> setOrgDirect(s, id, org));
    }

    private static int setOrgDirect(CommandSourceStack s, String id, String org) {
        Character resolved = PeopleMod.findNpc(id);
        if (resolved == null) { s.sendFailure(Component.literal("Нет персонажа " + id)); return 0; }
        CharacterRegistry owner = PeopleMod.authority().registryForGlobal(resolved.globalId);
        if (owner == null) { s.sendFailure(Component.literal("Не найден реестр персонажа " + id)); return 0; }
        try (dev.worldforge.people.CharacterAccessService.Lease lease =
                     owner.access().acquireGlobal(resolved.globalId)) {
            if (lease == null) { s.sendFailure(Component.literal("Персонаж временно недоступен " + id)); return 0; }
            Character character = lease.get();
            if (org.equals("none")) character.props.remove("org");
            else character.props.put("org", org);
            CharacterRegistry.recordCharacterMutation(character);
            if (!PeopleMod.persistCharacter(character, s.getServer())) {
                s.sendFailure(Component.literal("Изменение не подтверждено WAL; повторите команду позже."));
                return 0;
            }
            String name = character.name;
            s.sendSuccess(() -> Component.literal(name + (org.equals("none")
                    ? ": больше не представитель" : ": представитель " + org)), true);
            return 1;
        }
    }
}
