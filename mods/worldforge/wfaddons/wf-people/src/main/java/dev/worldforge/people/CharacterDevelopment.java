package dev.worldforge.people;

import java.util.*;

/**
 * Canonical character-development facade. Race, class, rarity, level, abilities and temporary
 * mood are orthogonal facets stored on the existing Character; this class never creates identities.
 * Race/class identifiers remain data-driven so modpacks can add their own taxonomies.
 */
public final class CharacterDevelopment {
    public enum Rarity {
        COMMON(0), UNCOMMON(1), RARE(2), EPIC(3), LEGENDARY(4), MYTHIC(5), UNIQUE(6);
        public final int tier;
        Rarity(int tier){this.tier=tier;}
    }
    public enum Mood { NEUTRAL, HAPPY, SAD, ANGRY, AFRAID, STRESSED, INSPIRED, EXHAUSTED }

    /** Conducts which can either reinforce or violate a person's internal principles. */
    public enum Conduct {
        KEEP_PROMISE, BREAK_PROMISE, TELL_TRUTH, LIE, SHOW_MERCY, CRUELTY, DEFEND_WEAK,
        ABANDON_ALLY, HONOR_DUTY, BETRAYAL, SEEK_REVENGE, FORGIVE, SHARE_WEALTH,
        EXPLOIT, FLEE, STAND_FIRM, CARE_FOR_CHILD, NEGLECT_CHILD
    }
    public enum Experience {
        BATTLE_VICTORY, BATTLE_DEFEAT, SURVIVED_DANGER, BETRAYED, BETRAYED_SOMEONE,
        BEREAVEMENT, PUBLIC_SUCCESS, PUBLIC_HUMILIATION, POVERTY, WEALTH_GAIN,
        EDUCATION, PARENTAL_CARE, NEGLECT, MARRIAGE, DIVORCE, ORPHANED,
        MENTORSHIP, SOCIAL_REJECTION, RECONCILIATION, COMRADE_FALLEN
    }

    /** Stable psychological dimensions; unlike mood, these change slowly and belong to the person. */
    public enum Temperament { RISK, DISCIPLINE, SOCIABILITY, EMPATHY, AMBITION, ADAPTABILITY, STABILITY, CURIOSITY }
    /** Long-term motives can coexist; a character may want both family security and political power. */
    public enum Drive { POWER, WEALTH, FAMILY, SECURITY, PRESTIGE, KNOWLEDGE, ADVENTURE, FAITH, REVENGE, INDEPENDENCE, DUTY, COMMUNITY }
    private static final String RACE="identity.race", CLASS="rpg.class", RARITY="character.rarity";
    private static final String LEVEL="progress.level", ABILITIES="character.abilities";
    private static final String MOOD="character.mood.state", MOOD_UNTIL="character.mood.until", MOOD_CAUSE="character.mood.cause";
    private CharacterDevelopment(){}

