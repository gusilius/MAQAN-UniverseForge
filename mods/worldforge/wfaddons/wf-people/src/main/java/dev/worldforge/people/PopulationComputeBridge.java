package dev.worldforge.people;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable server-thread capture and validated apply boundary for Rust population batches. */
public final class PopulationComputeBridge {
    public static final int SCHEMA_VERSION = 1;
    public static final class Captured {
        private final String worldId; private final int year; private final long elapsedDays;
        private final DomainMutationRevisions.Snapshot revisions;
        private final List<String> characterIds; private final byte[] request;
        private Captured(String worldId,int year,long elapsedDays,DomainMutationRevisions.Snapshot revisions,List<String> ids,byte[] request){
            this.worldId=worldId;this.year=year;this.elapsedDays=elapsedDays;this.revisions=revisions;
            this.characterIds=List.copyOf(ids);this.request=request.clone();
        }
        public String worldId(){return worldId;} public int year(){return year;} public long elapsedDays(){return elapsedDays;}
        public DomainMutationRevisions.Snapshot revisions(){return revisions;}
        public List<String> characterIds(){return characterIds;} public byte[] request(){return request.clone();}
    }
    private record Location(CharacterRegistry registry,Character character){}
    private record Update(String id,Map<String,Integer> needs,String intent,int age,String stage,long simulatedDays,int lastYear){}
    private PopulationComputeBridge(){}

    /** Capture a deterministic projection. Call only on the authoritative server thread. */
    public static Captured capture(String worldId,int year,long elapsedDays,Collection<CharacterRegistry> registries){
        if(worldId==null||worldId.isBlank())throw new IllegalArgumentException("worldId is required");
        if(elapsedDays<=0)throw new IllegalArgumentException("elapsedDays must be positive");
        Objects.requireNonNull(registries,"registries");
        DomainMutationRevisions.Snapshot before=WorldForgeApi.revisions().snapshot()
                .orElseThrow(()->new IllegalStateException("Canonical domain mutation is active"));
        TreeMap<String,Character> ordered=new TreeMap<>();
        for(CharacterRegistry registry:registries)for(Character c:Objects.requireNonNull(registry).all()){
            if(!c.alive)continue;
            if(ordered.putIfAbsent(c.globalId,c)!=null)throw new IllegalStateException("Duplicate canonical Character UUID: "+c.globalId);
        }
        List<Object> people=new ArrayList<>(ordered.size());List<String> ids=new ArrayList<>(ordered.size());
        for(Character c:ordered.values()){
            Map<String,Object> person=new LinkedHashMap<>();
            person.put("id",c.globalId);person.put("birth_year",c.birthYear);person.put("alive",true);
            person.put("needs",needsSnapshot(c));person.put("duties",new ArrayList<>(PopulationSimulation.duties(c)));
            person.put("profession",PopulationSimulation.profession(c));person.put("training_target",PopulationSimulation.trainingTarget(c));
            person.put("command",String.valueOf(c.props.getOrDefault("population.command","")));
            Object rawDays=c.props.get("population.simulated_days");
            long days=rawDays instanceof Number n?Math.max(0L,n.longValue()):0L;
            // The local Java JSON parser uses Double for numeric tokens; preserve signed-long precision as decimal text.
            person.put("simulated_days",Long.toString(days));people.add(person);ids.add(c.globalId);
        }
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("schema_version",SCHEMA_VERSION);request.put("world_id",worldId);request.put("year",year);
        request.put("elapsed_days",elapsedDays);request.put("characters",people);
        byte[] bytes=Json.write(request).getBytes(StandardCharsets.UTF_8);
        DomainMutationRevisions.Snapshot after=WorldForgeApi.revisions().snapshot()
                .orElseThrow(()->new IllegalStateException("Canonical mutation overlapped population capture"));
        if(!before.equals(after))throw new IllegalStateException("Canonical domain changed during population capture");
        return new Captured(worldId,year,elapsedDays,before,ids,bytes);
    }

