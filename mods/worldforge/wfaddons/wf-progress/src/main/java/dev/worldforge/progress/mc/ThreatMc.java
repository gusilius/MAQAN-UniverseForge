package dev.worldforge.progress.mc;

import dev.worldforge.progress.Profile;
import dev.worldforge.progress.Threat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;
import java.util.UUID;

/**
 * Угрозы по уровню в игре: естественные враги получают уровень зоны (множители здоровья и урона, подпись [N]), а у игроков периодически
 * появляются встречи: бандиты днём, звери и нежить ночью. Капитан встречи при смерти роняет золото и даёт опыт.
 */
@Mod.EventBusSubscriber(modid = "wf_progress")
public final class ThreatMc {
    public static final Threat THREAT = new Threat();
    private static final Random RNG = new Random();
    private static final UUID HP = UUID.fromString("5c0f4a3e-6a54-4f38-9b6f-2f2d8d3c0101"), DMG = UUID.fromString("5c0f4a3e-6a54-4f38-9b6f-2f2d8d3c0102");
    public static volatile int encounterEvery = 2400;          // тиков между попытками встречи (по умолчанию 2 минуты)
    public static volatile double encounterChance = 0.35;

    private ThreatMc() {}

    private static void mult(LivingEntity e, Attribute a, UUID id, double factor) {
        AttributeInstance i = e.getAttribute(a);
        if (i == null) return;
        i.removeModifier(id);
        if (Math.abs(factor - 1) > 1e-6) i.addPermanentModifier(new AttributeModifier(id, "wf_threat", factor - 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private static int nearbyPlayerLevel(LivingEntity e) {
        int best = 0;
        for (var p : e.level().players()) if (p.distanceToSqr(e) < 96 * 96) best = Math.max(best, ProgressMod.PROGRESS.profile(ProgressMod.owner((ServerPlayer) p)).level);
        return Math.max(1, best);
    }

    static void scale(LivingEntity e, int level, double hp, double dmg, String name) {
        mult(e, Attributes.MAX_HEALTH, HP, hp);
        mult(e, Attributes.ATTACK_DAMAGE, DMG, dmg);
        e.setHealth(e.getMaxHealth());
        e.getPersistentData().putInt("wf_level", level);
        if (name != null) { e.setCustomName(Component.literal("[" + level + "] " + name)); e.setCustomNameVisible(true); }
    }

    @SubscribeEvent
    public static void onSpawn(MobSpawnEvent.FinalizeSpawn e) {
        if (!(e.getEntity() instanceof Enemy) || e.getEntity().getPersistentData().contains("wf_level")) return;
        if (e.getSpawnType() != MobSpawnType.NATURAL && e.getSpawnType() != MobSpawnType.CHUNK_GENERATION && e.getSpawnType() != MobSpawnType.SPAWNER) return;
        Mob m = e.getEntity();
        BlockPos spawn = e.getLevel().getLevelData() instanceof net.minecraft.world.level.storage.ServerLevelData d ? new BlockPos(d.getXSpawn(), 0, d.getZSpawn()) : BlockPos.ZERO;
        double dist = Math.sqrt(m.blockPosition().distSqr(new BlockPos(spawn.getX(), m.getBlockY(), spawn.getZ())));
        int level = THREAT.zoneLevel(dist, nearbyPlayerLevel(m), 0);
        scale(m, level, THREAT.hpMult(level), THREAT.dmgMult(level), null);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.getServer().getTickCount() % encounterEvery != 0) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            if (p.isCreative() || p.isSpectator() || p.serverLevel().getDifficulty() == Difficulty.PEACEFUL || RNG.nextDouble() > encounterChance) continue;
            try { encounter(p); } catch (Exception ex) { ProgressMod.LOGGER.error("wf_progress: встреча: {}", ex.toString()); }
        }
    }

    static void encounter(ServerPlayer p) {
        ServerLevel lv = p.serverLevel();
        Profile pr = ProgressMod.PROGRESS.profile(ProgressMod.owner(p));
        BlockPos sp = lv.getSharedSpawnPos();
        int level = THREAT.zoneLevel(Math.sqrt(p.blockPosition().distSqr(new BlockPos(sp.getX(), p.getBlockY(), sp.getZ()))), pr.level, 0);
        boolean night = lv.isNight();
        String kind = night ? (RNG.nextBoolean() ? "undead" : "beasts") : "bandits";
        Threat.Encounter enc = THREAT.roll(kind, level, RNG);
        if (enc == null) enc = THREAT.roll(null, level, RNG);
        if (enc == null) return;
        double ang = RNG.nextDouble() * Math.PI * 2;
        int cx = p.getBlockX() + (int) (Math.cos(ang) * 34), cz = p.getBlockZ() + (int) (Math.sin(ang) * 34);
        BlockPos base = lv.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(cx, 0, cz));
        if (lv.getFluidState(base.below()).isSource()) return;
        Mob captain = null;
        for (Threat.Fighter f : enc.fighters) {
            EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(new ResourceLocation(f.entity)).orElse(null);
            if (t == null) continue;
            Entity en = t.create(lv);
            if (!(en instanceof Mob m)) continue;
            m.moveTo(base.getX() + RNG.nextInt(7) - 3 + 0.5, base.getY(), base.getZ() + RNG.nextInt(7) - 3 + 0.5, RNG.nextFloat() * 360, 0);
            scale(m, f.level, f.hpMult, f.dmgMult, f.name);
            gear(m, f);
            m.setPersistenceRequired();
            m.getPersistentData().putBoolean("wf_encounter", true);
            m.getPersistentData().putInt("wf_gold", 0);
            lv.addFreshEntity(m);
            m.setTarget(p);
            if (f.role.equals("captain") || f.role.equals("boss") || captain == null) captain = m;
        }
        if (captain != null) { captain.getPersistentData().putInt("wf_gold", enc.gold); captain.getPersistentData().putInt("wf_xp", (int) enc.xp); }
        p.sendSystemMessage(Component.literal("§c" + enc.def.name() + " (ур. " + enc.level + ") выходят на вас!"));
    }