    public static String race(Character c){return string(c,RACE,"unassigned");}
    public static String characterClass(Character c){return string(c,CLASS,"unassigned");}
    public static Rarity rarity(Character c){
        try{return Rarity.valueOf(string(c,RARITY,"COMMON").toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException ignored){return Rarity.COMMON;}
    }
    public static int level(Character c){
        Object v=c.props.get(LEVEL);return v instanceof Number n?Math.max(1,Math.min(1000,n.intValue())):1;
    }
    public static void setRace(Character c,String id){ setValue(c,RACE,id(id,"race")); }
    public static void setClass(Character c,String id){ setValue(c,CLASS,id(id,"class")); }
    public static void setRarity(Character c,Rarity rarity){ setValue(c,RARITY,Objects.requireNonNull(rarity).name()); }
    public static void setLevel(Character c,int level){
        if(level<1||level>1000)throw new IllegalArgumentException("level must be in 1..1000");
        setValue(c,LEVEL,level);
    }

    public static Mood mood(Character c,long worldMinute){
        Object raw=c.props.get(MOOD);
        Mood mood;
        try{mood=Mood.valueOf(raw==null?"NEUTRAL":String.valueOf(raw));}
        catch(IllegalArgumentException ignored){mood=Mood.NEUTRAL;}
        Object until=c.props.get(MOOD_UNTIL);
        if(mood!=Mood.NEUTRAL&&until instanceof Number n&&worldMinute>=n.longValue()){
            c.props.put(MOOD,Mood.NEUTRAL.name());c.props.remove(MOOD_UNTIL);c.props.remove(MOOD_CAUSE);
            CharacterRegistry.recordCharacterMutation();
            return Mood.NEUTRAL;
        }
        return mood;
    }
    public static void setMood(Character c,Mood mood,long worldMinute,long duration,String cause){
        Objects.requireNonNull(mood);
        if(worldMinute<0||duration<0)throw new IllegalArgumentException("invalid mood time");
        Map<String,Object> before = new LinkedHashMap<>();
        before.put(MOOD,c.props.get(MOOD)); before.put(MOOD_UNTIL,c.props.get(MOOD_UNTIL)); before.put(MOOD_CAUSE,c.props.get(MOOD_CAUSE));
        c.props.put(MOOD,mood.name());
        if(mood==Mood.NEUTRAL||duration==0){c.props.remove(MOOD_UNTIL);c.props.remove(MOOD_CAUSE);}
        else{
            c.props.put(MOOD_UNTIL,Math.addExact(worldMinute,duration));
            c.props.put(MOOD_CAUSE,cause==null?"":cause);
        }
        if (!Objects.equals(before.get(MOOD),c.props.get(MOOD))
                || !Objects.equals(before.get(MOOD_UNTIL),c.props.get(MOOD_UNTIL))
                || !Objects.equals(before.get(MOOD_CAUSE),c.props.get(MOOD_CAUSE)))
            CharacterRegistry.recordCharacterMutation();
    }
    public static String moodCause(Character c){return string(c,MOOD_CAUSE,"");}

    private static String psychologyIdentity(Character c) {
        return c.globalId == null || c.globalId.isBlank() ? c.id : c.globalId;
    }

    private static long psychologyHash(String value) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < value.length(); i++) h = (h ^ value.charAt(i)) * 0x100000001B3L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h;
    }

    private static boolean hasTrait(Character c, String id) {
        return CharacterStats.traits(c).contains(id);
    }

    /** Deterministic 0..100 baseline with trait-shaped variation and an explicit persisted override. */
    public static int temperament(Character c, Temperament facet) {
        Objects.requireNonNull(c); Objects.requireNonNull(facet);
        String key = "personality.temperament." + facet.name().toLowerCase(Locale.ROOT);
        Object stored = c.props.get(key);
        if (stored instanceof Number n) return clamp(n.intValue(), 0, 100);
        int value = 35 + (int) Math.floorMod(psychologyHash(psychologyIdentity(c) + "|temperament|" + facet), 31L);
        value += switch (facet) {
            case RISK -> trait(c,"brave",22) + trait(c,"reckless",22) + trait(c,"cautious",-22) + trait(c,"craven",-25) + trait(c,"paranoid",-8);
            case DISCIPLINE -> trait(c,"diligent",24) + trait(c,"dutiful",16) + trait(c,"patient",10) + trait(c,"lazy",-26) + trait(c,"wroth",-10);
            case SOCIABILITY -> trait(c,"gregarious",28) + trait(c,"sociable",24) + trait(c,"shy",-24) + trait(c,"reclusive",-28);
            case EMPATHY -> trait(c,"compassionate",28) + trait(c,"just",10) + trait(c,"generous",10) + trait(c,"cruel",-24) + trait(c,"sadistic",-28);
            case AMBITION -> trait(c,"ambitious",28) + trait(c,"ambitious_schemer",20) + trait(c,"content",-28) + trait(c,"humble",-8);
            case ADAPTABILITY -> trait(c,"adaptable",28) + trait(c,"innovative",16) + trait(c,"stubborn",-24) + trait(c,"traditionalist",-8);
            case STABILITY -> trait(c,"patient",18) + trait(c,"resilient",18) + trait(c,"wroth",-24) + trait(c,"melancholic",-12) + trait(c,"optimistic",8) + trait(c,"pessimistic",-8);
            case CURIOSITY -> trait(c,"curious",24) + trait(c,"scholar",24) + trait(c,"sceptical",10) + trait(c,"innovative",18) + trait(c,"traditionalist",-8);
        };
        return clamp(value, 0, 100);
    }

    public static void setTemperament(Character c, Temperament facet, int value) {
        Objects.requireNonNull(c); Objects.requireNonNull(facet);
        setValue(c,"personality.temperament." + facet.name().toLowerCase(Locale.ROOT),clamp(value,0,100));
    }

    /** Motivation strength is not an exclusive alignment: several strong, even competing drives are valid. */
    public static int drive(Character c, Drive drive) {
        Objects.requireNonNull(c); Objects.requireNonNull(drive);
        String key = "personality.drive." + drive.name().toLowerCase(Locale.ROOT);
        Object stored = c.props.get(key);
        if (stored instanceof Number n) return clamp(n.intValue(), 0, 100);
        int value = 25 + (int) Math.floorMod(psychologyHash(psychologyIdentity(c) + "|drive|" + drive), 51L);
        value += switch (drive) {
            case POWER -> trait(c,"ambitious",22) + trait(c,"ambitious_schemer",18) + trait(c,"independent",8) + trait(c,"content",-18);
            case WEALTH -> trait(c,"greedy",24) + trait(c,"resourceful",8) + trait(c,"generous",-12);
            case FAMILY -> trait(c,"compassionate",14) + trait(c,"dutiful",12) + trait(c,"independent",-6);
            case SECURITY -> trait(c,"cautious",18) + trait(c,"craven",22) + trait(c,"paranoid",14) + trait(c,"brave",-12) + trait(c,"reckless",-12);
            case PRESTIGE -> trait(c,"arrogant",14) + trait(c,"eloquent",10) + trait(c,"brave",8) + trait(c,"humble",-12);
            case KNOWLEDGE -> trait(c,"scholar",22) + trait(c,"curious",20) + trait(c,"sceptical",10) + trait(c,"innovative",8);
            case ADVENTURE -> trait(c,"reckless",20) + trait(c,"brave",12) + trait(c,"curious",8) + trait(c,"cautious",-14);
            case FAITH -> trait(c,"zealous",24) + trait(c,"traditionalist",10) + trait(c,"cynical",-12);
            case REVENGE -> trait(c,"vengeful",28) + trait(c,"wroth",10) + trait(c,"forgiving",-18);
            case INDEPENDENCE -> trait(c,"independent",24) + trait(c,"stubborn",12) + trait(c,"dutiful",-8);
            case DUTY -> trait(c,"dutiful",22) + trait(c,"just",10) + trait(c,"diligent",28) + trait(c,"lazy",-16);
            case COMMUNITY -> trait(c,"gregarious",16) + trait(c,"sociable",18) + trait(c,"generous",12) + trait(c,"reclusive",-18);
        };
        return clamp(value, 0, 100);
    }

    public static void setDrive(Character c, Drive drive, int value) {
        Objects.requireNonNull(c); Objects.requireNonNull(drive);
        setValue(c,"personality.drive." + drive.name().toLowerCase(Locale.ROOT),clamp(value,0,100));
    }

    /** Materializes all stable facets into the canonical Character save without creating another profile. */
    public static void ensurePsychology(Character c) {
        for (Temperament facet : Temperament.values()) {
            String key = "personality.temperament." + facet.name().toLowerCase(Locale.ROOT);
            if (!(c.props.get(key) instanceof Number)) c.props.put(key, temperament(c, facet));
        }
        for (Drive drive : Drive.values()) {
            String key = "personality.drive." + drive.name().toLowerCase(Locale.ROOT);
            if (!(c.props.get(key) instanceof Number)) c.props.put(key, drive(c, drive));
        }
        if (!(c.props.get("psychology.stress") instanceof Number)) c.props.put("psychology.stress", 0);
    }

    public static int stress(Character c) {
        Object raw = c.props.get("psychology.stress");
        return raw instanceof Number n ? clamp(n.intValue(), 0, 100) : 0;
    }

    public static int changeStress(Character c, int delta) {
        int next = clamp(stress(c) + delta, 0, 100);
        if (stress(c) != next) { c.props.put("psychology.stress", next); CharacterRegistry.recordCharacterMutation(); }
        return next;
    }

    private static void adjustTemperament(Character c, Temperament facet, int delta) {
        setTemperament(c, facet, clamp(temperament(c, facet) + delta, 0, 100));
    }
    private static void adjustDrive(Character c, Drive drive, int delta) {
        setDrive(c, drive, clamp(drive(c, drive) + delta, 0, 100));
    }

    /** Compact idempotent personal memory stored on the observer's canonical Character. */
    public static boolean remember(Character observer, String eventId, String kind, String subjectGlobalId,
                                   String detail, int emotionalWeight, long worldMinute) {
        Objects.requireNonNull(observer);
        if (eventId == null || !eventId.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("invalid memory event ID");
        if (kind == null || kind.isBlank() || kind.length() > 64 || worldMinute < 0)
            throw new IllegalArgumentException("invalid memory");
        List<Object> history = new ArrayList<>();
        Object raw = observer.props.get("character.memory.events");
        if (raw instanceof List<?> list) for (Object item : list) if (item instanceof Map<?,?> map) {
            if (eventId.equals(map.get("id"))) return false;
            Map<String,Object> copy = new LinkedHashMap<>();
            map.forEach((k,v) -> { if (k instanceof String key) copy.put(key,v); });
            history.add(copy);
        }
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("id", eventId); event.put("kind", kind);
        event.put("subject", subjectGlobalId == null ? "" : subjectGlobalId);
        event.put("detail", detail == null ? "" : detail.substring(0, Math.min(240, detail.length())));
        event.put("weight", clamp(emotionalWeight, -100, 100)); event.put("minute", worldMinute);
        history.add(event);
        while (history.size() > 96) history.remove(0);
        observer.props.put("character.memory.events", history);
        observer.props.put("character.memory.last_minute", worldMinute);
        CharacterRegistry.recordCharacterMutation();
        return true;
    }

    public static List<Map<String,Object>> memories(Character observer, int limit) {
        Object raw = observer.props.get("character.memory.events");
        if (!(raw instanceof List<?> list) || limit <= 0) return List.of();
        List<Map<String,Object>> result = new ArrayList<>();
        int start = Math.max(0, list.size() - Math.min(96, limit));
        for (int i = start; i < list.size(); i++) if (list.get(i) instanceof Map<?,?> map) {
            Map<String,Object> copy = new LinkedHashMap<>();
            map.forEach((k,v) -> { if (k instanceof String key) copy.put(key,v); });
            result.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns stress caused by acting against the character's own principles. */
    public static int recordConduct(Character actor, Conduct conduct, Character affected,
                                    String operationId, long worldMinute) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            return recordConductBatched(actor, conduct, affected, operationId, worldMinute);
        }
    }

    private static int recordConductBatched(Character actor, Conduct conduct, Character affected,
                                             String operationId, long worldMinute) {
        Objects.requireNonNull(actor); Objects.requireNonNull(conduct);
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("conduct operation ID required");
        int conflict = switch (conduct) {
            case BREAK_PROMISE -> trait(actor,"honest",14)+trait(actor,"honorable",18)+trait(actor,"loyal",12)+trait(actor,"dutiful",8);
            case TELL_TRUTH -> trait(actor,"deceitful",-4)+trait(actor,"opportunistic",-3);
            case LIE -> trait(actor,"honest",12)+trait(actor,"honorable",15);
            case SHOW_MERCY -> trait(actor,"cruel",12)+trait(actor,"sadistic",18);
            case CRUELTY -> trait(actor,"just",16)+trait(actor,"compassionate",18)+trait(actor,"protective",14)+trait(actor,"honorable",8);
            case DEFEND_WEAK -> trait(actor,"callous",-3)+trait(actor,"sadistic",-4);
            case ABANDON_ALLY -> trait(actor,"loyal",18)+trait(actor,"dutiful",10)+trait(actor,"honorable",12)+trait(actor,"compassionate",7);
            case HONOR_DUTY -> trait(actor,"opportunistic",-4)+trait(actor,"fickle",-4);
            case BETRAYAL -> trait(actor,"honest",12)+trait(actor,"honorable",18)+trait(actor,"loyal",18)+trait(actor,"dutiful",10);
            case SEEK_REVENGE -> trait(actor,"forgiving",15)+trait(actor,"just",4);
            case FORGIVE -> trait(actor,"vengeful",8)+trait(actor,"wroth",5);
            case SHARE_WEALTH -> trait(actor,"greedy",12)+trait(actor,"opportunistic",5);
            case EXPLOIT -> trait(actor,"generous",10)+trait(actor,"compassionate",10)+trait(actor,"just",8)+trait(actor,"protective",6);
            case FLEE -> trait(actor,"brave",12)+trait(actor,"dutiful",5)+trait(actor,"loyal",5);
            case STAND_FIRM -> trait(actor,"craven",-3);
            case CARE_FOR_CHILD -> trait(actor,"callous",-3);
            case NEGLECT_CHILD -> trait(actor,"protective",18)+trait(actor,"compassionate",14)+trait(actor,"dutiful",12)+trait(actor,"honorable",5);
            case KEEP_PROMISE -> trait(actor,"honest",-3)+trait(actor,"honorable",-4)+trait(actor,"loyal",-3);
        };
        int stressDelta = conflict > 0 ? Math.min(24, conflict) : conflict;
        if (!operationId.matches("[A-Za-z0-9_.:-]{1,160}"))
            throw new IllegalArgumentException("invalid conduct operation ID");
        String subject = affected == null ? "" : affected.globalId;
        String receiptKey = "psychology.conduct.receipts";
        Map<String,Object> receipts = new LinkedHashMap<>();
        Object receiptRaw = actor.props.get(receiptKey);
        if (receiptRaw instanceof Map<?,?> map) for (Map.Entry<?,?> entry : map.entrySet())
            if (entry.getKey() instanceof String key) receipts.put(key, entry.getValue());
        String payload = conduct.name() + "|" + subject + "|" + stressDelta;
        Object prior = receipts.get(operationId);
        if (prior != null) {
            if (!payload.equals(prior)) throw new IllegalStateException("Conduct operation ID collision: " + operationId);
            return 0;
        }
        if (receipts.size() >= 10000) throw new IllegalStateException("Conduct receipt limit reached");
        if (!remember(actor, "conduct:" + operationId, "conduct_" + conduct.name().toLowerCase(Locale.ROOT),
                subject, conduct.name().toLowerCase(Locale.ROOT), stressDelta, worldMinute)) {
            receipts.put(operationId, payload);
            actor.props.put(receiptKey, receipts);
            return 0;
        }
        changeStress(actor, stressDelta);
        receipts.put(operationId, payload);
        actor.props.put(receiptKey, receipts);
        if (affected != null && affected != actor)
            remember(affected, "witness:" + operationId + ":" + actor.globalId,
                    "witnessed_" + conduct.name().toLowerCase(Locale.ROOT), actor.globalId,
                    actor.name + ":" + conduct.name().toLowerCase(Locale.ROOT), -stressDelta, worldMinute);
        actor.props.put("psychology.last_conduct", conduct.name());
        if (stress(actor) >= 70) setMood(actor, Mood.STRESSED, worldMinute, 1440, "inner_conflict:" + conduct.name());
        return stressDelta;
    }

    /** Experiences slowly alter motivation and temperament; repeated experiences may unlock a compatible trait. */
    public static void recordExperience(Character c, Experience experience, String eventId,
                                         String detail, long worldMinute, int intensity) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            recordExperienceBatched(c, experience, eventId, detail, worldMinute, intensity);
        }
    }

    private static void recordExperienceBatched(Character c, Experience experience, String eventId,
                                                  String detail, long worldMinute, int intensity) {
        Objects.requireNonNull(c); Objects.requireNonNull(experience);
        if (eventId == null || eventId.isBlank() || intensity < 1 || intensity > 5)
            throw new IllegalArgumentException("invalid experience");
        if (!remember(c, "experience:" + eventId, "experience_" + experience.name().toLowerCase(Locale.ROOT),
                "", detail, intensity * 8, worldMinute)) return;
        String countKey = "personality.experience." + experience.name().toLowerCase(Locale.ROOT);
        int count = c.props.get(countKey) instanceof Number n ? Math.max(0,n.intValue()) : 0;
        c.props.put(countKey, count + 1);
        int step = Math.max(1, intensity / 2);
        String[] growth = switch (experience) {
            case BATTLE_VICTORY -> {
                adjustDrive(c,Drive.PRESTIGE,step); adjustTemperament(c,Temperament.RISK,1);
                changeStress(c,-intensity*2); setMilitaryMorale(c,militaryMorale(c)+intensity*5);
                setMood(c,Mood.INSPIRED,worldMinute,3L*1440L,"battle victory");
                yield new String[]{"brave","confident"};
            }
            case BATTLE_DEFEAT -> {
                adjustTemperament(c,Temperament.RISK,-step); adjustTemperament(c,Temperament.DISCIPLINE,1);
                changeStress(c,intensity*5); setMilitaryMorale(c,militaryMorale(c)-intensity*7);
                setMood(c,Mood.STRESSED,worldMinute,7L*1440L,"battle defeat");
                yield new String[]{"cautious","resilient"};
            }
            case SURVIVED_DANGER -> { adjustTemperament(c,Temperament.STABILITY,step); yield new String[]{"resilient","brave"}; }
            case BETRAYED -> { adjustTemperament(c,Temperament.STABILITY,-step); adjustDrive(c,Drive.SECURITY,step*2); yield new String[]{"paranoid","cynical"}; }
            case BETRAYED_SOMEONE -> { adjustDrive(c,Drive.POWER,step); adjustTemperament(c,Temperament.EMPATHY,-step); yield new String[]{"deceitful","opportunistic"}; }
            case BEREAVEMENT -> {
                adjustTemperament(c,Temperament.STABILITY,-step); adjustTemperament(c,Temperament.EMPATHY,1);
                adjustDrive(c,Drive.FAMILY,step); changeStress(c,intensity*8);
                setMood(c,Mood.SAD,worldMinute,Math.max(3L,intensity*7L)*1440L,"bereavement");
                yield new String[]{"melancholic","compassionate"};
            }
            case PUBLIC_SUCCESS -> { adjustDrive(c,Drive.PRESTIGE,step*2); adjustTemperament(c,Temperament.AMBITION,1); yield new String[]{"ambitious","confident"}; }
            case PUBLIC_HUMILIATION -> { adjustTemperament(c,Temperament.STABILITY,-step); adjustDrive(c,Drive.SECURITY,step); yield new String[]{"humble","paranoid"}; }
            case POVERTY -> { adjustDrive(c,Drive.SECURITY,step*2); yield new String[]{"resourceful","cautious"}; }
            case WEALTH_GAIN -> { adjustDrive(c,Drive.WEALTH,step*2); yield new String[]{"generous","greedy"}; }
            case EDUCATION -> { adjustTemperament(c,Temperament.CURIOSITY,step*2); adjustDrive(c,Drive.KNOWLEDGE,step*2); yield new String[]{"scholar","curious"}; }
            case PARENTAL_CARE -> { adjustTemperament(c,Temperament.EMPATHY,step); adjustDrive(c,Drive.FAMILY,step*2); yield new String[]{"compassionate","dutiful"}; }
            case NEGLECT -> { adjustTemperament(c,Temperament.SOCIABILITY,-step); adjustDrive(c,Drive.INDEPENDENCE,step*2); yield new String[]{"independent","paranoid"}; }
            case MARRIAGE -> { adjustDrive(c,Drive.FAMILY,step*2); adjustTemperament(c,Temperament.SOCIABILITY,1); yield new String[]{"loyal","compassionate"}; }
            case DIVORCE -> { adjustTemperament(c,Temperament.STABILITY,-step); adjustDrive(c,Drive.SECURITY,step*2); adjustDrive(c,Drive.INDEPENDENCE,step); yield new String[]{"independent","resilient"}; }
            case ORPHANED -> { adjustTemperament(c,Temperament.STABILITY,-step); adjustDrive(c,Drive.SECURITY,step*2); adjustDrive(c,Drive.FAMILY,step); yield new String[]{"independent","paranoid"}; }
            case MENTORSHIP -> { adjustTemperament(c,Temperament.CURIOSITY,step); adjustDrive(c,Drive.KNOWLEDGE,step*2); yield new String[]{"scholar","diligent"}; }
            case SOCIAL_REJECTION -> { adjustTemperament(c,Temperament.SOCIABILITY,-step); adjustDrive(c,Drive.SECURITY,step); yield new String[]{"shy","cynical"}; }
            case RECONCILIATION -> { adjustTemperament(c,Temperament.STABILITY,step); adjustTemperament(c,Temperament.EMPATHY,1); yield new String[]{"forgiving","compassionate"}; }
            case COMRADE_FALLEN -> {
                adjustTemperament(c,Temperament.STABILITY,-step); adjustTemperament(c,Temperament.EMPATHY,step);
                adjustDrive(c,Drive.COMMUNITY,step*2); changeStress(c,intensity*6);
                setMilitaryMorale(c,militaryMorale(c)-intensity*5);
                setMood(c,Mood.SAD,worldMinute,Math.max(2L,intensity*4L)*1440L,"comrade fallen");
                yield new String[]{"loyal","resilient"};
            }
        };
        String growthKey = "personality.growth." + growth[0];
        int progress = c.props.get(growthKey) instanceof Number n ? Math.max(0,n.intValue()) : 0;
        progress = Math.min(5, progress + intensity);
        c.props.put(growthKey, progress);
        if (progress >= 3 && c.traits.size() < 6) {
            if (CharacterStats.addTrait(c,growth[0])) c.props.put(growthKey + ".unlocked",true);
            else if (CharacterStats.addTrait(c,growth[1])) c.props.put("personality.growth."+growth[1]+".unlocked",true);
        }
        c.props.put("personality.last_experience",experience.name());
    }

    /** Blended inherited psychology and a small, compatible sample of family traits. */
    public static void inheritPsychology(Character child, Character father, Character mother, long worldMinute) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            inheritPsychologyBatched(child, father, mother, worldMinute);
        }
    }

    private static void inheritPsychologyBatched(Character child, Character father, Character mother, long worldMinute) {
        Objects.requireNonNull(child); Objects.requireNonNull(father); Objects.requireNonNull(mother);
        List<String> initial = new ArrayList<>(CharacterStats.traits(child));
        child.traits.clear(); child.traits.addAll(initial);
        for (Character parent : List.of(father,mother)) for (String traitId : CharacterStats.traits(parent)) {
            long roll = Math.floorMod(psychologyHash(child.globalId+"|inherit|"+parent.globalId+"|"+traitId),100L);
            if (roll < 12 && child.traits.size() < 6) CharacterStats.addTrait(child,traitId);
        }
        for (Temperament facet : Temperament.values()) {
            int base = temperament(child,facet);
            setTemperament(child,facet,(int)Math.round(base*.55+temperament(father,facet)*.225+temperament(mother,facet)*.225));
        }
        for (Drive drive : Drive.values()) {
            int base = drive(child,drive);
            setDrive(child,drive,(int)Math.round(base*.55+drive(father,drive)*.225+drive(mother,drive)*.225));
        }
        ensurePsychology(child);
        remember(child,"family:birth:"+child.globalId,"birth",father.globalId,"born into "+child.house,10,worldMinute);
        remember(father,"family:child-born:"+child.globalId,"child_born",child.globalId,child.name,18,worldMinute);
        remember(mother,"family:child-born:"+child.globalId,"child_born",child.globalId,child.name,18,worldMinute);
    }

    /** Annual upbringing is deterministic and replay-safe; the legacy overload uses only parents. */
    public static void upbringingTick(Character child, Character father, Character mother, int year) {
        upbringingTick(child, father, mother, null, year);
    }

    /** Parents shape early temperament; a kin guardian can provide a real substitute caregiver. */
    public static void upbringingTick(Character child, Character father, Character mother,
                                      Character guardian, int year) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            upbringingTickBatched(child, father, mother, guardian, year);
        }
    }

    private static void upbringingTickBatched(Character child, Character father, Character mother,
                                               Character guardian, int year) {
        if (child == null || !child.alive || year < child.birthYear || year-child.birthYear > 16) return;
        Object previous = child.props.get("family.upbringing.last_year");
        if (previous instanceof Number n && n.intValue() >= year) return;
        Map<String,Character> caregivers = new LinkedHashMap<>();
        for (Character caregiver : new Character[]{father,mother,guardian})
            if (caregiver != null && caregiver.alive && !caregiver.id.equals(child.id))
                caregivers.put(caregiver.id, caregiver);
        long minute = Math.max(0L, year * 525600L);
        boolean orphaned = (father == null || !father.alive) && (mother == null || !mother.alive);
        if (orphaned) recordExperience(child, Experience.ORPHANED,
                "family:orphaned:" + child.globalId, "loss of both parents", minute, 3);
        if (guardian != null && guardian.alive
                && (father == null || !guardian.id.equals(father.id))
                && (mother == null || !guardian.id.equals(mother.id)))
            recordExperience(child, Experience.MENTORSHIP,
                    "family:kin-mentor:" + child.globalId + ":" + guardian.globalId,
                    "raised by kin guardian " + guardian.name, minute, 1);
        if (caregivers.isEmpty()) {
            child.props.put("family.upbringing.last_year", year);
            adjustTemperament(child, Temperament.SOCIABILITY, -1);
            adjustDrive(child, Drive.INDEPENDENCE, 1);
            if (year % 3 == 0) recordExperience(child, Experience.NEGLECT,
                    "upbringing:neglect:" + child.globalId + ":" + year,
                    "grew up without a living caregiver", minute, 1);
            remember(child, "upbringing:year:" + child.globalId + ":" + year, "upbringing",
                    "", "no living caregiver", -35, minute);
            return;
        }
        int warmth=0, discipline=0, curiosity=0, count=0;
        for (Character caregiver : caregivers.values()) {
            warmth += temperament(caregiver,Temperament.EMPATHY);
            discipline += temperament(caregiver,Temperament.DISCIPLINE);
            curiosity += temperament(caregiver,Temperament.CURIOSITY);
            count++;
        }
        warmth/=count; discipline/=count; curiosity/=count;
        if (warmth >= 62) {
            adjustTemperament(child,Temperament.EMPATHY,1); adjustDrive(child,Drive.FAMILY,1);
            recordExperience(child,Experience.PARENTAL_CARE,"upbringing:care:"+child.globalId+":"+year,
                    "caregiver warmth",minute,1);
        } else if (warmth <= 28) {
            adjustTemperament(child,Temperament.SOCIABILITY,-1); adjustDrive(child,Drive.INDEPENDENCE,1);
            if (year%3==0) recordExperience(child,Experience.NEGLECT,"upbringing:neglect:"+child.globalId+":"+year,
                    "lack of emotional care",minute,1);
        }
        if (discipline >= 68) adjustTemperament(child,Temperament.DISCIPLINE,1);
        if (curiosity >= 68) { adjustTemperament(child,Temperament.CURIOSITY,1); adjustDrive(child,Drive.KNOWLEDGE,1); }
        child.props.put("family.upbringing.last_year",year);
        remember(child,"upbringing:year:"+child.globalId+":"+year,"upbringing",
                guardian != null ? guardian.globalId : father != null ? father.globalId : mother.globalId,
                "warmth="+warmth+",discipline="+discipline+",curiosity="+curiosity,
                warmth-50,minute);
    }

    private static int trait(Character c, String id, int amount) {
        return hasTrait(c, id) ? amount : 0;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /** Current soldier morale is stored on the same Character identity, not only on its entity projection. */
    public static int militaryMorale(Character c) {
        Object raw=c.props.get("military.morale");
        return raw instanceof Number n ? Math.max(0,Math.min(100,n.intValue())) : 100;
    }
    public static void setMilitaryMorale(Character c,int morale) {
        setValue(c,"military.morale",Math.max(0,Math.min(100,morale)));
    }

    /** Records a lasting injury on the canonical person, not on the disposable troop entity. */
    public static int recordInjury(Character c,int severity,String cause,long worldMinute) {
        Objects.requireNonNull(c);
        if(severity<1||severity>3||worldMinute<0)throw new IllegalArgumentException("invalid injury");
        int current=injurySeverity(c);
        int next=Math.min(5,current+severity);
        c.props.put("military.injury.severity",next);
        c.props.put("military.injury.last_minute",worldMinute);
        c.props.put("military.injury.cause",cause==null?"combat":cause);
        Object raw=c.props.get("military.injury.count");
        c.props.put("military.injury.count",raw instanceof Number n?Math.max(0,n.intValue())+1:1);
        List<Map<String,Object>> history=new ArrayList<>();
        Object old=c.props.get("military.injury.history");
        if(old instanceof List<?> list)for(Object item:list)if(item instanceof Map<?,?> map){
            Map<String,Object> copy=new LinkedHashMap<>();
            map.forEach((k,v)->{if(k instanceof String key)copy.put(key,v);});
            history.add(copy);
        }
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("severity",severity);event.put("cause",cause==null?"combat":cause);event.put("minute",worldMinute);
        history.add(event);
        while(history.size()>24)history.remove(0);
        c.props.put("military.injury.history",history);
        CharacterRegistry.recordCharacterMutation();
        return next;
    }

    /** One physical hit produces one durable wound and one trauma response. */
    public static boolean recordCombatInjury(Character c,int severity,String eventId,String cause,long worldMinute) {
        try (CharacterRegistry.MutationBatch ignored = CharacterRegistry.beginMutationBatch()) {
            return recordCombatInjuryBatched(c,severity,eventId,cause,worldMinute);
        }
    }

    private static boolean recordCombatInjuryBatched(Character c,int severity,String eventId,String cause,long worldMinute) {
        Objects.requireNonNull(c);
        if(severity<1||severity>3||eventId==null||eventId.isBlank()||worldMinute<0)
            throw new IllegalArgumentException("invalid combat injury event");
        if(!remember(c,"combat:injury:"+eventId,"combat_injury","",
                cause==null?"combat":cause,-severity*12,worldMinute)) return false;
        recordInjury(c,severity,cause,worldMinute);
        changeStress(c,severity*5);
        setMilitaryMorale(c,militaryMorale(c)-severity*7);
        setMood(c,severity>=3?Mood.AFRAID:Mood.STRESSED,worldMinute,(long)(severity+1)*1440L,
                cause==null?"combat injury":cause);
        return true;
    }

    public static int injurySeverity(Character c) {
        Object raw=c.props.get("military.injury.severity");
        return raw instanceof Number n?Math.max(0,Math.min(5,n.intValue())):0;
    }

    public static int injuryCount(Character c) {
        Object raw=c.props.get("military.injury.count");
        return raw instanceof Number n?Math.max(0,n.intValue()):0;
    }

    /** Healing is explicit and gradual; injuries do not vanish merely because an entity reloads. */
    public static int healInjury(Character c,int severityPoints) {
        if(severityPoints<0)throw new IllegalArgumentException("healing must be nonnegative");
        int previous=injurySeverity(c);
        int next=Math.max(0,previous-severityPoints);
        if(next==0){
            c.props.remove("military.injury.severity");
            c.props.remove("military.injury.cause");
            c.props.remove("military.injury.last_minute");
        } else c.props.put("military.injury.severity",next);
        if (next != previous) CharacterRegistry.recordCharacterMutation();
        return next;
    }

    public static double injuryCombatMultiplier(Character c) {
        return Math.max(0.55,1.0-injurySeverity(c)*0.09);
    }

    /** Effective court/leadership stat = stable base + personality traits + mood + military morale. */
    public static int effectiveAttribute(Character c,String attribute,long worldMinute){
        int value=CharacterStats.value(c,attribute);
        Mood mood=mood(c,worldMinute);
        int modifier=switch(mood){
            case HAPPY -> attribute.equals("diplomacy")?1:0;
            case SAD -> attribute.equals("diplomacy")?-1:0;
            case ANGRY -> attribute.equals("martial")?1:attribute.equals("diplomacy")?-2:0;
            case AFRAID -> attribute.equals("martial")?-2:0;
            case STRESSED -> attribute.equals("intrigue")?1:attribute.equals("stewardship")?-1:0;
            case INSPIRED -> attribute.equals("learning")||attribute.equals("stewardship")?1:0;
            case EXHAUSTED -> attribute.equals("martial")||attribute.equals("stewardship")?-2:0;
            case NEUTRAL -> 0;
        };
        if ("soldier".equals(String.valueOf(c.props.get("wf_unit_kind")))) {
            int morale=militaryMorale(c);
            if (morale < 20) modifier += attribute.equals("martial") ? -3 : attribute.equals("diplomacy") ? -1 : 0;
            else if (morale < 40) modifier += attribute.equals("martial") ? -2 : 0;
            else if (morale >= 85) modifier += attribute.equals("martial") ? 1 : 0;
        }
        return Math.max(0,Math.min(20,value+modifier));
    }

    public static List<String> abilities(Character c){
        Object raw=c.props.get(ABILITIES);
        if(!(raw instanceof List<?> list))return List.of();
        List<String> result=new ArrayList<>();
        for(Object item:list)if(item instanceof String s&&!s.isBlank()&&!result.contains(s))result.add(s);
        return Collections.unmodifiableList(result);
    }
    public static boolean learnAbility(Character c,String abilityId){
        String id=id(abilityId,"ability");
        List<String> values=new ArrayList<>(abilities(c));
        if(values.contains(id))return false;
        values.add(id);Collections.sort(values);c.props.put(ABILITIES,values);CharacterRegistry.recordCharacterMutation();return true;
    }
    public static boolean forgetAbility(Character c,String abilityId){
        List<String> values=new ArrayList<>(abilities(c));
        boolean changed=values.remove(id(abilityId,"ability"));
        if(changed){c.props.put(ABILITIES,values);CharacterRegistry.recordCharacterMutation();}
        return changed;
    }
    private static void setValue(Character c,String key,Object value){
        Objects.requireNonNull(c,"character");
        if(!Objects.equals(c.props.get(key),value)){
            c.props.put(key,value);
            CharacterRegistry.recordCharacterMutation();
        }
    }

    private static String string(Character c,String key,String fallback){
        Object value=c.props.get(key);return value==null?fallback:String.valueOf(value);
    }
    private static String id(String value,String field){
        if(value==null||value.isBlank()||value.length()>96||!value.matches("[A-Za-z0-9_.:-]+"))
            throw new IllegalArgumentException("invalid "+field+" id");
        return value;
    }
}
