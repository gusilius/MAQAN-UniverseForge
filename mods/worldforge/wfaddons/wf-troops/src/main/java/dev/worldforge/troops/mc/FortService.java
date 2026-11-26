package dev.worldforge.troops.mc;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.worldforge.troops.Formations;
import dev.worldforge.troops.Forts;
import dev.worldforge.troops.Squads;
import dev.worldforge.troops.TroopDefs;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Осады: раз в секунду считает урон по стенам, огонь башен и захват; команды /wftroops fort ... */
final class FortService {
    private FortService() {}

    static ServerLevel level(MinecraftServer server, String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim)));
    }

    static void step(MinecraftServer server, int ticks) {
        for (Forts.Fort f : new ArrayList<>(TroopsMod.FORTS.all())) {
            ServerLevel lv = level(server, f.dim);
            if (lv == null || !lv.hasChunkAt(BlockPos.containing(f.x, 64, f.z))) continue;
            double r = f.radius + 40;
            List<TroopEntity> troops = lv.getEntitiesOfClass(TroopEntity.class, new AABB(f.x - r, lv.getMinBuildHeight(), f.z - r, f.x + r, lv.getMaxBuildHeight(), f.z + r));
            double siege = 0;
            int attackers = 0, defenders = 0;
            TroopEntity captor = null;
            List<TroopEntity> hostile = new ArrayList<>();
            for (TroopEntity t : troops) {
                if (!t.isAlive()) continue;
                if (t.fortId == f.id) { defenders++; continue; }
                if (!f.hostileTo(t.ownerId(), t.realm)) continue;
                double d = Math.hypot(t.getX() - f.x, t.getZ() - f.z);
                if (d <= f.radius + 12) hostile.add(t);
                if (d <= f.radius) { attackers++; captor = t; }
                if (t.def() != null && t.def().siege() > 0) {
                    double reach = t.def().range() >= 6 ? t.def().range() : 4.0;           // таран и пехота бьют вплотную, катапульта издалека
                    if (d <= f.radius + reach) siege += t.def().siege();
                }
            }
            Forts.Tick tk = TroopsMod.FORTS.tick(f, siege, attackers, defenders, !hostile.isEmpty(), 1.0);
            if (tk.towerDps() > 0 && f.towers > 0) {
                hostile.sort(Comparator.comparingDouble(t -> Math.hypot(t.getX() - f.x, t.getZ() - f.z)));
                float each = (float) (tk.towerDps() / f.towers);
                for (int i = 0; i < f.towers && i < hostile.size(); i++) hostile.get(i).hurt(lv.damageSources().generic(), each);
            }
            if (tk.wallFell()) tell(lv, f, "Стены «" + f.name + "» пали! Путь в крепость открыт.");
            if (tk.captured() && captor != null) {
                TroopsMod.FORTS.capture(f, captor.ownerId(), captor.realm);
                tell(lv, f, "«" + f.name + "» захвачена!");
            }
            if (ticks % 60 == 0) ring(lv, f);
        }
    }

    private static void tell(ServerLevel lv, Forts.Fort f, String msg) {
        for (ServerPlayer p : lv.players()) if (Math.hypot(p.getX() - f.x, p.getZ() - f.z) < 250) p.displayClientMessage(Component.literal(msg), false);
    }

    /** Граница крепости частицами: целая стена - огоньки, рухнувшая - дым. */
    private static void ring(ServerLevel lv, Forts.Fort f) {
        boolean near = false;
        for (ServerPlayer p : lv.players()) if (Math.hypot(p.getX() - f.x, p.getZ() - f.z) < 120) { near = true; break; }
        if (!near) return;
        int n = Math.max(12, (int) (2 * Math.PI * f.radius / 5));
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n, x = f.x + Math.cos(a) * f.radius, z = f.z + Math.sin(a) * f.radius;
            double y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)) + 1.2;
            lv.sendParticles(f.breached() ? ParticleTypes.SMOKE : ParticleTypes.END_ROD, x, y, z, 1, 0.1, 0.4, 0.1, 0.0);
        }
    }

    // ---- команды ----
    static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return Commands.literal("fort").requires(s -> s.hasPermission(2))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(6, 64)).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "radius"), 2, ""))
                                .then(Commands.argument("towers", IntegerArgumentType.integer(0, Forts.MAX_TOWERS)).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "radius"), IntegerArgumentType.getInteger(c, "towers"), ""))
                                        .then(Commands.argument("realm", StringArgumentType.word()).executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "radius"), IntegerArgumentType.getInteger(c, "towers"), StringArgumentType.getString(c, "realm"))))))))
                .then(Commands.literal("neutral").then(Commands.argument("name", StringArgumentType.string()).then(Commands.argument("radius", DoubleArgumentType.doubleArg(6, 64))
                        .executes(c -> create(c.getSource(), StringArgumentType.getString(c, "name"), DoubleArgumentType.getDouble(c, "radius"), 2, "!")))))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                    int id = IntegerArgumentType.getInteger(c, "id");
                    return TroopsMod.executeMilitaryCommand(c.getSource(), "military.fort_remove",
                            Integer.toString(id), "remove", () -> {
                                boolean ok = TroopsMod.FORTS.remove(id);
                                c.getSource().sendSuccess(() -> Component.literal(ok ? "Крепость удалена" : "Нет такой крепости"), false);
                                return ok ? 1 : 0;
                            });
                })))
                .then(Commands.literal("garrison").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.argument("type", StringArgumentType.word())
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, Squads.MAX_SIZE)).executes(c -> garrison(c.getSource(), IntegerArgumentType.getInteger(c, "id"), StringArgumentType.getString(c, "type"), IntegerArgumentType.getInteger(c, "n")))))));
    }

    private static int create(CommandSourceStack src, String name, double radius, int towers, String realm) {
        return TroopsMod.executeMilitaryCommand(src, "military.fort_create", name,
                radius + "|" + towers + "|" + realm, () -> createDirect(src, name, radius, towers, realm));
    }

    private static int createDirect(CommandSourceStack src, String name, double radius, int towers, String realm) {
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception ex) {
            src.sendFailure(Component.literal("Только для игрока"));
            return 0;
        }
        boolean neutral = realm.equals("!");
        Forts.Fort f = TroopsMod.FORTS.create(name, neutral ? "" : p.getUUID().toString(), neutral ? "" : realm, p.level().dimension().location().toString(), p.getX(), p.getZ(), radius, towers);
        src.sendSuccess(() -> Component.literal("Крепость #" + f.id + " «" + f.name + "»: радиус " + (int) f.radius + ", стена " + (int) f.wallMax + ", башен " + f.towers + (neutral ? " (бандитская: враг всем)" : "")
                + ". Гарнизон: /wftroops fort garrison " + f.id + " <тип> <число>"), false);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        if (TroopsMod.FORTS.all().isEmpty()) { src.sendSuccess(() -> Component.literal("Крепостей нет"), false); return 1; }
        for (Forts.Fort f : TroopsMod.FORTS.all()) {
            src.sendSuccess(() -> Component.literal("#" + f.id + " «" + f.name + "» " + (int) f.x + ", " + (int) f.z + " r=" + (int) f.radius + ": стена " + (int) f.wall + "/" + (int) f.wallMax
                    + ", башен " + f.towers + ", гарнизон " + f.garrison + (f.capture > 0 ? ", захват " + (int) (f.capture * 100) + "%" : "") + (f.owner.isEmpty() && f.realm.isEmpty() ? ", ничья" : f.realm.isEmpty() ? "" : ", " + f.realm)), false);
        }
        return TroopsMod.FORTS.all().size();
    }

    private static int garrison(CommandSourceStack src, int id, String type, int n) {
        return TroopsMod.executeMilitaryCommand(src, "military.fort_garrison", Integer.toString(id),
                type + "|" + n, () -> garrisonDirect(src, id, type, n));
    }

    private static int garrisonDirect(CommandSourceStack src, int id, String type, int n) {
        Forts.Fort f = TroopsMod.FORTS.get(id);
        TroopDefs.Unit u = TroopsMod.DEFS.units.get(type);
        if (f == null) { src.sendFailure(Component.literal("Нет крепости " + id)); return 0; }
        if (u == null) { src.sendFailure(Component.literal("Нет такого типа: " + type)); return 0; }
        ServerLevel lv = level(src.getServer(), f.dim);
        if (lv == null) return 0;
        String owner = f.owner.isEmpty() ? "fort:" + f.id : f.owner;
        List<TroopEntity> made = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Formations.Slot> slots = Formations.slots(Formations.Type.CIRCLE, n, 1.5);
        List<double[]> pos = Formations.place(slots, f.x, f.z, 0);
        for (int i = 0; i < n; i++) {
            TroopEntity t = TroopsMod.TROOP.get().create(lv);
            if (t == null) continue;
            int x = (int) Math.floor(pos.get(i)[0]), z = (int) Math.floor(pos.get(i)[1]);
            t.moveTo(pos.get(i)[0], lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), pos.get(i)[1], 0, 0);
            t.setup(u, owner);
            t.realm = f.realm;
            t.fortId = f.id;
            made.add(t);
            ids.add(t.getUUID().toString());
        }
        if (made.isEmpty()) {
            src.sendFailure(Component.literal("Не удалось создать ни одного бойца гарнизона; состав не изменён."));
            return 0;
        }
        Squads.Squad s = TroopsMod.SQUADS.create(owner, "Гарнизон " + f.name, ids);
        s.realm = f.realm; s.stance = Squads.Stance.HOLD; s.cx = f.x; s.cz = f.z;
        for (TroopEntity t : made) {
            t.setSquadId(s.id);
            if (!lv.addFreshEntity(t)) {
                for (TroopEntity prepared : made) prepared.discard();
                TroopsMod.SQUADS.disband(s.id);
                TroopsMod.save();
                src.sendFailure(Component.literal("Мир отклонил создание гарнизона; подготовленные бойцы и отряд отменены."));
                return 0;
            }
        }
        TroopsMod.save();
        String quantity = made.size() == n ? "+" + made.size() : "+" + made.size() + " из " + n;
        src.sendSuccess(() -> Component.literal("Гарнизон «" + f.name + "»: " + quantity + " × " + u.name()), false);
        return made.size();
    }
}
