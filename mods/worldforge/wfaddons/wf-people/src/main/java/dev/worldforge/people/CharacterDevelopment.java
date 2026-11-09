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
