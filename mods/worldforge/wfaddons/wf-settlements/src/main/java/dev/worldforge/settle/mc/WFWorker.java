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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
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
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.4).add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void registerGoals() {
        if (getNavigation() instanceof GroundPathNavigation g) g.setCanOpenDoors(true);
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
        Colony.WorkOrder area = c.workOrders.get(w.workerId);
        entityData.set(SYNC_WORK_AREA, area == null ? "" : area.mode + "|" + area.x + "|" + area.z + "|"
                + area.radius + "|" + area.level + "|" + area.overlay);
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
                    cachedWorkTarget = findWorkTarget(w, area);
                    workScan = 30;
                }
                resourceTarget = cachedWorkTarget;
            }
            if (area != null) {
                if (resourceTarget != null) {
                    target = resourceTarget;
                    working = true;
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
                target = c.workPoint(w.level(), b);
                working = b.state == Building.State.BUILDING ? b.status.startsWith("строится") : "работает".equals(b.status);
            } else {
                if (idle == null || --idleTimer <= 0) {
                    idleTimer = 100 + w.random.nextInt(100);
                    int x = c.cx + w.random.nextInt(13) - 6, z = c.cz + w.random.nextInt(13) - 6;
                    idle = new BlockPos(x, w.getBlockY(), z);
                }
                target = idle;
            }
            boolean arrived = w.distanceToSqr(target.getX() + 0.5, w.getY(), target.getZ() + 0.5) < 6.25;
            if (!arrived && --repath <= 0) {
                repath = 10;
                w.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            }
            if (arrived) {
                w.getNavigation().stop();
                if (area != null && !area.complete && resourceTarget == null && workScan > 0) {
                    BlockPos waypoint = workWaypoint(w, area);
                    if (w.distanceToSqr(waypoint.getX() + 0.5, w.getY(), waypoint.getZ() + 0.5) < 6.25) {
                        area.cursor++;
                        workScan = 0;
                    }
                }
                if (area != null && resourceTarget != null) {
                    if (performWork(w, c, area, resourceTarget)) {
                        cachedWorkTarget = null;
                        workScan = 0;
                        wk.status = switch (area.mode) {
                            case "wood" -> "добывает древесину";
                            case "stone" -> "добывает камень";
                            case "ore" -> "добывает руду";
                            case "clear" -> "расчищает участок";
                            case "level" -> "выравнивает участок";
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

        /** Search only inside the assigned footprint; the worker never picks an unrelated distant resource node. */
        private static BlockPos workWaypoint(WFWorker worker, Colony.WorkOrder order) {
            int radius = Math.max(0, Math.min(64, order.radius));
            if (radius == 0) return order.cursor > 0 ? null : new BlockPos(order.x, order.level, order.z);
            int span = radius * 2 + 1, spacing = 12, columns = Math.max(1, (span + spacing - 1) / spacing);
            int index = Math.floorMod(order.cursor, columns * columns);
            int x = order.x - radius + Math.min(span - 1, (index % columns) * spacing + spacing / 2);
            int z = order.z - radius + Math.min(span - 1, (index / columns) * spacing + spacing / 2);
            return new BlockPos(x, worker.getBlockY(), z);
        }

        /** Search a small local window, then sweep the larger selected area by persistent waypoints. */
        private static BlockPos findWorkTarget(WFWorker worker, Colony.WorkOrder order) {
            if (order.complete) return null;
            int radius = Math.max(0, Math.min(64, order.radius));
            int scan = radius == 0 ? 0 : 8;
            int minX = Math.max(order.x - radius, worker.getBlockX() - scan);
            int maxX = Math.min(order.x + radius, worker.getBlockX() + scan);
            int minZ = Math.max(order.z - radius, worker.getBlockZ() - scan);
            int maxZ = Math.min(order.z + radius, worker.getBlockZ() + scan);
            if (radius == 0) { minX = maxX = order.x; minZ = maxZ = order.z; }
            int yCenter = order.level > worker.level().getMinBuildHeight() && order.level < worker.level().getMaxBuildHeight()
                    ? order.level : worker.getBlockY();
            int yRange = radius == 0 ? 16 : "ore".equals(order.mode) ? 32 : "stone".equals(order.mode) ? 20 : 10;
            int fromY = radius == 0 ? yCenter : Math.max(worker.level().getMinBuildHeight(), yCenter - yRange);
            int toY = radius == 0 ? yCenter : Math.min(worker.level().getMaxBuildHeight() - 1, yCenter + yRange);
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
                if (Math.hypot(x - order.x, z - order.z) > radius + 0.75 && radius > 0) continue;
                BlockPos probe = new BlockPos(x, worker.getBlockY(), z);
                if (!worker.level().hasChunkAt(probe)) continue;
                if ("level".equals(order.mode)) {
                    int surface = worker.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockPos candidate = surface > order.level ? new BlockPos(x, surface, z)
                            : surface < order.level && order.soil > 0 ? new BlockPos(x, surface + 1, z) : null;
                    if (candidate != null) {
                        double d = worker.distanceToSqr(candidate.getX() + 0.5, candidate.getY() + 0.5, candidate.getZ() + 0.5);
                        if (d < bestDistance) { bestDistance = d; best = candidate; }
                    }
                    continue;
                }
                for (int y = fromY; y <= toY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isWorkTarget(worker.level().getBlockState(pos), order.mode)) continue;
                    double d = worker.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestDistance) { bestDistance = d; best = pos; }
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
                default -> false;
            };
        }

        private static boolean performWork(WFWorker worker, Colony colony, Colony.WorkOrder order, BlockPos pos) {
            if (!(worker.level() instanceof net.minecraft.server.level.ServerLevel level) || !level.hasChunkAt(pos)) return false;
            BlockState state = level.getBlockState(pos);
            if (!isWorkTarget(state, order.mode)) return false;
            if ("clear".equals(order.mode)) {
                if (level.destroyBlock(pos, false, worker)) return true;
                return false;
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
