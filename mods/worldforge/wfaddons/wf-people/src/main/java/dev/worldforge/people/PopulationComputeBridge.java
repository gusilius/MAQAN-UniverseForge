package dev.worldforge.people;

import dev.worldforge.mod.Json;
import dev.worldforge.mod.api.DomainMutationRevisions;
import dev.worldforge.mod.api.WorldForgeApi;
import dev.worldforge.mod.api.WorldForgeComputeRuntime;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable server-thread capture and validated apply boundary for Rust population batches. */
public final class PopulationComputeBridge {
    public static final int SCHEMA_VERSION = 1;
    public static final class Scan {
        private final List<CharacterRegistry> registries;
        private final long[] membershipGenerations;
        private final int[] starts, limits;
        // Reuse one insertion-order iterator across successive server-tick slices. Repeated
        // List.get(index) would restart from the head for every Character and become O(n^2).
        @SuppressWarnings("unchecked")
        private final Iterator<Character>[] memberIterators;
        private final int[] iteratorPositions;

        private Scan(Collection<CharacterRegistry> source, int[] starts, int[] limits) {
            List<CharacterRegistry> ordered = new ArrayList<>();
            Set<CharacterRegistry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (CharacterRegistry registry : source) {
                Objects.requireNonNull(registry, "registry");
                if (seen.add(registry)) ordered.add(registry);
            }
            this.registries = List.copyOf(ordered);
            this.membershipGenerations = new long[ordered.size()];
            this.starts = starts == null ? new int[ordered.size()] : starts.clone();
            this.limits = limits == null ? new int[ordered.size()] : limits.clone();
            this.memberIterators = (Iterator<Character>[]) new Iterator<?>[ordered.size()];
            this.iteratorPositions = new int[ordered.size()];
            Arrays.fill(this.iteratorPositions, -1);
            if (this.starts.length != ordered.size() || this.limits.length != ordered.size())
                throw new IllegalArgumentException("Population scan partition shape changed");
            for (int i = 0; i < ordered.size(); i++) {
                CharacterRegistry registry = ordered.get(i);
                membershipGenerations[i] = registry.membershipGeneration();
                if (starts == null) this.starts[i] = 0;
                if (limits == null) this.limits[i] = registry.size();
                if (this.starts[i] < 0 || this.limits[i] < this.starts[i]
                        || this.limits[i] > registry.size())
                    throw new IllegalArgumentException("Invalid population scan partition bounds");
            }
        }

        /** Stable registry membership plus append-only per-registry views; no population-wide copy or sort. */
        public static Scan begin(Collection<CharacterRegistry> registries) {
            return new Scan(Objects.requireNonNull(registries, "registries"), null, null);
        }

        public boolean matches(Collection<CharacterRegistry> source) {
            if (source == null) return false;
            List<CharacterRegistry> current = new ArrayList<>();
            Set<CharacterRegistry> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (CharacterRegistry registry : source) if (registry != null && seen.add(registry)) current.add(registry);
            if (current.size() != registries.size()) return false;
            for (int i = 0; i < current.size(); i++) {
                if (current.get(i) != registries.get(i)
                        || current.get(i).membershipGeneration() != membershipGenerations[i]) return false;
            }
            return true;
        }

        /** Captures only newly appended tails after the current bounded pass has completed. */
        public Scan appendedTail() {
            int[] nextStarts = limits.clone();
            int[] nextLimits = limits.clone();
            boolean appended = false;
            for (int i = 0; i < registries.size(); i++) {
                CharacterRegistry registry = registries.get(i);
                if (registry.membershipGeneration() != membershipGenerations[i])
                    throw new IllegalStateException("Character registry was restored during population scan");
                int currentSize = registry.size();
                if (currentSize < limits[i])
                    throw new IllegalStateException("Append-only Character registry unexpectedly shrank");
                if (currentSize > limits[i]) {
                    nextLimits[i] = currentSize;
                    appended = true;
                }
            }
            return appended ? new Scan(registries, nextStarts, nextLimits) : null;
        }

