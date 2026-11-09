package dev.worldforge.settle.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Тело рабочего поселения. Хранит только id поселения и рабочего; всё остальное берётся из симуляции.
 * Не сохраняется в мир: тела создаёт и убирает {@link Bodies}.
 */
public class WFWorker extends PathfinderMob {
    private static final EntityDataAccessor<String> SYNC_COLONY = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SYNC_OWNER = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SYNC_PERSON = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SYNC_PROFESSION = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> SYNC_WORKER_ID = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> SYNC_BUILDING = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> SYNC_STATUS = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SYNC_WORK_AREA = SynchedEntityData.defineId(WFWorker.class, EntityDataSerializers.STRING);
    public String colonyId = "";
    public int workerId;
    private int age;

    public WFWorker(EntityType<? extends WFWorker> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setMaxUpStep(1.0F);
        setPathfindingMalus(PathType.DANGER_FIRE, 16.0F);
        setPathfindingMalus(PathType.DAMAGE_FIRE, 16.0F);
        setPathfindingMalus(PathType.DAMAGE_OTHER, 12.0F);
        setPathfindingMalus(PathType.LAVA, 16.0F);
        setPathfindingMalus(PathType.POWDER_SNOW, 8.0F);
        setPathfindingMalus(PathType.WATER, 3.0F);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.4).add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void registerGoals() {
        if (getNavigation() instanceof GroundPathNavigation g) {
            g.setCanOpenDoors(true);
            g.setCanFloat(true);
        }
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new WorkGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6.0f));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SYNC_COLONY, "");
        entityData.define(SYNC_OWNER, "");
        entityData.define(SYNC_PERSON, "");
        entityData.define(SYNC_PROFESSION, "");
        entityData.define(SYNC_WORKER_ID, 0);
        entityData.define(SYNC_BUILDING,0);
        entityData.define(SYNC_STATUS,"");
        entityData.define(SYNC_WORK_AREA,"");
    }

    public String syncedColonyId() { return entityData.get(SYNC_COLONY); }
    public String ownerId() { return entityData.get(SYNC_OWNER); }
    public String characterId() { return entityData.get(SYNC_PERSON); }
    public String professionId() { return entityData.get(SYNC_PROFESSION); }
    public int syncedWorkerId() { return entityData.get(SYNC_WORKER_ID); }
    public int assignedBuildingId() { return entityData.get(SYNC_BUILDING); }
    public String statusLabel() { return entityData.get(SYNC_STATUS); }
    public String workAreaLabel() { return entityData.get(SYNC_WORK_AREA); }

    public Colony colony() { return SettleMod.colony(colonyId); }

    public Worker worker() {
        Colony c = colony();
        return c == null ? null : c.s.worker(workerId);
    }

    public void refreshName() {
        Colony c = colony();
        if (c == null) return;
        entityData.set(SYNC_COLONY, colonyId == null ? "" : colonyId);
        entityData.set(SYNC_OWNER, c.owner == null ? "" : c.owner);
        entityData.set(SYNC_WORKER_ID, Math.max(0, workerId));
        Worker w = c.s.worker(workerId);
        if (w == null) {
            entityData.set(SYNC_PERSON, "");
            entityData.set(SYNC_PROFESSION, "");
            entityData.set(SYNC_BUILDING,0);
            entityData.set(SYNC_STATUS,"");
            entityData.set(SYNC_WORK_AREA,"");
            return;
        }
        entityData.set(SYNC_PERSON, w.person == null ? "" : w.person);
        entityData.set(SYNC_PROFESSION, w.profession == null ? "" : w.profession);
        entityData.set(SYNC_BUILDING,Math.max(0,w.building));
        entityData.set(SYNC_STATUS,w.status==null?"":w.status);
        Colony.WorkOrder area = c.workOrders.get(w.id);
        entityData.set(SYNC_WORK_AREA, area == null ? "" : area.mode + "|" + area.x + "|" + area.z + "|"
                + area.radius + "|" + area.level + "|" + area.overlay + "|" + area.halfX + "|" + area.halfZ);
        setCustomName(Component.literal(w.name + " (" + c.s.defs.professionName(w.profession) + ")"));
        setCustomNameVisible(true);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || ++age % 40 != 1) return;
        UUID live = Bodies.LIVE.get(Bodies.key(colonyId, workerId));
        if (worker() == null || !getUUID().equals(live)) {                     // лишнее или осиротевшее тело
            discard();
            return;
        }
        refreshName();
    }

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public boolean isInvulnerableTo(DamageSource src) { return !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY); }

    /** Идёт к зданию рабочего (стройка или место работы), там машет рукой, пока здание строится или работает; без здания бродит у центра. */
    static final class WorkGoal extends Goal {
        private final WFWorker w;
        private int repath, swing, idleTimer, workScan;
        private BlockPos cachedWorkTarget;
        private BlockPos idle;
        private BlockPos lastPosition;
        private int stuckTicks;
        private BlockPos cachedApproachFor;
        private BlockPos cachedApproachTarget;
        private BlockPos activeDetour;
        private BlockPos detourFor;

        WorkGoal(WFWorker w) {
            this.w = w;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() { return w.worker() != null; }

        @Override
        public boolean canContinueToUse() { return canUse(); }

        @Override
        public void tick() {
            Colony c = w.colony();
            Worker wk = w.worker();
            if (c == null || wk == null) return;
            BlockPos currentPosition = w.blockPosition();
            if (lastPosition != null && lastPosition.equals(currentPosition) && !w.getNavigation().isDone()) {
                if (++stuckTicks >= 60) {
                    w.getNavigation().stop();
                    repath = 0;
                    stuckTicks = 0;
                    wk.status = "застрял: перестраивает маршрут";
                }
            } else {
                stuckTicks = 0;
            }
            lastPosition = currentPosition;
            Building b = wk.building == 0 ? null : c.s.building(wk.building);
            BlockPos target;
            boolean working = false;
            Colony.WorkOrder area = c.workOrders.get(wk.id);
            BlockPos resourceTarget = null;
            if (area != null) {
                if (--workScan <= 0 || cachedWorkTarget == null
                        || !w.level().hasChunkAt(cachedWorkTarget)
                        || !isWorkTarget(w.level().getBlockState(cachedWorkTarget), area.mode)
                        || ("level".equals(area.mode) && cachedWorkTarget.getY() <= area.level)) {
                    cachedWorkTarget = findWorkTarget(w, c, area);
                    workScan = 30;
                }
                resourceTarget = cachedWorkTarget;
            }
            if (area != null) {
                if (resourceTarget != null) {
                    if (!resourceTarget.equals(cachedApproachFor)
                            || (cachedApproachTarget != null && !w.level().hasChunkAt(cachedApproachTarget))
                            || --repath <= 0) {
                        cachedApproachFor = resourceTarget.immutable();
                        cachedApproachTarget = workApproachTarget(w, resourceTarget);
                        if (cachedApproachTarget == null) cachedApproachTarget = placeTemporaryStep(w, c, resourceTarget);
                        repath = 20;
                    }
                    if (cachedApproachTarget == null) {
                        target = w.blockPosition();
                        wk.status = "нет прямого доступа: ищет обход или безопасную опору";
                    } else {
                        target = cachedApproachTarget;
                        working = true;
                    }
                } else {
                    BlockPos waypoint = workWaypoint(w, area);
                    if (waypoint == null) {
                        target = w.blockPosition();
                        wk.status = "участок обработан";
                    } else {
                        int tx = waypoint.getX(), tz = waypoint.getZ();
                        int ty = w.level().hasChunkAt(new BlockPos(tx, w.getBlockY(), tz))
                                ? w.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz)
                                : w.getBlockY();
                        target = new BlockPos(tx, ty, tz);
                        wk.status = "проверяет назначенный участок";
                    }
                }
            } else if (wk.manualFollow) {
                net.minecraft.server.MinecraftServer server = w.getServer();
                net.minecraft.server.level.ServerPlayer owner = null;
                if (server != null && wk.manualFollowOwner != null && !wk.manualFollowOwner.isBlank()) {
                    try { owner = server.getPlayerList().getPlayer(UUID.fromString(wk.manualFollowOwner)); }
                    catch (IllegalArgumentException ignored) { /* follow target is invalid */ }
                }
                if (owner == null || owner.serverLevel() != w.level()) {
                    w.getNavigation().stop();
                    w.setTarget(null);
                    return;
                }
                target = owner.blockPosition();
            } else if (wk.manualOrder) {
                int tx = c.cx + (int) Math.floor(wk.orderX);
                int tz = c.cz + (int) Math.floor(wk.orderZ);
                BlockPos probe = new BlockPos(tx, w.getBlockY(), tz);
                int ty = w.level().hasChunkAt(probe)
                        ? w.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz)
                        : w.getBlockY();
                target = new BlockPos(tx, ty, tz);
            } else if (b != null) {
                target = b.state == Building.State.BUILDING || b.status.startsWith("строится: укладка")
                        ? BuildingSite.builderWorkPoint((net.minecraft.server.level.ServerLevel) w.level(), c, b, w)
                        : c.workPoint(w.level(), b);
                working = b.state == Building.State.BUILDING || b.status.startsWith("строится: укладка");
            } else {
                if (idle == null || --idleTimer <= 0) {
                    idleTimer = 100 + w.random.nextInt(100);
                    int x = c.cx + w.random.nextInt(13) - 6, z = c.cz + w.random.nextInt(13) - 6;
                    idle = new BlockPos(x, w.getBlockY(), z);
                }
                target = idle;
            }
            if (activeDetour != null) {
                if (detourFor == null || !detourFor.equals(target)) {
                    activeDetour = null;
                    detourFor = null;
                } else if (w.distanceToSqr(activeDetour.getX() + 0.5, activeDetour.getY(), activeDetour.getZ() + 0.5) < 6.25) {
                    // Reached the safe waypoint; now retry the actual order destination from here.
                    activeDetour = null;
                    detourFor = null;
                    repath = 0;
                } else {
                    target = activeDetour;
                }
            }
            double targetFeetY = target.getY();
            boolean arrived = w.distanceToSqr(target.getX() + 0.5, targetFeetY, target.getZ() + 0.5) < 6.25;
            if (!arrived && --repath <= 0) {
                repath = 10;
                BlockPos route = target;
                if (w.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5) > 400
                        && w.tickCount % 40 == 0) {
                    BlockPos road = roadWaypoint(w, target);
                    if (road != null) route = road;
                }
                var path = w.getNavigation().createPath(route, 0);
                if (path == null || !path.canReach()) {
                    BlockPos detour = reachableDetour(w, target);
                    path = detour == null ? null : w.getNavigation().createPath(detour, 0);
                    if (path != null && path.canReach()) {
                        activeDetour = detour.immutable();
                        detourFor = target.immutable();
                        route = detour;
                        wk.status = "обходит препятствие по безопасному маршруту";
                    }
                }
                if (path != null && path.canReach()) {
                    w.getNavigation().moveTo(path, 1.0);
                } else {
                    // Do not push the NPC into a wall, cliff or unloaded chunk. Retry after a short
                    // cooldown so a temporary obstruction can clear without pathfinding every tick.
                    w.getNavigation().stop();
                    wk.status = "нет безопасного пути: ждёт и повторно ищет обход";
                    repath = 40;
                }
            }
            if (arrived) {
                w.getNavigation().stop();
                if (area != null && !area.complete && resourceTarget == null && workScan > 0) {
                    BlockPos waypoint = workWaypoint(w, area);
                    if (waypoint == null || w.distanceToSqr(waypoint.getX() + 0.5, w.getY(), waypoint.getZ() + 0.5) < 6.25) {
                        area.cursor++;
                        int halfX = Math.max(0, Math.min(64, area.halfX < 0 ? area.radius : area.halfX));
                        int halfZ = Math.max(0, Math.min(64, area.halfZ < 0 ? area.radius : area.halfZ));
                        int columns = halfX == 0 && halfZ == 0 ? 1 : Math.max(1, (halfX * 2 + 1 + 11) / 12);
                        int rows = halfX == 0 && halfZ == 0 ? 1 : Math.max(1, (halfZ * 2 + 1 + 11) / 12);
                        if (area.cursor >= columns * rows) {
                            if ("level".equals(area.mode) && area.soil > 0) area.cursor = 0;
                            else area.complete = true;
                        }
                        workScan = 0;
                    }
                }
                if (area != null && resourceTarget != null) {
                    if (performWork(w, c, area, resourceTarget)) {
                        cachedWorkTarget = null;
                        cachedApproachFor = null;
                        cachedApproachTarget = null;
                        workScan = 0;
                        wk.status = switch (area.mode) {
                            case "wood" -> "вырубает целое дерево";
                            case "stone" -> "добывает камень";
                            case "ore" -> "добывает руду";
                            case "clear" -> "расчищает участок";
                            case "level" -> "выравнивает участок";
                            case "harvest" -> "собирает урожай";
                            case "honey" -> "собирает мёд";
                            case "herbs" -> "собирает лекарственные растения";
                            default -> "работает на участке";
                        };
                        if (++swing >= 8) { swing = 0; w.swing(InteractionHand.MAIN_HAND); }
                    }
                } else if (area == null && wk.manualOrder) c.s.advanceManualOrder(wk.id);
                if (b != null) w.getLookControl().setLookAt(c.worldX(b) + 0.5, w.getEyeY(), c.worldZ(b) + 0.5);
                if (working && ++swing >= 15) {
                    swing = 0;
                    w.swing(InteractionHand.MAIN_HAND);
                }
            }
        }

        /**
         * If the requested tile is unreachable, search a small ring of safe, standable alternatives.
         * The detour must remain close to the order destination and have a path the vanilla navigator
         * can actually follow; this is a fallback, not permission to tunnel or jump across gaps.
         */
        private static BlockPos reachableDetour(WFWorker worker, BlockPos goal) {
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int radius = 1; radius <= 4; radius++) {
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = goal.offset(dx, dy, dz);
                        if (!standable(worker, candidate)) continue;
                        var path = worker.getNavigation().createPath(candidate, 0);
                        if (path == null || !path.canReach()) continue;
                        double goalDistance = candidate.distSqr(goal);
                        double travelCost = path.getNodeCount();
                        double score = goalDistance * 4.0 + travelCost;
                        if (score < bestScore) { bestScore = score; best = candidate; }
                    }
                }
                if (best != null) break;
            }
            return best;
        }

        /** Prefer existing roads when a long route has a reachable road tile that advances toward the goal. */
        private static BlockPos roadWaypoint(WFWorker worker, BlockPos target) {
            double current = Math.hypot(target.getX() - worker.getX(), target.getZ() - worker.getZ());
            BlockPos best = null;
            double bestScore = Double.MAX_VALUE;
            for (int x = worker.getBlockX() - 6; x <= worker.getBlockX() + 6; x++)
                for (int z = worker.getBlockZ() - 6; z <= worker.getBlockZ() + 6; z++) {
                    BlockPos probe = new BlockPos(x, worker.getBlockY(), z);
                    if (!worker.level().hasChunkAt(probe)) continue;
                    int surface = worker.level().getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, surface, z);
                    BlockState road = worker.level().getBlockState(feet.below());
                    if (!(road.is(Blocks.DIRT_PATH) || road.is(Blocks.GRAVEL) || road.is(Blocks.COBBLESTONE)
                            || road.is(Blocks.STONE_BRICKS) || road.is(Blocks.BRICKS)
                            || road.is(Blocks.OAK_PLANKS) || road.is(Blocks.SPRUCE_PLANKS)
                            || road.is(Blocks.DARK_OAK_PLANKS))) continue;
                    if (!standable(worker, feet)) continue;
                    double remaining = Math.hypot(target.getX() - (x + 0.5), target.getZ() - (z + 0.5));
                    if (remaining > current - 2.0) continue;
                    var path = worker.getNavigation().createPath(feet, 0);
                    if (path == null || !path.canReach()) continue;
                    double score = path.getNodeCount() + remaining - 5.0;
                    if (score < bestScore) { bestScore = score; best = feet; }
                }
            return best;
        }

        /** Search only inside the assigned footprint; the worker never picks an unrelated distant resource node. */
        private static BlockPos workWaypoint(WFWorker worker, Colony.WorkOrder order) {
            if (order.complete) return null;
            int halfX = Math.max(0, Math.min(64, order.halfX < 0 ? order.radius : order.halfX));
            int halfZ = Math.max(0, Math.min(64, order.halfZ < 0 ? order.radius : order.halfZ));
            if (halfX == 0 && halfZ == 0)
                return order.cursor > 0 ? null : new BlockPos(order.x, order.level, order.z);
            int columns = Math.max(1, (halfX * 2 + 1 + 11) / 12);
            int rows = Math.max(1, (halfZ * 2 + 1 + 11) / 12);
            if (order.cursor >= columns * rows) return null;
            int index = order.cursor;
            int x = order.x - halfX + Math.min(halfX * 2, (index % columns) * 12 + 6);
            int z = order.z - halfZ + Math.min(halfZ * 2, (index / columns) * 12 + 6);
            return new BlockPos(x, worker.getBlockY(), z);
        }

        /** Find a walkable adjacent cell; pathfinding must never be asked to walk into the solid resource block. */
        private static BlockPos workApproachTarget(WFWorker worker, BlockPos resource) {
            // The five immediate work positions cover normal terrain. Keep path checks bounded;
            // large candidate rings are handled by the higher-level detour planner, not per block.
            List<BlockPos> candidates = new ArrayList<>();
            BlockPos above = resource.above();
            if (standable(worker, above)) candidates.add(above);
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos side = resource.relative(direction);
                if (standable(worker, side)) candidates.add(side);
            }
            return candidates.stream().distinct()
                    .filter(pos -> {
                        var path = worker.getNavigation().createPath(pos, 0);
                        return path != null && path.canReach();
                    })
                    .min(java.util.Comparator.comparingDouble(pos ->
                            worker.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)))
                    .orElse(null);
        }

        /** Check whether one temporary, removable step can safely bridge a short vertical reach gap. */
        private static boolean canPlaceTemporaryStep(WFWorker worker, Colony colony, BlockPos resource) {
            int rise = resource.getY() - worker.getBlockY();
            if (colony == null || (colony.s.amount("wood") < 1 && colony.s.amount("stone") < 1)
                    || rise < 1 || rise > 4) return false;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos step = worker.blockPosition().relative(direction);
                if (!worker.level().hasChunkAt(step) || !worker.level().hasChunkAt(step.below())) continue;
                BlockState at = worker.level().getBlockState(step);
                BlockState floor = worker.level().getBlockState(step.below());
                if ((at.isAir() || at.canBeReplaced()) && at.getFluidState().isEmpty()
                        && floor.isSolid() && worker.level().getBlockState(step.above()).isAir()) return true;
            }
            return false;
        }

        private BlockPos placeTemporaryStep(WFWorker worker, Colony colony, BlockPos resource) {
            if (!canPlaceTemporaryStep(worker, colony, resource)
                    || !(worker.level() instanceof net.minecraft.server.level.ServerLevel level)) return null;
            String material = colony.s.amount("wood") >= 1 ? "wood" : "stone";
            BlockState scaffold = "wood".equals(material)
                    ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            if (!colony.s.consumeResource(material, 1)) return null;
            boolean placed = false;
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos step = worker.blockPosition().relative(direction);
                if (!level.hasChunkAt(step) || !level.hasChunkAt(step.below())) continue;
                BlockState at = level.getBlockState(step);
                if (!(at.isAir() || at.canBeReplaced()) || !at.getFluidState().isEmpty()
                        || !level.getBlockState(step.below()).isSolid() || !level.getBlockState(step.above()).isAir()) continue;
                if (!level.setBlock(step, scaffold, 3)) continue;
                colony.temporaryScaffolds.put(step.immutable(), material);
                placed = true;
                BlockPos feet = step.above();
                var path = worker.getNavigation().createPath(feet, 0);
                if (path != null && path.canReach()) return feet;
                break;
            }
            if (!placed) colony.s.receiveResource(material, 1);
            return null;
        }

        private static boolean standable(WFWorker worker, BlockPos feet) {
            if (!worker.level().hasChunkAt(feet) || !worker.level().hasChunkAt(feet.below())) return false;
            BlockState at = worker.level().getBlockState(feet);
            BlockState floor = worker.level().getBlockState(feet.below());
            return (at.isAir() || at.canBeReplaced()) && at.getFluidState().isEmpty() && floor.isSolid();
        }

        /** Search a small local window, then sweep the larger selected area by persistent waypoints. */
        private static BlockPos findWorkTarget(WFWorker worker, Colony colony, Colony.WorkOrder order) {
            if (order.complete) return null;
            int halfX = Math.max(0, Math.min(64, order.halfX < 0 ? order.radius : order.halfX));
            int halfZ = Math.max(0, Math.min(64, order.halfZ < 0 ? order.radius : order.halfZ));
            int scan = halfX == 0 && halfZ == 0 ? 0 : 8;
            int minX = Math.max(order.x - halfX, worker.getBlockX() - scan);
            int maxX = Math.min(order.x + halfX, worker.getBlockX() + scan);
            int minZ = Math.max(order.z - halfZ, worker.getBlockZ() - scan);
            int maxZ = Math.min(order.z + halfZ, worker.getBlockZ() + scan);
            if (halfX == 0 && halfZ == 0) { minX = maxX = order.x; minZ = maxZ = order.z; }
            int yCenter = order.level > worker.level().getMinBuildHeight() && order.level < worker.level().getMaxBuildHeight()
                    ? order.level : worker.getBlockY();
            boolean exactBlock = halfX == 0 && halfZ == 0;
            int yRange = exactBlock ? 0 : "ore".equals(order.mode) ? 32 : "stone".equals(order.mode) ? 20 : 10;
            int fromY = exactBlock ? yCenter : Math.max(worker.level().getMinBuildHeight(), yCenter - yRange);
            int toY = exactBlock ? yCenter : Math.min(worker.level().getMaxBuildHeight() - 1, yCenter + yRange);
            List<BlockPos> candidates = new ArrayList<>();
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                BlockPos probe = new BlockPos(x, worker.getBlockY(), z);
                if (!worker.level().hasChunkAt(probe)) continue;
                if ("level".equals(order.mode)) {
                    int surface = worker.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockPos candidate = surface > order.level ? new BlockPos(x, surface, z)
                            : surface < order.level && order.soil > 0 ? new BlockPos(x, surface + 1, z) : null;
                    if (candidate != null) candidates.add(candidate);
                    continue;
                }
                for (int y = fromY; y <= toY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isWorkTarget(worker.level().getBlockState(pos), order.mode)) continue;
                    candidates.add(pos);
                }
            }
            if (candidates.isEmpty()) return null;
            // Do not give up because the closest block is behind a wall or over a gap.
            // Try nearby candidates in distance order and choose the first with a real safe approach.
            candidates.sort(java.util.Comparator.comparingDouble(pos ->
                    worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
            Set<BlockPos> evaluated = new HashSet<>();
            int checked = 0;
            for (BlockPos candidate : candidates) {
                BlockPos selected = "wood".equals(order.mode) ? treeBaseTarget(worker, candidate) : candidate;
                if (!evaluated.add(selected.immutable())) continue;
                if (workApproachTarget(worker, selected) != null) return selected;
                if (canPlaceTemporaryStep(worker, colony, selected)) return selected;
                if (++checked >= 8) break;
            }
            return null;
        }

        /** Resolve a clicked upper log to the lowest reachable trunk block so workers do not try to climb the canopy. */
        private static BlockPos treeBaseTarget(WFWorker worker, BlockPos selected) {
            Set<BlockPos> seen = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(selected.immutable());
            BlockPos best = selected;
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                if (!seen.add(current) || !worker.level().hasChunkAt(current)
                        || !worker.level().getBlockState(current).is(BlockTags.LOGS)) continue;
                if (current.getY() < best.getY()
                        || (current.getY() == best.getY()
                        && worker.distanceToSqr(current.getX() + 0.5, current.getY() + 0.5, current.getZ() + 0.5)
                        < worker.distanceToSqr(best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5))) best = current;
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!seen.contains(next) && worker.level().hasChunkAt(next)
                                && worker.level().getBlockState(next).is(BlockTags.LOGS)) queue.addLast(next);
                    }
            }
            return best;
        }

        private static boolean isWorkTarget(BlockState state, String mode) {
            return switch (mode) {
                case "wood" -> state.is(BlockTags.LOGS);
                case "stone" -> state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLESTONE)
                        || state.is(Blocks.COBBLED_DEEPSLATE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE)
                        || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF);
                case "ore" -> state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                        || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                        || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES);
                case "clear" -> state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                        || state.is(BlockTags.CROPS) || state.is(Blocks.GRASS) || state.is(Blocks.TALL_GRASS)
                        || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN) || state.is(Blocks.DEAD_BUSH)
                        || state.is(Blocks.VINE);
                case "level" -> true;
                case "harvest" -> isMatureHarvest(state);
                case "honey" -> state.getBlock() instanceof net.minecraft.world.level.block.BeehiveBlock
                        && state.getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) >= 5;
                case "herbs" -> state.is(BlockTags.FLOWERS) || state.is(Blocks.BROWN_MUSHROOM)
                        || state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS);
                default -> false;
            };
        }

        private static boolean performWork(WFWorker worker, Colony colony, Colony.WorkOrder order, BlockPos pos) {
            if (!(worker.level() instanceof net.minecraft.server.level.ServerLevel level) || !level.hasChunkAt(pos)) return false;
            BlockState state = level.getBlockState(pos);
            if (!isWorkTarget(state, order.mode)) return false;
            if (worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 9.0) return false;
            if ("clear".equals(order.mode)) {
                return level.destroyBlock(pos, false, worker);
            }
            if ("wood".equals(order.mode)) return fellWholeTree(worker, colony, level, pos);
            if ("honey".equals(order.mode)) {
                if (!(state.getBlock() instanceof net.minecraft.world.level.block.BeehiveBlock)
                        || state.getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) < 5) return false;
                if (colony.s.amount("honey") + 1 > colony.s.capacity("honey")) return false;
                if (!level.setBlock(pos, state.setValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL, 0), 3)) return false;
                colony.s.receiveResource("honey", 1);
                return true;
            }
            if ("herbs".equals(order.mode)) {
                if (!(state.is(BlockTags.FLOWERS) || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                        || state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS))) return false;
                if (colony.s.amount("medicine") + 1 > colony.s.capacity("medicine")) return false;
                if (!level.destroyBlock(pos, false, worker)) return false;
                colony.s.receiveResource("medicine", 1);
                return true;
            }
            if ("harvest".equals(order.mode)) {
                if (!isMatureHarvest(state)) return false;
                if (colony.s.amount("food") + 1 > colony.s.capacity("food")) {
                    Worker resident = colony.s.worker(worker.workerId);
                    if (resident != null) resident.status = "склад еды заполнен";
                    return false;
                }
                if (!level.setBlock(pos, resetHarvestBlock(state), 3)) return false;
                colony.s.receiveResource("food", 1);
                return true;
            }
            if ("level".equals(order.mode)) {
                int desired = order.level;
                if (desired <= level.getMinBuildHeight() || desired >= level.getMaxBuildHeight()) return false;
                if (state.isAir()) {
                    if (order.soil <= 0 || !level.getBlockState(pos.below()).isSolid()) return false;
                    if (!level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3)) return false;
                    order.soil--;
                    return true;
                }
                if (pos.getY() <= desired) return false;
                if (state.is(BlockTags.DIRT) || state.is(Blocks.GRASS_BLOCK)) order.soil++;
                return level.destroyBlock(pos, false, worker);
            }
            String resource = "wood".equals(order.mode) ? "wood" : "stone".equals(order.mode) ? "stone" : oreResource(state);
            if (resource == null || !colony.s.defs.resourceNames.containsKey(resource)) resource = "stone";
            if (colony.s.amount(resource) + 1 > colony.s.capacity(resource)) {
                colony.s.worker(worker.workerId).status = "склад заполнен: " + resource;
                return false;
            }
            if (!level.destroyBlock(pos, false, worker)) return false;
            colony.s.receiveResource(resource, 1);
            return true;
        }

        /**
         * Fell the connected tree, not one log. Starting from any selected trunk block, walk the
         * connected log component, then remove leaves attached to that component. The area order
         * selects which tree to fell; once selected, the whole tree is completed even if its crown
         * extends beyond the rectangle.
         */
        private static boolean fellWholeTree(WFWorker worker, Colony colony,
                                             net.minecraft.server.level.ServerLevel level, BlockPos selected) {
            if (!level.getBlockState(selected).is(BlockTags.LOGS)) return false;
            Set<BlockPos> logs = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(selected.immutable());
            while (!queue.isEmpty()) {
                BlockPos current = queue.removeFirst();
                if (!logs.add(current) || !level.hasChunkAt(current)
                        || !level.getBlockState(current).is(BlockTags.LOGS)) continue;
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz);
                        if (!logs.contains(next) && level.hasChunkAt(next)
                                && level.getBlockState(next).is(BlockTags.LOGS)) queue.addLast(next);
                    }
            }
            if (logs.isEmpty()) return false;
            String resource = "wood";
            int freeCapacity = (int) Math.max(0,
                    Math.floor(colony.s.capacity(resource) - colony.s.amount(resource)));
            if (freeCapacity < logs.size()) {
                Worker resident = colony.s.worker(worker.workerId);
                if (resident != null) resident.status = "нужно место на складе для всего дерева (" + logs.size() + " древесины)";
                return false;
            }
            int felled = 0;
            for (BlockPos log : logs) {
                if (!level.hasChunkAt(log) || !level.getBlockState(log).is(BlockTags.LOGS)) continue;
                if (level.destroyBlock(log, false, worker)) felled++;
            }
            if (felled == 0) return false;
            colony.s.receiveResource(resource, felled);
            // Remove only leaves belonging to this tree's crown, never neighbouring trunks.
            Set<BlockPos> leaves = new HashSet<>();
            for (BlockPos log : logs) {
                for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++)
                    for (int dz = -4; dz <= 4; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 7) continue;
                        BlockPos leaf = log.offset(dx, dy, dz);
                        if (level.hasChunkAt(leaf) && level.getBlockState(leaf).is(BlockTags.LEAVES))
                            leaves.add(leaf);
                    }
            }
            for (BlockPos leaf : leaves) if (level.hasChunkAt(leaf)
                    && level.getBlockState(leaf).is(BlockTags.LEAVES)) level.destroyBlock(leaf, false, worker);
            return true;
        }

        private static boolean isMatureHarvest(BlockState state) {
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)
                return state.getValue(net.minecraft.world.level.block.CropBlock.AGE) >= crop.getMaxAge();
            if (state.getBlock() instanceof net.minecraft.world.level.block.SweetBerryBushBlock)
                return state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 3;
            if (state.getBlock() instanceof net.minecraft.world.level.block.CocoaBlock)
                return state.getValue(net.minecraft.world.level.block.CocoaBlock.AGE) >= 2;
            return false;
        }

        private static BlockState resetHarvestBlock(BlockState state) {
            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)
                return state.setValue(net.minecraft.world.level.block.CropBlock.AGE, 0);
            if (state.getBlock() instanceof net.minecraft.world.level.block.SweetBerryBushBlock)
                return state.setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1);
            if (state.getBlock() instanceof net.minecraft.world.level.block.CocoaBlock)
                return state.setValue(net.minecraft.world.level.block.CocoaBlock.AGE, 0);
            return state;
        }

        private static String oreResource(BlockState state) {
            if (state.is(BlockTags.COAL_ORES)) return "coal";
            if (state.is(BlockTags.IRON_ORES)) return "iron";
            if (state.is(BlockTags.COPPER_ORES)) return "copper";
            if (state.is(BlockTags.GOLD_ORES)) return "gold";
            if (state.is(BlockTags.REDSTONE_ORES)) return "redstone";
            if (state.is(BlockTags.LAPIS_ORES)) return "lapis";
            if (state.is(BlockTags.DIAMOND_ORES)) return "diamond";
            if (state.is(BlockTags.EMERALD_ORES)) return "emerald";
            return "stone";
        }
    }
}
