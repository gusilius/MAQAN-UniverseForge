//! Deterministic population batch over immutable Character projections.
use serde::{Deserialize,Serialize};
use std::collections::{BTreeMap,BTreeSet};
const MAX:i32=100;
mod i64_decimal_string {
 use serde::{Deserialize,Deserializer,Serializer};
 pub fn serialize<S:Serializer>(value:&i64,serializer:S)->Result<S::Ok,S::Error>{serializer.serialize_str(&value.to_string())}
 pub fn deserialize<'de,D:Deserializer<'de>>(deserializer:D)->Result<i64,D::Error>{
  #[derive(Deserialize)] #[serde(untagged)] enum Value{Text(String),Number(i64)}
  match Value::deserialize(deserializer)?{Value::Text(v)=>v.parse::<i64>().map_err(serde::de::Error::custom),Value::Number(v)=>Ok(v)}
 }
}
#[derive(Clone,Debug,Default,PartialEq,Eq,Serialize,Deserialize)]
pub struct Person{pub id:String,pub birth_year:i32,pub alive:bool,#[serde(default)]pub needs:BTreeMap<String,i32>,#[serde(default)]pub duties:BTreeSet<String>,#[serde(default)]pub profession:String,#[serde(default)]pub training_target:String,#[serde(default)]pub command:String,#[serde(default)]pub can_work:bool,#[serde(default)]pub elapsed_days:u64,#[serde(default,with="i64_decimal_string")]pub simulated_days:i64}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct Request{pub schema_version:u32,pub world_id:String,pub year:i32,pub elapsed_days:u64,#[serde(with="i64_decimal_string")]pub world_day:i64,pub characters:Vec<Person>}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct PersonResult{pub id:String,pub needs:BTreeMap<String,i32>,pub intent:String,pub age_years:i32,pub life_stage:String,#[serde(with="i64_decimal_string")]pub simulated_days:i64,pub last_simulated_year:i32,#[serde(with="i64_decimal_string")]pub world_day:i64}
#[derive(Clone,Debug,PartialEq,Eq,Serialize,Deserialize)]
pub struct Frame{pub schema_version:u32,pub world_id:String,pub year:i32,pub elapsed_days:u64,#[serde(with="i64_decimal_string")]pub world_day:i64,pub characters:Vec<PersonResult>}
fn need(n:&BTreeMap<String,i32>,k:&str)->i32{n.get(k).copied().unwrap_or(0).clamp(0,MAX)}
fn stage(a:i32)->&'static str{match a{0..=5=>"infant",6..=12=>"child",13..=17=>"adolescent",18..=59=>"adult",_=>"elder"}}
fn decide(p:&Person,n:&BTreeMap<String,i32>,age:i32)->&'static str{
 if !p.alive{return "idle"} if need(n,"hunger")>=70{return "eat"} if need(n,"rest")>=70{return "rest"}
 if need(n,"safety")>=70{return "seek_safety"} if need(n,"social")>=70{return "socialize"}
 if age<13{return "idle"} if age<18&&need(n,"purpose")>=45{return "train"}
 if need(n,"purpose")>=65{return if p.training_target.is_empty(){"work"}else{"train"}}
 if p.duties.contains("hero")&&!p.command.trim().is_empty(){return "follow_command"}
 if p.duties.contains("soldier"){return "patrol"} if !p.training_target.is_empty(){return "train"}
 if p.can_work&&!p.profession.is_empty(){return "work"}
 if p.duties.contains("specialist")||p.duties.contains("worker"){return "work"}
 if p.duties.contains("hero"){return "follow_command"} "idle"
}
pub fn advance(r:Request)->Result<Frame,String>{
 if r.schema_version!=1{return Err("unsupported population schema".into())}
 if r.world_id.trim().is_empty(){return Err("empty world id".into())}
 if r.elapsed_days==0{return Err("elapsed_days must be positive".into())}
 let mut seen=BTreeSet::new();let mut out=Vec::with_capacity(r.characters.len());
 for p in r.characters.iter().filter(|person| person.alive){
  if p.id.trim().is_empty()||!seen.insert(p.id.as_str()){return Err(format!("empty or duplicate character id: {}",p.id))}
  if p.elapsed_days==0{return Err(format!("zero elapsed days for active Character: {}",p.id))}
  let d=p.elapsed_days.min(MAX as u64) as i32;
  let drifts=[("hunger",10*d),("rest",6*d),("social",2*d),("purpose",d),("leisure",3*d)];
  let age=r.year.saturating_sub(p.birth_year).max(0);let mut needs=p.needs.clone();
  for (key,drift) in drifts{needs.insert(key.into(),need(&needs,key).saturating_add(drift).clamp(0,MAX));}
  out.push(PersonResult{id:p.id.clone(),needs:needs.clone(),intent:decide(p,&needs,age).into(),age_years:age,
   life_stage:stage(age).into(),simulated_days:p.simulated_days.max(0).saturating_add(p.elapsed_days.min(i64::MAX as u64) as i64),
   last_simulated_year:r.year,world_day:r.world_day});
 }
 out.sort_by(|a,b|a.id.cmp(&b.id));
 Ok(Frame{schema_version:1,world_id:r.world_id,year:r.year,elapsed_days:r.elapsed_days,world_day:r.world_day,characters:out})
}
#[cfg(test)]mod tests{use super::*;
 fn p(id:&str)->Person{Person{id:id.into(),birth_year:280,alive:true,..Default::default()}}
 #[test]fn multi_day_drift_and_intents_match_java_rules(){let mut hungry=p("a");hungry.needs.insert("hunger".into(),92);let mut soldier=p("b");soldier.duties.insert("soldier".into());
 let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:3,world_day:303,characters:vec![soldier,hungry]}).unwrap();
 assert_eq!(f.characters[0].intent,"eat");assert_eq!(f.characters[0].needs["hunger"],100);
 assert_eq!(f.characters[1].needs["hunger"],30);assert_eq!(f.characters[1].needs["rest"],18);
 assert_eq!(f.characters[1].needs["social"],6);assert_eq!(f.characters[1].needs["purpose"],3);
 assert_eq!(f.characters[1].needs["leisure"],9);assert_eq!(f.characters[1].intent,"patrol");}
 #[test]fn life_stage_profession_and_hero_command(){let mut worker=p("w");worker.profession="farmer".into();worker.can_work=true;let mut child=p("c");child.birth_year=300;child.profession="farmer".into();child.can_work=false;let mut hero=p("h");hero.duties.insert("hero".into());hero.command="defend".into();
 let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:1,world_day:303,characters:vec![worker,child,hero]}).unwrap();
 let m:BTreeMap<_,_>=f.characters.iter().map(|x|(x.id.as_str(),x)).collect();assert_eq!(m["w"].intent,"work");assert_eq!(m["c"].life_stage,"infant");assert_eq!(m["c"].intent,"idle");assert_eq!(m["h"].intent,"follow_command");}
 #[test]fn dead_people_are_not_in_the_population_batch(){let mut dead=p("d");dead.alive=false;dead.simulated_days=42;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:7,world_day:303,characters:vec![dead]}).unwrap();assert!(f.characters.is_empty());}
 #[test]fn java_long_is_encoded_as_an_exact_decimal_string(){let json=r#"{"schema_version":1,"world_id":"w","year":303,"elapsed_days":1,"characters":[{"id":"p","birth_year":280,"alive":true,"simulated_days":"9223372036854775806"}]}"#;let request:Request=serde_json::from_str(json).unwrap();let frame=advance(request).unwrap();let encoded=serde_json::to_value(frame).unwrap();assert_eq!(encoded["characters"][0]["simulated_days"],"9223372036854775807");}
 #[test]fn elapsed_days_saturate_at_java_long_max(){let mut p=p("p");p.simulated_days=i64::MAX-1;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:9,world_day:303,characters:vec![p]}).unwrap();assert_eq!(f.characters[0].simulated_days,i64::MAX);}
 #[test]fn output_must_acknowledge_per_character_elapsed_and_world_day(){let mut a=p("a");a.elapsed_days=7;let mut b=p("b");b.elapsed_days=2;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:7,world_day:303,characters:vec![a,b]}).unwrap();assert_eq!(f.elapsed_days,7);assert_eq!(f.characters[0].simulated_days,7);assert_eq!(f.characters[1].simulated_days,2);assert_eq!(f.characters[0].last_simulated_year,303);assert_eq!(f.characters[0].world_day,303);}
 #[test]fn profession_requires_the_same_can_work_gate_as_java(){let mut child=p("child");child.birth_year=300;child.profession="farmer".into();child.can_work=false;let f=advance(Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:1,world_day:303,characters:vec![child]}).unwrap();assert_eq!(f.characters[0].intent,"idle");}
 #[test]fn invalid_schema_and_duplicate_ids_fail_closed(){let a=p("same");let r=Request{schema_version:1,world_id:"w".into(),year:303,elapsed_days:1,world_day:303,characters:vec![a.clone(),a]};assert!(advance(r).unwrap_err().contains("duplicate"));let r=Request{schema_version:2,world_id:"w".into(),year:303,elapsed_days:1,world_day:303,characters:vec![]};assert!(advance(r).unwrap_err().contains("schema"));}
}