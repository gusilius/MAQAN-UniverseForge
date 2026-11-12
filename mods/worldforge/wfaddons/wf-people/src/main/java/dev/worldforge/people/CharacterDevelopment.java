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
    public static void setRace(Character c,String id){c.props.put(RACE,id(id,"race"));}
    public static void setClass(Character c,String id){c.props.put(CLASS,id(id,"class"));}
    public static void setRarity(Character c,Rarity rarity){c.props.put(RARITY,Objects.requireNonNull(rarity).name());}
    public static void setLevel(Character c,int level){
        if(level<1||level>1000)throw new IllegalArgumentException("level must be in 1..1000");
        c.props.put(LEVEL,level);
    }

    public static Mood mood(Character c,long worldMinute){
        Object raw=c.props.get(MOOD);
        Mood mood;
        try{mood=Mood.valueOf(raw==null?"NEUTRAL":String.valueOf(raw));}
        catch(IllegalArgumentException ignored){mood=Mood.NEUTRAL;}
        Object until=c.props.get(MOOD_UNTIL);
        if(mood!=Mood.NEUTRAL&&until instanceof Number n&&worldMinute>=n.longValue()){
            c.props.put(MOOD,Mood.NEUTRAL.name());c.props.remove(MOOD_UNTIL);c.props.remove(MOOD_CAUSE);
            return Mood.NEUTRAL;
        }
        return mood;
    }
    public static void setMood(Character c,Mood mood,long worldMinute,long duration,String cause){
        Objects.requireNonNull(mood);
        if(worldMinute<0||duration<0)throw new IllegalArgumentException("invalid mood time");
        c.props.put(MOOD,mood.name());
        if(mood==Mood.NEUTRAL||duration==0){c.props.remove(MOOD_UNTIL);c.props.remove(MOOD_CAUSE);}
        else{
            c.props.put(MOOD_UNTIL,Math.addExact(worldMinute,duration));
            c.props.put(MOOD_CAUSE,cause==null?"":cause);
        }
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
        c.props.put("personality.temperament." + facet.name().toLowerCase(Locale.ROOT), clamp(value, 0, 100));
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
            case DUTY -> trait(c,"dutiful",22) + trait(c,"just",10) + trait(c,"diligent",12) + trait(c,"lazy",-16);
            case COMMUNITY -> trait(c,"gregarious",16) + trait(c,"sociable",18) + trait(c,"generous",12) + trait(c,"reclusive",-18);
        };
        return clamp(value, 0, 100);
    }

    public static void setDrive(Character c, Drive drive, int value) {
        Objects.requireNonNull(c); Objects.requireNonNull(drive);
        c.props.put("personality.drive." + drive.name().toLowerCase(Locale.ROOT), clamp(value, 0, 100));
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
        c.props.put("psychology.stress", next);
        return next;
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
        c.props.put("military.morale",Math.max(0,Math.min(100,morale)));
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
        return next;
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
        int next=Math.max(0,injurySeverity(c)-severityPoints);
        if(next==0){
            c.props.remove("military.injury.severity");
            c.props.remove("military.injury.cause");
            c.props.remove("military.injury.last_minute");
        } else c.props.put("military.injury.severity",next);
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
        values.add(id);Collections.sort(values);c.props.put(ABILITIES,values);return true;
    }
    public static boolean forgetAbility(Character c,String abilityId){
        List<String> values=new ArrayList<>(abilities(c));
        boolean changed=values.remove(id(abilityId,"ability"));
        if(changed)c.props.put(ABILITIES,values);
        return changed;
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
