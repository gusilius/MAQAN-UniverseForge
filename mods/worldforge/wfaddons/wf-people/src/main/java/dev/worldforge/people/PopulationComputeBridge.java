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
        private final long targetWorldDay;
        private final DomainMutationRevisions.Snapshot revisions;
        private final List<String> characterIds, allCharacterIds;
        private final Map<String,Integer> birthYears;
        private final Map<String,Long> priorDays, elapsedById;
        private final byte[] request;
        private Captured(String worldId,int year,long elapsedDays,long targetWorldDay,DomainMutationRevisions.Snapshot revisions,
                         List<String> ids,List<String> allIds,Map<String,Integer> birthYears,Map<String,Long> priorDays,
                         Map<String,Long> elapsedById,byte[] request){
            this.worldId=worldId;this.year=year;this.elapsedDays=elapsedDays;this.targetWorldDay=targetWorldDay;this.revisions=revisions;
            this.characterIds=List.copyOf(ids);this.allCharacterIds=List.copyOf(allIds);
            this.birthYears=Map.copyOf(birthYears);this.priorDays=Map.copyOf(priorDays);this.elapsedById=Map.copyOf(elapsedById);this.request=request.clone();
        }
        public String worldId(){return worldId;} public int year(){return year;} public long elapsedDays(){return elapsedDays;}
        public long targetWorldDay(){return targetWorldDay;}
        public DomainMutationRevisions.Snapshot revisions(){return revisions;}
        public List<String> characterIds(){return characterIds;} public boolean hasWork(){return !characterIds.isEmpty();}
        public byte[] request(){return request.clone();}
    }
    private record Location(CharacterRegistry registry,Character character){}
    private record Update(String id,Map<String,Integer> needs,String intent,int age,String stage,long simulatedDays,int lastYear,long worldDay){}
    private PopulationComputeBridge(){}

    /** Compatibility capture: elapsedDays is measured from world day zero. */
    public static Captured capture(String worldId,int year,long elapsedDays,Collection<CharacterRegistry> registries){
        return captureForWorldDay(worldId,year,elapsedDays,0L,registries);
    }

    /**
     * Captures only Characters that have not acknowledged targetWorldDay.
     * Per-Character world-day receipts make retries safe across partial registry saves/reloads.
     */
    public static Captured captureForWorldDay(String worldId,int year,long targetWorldDay,long fallbackStartDay,
                                               Collection<CharacterRegistry> registries){
        if(worldId==null||worldId.isBlank())throw new IllegalArgumentException("worldId is required");
        if(targetWorldDay<0||fallbackStartDay<0||targetWorldDay<fallbackStartDay)
            throw new IllegalArgumentException("targetWorldDay cannot precede fallbackStartDay");
        Objects.requireNonNull(registries,"registries");
        DomainMutationRevisions.Snapshot before=WorldForgeApi.revisions().snapshot()
                .orElseThrow(()->new IllegalStateException("Canonical domain mutation is active"));
        TreeMap<String,Character> ordered=new TreeMap<>();
        for(CharacterRegistry registry:registries)for(Character c:Objects.requireNonNull(registry).all()){
            if(!c.alive)continue;
            if(ordered.putIfAbsent(c.globalId,c)!=null)throw new IllegalStateException("Duplicate canonical Character UUID: "+c.globalId);
        }
        List<Object> people=new ArrayList<>(ordered.size());List<String> ids=new ArrayList<>(ordered.size()),allIds=new ArrayList<>(ordered.size());
        Map<String,Integer> birthYears=new TreeMap<>();Map<String,Long> priorDays=new TreeMap<>(),elapsedById=new TreeMap<>();
        for(Character c:ordered.values()){
            allIds.add(c.globalId);
            Object rawWorldDay=c.props.get("population.last_simulated_world_day");
            long previousWorldDay=rawWorldDay instanceof Number n?Math.max(0L,n.longValue()):fallbackStartDay;
            long elapsed=previousWorldDay>=targetWorldDay?0L:targetWorldDay-previousWorldDay;
            if(elapsed==0)continue;
            Map<String,Object> person=new LinkedHashMap<>();
            person.put("id",c.globalId);person.put("birth_year",c.birthYear);person.put("alive",true);
            person.put("needs",needsSnapshot(c));person.put("duties",new ArrayList<>(PopulationSimulation.duties(c)));
            person.put("profession",PopulationSimulation.profession(c));person.put("training_target",PopulationSimulation.trainingTarget(c));
            person.put("command",String.valueOf(c.props.getOrDefault("population.command","")));
            person.put("can_work",PopulationSimulation.canWork(c,year));
            person.put("elapsed_days",elapsed);
            Object rawDays=c.props.get("population.simulated_days");
            long days=rawDays instanceof Number n?Math.max(0L,n.longValue()):0L;
            person.put("simulated_days",Long.toString(days));people.add(person);ids.add(c.globalId);
            birthYears.put(c.globalId,c.birthYear);priorDays.put(c.globalId,days);elapsedById.put(c.globalId,elapsed);
        }
        long batchElapsed=elapsedById.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("schema_version",SCHEMA_VERSION);request.put("world_id",worldId);request.put("year",year);
        request.put("elapsed_days",batchElapsed);request.put("world_day",targetWorldDay);request.put("characters",people);
        byte[] bytes=Json.write(request).getBytes(StandardCharsets.UTF_8);
        DomainMutationRevisions.Snapshot after=WorldForgeApi.revisions().snapshot()
                .orElseThrow(()->new IllegalStateException("Canonical mutation overlapped population capture"));
        if(!before.equals(after))throw new IllegalStateException("Canonical domain changed during population capture");
        return new Captured(worldId,year,batchElapsed,targetWorldDay,before,ids,allIds,birthYears,priorDays,elapsedById,bytes);
    }

    /** Validates the complete frame before the first write. False means stale and requires retry. */
    public static boolean apply(Captured captured,byte[] frameBytes,Collection<CharacterRegistry> registries){
        Objects.requireNonNull(captured);Objects.requireNonNull(frameBytes);Objects.requireNonNull(registries);
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions))return false;
        Map<?,?> frame=object(Json.parse(new String(frameBytes,StandardCharsets.UTF_8)),"frame");
        if(integer(frame.get("schema_version"),"schema_version")!=SCHEMA_VERSION)throw new IllegalArgumentException("Unsupported population frame schema");
        if(!captured.worldId.equals(string(frame.get("world_id"),"world_id"))
                ||captured.year!=integer(frame.get("year"),"year")
                ||captured.elapsedDays!=longNumber(frame.get("elapsed_days"),"elapsed_days")
                ||captured.targetWorldDay!=decimalLong(frame.get("world_day"),"world_day"))
            throw new IllegalArgumentException("Population frame does not match captured request");
        List<Update> updates=new ArrayList<>();Set<String> seen=new TreeSet<>();
        for(Object raw:list(frame.get("characters"),"characters")){
            Map<?,?> value=object(raw,"character update");String id=string(value.get("id"),"character id");
            if(!seen.add(id))throw new IllegalArgumentException("Duplicate population result ID: "+id);
            Map<String,Integer> needs=new TreeMap<>();
            for(Map.Entry<?,?> e:object(value.get("needs"),"needs").entrySet()){
                if(!(e.getKey() instanceof String key)||!(e.getValue() instanceof Number n))throw new IllegalArgumentException("Invalid need entry");
                double numeric=n.doubleValue();
                if(!Double.isFinite(numeric)||numeric!=Math.rint(numeric)
                        ||numeric<Integer.MIN_VALUE||numeric>Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Need value must be a 32-bit integer: "+key);
                boolean canonicalNeed=Set.of("hunger","rest","safety","social","purpose","leisure").contains(key);
                if(canonicalNeed&&(numeric<0||numeric>100))
                    throw new IllegalArgumentException("Canonical need must be in [0,100]: "+key);
                needs.put(key,(int)numeric);
            }
            for(String required:List.of("hunger","rest","social","purpose","leisure"))
                if(!needs.containsKey(required))throw new IllegalArgumentException("Missing required need: "+required);
            String intent=string(value.get("intent"),"intent");PopulationSimulation.intentFromKey(intent);
            int age=integer(value.get("age_years"),"age_years");
            Integer birthYear=captured.birthYears.get(id);
            if(birthYear==null||age!=Math.max(0,captured.year-birthYear))throw new IllegalArgumentException("Population age does not match captured birth year: "+id);
            String stage=string(value.get("life_stage"),"life_stage");
            String expectedStage=age<6?"infant":age<13?"child":age<18?"adolescent":age<60?"adult":"elder";
            if(!stage.equals(expectedStage))throw new IllegalArgumentException("Population life stage does not match age: "+id);
            long days=decimalLong(value.get("simulated_days"),"simulated_days");
            long prior=captured.priorDays.get(id),elapsed=captured.elapsedById.get(id);
            long expectedDays=elapsed>Long.MAX_VALUE-prior?Long.MAX_VALUE:prior+elapsed;
            if(days!=expectedDays)throw new IllegalArgumentException("Population elapsed-day acknowledgement mismatch: "+id);
            int lastYear=integer(value.get("last_simulated_year"),"last_simulated_year");
            if(lastYear!=captured.year)throw new IllegalArgumentException("Population year acknowledgement mismatch: "+id);
            long worldDay=decimalLong(value.get("world_day"),"world_day");
            if(worldDay!=captured.targetWorldDay)throw new IllegalArgumentException("Population world-day acknowledgement mismatch: "+id);
            updates.add(new Update(id,needs,intent,age,stage,days,lastYear,worldDay));
        }
        if(!seen.equals(new TreeSet<>(captured.characterIds)))throw new IllegalArgumentException("Population frame Character set differs from captured snapshot");
        Map<String,Location> current=new HashMap<>();
        for(CharacterRegistry registry:registries)for(Character c:registry.all())if(c.alive){
            if(current.putIfAbsent(c.globalId,new Location(registry,c))!=null)throw new IllegalStateException("Duplicate current Character UUID: "+c.globalId);
        }
        if(!current.keySet().equals(new TreeSet<>(captured.allCharacterIds))||!WorldForgeApi.revisions().isCurrent(captured.revisions))return false;
        for(Update u:updates){Location location=current.get(u.id);if(location==null||!location.character.alive)return false;}
        try(CharacterRegistry.MutationBatch ignored=CharacterRegistry.beginMutationBatch()){
            for(Update u:updates){
                Location location=current.get(u.id);
                location.registry.applyLifeStageResult(location.character,u.age,u.stage,captured.year);
                PopulationSimulation.applyComputedResult(location.character,u.needs,u.intent,u.simulatedDays,u.lastYear,u.worldDay);
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
