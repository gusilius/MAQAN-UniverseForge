package dev.worldforge.people.mc;

import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.Courts;
import dev.worldforge.people.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.*;

/** Появление тел: раз в SPAWN_EVERY тиков рядом с игроком в карте. Реестр - истина, тела - лишь картинка. */
final class NpcSpawner {
    static final double NEAR = SimulationLod.PEOPLE_SPAWN_RADIUS, FAR = SimulationLod.PEOPLE_RETAIN_RADIUS;
    static final int PER_PLAYER = 24;

    private NpcSpawner() {}

    private record Target(Character c, String roleId, boolean noble, double x, double z, double dist2) {}

    static void pass(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer p : level.players()) if (!p.isSpectator()) players.add(p);
            if (players.isEmpty()) continue;
            MapData m = EditorManager.dataFor(level);
            if (m == null || m.titles == null) continue;
            CharacterRegistry reg = PeopleMod.registry(m);
            if (reg == null) continue;
            try {
                Courts.ensure(m.titles, EditorManager.editorFor(m), reg, PeopleMod.ROLES, PeopleMod.NAMES, PeopleMod.seed(m), PeopleMod.YEAR);   // заполняет освободившиеся должности
            } catch (Exception ex) {
                PeopleMod.LOGGER.error("wf_people: Courts.ensure: {}", ex.toString());
            }
            Map<String, WFNpc> bodies = new LinkedHashMap<>();                 // id персонажа -> тело
            for (ServerPlayer p : players) {
                for (WFNpc b : level.getEntitiesOfClass(WFNpc.class, p.getBoundingBox().inflate(FAR + 40))) {
                    if (b.isRemoved()) continue;
                    String id = b.characterId();
                    if (id.isEmpty() || bodies.containsKey(id) && bodies.get(id) != b) b.discard();    // дубликат или бесхозное тело
                    else bodies.put(id, b);
                }
            }
            for (ServerPlayer p : players) spawnFor(level, m, reg, p, bodies);
        }
    }

    private static void spawnFor(ServerLevel level, MapData m, CharacterRegistry reg, ServerPlayer p, Map<String, WFNpc> bodies) {
        TitleData d = m.titles;
        Map<String, Integer> tiers = PeopleMod.tierIndex(d);
        Map<String, List<Role>> perRoles = new HashMap<>();
        for (Role r : PeopleMod.ROLES.all()) perRoles.computeIfAbsent(r.per, k -> new ArrayList<>()).add(r);
        double px = p.getX(), pz = p.getZ();
        Map<String, Target> targets = new LinkedHashMap<>();

        for (TitleData.Realm realm : d.realmList()) {                          // придворные при столице государства
            List<Role> roles = perRoles.get("realm");
            if (roles == null) break;
            TitleData.Title cap = realmCapital(d, realm);
            if (cap == null) continue;
            double d2 = sq(cap.capX - px, cap.capZ - pz);
            if (!SimulationLod.shouldSpawnPeople(level.dimension().location().toString(), cap.capX, cap.capZ)) continue;
            for (Role r : roles) for (Character c : reg.holders(r.id, Courts.realmPlace(realm.id)))
                addTargetAndFamily(targets, reg, c, r, cap.capX, cap.capZ, d2);
        }
        for (TitleData.Title t : d.allTitles()) {                              // носители должностей титулов (барон, жители...)
            String key = null;
            for (Map.Entry<String, Integer> e : tiers.entrySet()) if (e.getValue() == t.tier) key = e.getKey();
            List<Role> roles = key == null ? null : perRoles.get(key);
            if (roles == null) continue;
            double d2 = sq(t.capX - px, t.capZ - pz);
            if (!SimulationLod.shouldSpawnPeople(level.dimension().location().toString(), t.capX, t.capZ)) continue;
            for (Role r : roles) for (Character c : reg.holders(r.id, Courts.titlePlace(t.id)))
                addTargetAndFamily(targets, reg, c, r, t.capX, t.capZ, d2);
        }

        List<Target> list = new ArrayList<>(targets.values());
        list.sort(Comparator.comparingDouble(Target::dist2));
        int near = 0;
        for (WFNpc b : bodies.values()) if (sq(b.getX() - px, b.getZ() - pz) <= FAR * FAR) near++;
        for (Target t : list) {
            if (near >= PER_PLAYER) break;
            if (bodies.containsKey(t.c().id)) continue;
            WFNpc npc = spawn(level, t);
            if (npc != null) {
                bodies.put(t.c().id, npc);
                near++;
            }
        }
    }

    /** A title holder's immediate family shares the residence and becomes visible with it. */
    private static void addTargetAndFamily(Map<String, Target> targets, CharacterRegistry registry,
                                           Character head, Role role, double x, double z, double distanceSquared) {
        targets.putIfAbsent(head.id, new Target(head, role.id, role.noble, x, z, distanceSquared));
        if (!role.family) return;

        if (head.spouse != null && !head.spouse.isBlank()) {
            Character spouse = registry.get(head.spouse);
            if (spouse != null && spouse.alive)
                targets.putIfAbsent(spouse.id, new Target(spouse, role.id + ".family", role.noble, x, z, distanceSquared + 0.01));
        }
        for (String childId : head.children) {
            Character child = registry.get(childId);
            if (child != null && child.alive)
                targets.putIfAbsent(child.id, new Target(child, role.id + ".family", role.noble, x, z, distanceSquared + 0.02));
        }
    }

    private static TitleData.Title realmCapital(TitleData d, TitleData.Realm realm) {
        TitleData.Title best = null;
        for (String k : realm.titleKeys) {
            TitleData.Title t = d.byKey(k);
            if (t != null && (best == null || t.tier > best.tier)) best = t;
        }
        return best;
    }

    private static WFNpc spawn(ServerLevel level, Target t) {
        Random rnd = new Random(t.c().id.hashCode() * 31L + t.roleId().hashCode());
        double ang = rnd.nextDouble() * Math.PI * 2;
        double rad = t.noble() ? 2 + rnd.nextDouble() * 5 : 4 + rnd.nextDouble() * 16;
        int x = (int) Math.floor(t.x() + Math.cos(ang) * rad), z = (int) Math.floor(t.z() + Math.sin(ang) * rad);
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight() + 1, z))) return null;        // не грузим чанки ради людей
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        if (!below.getFluidState().isEmpty() || below.is(BlockTags.LEAVES) || below.isAir()) return null;
        WFNpc npc = PeopleMod.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360F, 0F);
        npc.bind(t.c());
        npc.setPost(x, z);
        if (!level.addFreshEntity(npc)) {
            npc.discard();
            return null;
        }
        if (!regBind(level, t.c(), npc)) return null;
        return npc;
    }

    private static boolean regBind(ServerLevel level, Character character, WFNpc npc) {
        CharacterRegistry registry = PeopleMod.peek(level);
        if (registry == null) return false;
        if (!registry.bindEntity(character.id, "npc", npc.getUUID().toString())) {
            PeopleMod.LOGGER.error("wf_people: NPC entity {} already belongs to another Character; removing duplicate body", npc.getUUID());
            npc.discard();
            return false;
        }
        return true;
    }

    private static double sq(double dx, double dz) { return dx * dx + dz * dz; }
}
