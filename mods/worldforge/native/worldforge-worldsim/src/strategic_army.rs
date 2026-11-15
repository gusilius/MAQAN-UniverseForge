//! Deterministic batched strategic army supply calculation.
//! Java resolves territory ownership/occupation and applies casualties, morale, events and persistence.
use serde::{Deserialize,Serialize};
#[derive(Deserialize,Serialize)]
struct Request { schema_version:u32, armies:Vec<ArmyInput> }
#[derive(Deserialize,Serialize)]
struct ArmyInput { army_id:i32, men:i32, supply:f64, morale:f64, home:bool }
#[derive(Serialize,Deserialize)]
struct ArmyOutput { army_id:i32, supply:f64, morale:f64, casualties:i32, starved:bool, low_supply:bool }
#[derive(Serialize,Deserialize)]
struct Frame { status:String, armies:Vec<ArmyOutput> }
fn validate(input:&ArmyInput)->bool {
 input.army_id>0 && input.men>0 && input.supply.is_finite() && (0.0..=1.0).contains(&input.supply)
 && input.morale.is_finite() && (0.0..=1.0).contains(&input.morale)
}
fn compute_one(input:&ArmyInput)->ArmyOutput {
 let mut supply=input.supply;
 let mut morale=input.morale;
 let mut casualties=0;
 if input.home { supply=(supply+0.25).min(1.0); }
 else {
   let use_band=(input.men/80).clamp(1,3) as f64;
   let use_rate=use_band/25.0;
   supply=(supply-use_rate).max(0.0);
   if supply<=0.0 {
     morale=(morale-0.03).max(0.05);
     casualties=((input.men as f64*0.02).round() as i32).max(1);
   }
 }
 ArmyOutput{army_id:input.army_id,supply,morale,casualties,starved:!input.home&&supply<=0.0,low_supply:!input.home&&supply>0.0&&supply<0.25}
}
pub fn compute(bytes:&[u8])->Result<Vec<u8>,String>{
 let request:Request=serde_json::from_slice(bytes).map_err(|e|e.to_string())?;
 if request.schema_version!=1{return Err("unsupported strategic army schema".into());}
 if request.armies.len()>100_000{return Err("strategic army batch exceeds 100000 armies".into());}
 let mut ids=std::collections::BTreeSet::new();
 if request.armies.iter().any(|a|!validate(a)||!ids.insert(a.army_id)){return Err("invalid or duplicate strategic army input".into());}
 let frame=Frame{status:"ok".to_string(),armies:request.armies.iter().map(compute_one).collect()};
 serde_json::to_vec(&frame).map_err(|e|e.to_string())
}
#[cfg(test)]
mod tests {
 use super::*;
 fn army(id:i32,men:i32,supply:f64,morale:f64,home:bool)->ArmyInput{ArmyInput{army_id:id,men,supply,morale,home}}
 #[test] fn resupply_matches_daily_java_formula(){let x=compute_one(&army(1,100,0.8,0.7,true));assert_eq!(x.supply,1.0);assert_eq!(x.morale,0.7);assert_eq!(x.casualties,0);}
 #[test] fn foreign_army_consumes_supply_by_size_band(){let small=compute_one(&army(1,40,1.0,0.8,false));let medium=compute_one(&army(2,160,1.0,0.8,false));let boundary=compute_one(&army(4,159,1.0,0.8,false));let large=compute_one(&army(3,400,1.0,0.8,false));assert!((small.supply-0.96).abs()<1e-12);assert!((medium.supply-0.92).abs()<1e-12);assert!((boundary.supply-0.96).abs()<1e-12);assert!((large.supply-0.88).abs()<1e-12);}
 #[test] fn starvation_applies_minimum_loss_and_morale_floor(){let x=compute_one(&army(1,10,0.0,0.06,false));assert_eq!(x.casualties,1);assert_eq!(x.morale,0.05);assert!(x.starved);}
 #[test] fn batch_preserves_order_and_rejects_duplicates(){let req=Request{schema_version:1,armies:vec![army(4,80,1.0,1.0,false),army(2,80,0.5,0.5,true)]};let bytes=serde_json::to_vec(&req).unwrap();let frame:Frame=serde_json::from_slice(&compute(&bytes).unwrap()).unwrap();assert_eq!(frame.armies.iter().map(|x|x.army_id).collect::<Vec<_>>(),vec![4,2]);let bad=Request{schema_version:1,armies:vec![army(1,20,1.0,1.0,true),army(1,30,1.0,1.0,true)]};assert!(serde_json::to_vec(&bad).is_ok());let raw=serde_json::to_vec(&bad).unwrap();assert!(compute(&raw).is_err());}
 #[test] fn invalid_numeric_snapshot_rejected(){assert!(!validate(&army(1,5,f64::NAN,0.5,true)));assert!(!validate(&army(1,5,0.5,1.5,true)));}
}
