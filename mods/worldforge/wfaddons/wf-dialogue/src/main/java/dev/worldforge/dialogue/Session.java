package dev.worldforge.dialogue;

import java.util.ArrayList;
import java.util.List;

/** One server-side conversation; line and choice history is recorded in owner-backed memory. */
public final class Session {
    public static final class View {
        public final String speaker,text;
        public final List<String> choices;
        public final boolean cont,end;
        public final int revision;
        View(String speaker,String text,List<String> choices,boolean cont,boolean end,int revision){
            this.speaker=speaker;this.text=text;this.choices=choices;this.cont=cont;this.end=end;this.revision=revision;
        }
    }
    private final Dialogue dialogue;
    private final Ctx ctx;
    private Dialogue.Node node;
    private List<Dialogue.Choice> visible=List.of();
    private boolean ended,started;
    private int steps,revision;
    public Session(Dialogue dialogue,Ctx ctx){this(dialogue,ctx,false);}
    /** Deferred sessions let the server journal initial node effects before exposing a conversation. */
    public Session(Dialogue dialogue,Ctx ctx,boolean deferStart){
        this.dialogue=dialogue;this.ctx=ctx;
        if(!deferStart)start();
    }
    public void start(){
        if(started)throw new IllegalStateException("Диалог уже запущен");
        started=true;
        revision++;
        enter(dialogue.start);
    }
    private void enter(String id){
        Dialogue.Node n=dialogue.nodes.get(id);
        if(n==null||++steps>200){ended=true;node=null;return;}
        node=n;
        for(Call c:n.effects)DialogueApi.run(c,ctx);
        visible=new ArrayList<>();
        for(Dialogue.Choice ch:n.choices)if(DialogueApi.all(rawOf(ch),ctx))visible.add(ch);
        ctx.memory().recordEvent("line",template(n.speaker)+": "+template(n.text));
    }
    private static Object rawOf(Dialogue.Choice ch){
        List<Object> l=new ArrayList<>();
        for(Call c:ch.conditions){java.util.Map<String,Object> m=new java.util.LinkedHashMap<>(c.args);m.put("if",c.id);l.add(m);}
        return l;
    }
    public boolean ended(){return ended;}
    public int revision(){return revision;}
    public View view(){
        if(!started||ended||node==null)return null;
        List<String> texts=new ArrayList<>();for(Dialogue.Choice c:visible)texts.add(template(c.text));
        boolean noChoices=node.choices.isEmpty();
        return new View(template(node.speaker),template(node.text),texts,noChoices&&node.next!=null,noChoices&&node.next==null,revision);
    }
    public void choose(int i){choose(i,revision);}
    /** A choice is accepted once for the exact view revision that the client saw. */
    public void choose(int i,int expectedRevision){
        if(!started||ended||node==null)return;
        if(expectedRevision!=revision)throw new IllegalStateException("Ответ устарел: обновите диалог");
        if(!node.choices.isEmpty()&&(i<0||i>=visible.size()))
            throw new IllegalArgumentException("Нет ответа с номером "+i);
        revision++;
        if(node.choices.isEmpty()){if(node.next==null){ended=true;return;}enter(node.next);return;}
        Dialogue.Choice c=visible.get(i);
        ctx.memory().recordEvent("choice",template(c.text));
        for(Call e:c.effects)DialogueApi.run(e,ctx);
        if(c.next==null){ended=true;return;}enter(c.next);
    }
    private String template(String s){
        StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char ch=s.charAt(i);int close=ch=='{'?s.indexOf('}',i):-1;
            if(close>0){String key=s.substring(i+1,close),value=ctx.vars.get(key);
                if(value==null&&key.startsWith("memory:")){Object remembered=ctx.memory().recall(key.substring(7));value=remembered==null?null:String.valueOf(remembered);}
                out.append(value==null?"{"+key+"}":value);i=close;}
            else out.append(ch);
        }
        return out.toString();
    }
}