        private CharacterRegistry registry(int index) { return registries.get(index); }

        /**
         * Returns the next Character at the requested stable cursor. Sequential slices reuse
         * their iterator, so scanning N residents costs O(N), not O(N * numberOfSlices).
         * If a registry append invalidates LinkedHashMap's fail-fast iterator, rebuild it once
         * at the requested cursor; a failed/stale slice can use the same recovery path.
         */
        public Character nextCharacter(int registryIndex, int memberIndex) {
            CharacterRegistry registry = registries.get(registryIndex);
            Iterator<Character> iterator = memberIterators[registryIndex];
            if (iterator == null || iteratorPositions[registryIndex] != memberIndex) {
                iterator = registry.all().iterator();
                for (int skipped = 0; skipped < memberIndex; skipped++) {
                    if (!iterator.hasNext())
                        throw new ConcurrentModificationException("Character population changed during cursor recovery");
                    iterator.next();
                }
                memberIterators[registryIndex] = iterator;
                iteratorPositions[registryIndex] = memberIndex;
            }
            try {
                if (!iterator.hasNext())
                    throw new ConcurrentModificationException("Character population changed during cursor scan");
                Character character = iterator.next();
                iteratorPositions[registryIndex] = memberIndex + 1;
                return character;
            } catch (ConcurrentModificationException changed) {
                iterator = registry.all().iterator();
                for (int skipped = 0; skipped < memberIndex; skipped++) {
                    if (!iterator.hasNext())
                        throw new ConcurrentModificationException("Character population changed during cursor recovery");
                    iterator.next();
                }
                if (!iterator.hasNext())
                    throw new ConcurrentModificationException("Character population changed during cursor recovery");
                Character character = iterator.next();
                memberIterators[registryIndex] = iterator;
                iteratorPositions[registryIndex] = memberIndex + 1;
                return character;
            }
        }

        /** Releases the final LinkedHashMap nodes retained by exhausted scan iterators. */
        private void releaseIterators() {
            Arrays.fill(memberIterators, null);
            Arrays.fill(iteratorPositions, -1);
        }

        private int limit(int index) { return limits[index]; }
        /** Number of stable Character partitions in this scan. */
        public int registryCount() { return registries.size(); }
        /** First member offset for a partition, exposed for the cross-addon bounded coordinator. */
        public int start(int index) { return starts[index]; }
        public int firstMemberCursor() { return starts.length == 0 ? 0 : starts[0]; }
        private boolean current() {
            for (int i = 0; i < registries.size(); i++)
                if (registries.get(i).membershipGeneration() != membershipGenerations[i]) return false;
            return true;
        }
    }

