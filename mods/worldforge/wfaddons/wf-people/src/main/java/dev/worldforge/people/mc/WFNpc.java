package dev.worldforge.people.mc;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterAccessService;
import dev.worldforge.mod.api.SimulationLod;
import dev.worldforge.people.CharacterRegistry;
import dev.worldforge.people.CharacterInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Тело персонажа: только изображение. Истина о человеке лежит в CharacterRegistry, здесь хранится лишь id. */
public class WFNpc extends PathfinderMob {
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(WFNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> OWNER = SynchedEntityData.defineId(WFNpc.class, EntityDataSerializers.STRING);
    private static final double LEASH = 12.0;

    private String charId = "";
    private int postX, postZ;
    private boolean hasPost;
    private String ownerId="";
    private BlockPos commandedBlock;
    private int commandedBlockMode; // 1 chop, 2 place from equipped BlockItem

    public WFNpc(EntityType<? extends WFNpc> type, Level level) {
        super(type, level);
        setInvulnerable(true);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(VARIANT, 0);
        this.entityData.define(OWNER, "");
    }

    public int variant() { return this.entityData.get(VARIANT); }

    public String characterId() { return charId; }
    public String ownerId() { return this.entityData.get(OWNER); }
    public void setOwnerId(String owner) {
        ownerId=owner==null?"":owner;
        this.entityData.set(OWNER,ownerId);
    }

    public void setPost(int x, int z) {
        postX = x;
        postZ = z;
        hasPost = true;
    }

    /** Привязывает тело к персонажу: имя, облик. */
    public void bind(Character c) {
        charId = c.id;
        this.entityData.set(VARIANT, "f".equals(c.gender) ? 1 : 0);
        setCustomName(Component.literal(PeopleMod.label(c)));
        setCustomNameVisible(true);
        syncEquipment(c);
    }

    /** Server-side RTS order: use vanilla path navigation rather than teleporting the NPC. */
    public void commandMove(double x,double z){
        if(level().isClientSide||!Double.isFinite(x)||!Double.isFinite(z))return;
        commandedBlock=null;commandedBlockMode=0;setTarget(null);
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        if(!level().hasChunkAt(new BlockPos(bx,level().getMinBuildHeight()+1,bz)))return;
        int y=level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,bx,bz);
        getNavigation().moveTo(x,y,z,1.0);
    }
    public void commandAttack(String entityUuid){
        if(level().isClientSide||entityUuid==null)return;
        try{
            java.util.UUID id=java.util.UUID.fromString(entityUuid);
            net.minecraft.world.entity.Entity target=((ServerLevel)level()).getEntity(id);
            if(target instanceof net.minecraft.world.entity.LivingEntity living&&living.isAlive()&&living!=this){
                commandedBlock=null;commandedBlockMode=0;setTarget(living);getNavigation().moveTo(living,1.0);
            }
        }catch(RuntimeException ignored){}
    }
    public void commandStop(){
        if(level().isClientSide)return;
        commandedBlock=null;commandedBlockMode=0;setTarget(null);getNavigation().stop();
    }
    public void commandBlock(int x,int y,int z,int mode){
        if(level().isClientSide||mode<1||mode>2)return;
        BlockPos target=new BlockPos(x,y,z);
        if(!level().hasChunkAt(target))return;
        commandedBlock=target;commandedBlockMode=mode;setTarget(null);
        BlockPos approach=null;double best=Double.POSITIVE_INFINITY;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            int ax=x+d[0],az=z+d[1];
            if(!level().hasChunkAt(new BlockPos(ax,level().getMinBuildHeight()+1,az)))continue;
            int ay=level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,ax,az);
            BlockPos candidate=new BlockPos(ax,ay,az);
            double distance=distanceToSqr(ax+0.5,ay,az+0.5);
            if(distance<best){best=distance;approach=candidate;}
        }
        if(approach==null){commandedBlock=null;commandedBlockMode=0;return;}
        getNavigation().moveTo(approach.getX()+0.5,approach.getY(),approach.getZ()+0.5,1.0);
    }
    private void tickCommandedBlock(ServerLevel level){
        if(commandedBlock==null)return;
        if(!level.hasChunkAt(commandedBlock)){commandStop();return;}
        if(distanceToSqr(Vec3.atCenterOf(commandedBlock))>16.0)return;
        BlockPos target=commandedBlock;int mode=commandedBlockMode;
        commandedBlock=null;commandedBlockMode=0;getNavigation().stop();
        if(mode==1)level.destroyBlock(target,true,this);
        else if(mode==2){
            BlockPos place=target.above();
            if(getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem block
                    &&level.getBlockState(place).canBeReplaced()){
                var state=block.getBlock().defaultBlockState();
                if(state.canSurvive(level,place)&&level.setBlock(place,state,3)
                        &&!getMainHandItem().isEmpty())getMainHandItem().shrink(1);
            }
        }
    }

    /** The Character owns equipment; this entity only renders the saved slots. */
    private void syncEquipment(Character character) {
        Map<String, EquipmentSlot> slots = Map.of(
                "head", EquipmentSlot.HEAD, "chest", EquipmentSlot.CHEST,
                "legs", EquipmentSlot.LEGS, "feet", EquipmentSlot.FEET,
                "mainhand", EquipmentSlot.MAINHAND, "offhand", EquipmentSlot.OFFHAND);
        Map<String, CharacterInventory.Item> saved = CharacterInventory.equipment(character);
        for (Map.Entry<String, EquipmentSlot> entry : slots.entrySet()) {
            CharacterInventory.Item item = saved.get(entry.getKey());
            ItemStack stack = ItemStack.EMPTY;
            if (item != null) {
                try {
                    if (!item.data().isBlank()) {
                        CompoundTag tag = TagParser.parseTag(item.data());
                        stack = tag.contains("id") ? ItemStack.of(tag) : ItemStack.EMPTY;
                    }
                } catch (Exception ignored) { stack = ItemStack.EMPTY; }
                if (stack.isEmpty()) {
                    ResourceLocation id = ResourceLocation.tryParse(item.id());
                    if (id != null) {
                        net.minecraft.world.item.Item registered = BuiltInRegistries.ITEM.get(id);
                        if (registered != net.minecraft.world.item.Items.AIR)
                            stack = new ItemStack(registered, item.count());
                    }
                    if (!stack.isEmpty() && !item.data().isBlank()) try {
                        CompoundTag tag = TagParser.parseTag(item.data());
                        if (!tag.contains("id")) stack.setTag(tag);
                    } catch (Exception ignored) { /* malformed optional NBT does not erase the item */ }
                }
            }
            setItemSlot(entry.getValue(), stack);
        }
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this,1.0,true));
        this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(3, new PostStrollGoal(this));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1,new HurtByTargetGoal(this));
    }

    /** Гуляет недалеко от своего поста. */
    private static final class PostStrollGoal extends RandomStrollGoal {
        private final WFNpc npc;

        PostStrollGoal(WFNpc npc) {
            super(npc, 0.6, 120);
            this.npc = npc;
        }

        @Override
        protected Vec3 getPosition() {
            Vec3 v = DefaultRandomPos.getPos(this.mob, 6, 3);
            if (!npc.hasPost) return v;
            double dx = npc.getX() - npc.postX, dz = npc.getZ() - npc.postZ;
            if (dx * dx + dz * dz > LEASH * LEASH) return new Vec3(npc.postX + 0.5, npc.getY(), npc.postZ + 0.5);
            if (v == null) return null;
            double vx = v.x - npc.postX, vz = v.z - npc.postZ;
            return vx * vx + vz * vz > LEASH * LEASH ? null : v;
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!this.level().isClientSide && player instanceof ServerPlayer sp) PeopleMod.interact(sp, this);
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    /** Раз в 5 секунд: тело исчезает, если рядом нет игроков или персонаж умер/неизвестен; иначе обновляет имя. */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        tickCommandedBlock(sl);
        if (this.tickCount % 100 != 7) return;
        if (!SimulationLod.shouldRetainPeople(sl.dimension().location().toString(), getX(), getZ())) {
            discard();
            return;
        }
        CharacterRegistry reg = PeopleMod.peek(sl);
        if (reg == null) return;
        try (CharacterAccessService.Lease lease = reg.access().acquire(charId)) {
            if (lease == null || !lease.get().alive) {
                discard();
                return;
            }
            bind(lease.get());
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("wf_char", charId);
        tag.putString("wf_owner",ownerId());
        tag.putBoolean("wf_has_post", hasPost);
        tag.putInt("wf_post_x", postX);
        tag.putInt("wf_post_z", postZ);
        tag.putInt("wf_variant", variant());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        charId = tag.getString("wf_char");
        ownerId=tag.getString("wf_owner");
        this.entityData.set(OWNER,ownerId);
        hasPost = tag.getBoolean("wf_has_post");
        postX = tag.getInt("wf_post_x");
        postZ = tag.getInt("wf_post_z");
        this.entityData.set(VARIANT, tag.getInt("wf_variant"));
    }
}
