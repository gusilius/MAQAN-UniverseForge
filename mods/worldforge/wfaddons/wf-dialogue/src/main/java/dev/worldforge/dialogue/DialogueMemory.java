package dev.worldforge.dialogue;

import java.util.*;

/**
 * Character-owned semantic dialogue memory. The supplied map must be the canonical
 * Character.props map; no independent NPC identity or persistence is created here.
 */
public final class DialogueMemory {
    private static final int MAX_FACTS=128, MAX_EVENTS=64, MAX_RECORDS=64;
    private final Map<String,Object> storage;

    public DialogueMemory(Map<String,Object> ownerProps) {
        storage=ownerProps==null?new LinkedHashMap<>():ownerProps;
        if(!(storage.get("dialogue.memory") instanceof Map<?,?>)) storage.put("dialogue.memory",new LinkedHashMap<String,Object>());
        Map<String,Object> root=root();
        if(!(root.get("facts") instanceof Map<?,?>)) root.put("facts",new LinkedHashMap<String,Object>());
        if(!(root.get("events") instanceof List<?>)) root.put("events",new ArrayList<Object>());
        if(!(root.get("records") instanceof Map<?,?>)) root.put("records",new LinkedHashMap<String,Object>());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> root(){return (Map<String,Object>)storage.get("dialogue.memory");}
    @SuppressWarnings("unchecked") private Map<String,Object> facts(){return (Map<String,Object>)root().get("facts");}
    @SuppressWarnings("unchecked") private List<Object> events(){return (List<Object>)root().get("events");}
    @SuppressWarnings("unchecked") private Map<String,Object> records(){return (Map<String,Object>)root().get("records");}

    public void remember(String key,Object value){facts().put(key(key),value==null?"":value);trim(facts(),MAX_FACTS);}
    public Object recall(String key){return facts().get(key(key));}
    public boolean knows(String key){return facts().containsKey(key(key));}
    public void forget(String key){facts().remove(key(key));}

    /** Store a semantic record such as an open promise, request or grievance. */
    public void putRecord(String category,String id,Map<String,Object> value){
        String recordKey=recordKey(category,id);
        records().put(recordKey,new LinkedHashMap<>(value));
        trim(records(),MAX_RECORDS);
    }
    public Map<String,Object> record(String category,String id){
        Object raw=records().get(recordKey(category,id));
        if(!(raw instanceof Map<?,?> map)) return Map.of();
        Map<String,Object> copy=new LinkedHashMap<>();
        for(Map.Entry<?,?> e:map.entrySet()) if(e.getKey() instanceof String k) copy.put(k,e.getValue());
        return Collections.unmodifiableMap(copy);
    }
    public boolean hasRecord(String category,String id){return records().containsKey(recordKey(category,id));}
    public boolean resolveRecord(String category,String id,String resolution){
        String k=recordKey(category,id);
        Object raw=records().get(k);
        if(!(raw instanceof Map<?,?> old)) return false;
        Map<String,Object> updated=new LinkedHashMap<>();
        for(Map.Entry<?,?> e:old.entrySet()) if(e.getKey() instanceof String key) updated.put(key,e.getValue());
        updated.put("status","resolved");
        updated.put("resolution",resolution==null?"resolved":resolution);
        records().put(k,updated);
        return true;
    }
    public List<Map<String,Object>> records(String category){
        String prefix=key(category)+".";
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map.Entry<String,Object> e:records().entrySet()) if(e.getKey().startsWith(prefix)&&e.getValue() instanceof Map<?,?> map){
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> field:map.entrySet()) if(field.getKey() instanceof String k) copy.put(k,field.getValue());
            out.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(out);
    }

    public void record(String kind,String text){
        Map<String,Object> e=new LinkedHashMap<>();
        e.put("sequence",events().isEmpty()?1:sequence(events().get(events().size()-1))+1);
        e.put("kind",kind==null?"conversation":kind);e.put("text",text==null?"":text);
        events().add(e);while(events().size()>MAX_EVENTS)events().remove(0);
    }
    public List<Map<String,Object>> recent(int limit){
        int n=Math.max(0,Math.min(limit,MAX_EVENTS));List<Map<String,Object>> out=new ArrayList<>();List<Object> src=events();
        for(int i=Math.max(0,src.size()-n);i<src.size();i++) if(src.get(i) instanceof Map<?,?> map){
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> e:map.entrySet()) if(e.getKey() instanceof String k) copy.put(k,e.getValue());
            out.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(out);
    }
    private static int sequence(Object x){return x instanceof Map<?,?> map&&map.get("sequence") instanceof Number n?n.intValue():0;}
    private static String recordKey(String category,String id){return key(category)+"."+key(id);}
    private static String key(String s){if(s==null||s.isBlank()||s.length()>128)throw new IllegalArgumentException("memory key must be 1..128 characters");return s.trim();}
    private static void trim(Map<String,?> map,int max){while(map.size()>max)map.remove(map.keySet().iterator().next());}
}