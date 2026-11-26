package dev.worldforge.people.mc;

import dev.worldforge.mod.EditorManager;
import dev.worldforge.mod.MapData;
import dev.worldforge.mod.TitleData;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
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
    private static final int COURT_RECONCILE_EVERY_PASSES = 6;
    private static final Map<MapData, Long> LAST_COURT_RECONCILE_PASS = new WeakHashMap<>();
    private static long passCounter;

    static void clearRuntimeState() {
        LAST_COURT_RECONCILE_PASS.clear();
        passCounter = 0;
    }

    private record Target(String characterId, Role role, String spawnRoleId, double x, double z, double dist2) {}
    private record SpawnAnchor(double x, double z, List<Target> targets) {}

    /** One title/realm traversal per level pass; each player queries only nearby spatial cells. */
    private static final class TargetIndex {
        private final NpcSpawnSpatialIndex<SpawnAnchor> cells = new NpcSpawnSpatialIndex<>();
        private final Map<String, SpawnAnchor> anchors = new LinkedHashMap<>();

        void add(String characterId, Role role, double x, double z) {
            if (characterId == null || characterId.isBlank()) return;
            String key = Math.round(x) + ":" + Math.round(z);
            SpawnAnchor anchor = anchors.computeIfAbsent(key, ignored -> {
                SpawnAnchor created = new SpawnAnchor(x, z, new ArrayList<>());
                cells.add(created, x, z);
                return created;
            });
            anchor.targets().add(new Target(characterId, role, role.id, x, z, 0));
        }

        List<Target> nearby(double x, double z, double radius) {
            List<Target> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            double radius2 = radius * radius;
            for (SpawnAnchor anchor : cells.query(x, z, radius)) {
                double distance = sq(anchor.x() - x, anchor.z() - z);
                if (distance > radius2) continue;
                for (Target target : anchor.targets()) {
                    if (seen.add(target.characterId()))
                        result.add(new Target(target.characterId(), target.role(), target.spawnRoleId(), target.x(), target.z(), distance));
                }
            }
            return result;
        }
    }

    private NpcSpawner() {}

    static void pass(MinecraftServer server) {
        long currentPass = ++passCounter;
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = new ArrayList<>();
            for (ServerPlayer p : level.players()) if (!p.isSpectator()) players.add(p);
            if (players.isEmpty()) continue;
            MapData map = EditorManager.dataFor(level);
            if (map == null || map.titles == null) continue;
            CharacterRegistry registry = PeopleMod.registry(map);
            if (registry == null) continue;
            long lastCourtPass = LAST_COURT_RECONCILE_PASS.getOrDefault(map, Long.MIN_VALUE);
            if (lastCourtPass == Long.MIN_VALUE || currentPass - lastCourtPass >= COURT_RECONCILE_EVERY_PASSES) {
                try {
                    Courts.ensure(map.titles, EditorManager.editorFor(map), registry,
                            PeopleMod.ROLES, PeopleMod.NAMES, PeopleMod.seed(map), PeopleMod.YEAR);
                    LAST_COURT_RECONCILE_PASS.put(map, currentPass);
                } catch (Exception ex) {
                    PeopleMod.LOGGER.error("wf_people: Courts.ensure: {}", ex.toString());
                }
            }

            // Physical people are not generated from political title tiers.
            // VanillaVillageAdapter adopts villagers already present in a confirmed
            // Minecraft village and binds them to canonical Character IDs.
            // This hard gate prevents realm/barony population from becoming thousands of entities.
        }
    }

    private static TargetIndex buildTargetIndex(ServerLevel level, MapData map, CharacterRegistry registry) {
        TitleData titles = map.titles;
        Map<String, List<Role>> rolesByPlace = new HashMap<>();
        for (Role role : PeopleMod.ROLES.all())
            rolesByPlace.computeIfAbsent(role.per, ignored -> new ArrayList<>()).add(role);
        Map<Integer, List<Role>> rolesByTier = new HashMap<>();
        Map<String, Integer> tiers = PeopleMod.tierIndex(titles);
        for (Map.Entry<String, Integer> tier : tiers.entrySet()) {
            List<Role> roles = rolesByPlace.get(tier.getKey());
            if (roles != null) rolesByTier.put(tier.getValue(), roles);
        }

        // Resolve eligible role/place anchors first. The previous implementation called
        // registry.holders() for every title/role pair; holders() scans the entire population.
        Map<NpcSpawnTargetLookup.RolePlace, NpcSpawnTargetLookup.Location> locations = new HashMap<>();
        String dimension = level.dimension().location().toString();
        for (TitleData.Realm realm : titles.realmList()) {
            TitleData.Title capital = realmCapital(titles, realm);
            if (capital == null || !SimulationLod.shouldSpawnPeople(dimension, capital.capX, capital.capZ)) continue;
            List<Role> roles = rolesByPlace.get("realm");
            if (roles == null) continue;
            String place = Courts.realmPlace(realm.id);
            for (Role role : roles)
                locations.putIfAbsent(new NpcSpawnTargetLookup.RolePlace(role.id, place),
                        new NpcSpawnTargetLookup.Location(role, capital.capX, capital.capZ));
        }
        for (TitleData.Title title : titles.allTitles()) {
            List<Role> roles = rolesByTier.get(title.tier);
            if (roles == null || !SimulationLod.shouldSpawnPeople(dimension, title.capX, title.capZ)) continue;
            String place = Courts.titlePlace(title.id);
            for (Role role : roles)
                locations.putIfAbsent(new NpcSpawnTargetLookup.RolePlace(role.id, place),
                        new NpcSpawnTargetLookup.Location(role, title.capX, title.capZ));
        }
        // Resolve only indexed holders for the eligible role/place pairs.
        TargetIndex index = new TargetIndex();
        for (NpcSpawnTargetLookup.Candidate candidate : NpcSpawnTargetLookup.collect(registry, locations))
            index.add(candidate.characterId(), candidate.role(), candidate.x(), candidate.z());
        return index;
    }

    private static void spawnFor(ServerLevel level, CharacterRegistry registry, ServerPlayer player,
                                 Map<String, WFNpc> bodies, TargetIndex index) {
        double px = player.getX(), pz = player.getZ();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Target target : index.nearby(px, pz, NEAR))
            addTargetAndFamily(targets, registry, target);

        List<Target> list = new ArrayList<>(targets.values());
        list.sort(Comparator.comparingDouble(Target::dist2));
        int near = 0;
        for (WFNpc body : bodies.values())
            if (sq(body.getX() - px, body.getZ() - pz) <= FAR * FAR) near++;
        for (Target target : list) {
            if (near >= PER_PLAYER) break;
            if (bodies.containsKey(target.characterId())) continue;
            try (CharacterAccessService.Lease lease = registry.access().acquire(target.characterId())) {
                if (lease == null || !lease.get().alive) continue;
                WFNpc npc = spawn(level, target, lease.get());
                if (npc != null) {
                    bodies.put(target.characterId(), npc);
                    near++;
                }
            }
        }
    }

    /** Store only IDs in the target set; leases cover every read of the canonical records. */
    private static void addTargetAndFamily(Map<String, Target> targets, CharacterRegistry registry, Target head) {
        targets.putIfAbsent(head.characterId(), head);
        if (!head.role().family) return;
        try (CharacterAccessService.Lease lease = registry.access().acquire(head.characterId())) {
            if (lease == null || !lease.get().alive) return;
            Character person = lease.get();
            String spouseId = person.spouse;
            if (spouseId != null && !spouseId.isBlank()) {
                try (CharacterAccessService.Lease spouseLease = registry.access().acquire(spouseId)) {
                    if (spouseLease != null && spouseLease.get().alive) {
                        Character spouse = spouseLease.get();
                        targets.putIfAbsent(spouse.id, new Target(spouse.id, head.role(),
                                head.role().id + ".family", head.x(), head.z(), head.dist2() + 0.01));
                    }
                }
            }
            for (String childId : person.children) {
                try (CharacterAccessService.Lease childLease = registry.access().acquire(childId)) {
                    if (childLease != null && childLease.get().alive) {
                        Character child = childLease.get();
                        targets.putIfAbsent(child.id, new Target(child.id, head.role(),
                                head.role().id + ".family", head.x(), head.z(), head.dist2() + 0.02));
                    }
                }
            }
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

    private static WFNpc spawn(ServerLevel level, Target t, Character character) {
        Random rnd = new Random(character.id.hashCode() * 31L + t.spawnRoleId().hashCode());
        double ang = rnd.nextDouble() * Math.PI * 2;
        double rad = t.role().noble ? 2 + rnd.nextDouble() * 5 : 4 + rnd.nextDouble() * 16;
        int x = (int) Math.floor(t.x() + Math.cos(ang) * rad), z = (int) Math.floor(t.z() + Math.sin(ang) * rad);
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight() + 1, z))) return null;        // не грузим чанки ради людей
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        BlockState below = level.getBlockState(new BlockPos(x, y - 1, z));
        if (!below.getFluidState().isEmpty() || below.is(BlockTags.LEAVES) || below.isAir()) return null;
        WFNpc npc = PeopleMod.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360F, 0F);
        npc.bind(character);
        npc.setPost(x, z);
        if (!level.addFreshEntity(npc)) {
            npc.discard();
            return null;
        }
        if (!regBind(level, character, npc)) return null;
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
