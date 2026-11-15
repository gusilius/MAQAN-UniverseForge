package dev.worldforge.troops;

import dev.worldforge.mod.Json;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Batched optional provider for deterministic pure troop calculations. */
final class TroopCompute {
    record SupplyResult(double consumed,double remainingRations,double totalConsumed,double readiness,double daysWithoutFullRations,double shortage){}
    private static volatile Method method; private static volatile boolean resolved, unavailable;
    private TroopCompute(){}
    private static Method provider(){
        if(resolved){if(unavailable)return null;if(method==null)throw new IllegalStateException("Troop native provider resolution failed");return method;}
        synchronized(TroopCompute.class){
            if(resolved)return provider();
            try{
                Class<?> c=Class.forName("dev.worldforge.worldsim.mc.NativeWorldSimulation",false,TroopCompute.class.getClassLoader());
                if(!Boolean.TRUE.equals(c.getMethod("load").invoke(null)))unavailable=true;
                else method=c.getMethod("advanceTroopCompute",byte[].class);
            }catch(ClassNotFoundException|NoClassDefFoundError absent){unavailable=true;}
            catch(InvocationTargetException e){throw new IllegalStateException("WorldSim troop provider failed during load",e.getCause());}
            catch(ReflectiveOperationException e){throw new IllegalStateException("WorldSim troop provider contract mismatch",e);}
            resolved=true; return method;
        }
    }
    private static Map<?,?> call(Map<String,Object> req){
        Method m=provider();if(m==null)return null;
        try{
            byte[] out=(byte[])m.invoke(null,Json.write(req).getBytes(StandardCharsets.UTF_8));
            if(out==null||out.length==0)throw new IllegalStateException("empty native troop result");
            Object parsed=Json.parse(new String(out,StandardCharsets.UTF_8));
            if(!(parsed instanceof Map<?,?> result)||!"ok".equals(result.get("status")))throw new IllegalStateException("native troop compute rejected frame: "+parsed);
            return result;
        }catch(InvocationTargetException e){throw new IllegalStateException("native troop compute failed after ownership",e.getCause());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("native troop invocation failed",e);}
    }
    static Double moraleStep(double morale,double alive,double lost,double outnumber,boolean leader,boolean enemies,double dt){
        Map<String,Object> q=base("morale_step");q.put("morale",morale);q.put("alive_ratio",alive);q.put("lost_now",lost);q.put("outnumber",outnumber);q.put("leader",leader);q.put("enemies_near",enemies);q.put("dt",dt);
        Map<?,?> r=call(q);return r==null?null:num(r,"morale");
    }
    static Formations.Plan formationPlan(Formations.Type type,int n,double spacing,List<String> roles,List<double[]> current,double cx,double cz,double yaw){
        Map<String,Object> q=base("formation_plan");q.put("formation",type.name());q.put("count",n);q.put("spacing",spacing);q.put("roles",roles==null?List.of():roles);
        List<Object> points=new ArrayList<>();if(current!=null)for(double[] p:current)points.add(List.of(p[0],p.length>1?p[1]:0));q.put("current_local",points);
        q.put("cx",cx);q.put("cz",cz);q.put("yaw",yaw);Map<?,?> r=call(q);if(r==null)return null;
        List<Formations.Slot> slots=new ArrayList<>();for(Object x:list(r,"slots")){List<?> a=(List<?>)x;slots.add(new Formations.Slot(num(a.get(0)),num(a.get(1))));}
        List<double[]> world=new ArrayList<>();for(Object x:list(r,"world")){List<?> a=(List<?>)x;world.add(new double[]{num(a.get(0)),num(a.get(1))});}
        List<?> raw=list(r,"assignment");int[] assign=new int[raw.size()];boolean[] used=new boolean[n];
        for(int i=0;i<assign.length;i++){assign[i]=((Number)raw.get(i)).intValue();if(assign[i]<0||assign[i]>=n||used[assign[i]])throw new IllegalStateException("invalid native formation assignment");used[assign[i]]=true;}
        if(slots.size()!=n||world.size()!=n||(!roles.isEmpty()&&assign.length!=n))throw new IllegalStateException("native formation cardinality mismatch");
        return new Formations.Plan(List.copyOf(slots),List.copyOf(world),assign);
    }
    static SupplyResult supply(int soldiers,double days,double available,double consumed,double readiness,double shortageDays,double totalConsumed,boolean persisted){
        Map<String,Object> q=base("supply");q.put("soldiers",soldiers);q.put("days",days);q.put("available_rations",available);q.put("consumed",consumed);q.put("readiness",readiness);q.put("days_without_full_rations",shortageDays);q.put("total_consumed",totalConsumed);q.put("persisted_advance",persisted);
        Map<?,?> r=call(q);return r==null?null:new SupplyResult(num(r,"consumed"),num(r,"remaining_rations"),num(r,"total_consumed"),num(r,"readiness"),num(r,"days_without_full_rations"),num(r,"shortage"));
    }
    private static Map<String,Object> base(String op){Map<String,Object> m=new LinkedHashMap<>();m.put("schema_version",1);m.put("operation",op);return m;}
    private static List<?> list(Map<?,?> m,String k){if(!(m.get(k) instanceof List<?> l))throw new IllegalStateException("missing native field "+k);return l;}
    private static double num(Map<?,?> m,String k){return num(m.get(k));}
    private static double num(Object v){if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("invalid native number");return n.doubleValue();}
}
