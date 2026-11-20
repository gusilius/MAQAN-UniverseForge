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
        private final List<String> characterIds;
        private final Map<String,Integer> birthYears;
        private final Map<String,Long> priorDays, elapsedById;
        private final byte[] request;
        private Captured(String worldId,int year,long elapsedDays,long targetWorldDay,DomainMutationRevisions.Snapshot revisions,
                         List<String> ids,Map<String,Integer> birthYears,Map<String,Long> priorDays,
                         Map<String,Long> elapsedById,byte[] request){
            this.worldId=worldId;this.year=year;this.elapsedDays=elapsedDays;this.targetWorldDay=targetWorldDay;this.revisions=revisions;
            this.characterIds=List.copyOf(ids);
            this.birthYears=Map.copyOf(birthYears);this.priorDays=Map.copyOf(priorDays);this.elapsedById=Map.copyOf(elapsedById);this.request=request.clone();
        }
        public String worldId(){return worldId;} public int year(){return year;} public long elapsedDays(){return elapsedDays;}
        public long targetWorldDay(){return targetWorldDay;}
        public DomainMutationRevisions.Snapshot revisions(){return revisions;}
        public List<String> characterIds(){return characterIds;} public boolean hasWork(){return !characterIds.isEmpty();}
        public byte[] request(){return request.clone();}
    }
    private record Update(String id,Map<String,Integer> needs,String intent,int age,String stage,long simulatedDays,int lastYear,long worldDay){}
    /**
     * Reusable membership-only index. It holds canonical references and is rebuilt
     * only when registry membership changes, not for every capture/apply operation.
     */
    private static final class RegistryIndex {
        private final List<CharacterRegistry> registries;
        private final long[] revisions;
        private final Character[] members;
        /** Registry ownership is indexed beside sorted members to avoid a registry scan per result. */
        private final CharacterRegistry[] owners;
        private RegistryIndex(Collection<CharacterRegistry> source) {
            registries = List.copyOf(source);
            revisions = new long[registries.size()];
            int memberCount = 0;
            for (CharacterRegistry registry : registries) memberCount = Math.addExact(memberCount, registry.all().size());
            members = new Character[memberCount];
            owners = new CharacterRegistry[memberCount];
            int cursor = 0;
            for (int i = 0; i < registries.size(); i++) {
                CharacterRegistry registry = Objects.requireNonNull(registries.get(i));
                revisions[i] = registry.revision();
                for (Character character : registry.all()) {
                    members[cursor] = character;
                    owners[cursor] = registry;
                    cursor++;
                }
            }
            sortMembersAndOwners();
            for (int i = 1; i < members.length; i++) {
                if (members[i - 1].globalId.equals(members[i].globalId))
                    throw new IllegalStateException("Duplicate canonical Character UUID: " + members[i].globalId);
            }
        }
        /** In-place paired heapsort avoids allocating one wrapper object per NPC during index rebuild. */
        private void sortMembersAndOwners() {
            int size = members.length;
            for (int root = size / 2 - 1; root >= 0; root--) siftDown(root, size);
            for (int end = size - 1; end > 0; end--) {
                swap(0, end);
                siftDown(0, end);
            }
        }
        private void siftDown(int root, int end) {
            while (true) {
                int child = root * 2 + 1;
                if (child >= end) return;
                if (child + 1 < end && compare(child, child + 1) < 0) child++;
                if (compare(root, child) >= 0) return;
                swap(root, child);
                root = child;
            }
        }
        private int compare(int left, int right) {
            return members[left].globalId.compareTo(members[right].globalId);
        }
        private void swap(int left, int right) {
            Character character = members[left];
            members[left] = members[right];
            members[right] = character;
            CharacterRegistry owner = owners[left];
            owners[left] = owners[right];
            owners[right] = owner;
        }
        }
        private boolean matches(Collection<CharacterRegistry> source) {
            if (source.size() != registries.size()) return false;
            int i = 0;
            for (CharacterRegistry registry : source) {
                if (registry != registries.get(i) || registry.revision() != revisions[i]) return false;
                i++;
            }
            return true;
        }
        private Character[] members() { return members; }
        private int findIndex(String globalId) {
            int low = 0, high = members.length - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                int cmp = members[mid].globalId.compareTo(globalId);
                if (cmp == 0) return mid;
                if (cmp < 0) low = mid + 1; else high = mid - 1;
            }
            return -1;
        }
        private Character find(String globalId) {
            int index = findIndex(globalId);
            return index < 0 ? null : members[index];
        }
        private CharacterRegistry ownerAt(int index) { return owners[index]; }
    }
    private static volatile RegistryIndex cachedIndex;
    private static RegistryIndex indexFor(Collection<CharacterRegistry> registries) {
        RegistryIndex current = cachedIndex;
        if (current != null && current.matches(registries)) return current;
        synchronized (PopulationComputeBridge.class) {
            current = cachedIndex;
            if (current == null || !current.matches(registries)) cachedIndex = current = new RegistryIndex(registries);
            return current;
        }
    }

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
        DomainMutationRevisions.Snapshot before=WorldForgeApi.revisions().snapshot(DomainMutationRevisions.Domain.CHARACTERS)
                .orElseThrow(()->new IllegalStateException("Canonical domain mutation is active"));
        RegistryIndex registryIndex = indexFor(registries);
        Character[] ordered = registryIndex.members();
        List<Object> people = new ArrayList<>(ordered.length);
        List<String> ids = new ArrayList<>(ordered.length);
        Map<String,Integer> birthYears = new HashMap<>();
        Map<String,Long> priorDays = new HashMap<>(), elapsedById = new HashMap<>();
        for (Character c : ordered) {
            if (!c.alive) continue; // cached membership must not cache mutable life-state
            Object rawWorldDay=c.props.get("population.last_simulated_world_day");
            long previousWorldDay=rawWorldDay==null?fallbackStartDay:PopulationSimulation.persistedNonNegativeLong(rawWorldDay);
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
            long days=PopulationSimulation.persistedNonNegativeLong(rawDays);
            person.put("simulated_days",Long.toString(days));people.add(person);ids.add(c.globalId);
            birthYears.put(c.globalId,c.birthYear);priorDays.put(c.globalId,days);elapsedById.put(c.globalId,elapsed);
        }
        long batchElapsed=elapsedById.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("schema_version",SCHEMA_VERSION);request.put("world_id",worldId);request.put("year",year);
        request.put("elapsed_days",batchElapsed);request.put("world_day",targetWorldDay);request.put("characters",people);
        byte[] bytes=Json.write(request).getBytes(StandardCharsets.UTF_8);
        DomainMutationRevisions.Snapshot after=WorldForgeApi.revisions().snapshot(DomainMutationRevisions.Domain.CHARACTERS)
                .orElseThrow(()->new IllegalStateException("Canonical mutation overlapped population capture"));
        if(!before.equals(after))throw new IllegalStateException("Canonical domain changed during population capture");
        return new Captured(worldId,year,batchElapsed,targetWorldDay,before,ids,birthYears,priorDays,elapsedById,bytes);
    }

    /** Validates the complete frame before the first write. False means stale and requires retry. */
    public static boolean apply(Captured captured,byte[] frameBytes,Collection<CharacterRegistry> registries){
        Objects.requireNonNull(captured);Objects.requireNonNull(frameBytes);Objects.requireNonNull(registries);
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions,DomainMutationRevisions.Domain.CHARACTERS))return false;
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
        RegistryIndex current = indexFor(registries);
        if (!WorldForgeApi.revisions().isCurrent(captured.revisions, DomainMutationRevisions.Domain.CHARACTERS)) return false;
        int[] memberIndices = new int[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            Update u = updates.get(i);
            int memberIndex = current.findIndex(u.id);
            if (memberIndex < 0 || !current.members()[memberIndex].alive) return false;
            memberIndices[i] = memberIndex;
        }
        if (!WorldForgeApi.revisions().isCurrent(captured.revisions, DomainMutationRevisions.Domain.CHARACTERS)) return false;
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            for (int i = 0; i < updates.size(); i++) {
                Update u = updates.get(i);
                Character character = current.members()[memberIndices[i]];
                CharacterRegistry owner = current.ownerAt(memberIndices[i]);
                owner.applyLifeStageResult(character, u.age, u.stage, captured.year);
                PopulationSimulation.applyComputedResult(character, u.needs, u.intent, u.simulatedDays, u.lastYear, u.worldDay);
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
