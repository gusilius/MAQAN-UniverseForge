package dev.worldforge.dialogue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Conversation variables, flags, services and owner-backed character memory. */
public final class Ctx {
    public final Map<String,String> vars=new HashMap<>();
    public final Set<String> flags;
    private final Map<Class<?>,Object> services=new HashMap<>();
    private final DialogueMemory memory;
    public Ctx(Set<String> flags){this(flags,new DialogueMemory(null));}
    public Ctx(Set<String> flags,DialogueMemory memory){
        this.flags=flags==null?new HashSet<>():flags;
        this.memory=memory==null?new DialogueMemory(null):memory;
        services.put(DialogueMemory.class,this.memory);
    }
    public Ctx var(String k,String v){vars.put(k,v);return this;}
    public DialogueMemory memory(){return memory;}
    public <T> Ctx service(Class<T> type,T impl){services.put(type,impl);return this;}
    public <T> T service(Class<T> type){Object o=services.get(type);return o==null?null:type.cast(o);}
}