package dev.worldforge.troops.mc;

import dev.worldforge.mod.api.ActionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Optional bridge to player-owned wf_people bodies; reflection keeps wf_people optional. */
final class NpcBodyOrders {
    private NpcBodyOrders(){}
    private static boolean isNpc(Entity entity){
        return entity!=null&&"wf_people:npc".equals(entity.getType().builtInRegistryHolder().key().location().toString());
    }
    private static String owner(Entity entity){
        if(!isNpc(entity))return "";
        try{return String.valueOf(entity.getClass().getMethod("ownerId").invoke(entity));}
        catch(ReflectiveOperationException|RuntimeException ignored){return "";}
    }
    private static boolean owned(ServerPlayer player,Entity entity){
        return isNpc(entity)&&player.getUUID().toString().equals(owner(entity));
    }
    private static boolean invoke(Entity entity,String method,Class<?>[] types,Object...args){
        try{Method m=entity.getClass().getMethod(method,types);m.invoke(entity,args);return true;}
        catch(ReflectiveOperationException|RuntimeException ignored){return false;}
    }
    static ActionResult command(ServerPlayer player,OrderPacket packet){
        List<Entity> selected=new ArrayList<>();
        for(String raw:packet.ids())try{
            Entity entity=player.serverLevel().getEntity(UUID.fromString(raw));
            if(owned(player,entity))selected.add(entity);
        }catch(RuntimeException ignored){}
        if(selected.isEmpty())return null;
        int changed=0;
        for(Entity entity:selected){
            boolean ok;
            if(packet.kind()==dev.worldforge.troops.Squads.Kind.ATTACK.ordinal())
                ok=invoke(entity,"commandAttack",new Class<?>[]{String.class},packet.target());
            else if(packet.kind()==dev.worldforge.troops.Squads.Kind.STOP.ordinal()
                    ||packet.kind()==dev.worldforge.troops.Squads.Kind.HOLD.ordinal())
                ok=invoke(entity,"commandStop",new Class<?>[]{});
            else ok=invoke(entity,"commandMove",new Class<?>[]{double.class,double.class},packet.x(),packet.z());
            if(ok)changed++;
        }
        return changed>0?ActionResult.ok("Приказ NPC выполнен: "+changed)
                :ActionResult.fail("Выбранные NPC не приняли приказ");
    }
    static ActionResult work(ServerPlayer player,OrderPacket packet){
        int changed=0;
        String[] parts=packet.target().split(",",-1);if(parts.length!=3)return ActionResult.fail("Неверные координаты рабочей задачи");
        int x,y,z;try{x=Integer.parseInt(parts[0]);y=Integer.parseInt(parts[1]);z=Integer.parseInt(parts[2]);}
        catch(NumberFormatException ex){return ActionResult.fail("Неверные координаты рабочей задачи");}
        for(String raw:packet.ids())try{
            Entity entity=player.serverLevel().getEntity(UUID.fromString(raw));
            if(owned(player,entity)&&invoke(entity,"commandBlock",new Class<?>[]{int.class,int.class,int.class,int.class},x,y,z,packet.arg()))changed++;
        }catch(RuntimeException ignored){}
        return changed>0?ActionResult.ok("Рабочий приказ NPC принят: "+changed)
                :ActionResult.fail("Нет принадлежащих игроку NPC для этой задачи");
    }
}