    public static final class Captured {
        private final String worldId; private final int year; private final long elapsedDays;
        private final long targetWorldDay;
        private final DomainMutationRevisions.Snapshot revisions;
        private final Scan scan;
        private final List<String> characterIds;
        /** One compact per-character entry replaces five parallel maps and boxed values. */
        private final Map<String,CapturedCharacter> characters;
        private final int nextRegistryCursor, nextMemberCursor;
        private final boolean scanComplete;
        private final byte[] request;
        private Captured(String worldId,int year,long elapsedDays,long targetWorldDay,DomainMutationRevisions.Snapshot revisions,
                         Scan scan,List<String> ids,Map<String,CapturedCharacter> characters,
                         int nextRegistryCursor,int nextMemberCursor,boolean scanComplete,byte[] request){
            this.worldId=worldId;this.year=year;this.elapsedDays=elapsedDays;this.targetWorldDay=targetWorldDay;this.revisions=revisions;
            this.scan=scan;this.characterIds=Collections.unmodifiableList(ids);
            // All collections and bytes are privately owned by this capture.
            this.characters=characters;
            this.nextRegistryCursor=nextRegistryCursor;this.nextMemberCursor=nextMemberCursor;this.scanComplete=scanComplete;
            this.request=request;
        }
        public String worldId(){return worldId;} public int year(){return year;} public long elapsedDays(){return elapsedDays;}
        public long targetWorldDay(){return targetWorldDay;}
        public DomainMutationRevisions.Snapshot revisions(){return revisions;}
        public Scan scan(){return scan;}
        public List<String> characterIds(){return characterIds;} public boolean hasWork(){return !characterIds.isEmpty();}
        public int nextRegistryCursor(){return nextRegistryCursor;} public int nextMemberCursor(){return nextMemberCursor;}
        public boolean scanComplete(){return scanComplete;}
        public byte[] request(){return request.clone();}
        /**
         * Submits the owned immutable snapshot directly to the bounded runtime. The runtime
         * takes its defensive copy synchronously, avoiding an intermediate full-size clone.
         */
        public boolean submit(WorldForgeComputeRuntime runtime, WorldForgeComputeRuntime.Ticket ticket,
                              byte[] secondary, WorldForgeComputeRuntime.Operation operation) {
            return Objects.requireNonNull(runtime, "runtime").submit(ticket, request, secondary, operation);
        }
    }
    /** Detached acknowledgement metadata; never retain a mutable Character across async compute. */
    private record CapturedCharacter(CharacterRegistry owner, int birthYear, long priorDays, long elapsedDays) {}
    private record Update(String id,Map<String,Integer> needs,String intent,int age,String stage,long simulatedDays,int lastYear,long worldDay){}
    private static final Set<String> CANONICAL_NEEDS = Set.of("hunger", "rest", "safety", "social", "purpose", "leisure");
    private static final List<String> REQUIRED_NEEDS = List.of("hunger", "rest", "social", "purpose", "leisure");
    private PopulationComputeBridge(){}

    /** Compatibility capture: elapsedDays is measured from world day zero. */
    public static Captured capture(String worldId,int year,long elapsedDays,Collection<CharacterRegistry> registries){
        Scan scan=Scan.begin(registries);
        return captureForWorldDay(worldId,year,elapsedDays,0L,registries,scan,0,
                scan.firstMemberCursor(), Integer.MAX_VALUE);
    }

    /** Begins one stable scan. Registry appends are tracked as tail ranges; wholesale restores invalidate it. */
    public static Scan beginScan(Collection<CharacterRegistry> registries) {
        return Scan.begin(registries);
    }