    /** Validates the complete frame before the first write. False means stale and requires retry. */
    public static boolean apply(Captured captured,byte[] frameBytes,Collection<CharacterRegistry> registries){
        Objects.requireNonNull(captured);Objects.requireNonNull(frameBytes);Objects.requireNonNull(registries);
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions))return false;
        Map<?,?> frame=object(Json.parse(new String(frameBytes,StandardCharsets.UTF_8)),"frame");
        if(integer(frame.get("schema_version"),"schema_version")!=SCHEMA_VERSION)throw new IllegalArgumentException("Unsupported population frame schema");
        if(!captured.worldId.equals(string(frame.get("world_id"),"world_id"))
                ||captured.year!=integer(frame.get("year"),"year")
                ||captured.elapsedDays!=longNumber(frame.get("elapsed_days"),"elapsed_days"))
            throw new IllegalArgumentException("Population frame does not match captured request");
        List<Update> updates=new ArrayList<>();Set<String> seen=new TreeSet<>();
        for(Object raw:list(frame.get("characters"),"characters")){
            Map<?,?> value=object(raw,"character update");String id=string(value.get("id"),"character id");
            if(!seen.add(id))throw new IllegalArgumentException("Duplicate population result ID: "+id);
            Map<String,Integer> needs=new TreeMap<>();
            for(Map.Entry<?,?> e:object(value.get("needs"),"needs").entrySet()){
                if(!(e.getKey() instanceof String key)||!(e.getValue() instanceof Number n))throw new IllegalArgumentException("Invalid need entry");
                needs.put(key,n.intValue());
            }
            String intent=string(value.get("intent"),"intent");PopulationSimulation.intentFromKey(intent);
            int age=integer(value.get("age_years"),"age_years");String stage=string(value.get("life_stage"),"life_stage");
            PopulationSimulation.LifeStage.valueOf(stage.toUpperCase(Locale.ROOT));
            long days=decimalLong(value.get("simulated_days"),"simulated_days");
            if(days<0)throw new IllegalArgumentException("Negative simulated_days");
            updates.add(new Update(id,needs,intent,age,stage,days,integer(value.get("last_simulated_year"),"last_simulated_year")));
        }
        if(!seen.equals(new TreeSet<>(captured.characterIds)))throw new IllegalArgumentException("Population frame Character set differs from captured snapshot");
        Map<String,Location> current=new HashMap<>();
        for(CharacterRegistry registry:registries)for(Character c:registry.all())if(c.alive){
            if(current.putIfAbsent(c.globalId,new Location(registry,c))!=null)throw new IllegalStateException("Duplicate current Character UUID: "+c.globalId);
        }
        if(!current.keySet().equals(seen)||!WorldForgeApi.revisions().isCurrent(captured.revisions))return false;
        for(Update u:updates){Location location=current.get(u.id);if(location==null||!location.character.alive)return false;}
        try(CharacterRegistry.MutationBatch ignored=CharacterRegistry.beginMutationBatch()){
            for(Update u:updates){
                Location location=current.get(u.id);
                location.registry.applyLifeStageResult(location.character,u.age,u.stage,captured.year);
                PopulationSimulation.applyComputedResult(location.character,u.needs,u.intent,u.simulatedDays,u.lastYear);
            }
        }
        return true;
    }

    private static Map<String,Integer> needsSnapshot(Character c){
        Map<String,Integer> result=new TreeMap<>();Object raw=c.props.get("population.needs");
        if(raw instanceof Map<?,?> map)for(Map.Entry<?,?> e:map.entrySet())
            if(e.getKey() instanceof String key&&e.getValue() instanceof Number n)result.put(key,n.intValue());
        return result;
    }
    private static Map<?,?> object(Object v,String field){if(v instanceof Map<?,?> m)return m;throw new IllegalArgumentException("Expected object: "+field);}
    private static List<?> list(Object v,String field){if(v instanceof List<?> l)return l;throw new IllegalArgumentException("Expected array: "+field);}
    private static String string(Object v,String field){if(v instanceof String s&&!s.isBlank())return s;throw new IllegalArgumentException("Expected string: "+field);}
    private static int integer(Object v,String field){if(v instanceof Number n)return n.intValue();throw new IllegalArgumentException("Expected integer: "+field);}
    private static long longNumber(Object v,String field){if(v instanceof Number n)return n.longValue();throw new IllegalArgumentException("Expected long: "+field);}
    private static long decimalLong(Object v,String field){
        if(v instanceof String s)try{return Long.parseLong(s);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid long: "+field,e);}
        if(v instanceof Number n)return n.longValue();throw new IllegalArgumentException("Expected decimal long: "+field);
    }
}
