package dev.worldforge.dialogue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Extensible conditions/effects. Domain addons register authoritative actions; dialogue text never commits gameplay state itself. */
public final class DialogueApi {
    public static final int API_VERSION=1;
    private static final Map<String,Condition> CONDITIONS=new TreeMap<>();
    private static final Map<String,Effect> EFFECTS=new TreeMap<>();
    private static boolean builtins;
    private DialogueApi(){}
    public static synchronized void registerCondition(String id,Condition c){
        if(id==null||id.isBlank()||c==null)throw new IllegalArgumentException("condition id and implementation are required");
        ensure();if(CONDITIONS.containsKey(id))throw new IllegalStateException("Условие уже зарегистрировано: "+id);CONDITIONS.put(id,c);
    }
    public static synchronized void registerEffect(String id,Effect e){
        if(id==null||id.isBlank()||e==null)throw new IllegalArgumentException("effect id and implementation are required");
        ensure();if(EFFECTS.containsKey(id))throw new IllegalStateException("Эффект уже зарегистрирован: "+id);EFFECTS.put(id,e);
    }
    public static synchronized boolean hasCondition(String id){ensure();return CONDITIONS.containsKey(id);}
    public static synchronized boolean hasEffect(String id){ensure();return EFFECTS.containsKey(id);}
    public static synchronized java.util.Set<String> conditionIds(){ensure();return new java.util.TreeSet<>(CONDITIONS.keySet());}
    public static synchronized java.util.Set<String> effectIds(){ensure();return new java.util.TreeSet<>(EFFECTS.keySet());}
    public static synchronized void resetForTests(){CONDITIONS.clear();EFFECTS.clear();builtins=false;}
    static synchronized boolean test(Call c,Ctx ctx){ensure();Condition cond=CONDITIONS.get(c.id);return cond!=null&&cond.test(ctx,c.args);}
    static synchronized void run(Call c,Ctx ctx){ensure();Effect e=EFFECTS.get(c.id);if(e!=null)e.run(ctx,c.args);}
    static boolean all(Object calls,Ctx ctx){for(Call c:Dialogue.calls(calls,"if"))if(!test(c,ctx))return false;return true;}

    private static void ensure(){
        if(builtins)return;builtins=true;
        CONDITIONS.put("flag",(c,a)->c.flags.contains(str(a,"name")));
        CONDITIONS.put("not",(c,a)->!all(a.get("of"),c));
        CONDITIONS.put("all",(c,a)->all(a.get("of"),c));
        CONDITIONS.put("any",(c,a)->{for(Call call:Dialogue.calls(a.get("of"),"if"))if(test(call,c))return true;return false;});
        CONDITIONS.put("var_equals",(c,a)->str(a,"value").equals(c.vars.get(str(a,"var"))));
        CONDITIONS.put("memory_has",(c,a)->c.memory().knows(str(a,"key")));
        CONDITIONS.put("memory_equals",(c,a)->String.valueOf(c.memory().recall(str(a,"key"))).equals(str(a,"value")));
        CONDITIONS.put("record_exists",(c,a)->c.memory().hasRecord(str(a,"category"),str(a,"id")));
        CONDITIONS.put("record_open",(c,a)->{
            Map<String,Object> r=c.memory().record(str(a,"category"),str(a,"id"));
            return !r.isEmpty()&&!"resolved".equals(r.get("status"));
        });
        EFFECTS.put("set_flag",(c,a)->c.flags.add(str(a,"name")));
        EFFECTS.put("clear_flag",(c,a)->c.flags.remove(str(a,"name")));
        EFFECTS.put("remember",(c,a)->c.memory().remember(str(a,"key"),value(a.get("value"))));
        EFFECTS.put("forget",(c,a)->c.memory().forget(str(a,"key")));
        EFFECTS.put("record_event",(c,a)->c.memory().recordEvent(str(a,"kind"),str(a,"text")));
        EFFECTS.put("promise",(c,a)->createRecord(c,a,"promise"));
        EFFECTS.put("request",(c,a)->createRecord(c,a,"request"));
        EFFECTS.put("grievance",(c,a)->createRecord(c,a,"grievance"));
        EFFECTS.put("resolve_record",(c,a)->{
            String category=str(a,"category"),id=str(a,"id"),resolution=str(a,"resolution");
            if(c.memory().resolveRecord(category,id,resolution))c.memory().recordEvent(category+"_resolved",id+": "+resolution);
        });
        EFFECTS.put("relationship_delta",(c,a)->adjustRelationship(c,str(a,"target"),integer(a.get("delta"),0)));
    }
    private static void createRecord(Ctx c,Map<String,Object> a,String category){
        String id=str(a,"id");Map<String,Object> record=new LinkedHashMap<>();
        record.put("id",id);record.put("category",category);record.put("status","open");
        record.put("text",str(a,"text"));record.put("created_by",c.vars.getOrDefault("npc_id",""));
        record.put("target",str(a,"target"));record.put("due",str(a,"due"));
        c.memory().putRecord(category,id,record);c.memory().recordEvent(category+"_created",id+": "+record.get("text"));
    }
    private static void adjustRelationship(Ctx c,String target,int delta){
        if(target.isBlank())target=c.vars.getOrDefault("player_id","");
        if(target.isBlank())return;
        int next=c.memory().adjustRelationship(target,delta);
        c.memory().recordEvent("relationship",target+"="+next);
    }
    private static String str(Map<String,Object> a,String key){Object v=a.get(key);return v==null?"":String.valueOf(v);}
    private static int integer(Object v,int fallback){return v instanceof Number n?n.intValue():fallback;}
    private static Object value(Object v){return v==null?"":v;}
}