    /**
     * Captures a bounded range from append-only CharacterRegistry views. No global member array,
     * sort, wrapper allocation or population-wide traversal is performed during a slice.
     */
    public static Captured captureForWorldDay(String worldId,int year,long targetWorldDay,long fallbackStartDay,
                                               Collection<CharacterRegistry> registries,Scan scan,
                                               int startRegistryCursor,int startMemberCursor,int scanLimit){
        if(worldId==null||worldId.isBlank())throw new IllegalArgumentException("worldId is required");
        if(targetWorldDay<0||fallbackStartDay<0||targetWorldDay<fallbackStartDay)
            throw new IllegalArgumentException("targetWorldDay cannot precede fallbackStartDay");
        if(startRegistryCursor<0||startMemberCursor<0||scanLimit<1)
            throw new IllegalArgumentException("population scan cursor/limit is invalid");
        Objects.requireNonNull(registries,"registries");Objects.requireNonNull(scan,"scan");
        if(!scan.matches(registries))throw new IllegalStateException("Character registry membership changed during population scan");
        if(startRegistryCursor>scan.registryCount()
                ||(startRegistryCursor==scan.registryCount()&&startMemberCursor!=0))
            throw new IllegalArgumentException("population scan cursor exceeds registry bounds");
        if(startRegistryCursor<scan.registryCount()
                &&(startMemberCursor<scan.start(startRegistryCursor)||startMemberCursor>scan.limit(startRegistryCursor)))
            throw new IllegalArgumentException("population member cursor exceeds partition bounds");

        DomainMutationRevisions.Snapshot before=WorldForgeApi.revisions().snapshot(DomainMutationRevisions.Domain.CHARACTERS)
                .orElseThrow(()->new IllegalStateException("Canonical domain mutation is active"));
        List<Object> people=new ArrayList<>(Math.min(scanLimit,1024));
        List<String> ids=new ArrayList<>(Math.min(scanLimit,1024));
        int captureCapacity=Math.max(16,Math.min(1024,Math.min(scanLimit,768)*4/3));
        Map<String,CapturedCharacter> characters=new HashMap<>(captureCapacity);
        long batchElapsed=0L;
        int registryCursor=startRegistryCursor;
        int memberCursor=startMemberCursor;
        int inspected=0;
        while(registryCursor<scan.registryCount()&&inspected<scanLimit){
            CharacterRegistry registry=scan.registry(registryCursor);
            int end=scan.limit(registryCursor);
            while(memberCursor<end&&inspected<scanLimit){
                Character character=scan.nextCharacter(registryCursor,memberCursor++);
                inspected++;
                if(!character.alive)continue;
                Object rawWorldDay=character.props.get("population.last_simulated_world_day");
                long previousWorldDay=rawWorldDay==null?fallbackStartDay:PopulationSimulation.persistedNonNegativeLong(rawWorldDay);
                long elapsed=previousWorldDay>=targetWorldDay?0L:targetWorldDay-previousWorldDay;
                if(elapsed==0)continue;
                String id=character.globalId;
                Map<String,Object> person=new LinkedHashMap<>();
                person.put("id",id);person.put("birth_year",character.birthYear);person.put("alive",true);
                person.put("needs",needsSnapshot(character));person.put("duties",new ArrayList<>(PopulationSimulation.duties(character)));
                person.put("profession",PopulationSimulation.profession(character));person.put("training_target",PopulationSimulation.trainingTarget(character));
                person.put("command",String.valueOf(character.props.getOrDefault("population.command","")));
                person.put("can_work",PopulationSimulation.canWork(character,year));person.put("elapsed_days",elapsed);
                long days=PopulationSimulation.persistedNonNegativeLong(character.props.get("population.simulated_days"));
                person.put("simulated_days",Long.toString(days));people.add(person);ids.add(id);
                characters.put(id,new CapturedCharacter(registry,character.birthYear,days,elapsed));
                batchElapsed=Math.max(batchElapsed,elapsed);
            }
            if(memberCursor>=end){
                registryCursor++;
                memberCursor=registryCursor<scan.registryCount()?scan.start(registryCursor):0;
            }
        }
        boolean complete=registryCursor>=scan.registryCount();
        if (complete) scan.releaseIterators();
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("schema_version",SCHEMA_VERSION);request.put("world_id",worldId);request.put("year",year);
        request.put("elapsed_days",batchElapsed);request.put("world_day",targetWorldDay);request.put("characters",people);
        byte[] bytes=Json.write(request).getBytes(StandardCharsets.UTF_8);
        // Compare only the population dependency lane. Snapshot.equals also compares the
        // global epoch, which would let unrelated social writes invalidate this capture.
        if(!WorldForgeApi.revisions().isCurrent(before,DomainMutationRevisions.Domain.CHARACTERS))
            throw new IllegalStateException("Population domain changed during population capture");
        if(!scan.current())throw new IllegalStateException("Character registry was restored during population capture");
        return new Captured(worldId,year,batchElapsed,targetWorldDay,before,scan,ids,characters,
                registryCursor,memberCursor,complete,bytes);
    }

