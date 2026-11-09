package dev.worldforge.dialogue;

import java.util.*;

/** Bounded conversational memory backed by an owner-supplied Character.props map. */
public final class DialogueMemory {
    private static final int MAX_FACTS=128, MAX_EVENTS=64;
    private final Map<String,Object> storage;
    public DialogueMemory(Map<String,Object> ownerProps) {
        storage=ownerProps==null?new LinkedHashMap<>():ownerProps;
        if (!(storage.get("dialogue.memory") instanceof Map<?,?>)) storage.put("dialogue.memory",new LinkedHashMap<String,Object>());
        Map<String,Object> root=root();
        if (!(root.get("facts") instanceof Map<?,?>)) root.put("facts",new LinkedHashMap<String,Object>());
        if (!(root.get("events") instanceof List<?>)) root.put("events",new ArrayList<Object>());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> root(){return (Map<String,Object>)storage.get("dialogue.memory");}
    @SuppressWarnings("unchecked") private Map<String,Object> facts(){return (Map<String,Object>)root().get("facts");}
    @SuppressWarnings("unchecked") private List<Object> events(){return (List<Object>)root().get("events");}
    public void remember(String key,Object value){facts().put(key(key),value==null?"":value);while(facts().size()>MAX_FACTS)facts().remove(facts().keySet().iterator().next());}
    public Object recall(String key){return facts().get(key(key));}
    public boolean knows(String key){return facts().containsKey(key(key));}
    public void forget(String key){facts().remove(key(key));}
    public void record(String kind,String text){
        Map<String,Object> e=new LinkedHashMap<>(); e.put("sequence",events().isEmpty()?1:sequence(events().get(events().size()-1))+1);
        e.put("kind",kind==null?"conversation":kind);e.put("text",text==null?"":text);events().add(e);
        while(events().size()>MAX_EVENTS)events().remove(0);
    }
    public List<Map<String,Object>> recent(int limit){
        int n=Math.max(0,Math.min(limit,MAX_EVENTS));List<Map<String,Object>> out=new ArrayList<>();List<Object> src=events();
        for(int i=Math.max(0,src.size()-n);i<src.size();i++)if(src.get(i) instanceof Map<?,?> m){
            Map<String,Object> copy=new LinkedHashMap<>();for(Map.Entry<?,?> e:m.entrySet())if(e.getKey() instanceof String k)copy.put(k,e.getValue());
            out.add(Collections.unmodifiableMap(copy));
        }return Collections.unmodifiableList(out);
    }
    private static int sequence(Object x){return x instanceof Map<?,?> m&&m.get("sequence") instanceof Number n?n.intValue():0;}
    private static String key(String s){if(s==null||s.isBlank()||s.length()>128)throw new IllegalArgumentException("memory key must be 1..128 characters");return s.trim();}
}