    private static void gear(Mob m, Threat.Fighter f) {
        int a = f.armor;
        if (!(m.getType() == EntityType.WOLF || m.getType() == EntityType.ZOMBIE && a < 2)) {
            if (a >= 2) m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(a >= 8 ? Items.DIAMOND_CHESTPLATE : a >= 5 ? Items.IRON_CHESTPLATE : Items.CHAINMAIL_CHESTPLATE));
            if (a >= 4) m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(a >= 8 ? Items.DIAMOND_HELMET : Items.IRON_HELMET));
            if (a >= 6) m.setItemSlot(EquipmentSlot.LEGS, new ItemStack(a >= 8 ? Items.DIAMOND_LEGGINGS : Items.IRON_LEGGINGS));
        }
        m.setDropChance(EquipmentSlot.CHEST, 0.05f); m.setDropChance(EquipmentSlot.HEAD, 0.05f); m.setDropChance(EquipmentSlot.LEGS, 0.05f);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity m = e.getEntity();
        int gold = m.getPersistentData().getInt("wf_gold");
        if (gold > 0 && m.level() instanceof ServerLevel lv) {
            while (gold > 0) { int d = Math.min(gold, 64); lv.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(lv, m.getX(), m.getY(), m.getZ(), new ItemStack(Items.GOLD_NUGGET, d))); gold -= d; }
            if (e.getSource().getEntity() instanceof ServerPlayer p) {
                int up = ProgressMod.PROGRESS.award(ProgressMod.owner(p), m.getPersistentData().getInt("wf_xp"));
                if (up > 0) ProgressMod.levelUp(p, ProgressMod.PROGRESS.profile(ProgressMod.owner(p)));
                p.sendSystemMessage(Component.literal("§6Банда разбита: добыча и опыт ваши."));
            }
        }
    }
}