    /** Validates the complete frame before the first write. False means stale and requires retry. */
    public static boolean apply(Captured captured,byte[] frameBytes,Collection<CharacterRegistry> registries){
        Objects.requireNonNull(captured);Objects.requireNonNull(frameBytes);Objects.requireNonNull(registries);
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions,DomainMutationRevisions.Domain.CHARACTERS)
                ||!captured.scan.matches(registries))return false;
        Map<?,?> frame=object(Json.parse(new String(frameBytes,StandardCharsets.UTF_8)),"frame");
        if(integer(frame.get("schema_version"),"schema_version")!=SCHEMA_VERSION)throw new IllegalArgumentException("Unsupported population frame schema");
        if(!captured.worldId.equals(string(frame.get("world_id"),"world_id"))
                ||captured.year!=integer(frame.get("year"),"year")
                ||captured.elapsedDays!=longNumber(frame.get("elapsed_days"),"elapsed_days")
                ||captured.targetWorldDay!=decimalLong(frame.get("world_day"),"world_day"))
            throw new IllegalArgumentException("Population frame does not match captured request");
        List<Update> updates=new ArrayList<>();Set<String> seen=new HashSet<>(Math.max(16,captured.characters.size()*4/3));
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
                boolean canonicalNeed=CANONICAL_NEEDS.contains(key);
                if(canonicalNeed&&(numeric<0||numeric>100))
                    throw new IllegalArgumentException("Canonical need must be in [0,100]: "+key);
                needs.put(key,(int)numeric);
            }
            for(String required:REQUIRED_NEEDS)
                if(!needs.containsKey(required))throw new IllegalArgumentException("Missing required need: "+required);
            String intent=string(value.get("intent"),"intent");PopulationSimulation.intentFromKey(intent);
            int age=integer(value.get("age_years"),"age_years");
            CapturedCharacter source=captured.characters.get(id);
            if(source==null||age!=Math.max(0,captured.year-source.birthYear()))
                throw new IllegalArgumentException("Population age does not match captured birth year: "+id);
            String stage=string(value.get("life_stage"),"life_stage");
            String expectedStage=age<6?"infant":age<13?"child":age<18?"adolescent":age<60?"adult":"elder";
            if(!stage.equals(expectedStage))throw new IllegalArgumentException("Population life stage does not match age: "+id);
            long days=decimalLong(value.get("simulated_days"),"simulated_days");
            long prior=source.priorDays(),elapsed=source.elapsedDays();
            long expectedDays=elapsed>Long.MAX_VALUE-prior?Long.MAX_VALUE:prior+elapsed;
            if(days!=expectedDays)throw new IllegalArgumentException("Population elapsed-day acknowledgement mismatch: "+id);
            int lastYear=integer(value.get("last_simulated_year"),"last_simulated_year");
            if(lastYear!=captured.year)throw new IllegalArgumentException("Population year acknowledgement mismatch: "+id);
            long worldDay=decimalLong(value.get("world_day"),"world_day");
            if(worldDay!=captured.targetWorldDay)throw new IllegalArgumentException("Population world-day acknowledgement mismatch: "+id);
            updates.add(new Update(id,needs,intent,age,stage,days,lastYear,worldDay));
        }
        if(!seen.equals(captured.characters.keySet()))throw new IllegalArgumentException("Population frame Character set differs from captured snapshot");
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions,DomainMutationRevisions.Domain.CHARACTERS)
                ||!captured.scan.matches(registries))return false;
        for(Update update:updates){
            CapturedCharacter source=captured.characters.get(update.id);
            CharacterRegistry owner=source==null?null:source.owner();
            Character character=owner==null?null:owner.getByGlobalId(update.id);
            if(character==null||!character.alive||character.birthYear!=source.birthYear())return false;
        }
        if(!WorldForgeApi.revisions().isCurrent(captured.revisions,DomainMutationRevisions.Domain.CHARACTERS))return false;
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            for(Update update:updates){
                CapturedCharacter source=captured.characters.get(update.id);
                CharacterRegistry owner=source.owner();
                Character character=owner.getByGlobalId(update.id);
                if(character==null||!character.alive||character.birthYear!=source.birthYear())
                    throw new IllegalStateException("Canonical Character changed after population frame validation: "+update.id);
                owner.applyLifeStageResult(character,update.age,update.stage,captured.year);
                PopulationSimulation.applyComputedResult(character,update.needs,update.intent,update.simulatedDays,update.lastYear,update.worldDay);
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
