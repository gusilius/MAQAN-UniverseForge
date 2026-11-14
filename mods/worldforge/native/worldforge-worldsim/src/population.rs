//! Deterministic population batch over immutable Character projections.
use serde::{Deserialize,Serialize};
use std::collections::{BTreeMap,BTreeSet};
const MAX:i32=100;
#[derive(Clone,Debug,Default,PartialEq,Eq,Serialize,Deserialize)]
pub struct Person{pub id:String,pub birth_year:i32,pub alive:bool,#[serde(default)]pub needs:BTreeMap<String,i32>,#[serde(default)]pub duties:BTreeSet<String>,#[serde(default)]pub profession:String,#[serde(default)]pub training_target:String,#[serde(default)]pub command:String,#[serde(default)]pub simulated_days:i64}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct Request{pub schema_version:u32,pub world_id:String,pub year:i32,pub elapsed_days:u64,pub characters:Vec<Person>}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct PersonResult{pub id:String,pub needs:BTreeMap<String,i32>,pub intent:String,pub age_years:i32,pub life_stage:String,pub simulated_days:i64,pub last_simulated_year:i32}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct Frame{pub schema_version:u32,pub world_id:String,pub year:i32,pub elapsed_days:u64,pub characters:Vec<PersonResult>}
fn need(n:&BTreeMap<String,i32>,k:&str)->i32{n.get(k).copied().unwrap_or(0).clamp(0,MAX)}
fn stage(a:i32)->&'static str{match a{0..=5=>"infant",6..=12=>"child",13..=17=>"adolescent",18..=59=>"adult",_=>"elder"}}
fn decide(p:&Person,n:&BTreeMap<String,i32>,age:i32)->&'static str{
 if !p.alive{return "idle"} if need(n,"hunger")>=70{return "eat"} if need(n,"rest")>=70{return "rest"}
 if need(n,"safety")>=70{return "seek_safety"} if need(n,"social")>=70{return "socialize"}
 if age<13{return "idle"} if age<18&&need(n,"purpose")>=45{return "train"}
 if need(n,"purpose")>=65{return if p.training_target.is_empty(){"work"}else{"train"}}
 if p.duties.contains("hero")&&!p.command.trim().is_empty(){return "follow_command"}
 if p.duties.contains("soldier"){return "patrol"} if !p.training_target.is_empty(){return "train"}
 if age>=13&&!p.profession.is_empty(){return "work"}
 if p.duties.contains("specialist")||p.duties.contains("worker"){return "work"}
 if p.duties.contains("hero"){return "follow_command"} "idle"
}
pub fn advance(r:Request)->Result<Frame,String>{
 if r.schema_version!=1{return Err("unsupported population schema".into())}
 if r.world_id.trim().is_empty(){return Err("empty world id".into())}
 if r.elapsed_days==0{return Err("elapsed_days must be positive".into())}
 let d=r.elapsed_days.min(MAX as u64) as i32;
 let drifts=[("hunger",10*d),("rest",6*d),("social",2*d),("purpose",d),("leisure",3*d)];
 let mut seen=BTreeSet::new();let mut out=Vec::with_capacity(r.characters.len());
 for p in &r.characters{
  if p.id.trim().is_empty()||!seen.insert(p.id.as_str()){return Err(format!("empty or duplicate character id: {}",p.id))}
  let age=r.year.saturating_sub(p.birth_year).max(0);let mut needs=p.needs.clone();
  if p.alive{for (key,drift) in drifts{needs.insert(key.into(),need(&needs,key).saturating_add(drift).clamp(0,MAX));}}
  out.push(PersonResult{id:p.id.clone(),needs:needs.clone(),intent:decide(p,&needs,age).into(),age_years:age,
   life_stage:stage(age).into(),simulated_days:if p.alive{p.simulated_days.max(0).saturating_add(r.elapsed_days.min(i64::MAX as u64) as i64)}else{p.simulated_days},last_simulated_year:r.year});
 }
 out.sort_by(|a,b|a.id.cmp(&b.id));
 Ok(Frame{schema_version:1,world_id:r.world_id,year:r.year,elapsed_days:r.elapsed_days,characters:out})
}
#[cfg(test)]mod tests{use super::*;
 fn p(id:&str)->Person{Person{id:id.into(),birth_year:280,alive:true,..Default::default()}}
 #[test]fn multi_day_drift_and_intents_match_java_rules(){let mut hungry=p("a");hungry.needs.insert("hunger".into(),92);let mut soldier=p("b");soldier.duties.insert("soldier".into());
 let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:3,characters:vec![soldier,hungry]}).unwrap();
 assert_eq!(f.characters[0].intent,"eat");assert_eq!(f.characters[0].needs["hunger"],100);
 assert_eq!(f.characters[1].needs["hunger"],30);assert_eq!(f.characters[1].needs["rest"],18);
 assert_eq!(f.characters[1].needs["social"],6);assert_eq!(f.characters[1].needs["purpose"],3);
 assert_eq!(f.characters[1].needs["leisure"],9);assert_eq!(f.characters[1].intent,"patrol");}
 #[test]fn life_stage_profession_and_hero_command(){let mut worker=p("w");worker.profession="farmer".into();let mut child=p("c");child.birth_year=300;let mut hero=p("h");hero.duties.insert("hero".into());hero.command="defend".into();
 let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:1,characters:vec![worker,child,hero]}).unwrap();
 let m:BTreeMap<_,_>=f.characters.iter().map(|x|(x.id.as_str(),x)).collect();assert_eq!(m["w"].intent,"work");assert_eq!(m["c"].life_stage,"infant");assert_eq!(m["c"].intent,"idle");assert_eq!(m["h"].intent,"follow_command");}
 #[test]fn dead_people_are_not_advanced(){let mut dead=p("d");dead.alive=false;dead.simulated_days=42;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:7,characters:vec![dead]}).unwrap();assert_eq!(f.characters[0].simulated_days,42);assert!(f.characters[0].needs.is_empty());}
 #[test]fn elapsed_days_saturate_at_java_long_max(){let mut p=p("p");p.simulated_days=i64::MAX-1;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:9,characters:vec![p]}).unwrap();assert_eq!(f.characters[0].simulated_days,i64::MAX);}
 #[test]fn invalid_schema_and_duplicate_ids_fail_closed(){let a=p("same");let r=Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:1,characters:vec![a.clone(),a]};assert!(advance(r).unwrap_err().contains("duplicate"));let r=Request{schema_version:2,world_id:"w".into(),year:303,elapsed_days:1,characters:vec![]};assert!(advance(r).unwrap_err().contains("schema"));}
}