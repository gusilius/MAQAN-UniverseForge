package dev.worldforge.troops.mc;

import dev.worldforge.mod.api.ActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Server-authoritative orders for the actual player entity; movement is collision-resolved and never teleports or loads chunks. */
final class PlayerBodyOrders {
    private enum Kind { MOVE, ATTACK, CHOP, BUILD }
    private static final Map<UUID,Order> ORDERS=new HashMap<>();
    private static final int MAX_VISITED=2600;
    private static final double SPEED=0.19;
    private static final class Order {
        final Kind kind; final double x,z; final UUID target; final BlockPos block;
        List<BlockPos> path=List.of(); long lastPathTick=Long.MIN_VALUE; long lastActionTick=Long.MIN_VALUE;
        Order(Kind kind,double x,double z,UUID target,BlockPos block){this.kind=kind;this.x=x;this.z=z;this.target=target;this.block=block;}
    }
    private record Node(BlockPos pos,double g,double f) {}
    private PlayerBodyOrders(){}

    static ActionResult issue(ServerPlayer player,OrderPacket packet){
        if(player==null||packet==null||packet.action()!=OrderPacket.PLAYER_BODY
                ||packet.ids().size()!=1||!packet.ids().get(0).equals(player.getUUID().toString()))
            return ActionResult.fail("Приказ тела отклонён: неверный владелец");
        int action=packet.arg();Order order;
        if(action==OrderPacket.BODY_MOVE)order=new Order(Kind.MOVE,packet.x(),packet.z(),null,null);
        else if(action==OrderPacket.BODY_ATTACK){
            UUID target;try{target=UUID.fromString(packet.target());}catch(RuntimeException ex){return ActionResult.fail("Цель атаки не найдена");}
            order=new Order(Kind.ATTACK,0,0,target,null);
        }else{
            String[] parts=packet.target().split(",",-1);if(parts.length!=3)return ActionResult.fail("Неверные координаты блока");
            try{BlockPos block=new BlockPos(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                order=new Order(action==OrderPacket.BODY_CHOP?Kind.CHOP:Kind.BUILD,0,0,null,block);
            }catch(NumberFormatException ex){return ActionResult.fail("Неверные координаты блока");}
        }
        ORDERS.put(player.getUUID(),order);
        return ActionResult.ok(switch(order.kind){case MOVE->"Персонаж получил приказ на перемещение";case ATTACK->"Персонаж получил приказ атаковать";case CHOP->"Персонаж получил приказ добыть блок";case BUILD->"Персонаж получил приказ построить блок";});
    }

    static void tick(ServerPlayer player){
        if(player==null||!player.isAlive())return;
        Order order=ORDERS.get(player.getUUID());if(order==null)return;
        ServerLevel level=player.serverLevel();
        if(order.kind==Kind.ATTACK){
            Entity entity=level.getEntity(order.target);
            if(!(entity instanceof LivingEntity target)||!target.isAlive()||target==player||target instanceof net.minecraft.world.entity.player.Player){ORDERS.remove(player.getUUID());return;}
            if(player.distanceTo(target)<=3.0){
                player.setDeltaMovement(Vec3.ZERO);
                if(level.getGameTime()-order.lastActionTick>=12){player.attack(target);order.lastActionTick=level.getGameTime();}
                return;
            }
            BlockPos approach=approach(player,level,target.getX(),target.getZ());
            if(approach==null){player.setDeltaMovement(Vec3.ZERO);return;}
            moveToward(player,level,order,approach.getX()+0.5,approach.getZ()+0.5,0.9);return;
        }
        if(order.kind==Kind.MOVE){moveToward(player,level,order,order.x,order.z,1.25);return;}
        BlockPos target=order.block;
        if(target==null||!level.hasChunkAt(target)){ORDERS.remove(player.getUUID());return;}
        if(player.distanceToSqr(Vec3.atCenterOf(target))<=16.0){
            if(level.getGameTime()-order.lastActionTick<8)return;
            order.lastActionTick=level.getGameTime();
            if(order.kind==Kind.CHOP){
                if(level.destroyBlock(target,true,player)){player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);ORDERS.remove(player.getUUID());}
                else ORDERS.remove(player.getUUID());
            }else{
                BlockPos place=target.above();ItemStack held=player.getMainHandItem();
                if(!(held.getItem() instanceof BlockItem blockItem)||!level.getBlockState(place).canBeReplaced()){ORDERS.remove(player.getUUID());return;}
                BlockState state=blockItem.getBlock().defaultBlockState();
                if(!state.canSurvive(level,place)||!level.setBlock(place,state,3)){ORDERS.remove(player.getUUID());return;}
                if(!player.isCreative())held.shrink(1);
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);ORDERS.remove(player.getUUID());
            }
            return;
        }
        BlockPos approach=approach(player,level,target.getX(),target.getZ());
        if(approach==null){player.setDeltaMovement(Vec3.ZERO);return;}
        moveToward(player,level,order,approach.getX()+0.5,approach.getZ()+0.5,0.9);
    }

    private static void moveToward(ServerPlayer player,ServerLevel level,Order order,double tx,double tz,double stopDistance){
        double dx=tx-player.getX(),dz=tz-player.getZ(),distance=Math.hypot(dx,dz);
        if(distance<=stopDistance){player.setDeltaMovement(Vec3.ZERO);if(order.kind==Kind.MOVE)ORDERS.remove(player.getUUID());return;}
        long tick=level.getGameTime();
        if(order.path.isEmpty()||tick-order.lastPathTick>=30){order.path=findPath(player,level,(int)Math.floor(tx),(int)Math.floor(tz));order.lastPathTick=tick;}
        if(order.path.size()<2){player.setDeltaMovement(Vec3.ZERO);return;}
        BlockPos next=order.path.get(1);
        double nx=next.getX()+0.5-player.getX(),nz=next.getZ()+0.5-player.getZ(),horizontal=Math.hypot(nx,nz);
        if(horizontal<0.24){order.path=List.copyOf(order.path.subList(1,order.path.size()));return;}
        double step=Math.min(SPEED,horizontal),dy=Math.max(-0.12,Math.min(0.12,next.getY()-player.getY()));
        Vec3 motion=new Vec3(nx/horizontal*step,dy,nz/horizontal*step);
        player.setYRot((float)Math.toDegrees(Math.atan2(-motion.x,motion.z)));
        player.move(MoverType.SELF,motion);player.hurtMarked=true;
    }

    /** Walkable adjacent tile; never route into the target's occupied block/entity cell. */
    private static BlockPos approach(ServerPlayer player,ServerLevel level,double targetX,double targetZ){
        int tx=(int)Math.floor(targetX),tz=(int)Math.floor(targetZ);
        BlockPos best=null;double bestDistance=Double.POSITIVE_INFINITY;
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}){
            BlockPos candidate=surface(player,level,tx+d[0],tz+d[1]);if(candidate==null)continue;
            double distance=player.distanceToSqr(candidate.getX()+0.5,player.getY(),candidate.getZ()+0.5);
            if(distance<bestDistance){bestDistance=distance;best=candidate;}
        }
        return best;
    }

    private static List<BlockPos> findPath(ServerPlayer player,ServerLevel level,int goalX,int goalZ){
        int sx=(int)Math.floor(player.getX()),sz=(int)Math.floor(player.getZ());
        BlockPos start=surface(player,level,sx,sz),goal=surface(player,level,goalX,goalZ);
        if(start==null||goal==null)return List.of();
        if(sx==goalX&&sz==goalZ)return List.of(start,goal);
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<Long,Double> cost=new HashMap<>();Map<Long,BlockPos> came=new HashMap<>();
        cost.put(key(sx,sz),0.0);open.add(new Node(start,0,Math.hypot(goalX-sx,goalZ-sz)));
        BlockPos reached=null;int visited=0;int[][] dirs={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        while(!open.isEmpty()&&visited++<MAX_VISITED){
            Node node=open.poll();BlockPos pos=node.pos();long current=key(pos.getX(),pos.getZ());
            if(node.g()>cost.getOrDefault(current,Double.POSITIVE_INFINITY)+1e-6)continue;
            if(pos.getX()==goalX&&pos.getZ()==goalZ){reached=pos;break;}
            for(int[] dir:dirs){
                int x=pos.getX()+dir[0],z=pos.getZ()+dir[1];BlockPos next=surface(player,level,x,z);
                if(next==null||Math.abs(next.getY()-pos.getY())>1)continue;
                double ng=node.g()+(dir[0]!=0&&dir[1]!=0?1.41421356:1.0)+Math.abs(next.getY()-pos.getY())*0.35;
                long k=key(x,z);if(ng>=cost.getOrDefault(k,Double.POSITIVE_INFINITY))continue;
                cost.put(k,ng);came.put(k,pos);open.add(new Node(next,ng,ng+Math.hypot(goalX-x,goalZ-z)));
            }
        }
        if(reached==null)return List.of();
        List<BlockPos> path=new ArrayList<>();BlockPos cursor=reached;path.add(cursor);
        while(cursor.getX()!=sx||cursor.getZ()!=sz){cursor=came.get(key(cursor.getX(),cursor.getZ()));if(cursor==null)return List.of();path.add(cursor);}
        Collections.reverse(path);return List.copyOf(path);
    }
    private static BlockPos surface(ServerPlayer player,ServerLevel level,int x,int z){
        BlockPos sample=new BlockPos(x,level.getMinBuildHeight()+1,z);if(!level.hasChunkAt(sample))return null;
        int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        if(y<=level.getMinBuildHeight()+1||y>=level.getMaxBuildHeight()-2)return null;
        BlockPos feet=new BlockPos(x,y,z);AABB box=player.getBoundingBox().move(x+0.5-player.getX(),y-player.getY(),z+0.5-player.getZ());
        if(!level.noCollision(player,box)||level.getBlockState(feet.below()).getCollisionShape(level,feet.below()).isEmpty())return null;
        return feet;
    }
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
