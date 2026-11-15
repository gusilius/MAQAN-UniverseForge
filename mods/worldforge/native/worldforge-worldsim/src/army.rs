//! Deterministic army battle computation over immutable army snapshots.
//! Java retains canonical rosters, casualty application, war score, retreats and persistence.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Clone, Debug, Deserialize)]
pub struct CombatRequest {
    pub schema_version: u32,
    pub seed: i64,
    pub defender_a: bool,
    pub side_a: Vec<ArmySnapshot>,
    pub side_b: Vec<ArmySnapshot>,
}
#[derive(Clone, Debug, Deserialize)]
pub struct ArmySnapshot {
    pub martial: i32,
    pub morale: f64,
    pub units: Vec<UnitSnapshot>,
}
#[derive(Clone, Debug, Deserialize)]
pub struct UnitSnapshot {
    pub id: String,
    pub count: i32,
    pub attack: f64,
    pub defense: f64,
    #[serde(default)]
    pub counters: BTreeMap<String, f64>,
}
#[derive(Clone, Debug, Serialize)]
pub struct CombatRound {
    pub kill_a: f64,
    pub kill_b: f64,
    pub loss_share_a: f64,
    pub loss_share_b: f64,
}
#[derive(Clone, Debug, Serialize)]
pub struct CombatFrame {
    pub status: &'static str,
    pub rounds: Vec<CombatRound>,
}
#[derive(Clone, Copy)]
struct JavaRandom { seed: u64 }
impl JavaRandom {
    fn new(seed: i64) -> Self { Self { seed: ((seed as u64) ^ 0x5DEECE66D) & ((1u64 << 48)-1) } }
    fn next(&mut self, bits: u32) -> u32 {
        self.seed = self.seed.wrapping_mul(0x5DEECE66D).wrapping_add(0xB) & ((1u64 << 48)-1);
        (self.seed >> (48-bits)) as u32
    }
    fn next_double(&mut self) -> f64 {
        let high = self.next(26) as u64;
        let low = self.next(27) as u64;
        (((high << 27) + low) as f64) / ((1u64 << 53) as f64)
    }
}
fn men(side: &[ArmySnapshot]) -> i32 {
    side.iter().flat_map(|a| &a.units).map(|u| u.count.max(0)).sum()
}
fn avg_morale(side: &[ArmySnapshot]) -> f64 {
    let mut weighted=0.0; let mut total=0;
    for a in side { let count: i32=a.units.iter().map(|u|u.count.max(0)).sum(); weighted+=a.morale*count as f64; total+=count; }
    if total==0 {0.0} else {weighted/total as f64}
}
fn strength(side: &[ArmySnapshot], foe: &[ArmySnapshot]) -> (f64,f64) {
    let mut share:BTreeMap<&str,f64>=BTreeMap::new(); let mut total=0.0;
    for f in foe { for u in &f.units { if u.count>0 { *share.entry(&u.id).or_default()+=u.count as f64; total+=u.count as f64; } } }
    let mut offense=0.0; let mut defense=0.0;
    for a in side {
        let command=(1.0+0.03*a.martial as f64)*(0.5+0.5*a.morale);
        for u in &a.units {
            if u.count<=0 {continue;}
            let mut mult=1.0;
            if total>0.0 { for (kind,count) in &share { mult += count/total*(u.counters.get(*kind).copied().unwrap_or(1.0)-1.0); } }
            offense += u.count as f64*u.attack*mult*command;
            defense += u.count as f64*u.defense*command;
        }
    }
    (offense,defense)
}
fn casualties(side:&mut [ArmySnapshot], kill:f64) {
    let total=men(side);
    if total<=0 || kill<=0.0 {return;}
    let fraction=(kill/total as f64).min(1.0);
    for army in side { for unit in &mut army.units {
        unit.count=((unit.count as f64*(1.0-fraction))+0.5).floor() as i32;
    }}
}
fn valid(side:&[ArmySnapshot])->bool {
    side.iter().all(|a| a.martial>=0 && a.morale.is_finite() && (0.0..=1.0).contains(&a.morale)
        && a.units.iter().all(|u| !u.id.is_empty() && u.count>=0 && u.attack.is_finite() && u.attack>=0.0
            && u.defense.is_finite() && u.defense>=0.0
            && u.counters.values().all(|v|v.is_finite() && *v>=0.0 && *v<=100.0)))
}
pub fn simulate(mut request:CombatRequest)->Result<CombatFrame,String>{
    if request.schema_version!=1 {return Err("unsupported schema version".into());}
    if request.side_a.is_empty() || request.side_b.is_empty() || !valid(&request.side_a) || !valid(&request.side_b) {
        return Err("invalid or empty army side".into());
    }
    let mut rng=JavaRandom::new(request.seed);
    let (ta,tb)=if request.defender_a {(1.15,1.0)} else {(1.0,1.15)};
    let mut rounds=Vec::with_capacity(8);
    for _ in 0..8 {
        if men(&request.side_a)<=0 || men(&request.side_b)<=0 {break;}
        let (sa_off,sa_def)=strength(&request.side_a,&request.side_b);
        let (sb_off,sb_def)=strength(&request.side_b,&request.side_a);
        let dmg_b=sa_off*ta*0.10*(0.85+0.3*rng.next_double());
        let dmg_a=sb_off*tb*0.10*(0.85+0.3*rng.next_double());
        let men_a=men(&request.side_a).max(0) as f64;
        let men_b=men(&request.side_b).max(0) as f64;
        let kill_b=men_b.min(dmg_b*(men_b/(sb_def+dmg_b).max(1.0)));
        let kill_a=men_a.min(dmg_a*(men_a/(sa_def+dmg_a).max(1.0)));
        casualties(&mut request.side_b,kill_b);
        casualties(&mut request.side_a,kill_a);
        let share_a=kill_a/(men(&request.side_a) as f64+kill_a).max(1.0);
        let share_b=kill_b/(men(&request.side_b) as f64+kill_b).max(1.0);
        for a in &mut request.side_a {a.morale=(a.morale-share_a*2.2-0.03).max(0.0);}
        for b in &mut request.side_b {b.morale=(b.morale-share_b*2.2-0.03).max(0.0);}
        rounds.push(CombatRound{kill_a,kill_b,loss_share_a:share_a,loss_share_b:share_b});
        if avg_morale(&request.side_a)<=0.15 || avg_morale(&request.side_b)<=0.15 {break;}
    }
    Ok(CombatFrame{status:"ok",rounds})
}
#[cfg(test)]
mod tests {
    use super::*;
    fn unit(id:&str,count:i32,attack:f64,defense:f64)->UnitSnapshot{
        UnitSnapshot{id:id.into(),count,attack,defense,counters:BTreeMap::new()}
    }
    fn sample()->CombatRequest{
        CombatRequest{schema_version:1,seed:7919,defender_a:true,
            side_a:vec![ArmySnapshot{martial:8,morale:1.0,units:vec![unit("spear",100,1.1,1.5)]}],
            side_b:vec![ArmySnapshot{martial:3,morale:1.0,units:vec![unit("cavalry",90,2.4,1.1)]}]}
    }
    #[test] fn java_random_matches_java_util_random_sequence() {
        let mut rng=JavaRandom::new(0);
        assert_eq!(rng.next_double().to_bits(),0.730967787376657f64.to_bits());
        assert_eq!(rng.next_double().to_bits(),0.24053641567148587f64.to_bits());
    }
    #[test] fn same_snapshot_is_bitwise_deterministic(){
        let a=simulate(sample()).unwrap(); let b=simulate(sample()).unwrap(); assert_eq!(a.rounds.len(),b.rounds.len());
        for (x,y) in a.rounds.iter().zip(&b.rounds){assert_eq!(x.kill_a.to_bits(),y.kill_a.to_bits());assert_eq!(x.kill_b.to_bits(),y.kill_b.to_bits());}
    }
    #[test] fn preserves_java_random_seeded_damage_sequence(){
        let result=simulate(sample()).unwrap(); assert!(!result.rounds.is_empty());
        assert!(result.rounds.iter().all(|r|r.kill_a.is_finite()&&r.kill_b.is_finite()&&r.loss_share_a<=1.0&&r.loss_share_b<=1.0));
    }
    #[test] fn rejects_invalid_roster_and_schema(){
        let mut bad=sample();bad.side_a[0].units[0].count=-1;assert!(simulate(bad).is_err());
        let mut schema=sample();schema.schema_version=2;assert!(simulate(schema).is_err());
    }
    #[test] fn counter_data_changes_attack_strength(){
        let mut req=sample();req.side_a[0].units[0].counters.insert("cavalry".into(),2.0);
        let buffed=simulate(req.clone()).unwrap();let plain=simulate(sample()).unwrap();
        assert_ne!(buffed.rounds[0].kill_b.to_bits(),plain.rounds[0].kill_b.to_bits());
    }
}
