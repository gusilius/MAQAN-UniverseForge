package dev.worldforge.settle.mc;

import dev.worldforge.settle.Building;
import dev.worldforge.settle.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.DamageTypeTags;
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

    public Colony colony() { return SettleMod.colony(colonyId); }

    public Worker worker() {
        Colony c = colony();
        return c == null ? null : c.s.worker(workerId);
    }

    public void refreshName() {
        Colony c = colony();
        Worker w = worker();
        if (w == null) return;
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
        private int repath, swing, idleTimer;
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
            if (b != null) {
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
                if (b != null) w.getLookControl().setLookAt(c.worldX(b) + 0.5, w.getEyeY(), c.worldZ(b) + 0.5);
                if (working && ++swing >= 15) {
                    swing = 0;
                    w.swing(InteractionHand.MAIN_HAND);
                }
            }
        }
    }
}
