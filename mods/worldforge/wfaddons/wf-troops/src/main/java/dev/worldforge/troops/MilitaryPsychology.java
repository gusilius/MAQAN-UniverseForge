package dev.worldforge.troops;

import dev.worldforge.people.Character;
import dev.worldforge.people.CharacterDevelopment;
import dev.worldforge.settle.ResidentSocial;

/** Bridges real physical military events into the canonical Character and social systems. */
public final class MilitaryPsychology {
    private MilitaryPsychology() {}

    public static boolean recordInjury(Character soldier,int severity,String eventId,String cause,long worldMinute) {
        if(soldier==null||!soldier.alive) return false;
        return CharacterDevelopment.recordCombatInjury(soldier,severity,eventId,cause,worldMinute);
    }

    public static void recordBattleOutcome(Character soldier,boolean victory,String eventId,
                                           long worldMinute,int intensity) {
        if(soldier==null||!soldier.alive) return;
        CharacterDevelopment.recordExperience(soldier,
                victory?CharacterDevelopment.Experience.BATTLE_VICTORY:CharacterDevelopment.Experience.BATTLE_DEFEAT,
                eventId,victory?"won a battle":"lost a battle",worldMinute,Math.max(1,Math.min(5,intensity)));
    }

    /** A fallen squadmate strengthens a real social bond and leaves a distinct grief memory. */
    public static void recordComradeLoss(Character witness,Character fallen,String eventId,long worldMinute) {
        if(witness==null||fallen==null||witness==fallen||!witness.alive||!fallen.alive) return;
        int empathy=CharacterDevelopment.temperament(witness,CharacterDevelopment.Temperament.EMPATHY);
        int bondDelta=Math.max(1,Math.min(3,1+empathy/40));
        if(!CharacterDevelopment.remember(witness,eventId+":bond","comrade_bond",fallen.globalId,
                "served alongside "+fallen.name,bondDelta*5,worldMinute)) return;
        ResidentSocial.adjust(witness,fallen.globalId,bondDelta);
        int closeness=ResidentSocial.score(witness,fallen.globalId);
        int intensity=closeness>=70?3:2;
        CharacterDevelopment.recordExperience(witness,CharacterDevelopment.Experience.COMRADE_FALLEN,
                eventId+":grief","lost squadmate "+fallen.name,worldMinute,intensity);
    }
}
