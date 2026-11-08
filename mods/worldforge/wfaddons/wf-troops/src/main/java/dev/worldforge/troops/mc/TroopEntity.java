package dev.worldforge.troops.mc;

import dev.worldforge.troops.TroopDefs;
import dev.worldforge.troops.TroopProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.UUID;

/** Солдат. Сам ничего не решает: отряд (TroopController) задаёт точку, режим боя и цель; здесь только исполнение. */
public class TroopEntity extends PathfinderMob {
    public static final EntityDataAccessor<String> UNIT = SynchedEntityData.defineId(TroopEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(TroopEntity.class, EntityDataSerializers.STRING);
    public static final EntityDataAccessor<Integer> MORALE = SynchedEntityData.defineId(TroopEntity.class, EntityDataSerializers.INT);
    private static final UUID MORALE_MOD = UUID.fromString("5b0a8e0e-6c3d-4a0e-9d0a-0d1f6f0c7a11");
    public static final EntityDataAccessor<String> SQUAD = SynchedEntityData.defineId(TroopEntity.class, EntityDataSerializers.STRING);

    /** Режим боя во время приказа. */
    public enum Engage { NONE, RANGE, NEAR, FULL }

    // серверное состояние приказа
    public boolean hasDest;
    public double destX, destZ;
    public double destYaw = Double.NaN;
    public Engage engage = Engage.NEAR;
    public UUID forced;                    // цель приказа "атаковать"
    public String realm = "";
    public int fortId;                     // защитник крепости (0 - полевой солдат)
    public int armyId;                     // из какой армии wf-army призван (0 - нанят за золото)
    /** Stable foreign key into wf-people.CharacterRegistry; empty when wf-people is absent. */
    public String characterId = "";
    /** Recruitment sets this until all stock/payments commit; legacy saved troops still bind on load. */
    public boolean deferCharacterBinding;
    private int cooldown, seek;
    private int combatXp;
    private TroopDefs.Unit def;

    public TroopEntity(EntityType<? extends TroopEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20).add(Attributes.MOVEMENT_SPEED, 0.25).add(Attributes.ATTACK_DAMAGE, 3)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.ARMOR, 0).add(Attributes.KNOCKBACK_RESISTANCE, 0.1);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(UNIT, "");
        entityData.define(OWNER, "");
        entityData.define(SQUAD, "");
        entityData.define(MORALE, 100);
    }

    public String unitId() { return entityData.get(UNIT); }
    public String ownerId() { return entityData.get(OWNER); }
    public int moralePct() { return entityData.get(MORALE); }
    public void setMorale(int pct) { if (entityData.get(MORALE) != pct) entityData.set(MORALE, pct); }
    public double dmgMult = 1;

    /** Множитель урона от морали и ауры командира: добавочный модификатор, чтобы не трогать базу типа. */
    public void setDamageMult(double m) {
        var ai = getAttribute(Attributes.ATTACK_DAMAGE);
        if (ai == null) return;
        ai.removeModifier(MORALE_MOD);
        if (Math.abs(m - 1) > 0.01) ai.addTransientModifier(new AttributeModifier(MORALE_MOD, "wf_morale", m - 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        dmgMult = m;
    }

    /** Враги вокруг точки: {сколько, x центра, z центра}. */
    public double[] enemiesAround(double cx, double cz, double r) {
        AABB box = new AABB(cx - r, getY() - 8, cz - r, cx + r, getY() + 8, cz + r);
        double sx = 0, sz = 0;
        int n = 0;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, box, this::isEnemy)) { sx += e.getX(); sz += e.getZ(); n++; }
        return n == 0 ? new double[]{0, cx, cz} : new double[]{n, sx / n, sz / n};
    }

    public String squadId() { return entityData.get(SQUAD); }
    public void setSquadId(String s) { entityData.set(SQUAD, s); }
    public TroopDefs.Unit def() { return def; }
    public int combatXp() { return combatXp; }
    public void setCombatXpFromCharacter(long xp) { combatXp = (int) Math.max(0, Math.min(TroopProgression.MAX_XP, xp)); }
    public int veteranRank() { return TroopProgression.rank(combatXp); }
    public void awardCombatXp(int amount) {
        int before = veteranRank();
        combatXp = TroopProgression.addXp(combatXp, amount);
        TroopsMod.syncPerson(this);
        if (veteranRank() > before && def != null) {
            float oldHealth = getHealth();
            double oldMax = getMaxHealth();
            setup(def, ownerId());
            setHealth((float) Math.min(getMaxHealth(), oldHealth + Math.max(0, getMaxHealth() - oldMax)));
        }
    }

    /** Применяет тип: здоровье, урон, скорость, броня, снаряжение. */
    public void setup(TroopDefs.Unit u, String owner) {
        this.def = u;
        entityData.set(UNIT, u.id());
        entityData.set(OWNER, owner);
        int veteran = veteranRank();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(u.hp() * TroopProgression.healthMultiplier(veteran));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(u.dmg() * TroopProgression.damageMultiplier(veteran));
        getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.25 * u.speed());
        getAttribute(Attributes.ARMOR).setBaseValue(u.armor() + TroopProgression.armorBonus(veteran));
        getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(Math.max(32, u.sight() + 8));
        setHealth((float) (u.hp() * TroopProgression.healthMultiplier(veteran)));
        equip(EquipmentSlot.MAINHAND, u.weapon());
        equip(EquipmentSlot.HEAD, u.head());
        equip(EquipmentSlot.CHEST, u.chest());
        equip(EquipmentSlot.LEGS, u.legs());
        equip(EquipmentSlot.FEET, u.feet());
        setCustomName(net.minecraft.network.chat.Component.literal(u.name()));
        setCustomNameVisible(false);
        for (EquipmentSlot s : EquipmentSlot.values()) setDropChance(s, 0f);
    }

    private void equip(EquipmentSlot slot, String id) {
        if (id == null || id.isEmpty()) return;
        Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (it != null && it != net.minecraft.world.item.Items.AIR) setItemSlot(slot, new ItemStack(it));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putString("wf_unit", unitId());
        t.putString("wf_owner", ownerId());
        t.putString("wf_squad", squadId());
        t.putString("wf_realm", realm);
        t.putInt("wf_army", armyId);
        t.putInt("wf_fort", fortId);
        t.putInt("wf_combat_xp", combatXp);
        t.putString("wf_character", characterId == null ? "" : characterId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        realm = t.getString("wf_realm");
        armyId = t.getInt("wf_army");
        fortId = t.getInt("wf_fort");
        combatXp = Math.max(0, Math.min(TroopProgression.MAX_XP, t.getInt("wf_combat_xp")));
        characterId = t.getString("wf_character");
        TroopDefs.Unit u = TroopsMod.DEFS.units.get(t.getString("wf_unit"));
        if (u != null) {
            float hp = getHealth();
            setup(u, t.getString("wf_owner"));
            setHealth((float) Math.min(hp <= 0 ? getMaxHealth() : hp, getMaxHealth()));
        } else entityData.set(OWNER, t.getString("wf_owner"));
        entityData.set(SQUAD, t.getString("wf_squad"));
    }

    @Override
    protected void registerGoals() {
        if (getNavigation() instanceof GroundPathNavigation g) g.setCanOpenDoors(true);
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new Brain(this));
    }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    @Override
    public void die(DamageSource src) {
        if (!level().isClientSide) TroopsMod.onDeath(this);
        super.die(src);
    }

    // ---- враги ----
    boolean isEnemy(LivingEntity e) {
        if (e == this || !e.isAlive()) return false;
        if (e instanceof TroopEntity t) return !t.ownerId().equals(ownerId()) && t.hostileTo(this);
        return e instanceof Enemy;
    }

    /** Чужие отряды враждебны, если по ним отдан прямой приказ, это защитники и враги крепости или их государства воюют (свойство «enemies» государства). */
    boolean hostileTo(TroopEntity o) { return o.forced != null && o.forced.equals(getUUID()) || (forced != null && forced.equals(o.getUUID())) || TroopsMod.fortHostile(this, o) || TroopsMod.atWar(realm, o.realm); }

    private LivingEntity findTarget(double radius) {
        AABB box = getBoundingBox().inflate(radius, 6, radius);
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : level().getEntitiesOfClass(LivingEntity.class, box, this::isEnemy)) {
            double d = distanceToSqr(e);
            if (d < bd && d <= radius * radius) { bd = d; best = e; }
        }
        return best;
    }

    private LivingEntity forcedTarget() {
        if (forced == null || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        net.minecraft.world.entity.Entity e = sl.getEntity(forced);
        return e instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    private void shoot(LivingEntity target) {
        Arrow a = new Arrow(level(), this);
        double dx = target.getX() - getX(), dy = target.getEyeY() - 0.2 - a.getY(), dz = target.getZ() - getZ(), h = Math.sqrt(dx * dx + dz * dz);
        a.setBaseDamage(Math.max(0.5, (def == null ? 1.5 : def.dmg() * TroopProgression.damageMultiplier(veteranRank()) / 3.0) * dmgMult));
        a.pickup = AbstractArrow.Pickup.DISALLOWED;
        a.shoot(dx, dy + h * 0.2, dz, 1.7f, 3.0f);
        level().addFreshEntity(a);
        awardCombatXp(1);
        playSound(net.minecraft.sounds.SoundEvents.SKELETON_SHOOT, 1f, 1f / (random.nextFloat() * 0.4f + 0.8f));
        swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** Один мозг: идти к точке отряда / драться по режиму боя. */
    static final class Brain extends Goal {
        private final TroopEntity m;
        private LivingEntity target;
        private int repath;

        Brain(TroopEntity m) { this.m = m; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override public boolean canUse() { return true; }
        @Override public boolean canContinueToUse() { return true; }

        @Override
        public void tick() {
            if (m.level().isClientSide) return;
            if (m.cooldown > 0) m.cooldown--;
            TroopDefs.Unit d = m.def;
            boolean ranged = d != null && d.range() >= 6 && (d.role().equals("ranged") || d.role().equals("cavalry") || d.role().equals("siege"));
            double reach = d == null ? 2.5 : d.range();
            LivingEntity forced = m.forcedTarget();
            if (forced != null) target = forced;
            else if (target == null || !target.isAlive() || m.distanceToSqr(target) > 60 * 60) target = null;
            if (forced == null && m.engage != Engage.NONE && (target == null || --m.seek <= 0)) {
                m.seek = 10;
                double r = switch (m.engage) { case RANGE -> reach + 0.5; case NEAR -> Math.min(14, d == null ? 14 : d.sight()); default -> d == null ? 24 : d.sight(); };
                target = m.findTarget(r);
            }
            if (forced == null && m.engage == Engage.NONE) target = null;
            boolean mustMove = m.hasDest && (m.engage == Engage.NONE || target == null);
            if (target != null && !mustMove) {
                double dist = Math.sqrt(m.distanceToSqr(target));
                m.getLookControl().setLookAt(target, 30, 30);
                if (ranged) {
                    if (dist <= reach && m.hasLineOfSight(target)) {
                        m.getNavigation().stop();
                        if (m.cooldown <= 0) { m.cooldown = 28; m.shoot(target); }
                    } else if (m.engage != Engage.RANGE && --repath <= 0) {
                        repath = 8;
                        m.getNavigation().moveTo(target, 1.0);
                    }
                } else {
                    if (dist <= reach + target.getBbWidth() / 2) {
                        m.getNavigation().stop();
                        if (m.cooldown <= 0) { m.cooldown = 20; m.swing(net.minecraft.world.InteractionHand.MAIN_HAND); if (m.doHurtTarget(target)) m.awardCombatXp(target.isAlive() ? 2 : 10); }
                    } else if (m.engage != Engage.RANGE && --repath <= 0) {
                        repath = 8;
                        m.getNavigation().moveTo(target, 1.15);
                    }
                }
                return;
            }
            if (m.hasDest) {
                double dx = m.destX - m.getX(), dz = m.destZ - m.getZ();
                if (dx * dx + dz * dz > 1.0) {
                    if (--repath <= 0) {
                        repath = 10;
                        int y = m.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(m.destX), (int) Math.floor(m.destZ));
                        m.getNavigation().moveTo(m.destX, y, m.destZ, 1.0);
                    }
                } else {
                    m.getNavigation().stop();
                    if (!Double.isNaN(m.destYaw)) {
                        float yaw = (float) m.destYaw;
                        m.setYRot(yaw); m.setYHeadRot(yaw); m.yBodyRot = yaw;
                    }
                }
            } else m.getNavigation().stop();
        }
    }
}
