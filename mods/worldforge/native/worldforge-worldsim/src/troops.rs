//! Pure troop compute kernels. Java retains entity mutation, canonical command execution and persistence.
use serde_json::{json,Value};
fn n(v:&Value,k:&str)->Result<f64,String>{let x=v.get(k).and_then(Value::as_f64).ok_or_else(||format!("missing {k}"))?;if !x.is_finite(){return Err(format!("non-finite {k}"));}Ok(x)}
fn i(v:&Value,k:&str)->Result<i64,String>{v.get(k).and_then(Value::as_i64).ok_or_else(||format!("missing {k}"))}
fn b(v:&Value,k:&str)->Result<bool,String>{v.get(k).and_then(Value::as_bool).ok_or_else(||format!("missing {k}"))}
fn clamp(x:f64,a:f64,z:f64)->f64{x.max(a).min(z)}
fn morale(v:&Value)->Result<Value,String>{
 let m=n(v,"morale")?;let alive=n(v,"alive_ratio")?;let lost=n(v,"lost_now")?;let out=n(v,"outnumber")?;let dt=n(v,"dt")?;let leader=b(v,"leader")?;let enemies=b(v,"enemies_near")?;
 if !(0.0..=1.0).contains(&m)||!(0.0..=1.0).contains(&alive)||lost<0.0||out<0.0||dt<0.0||dt>60.0{return Err("morale input out of range".into());}
 let mut value=m-lost*1.2;let mut target=0.4+0.6*alive;if enemies{target-=clamp(out-1.0,0.0,1.0)*0.25;}if leader{target+=0.1;}target=clamp(target,0.0,1.0);
 if value>target{value-=(value-target).min(0.15*dt);}else{value+=(target-value).min((if enemies{0.01}else{0.05})*dt);}
 Ok(json!({"status":"ok","morale":clamp(value,0.0,1.0)}))
}
#[derive(Clone,Copy)]struct Slot{x:f64,z:f64}
#[derive(Clone,Copy)]struct JavaRandom{seed:u64}
impl JavaRandom{fn new(s:i64)->Self{Self{seed:((s as u64)^0x5DEECE66D)&((1u64<<48)-1)}}fn next(&mut self,b:u32)->u32{self.seed=self.seed.wrapping_mul(0x5DEECE66D).wrapping_add(0xB)&((1u64<<48)-1);(self.seed>>(48-b))as u32}fn double(&mut self)->f64{let h=self.next(26)as u64;let l=self.next(27)as u64;(((h<<27)+l)as f64)/((1u64<<53)as f64)}}
fn slots(kind:&str,count:usize,s:f64)->Result<Vec<Slot>,String>{
 if count>10000{return Err("formation exceeds 10000".into());}let n=count;let mut out=Vec::with_capacity(n);if n==0{return Ok(out);}
 match kind{
 "LINE"|"BLOCK"|"COLUMN"=>{let per=match kind{"LINE"=>n.min(12),"COLUMN"=>n.min(2),_=>((n as f64*1.4).sqrt().ceil()as usize).max(1)};let rows=(n+per-1)/per;for k in 0..n{let r=k/per;let c=k%per;let width=per.min(n-r*per);out.push(Slot{x:(c as f64-(width as f64-1.0)/2.0)*s,z:((rows as f64-1.0)/2.0-r as f64)*s});}},
 "WEDGE"=>{let(mut row,mut placed)=(0,0);while placed<n{let width=row+1;for c in 0..width{if placed==n{break;}out.push(Slot{x:(c as f64-(width as f64-1.0)/2.0)*s,z:-(row as f64)*s});placed+=1;}row+=1;}let mean=out.iter().map(|x|x.z).sum::<f64>()/n as f64;for x in &mut out{x.z-=mean;}},
 "CIRCLE"=>{let(mut ring,mut placed)=(0,0);while placed<n{let cap=if ring==0{1}else{ring*6};for c in 0..cap{if placed==n{break;}let a=2.0*std::f64::consts::PI*c as f64/cap as f64;out.push(Slot{x:a.sin()*ring as f64*s,z:a.cos()*ring as f64*s});placed+=1;}ring+=1;}},
 "LOOSE"=>{let mut rng=JavaRandom::new((n as i64).wrapping_mul(31).wrapping_add(7));let side=(n as f64).sqrt().max(2.0)*s*1.6;for _ in 0..n{out.push(Slot{x:(rng.double()-0.5)*side,z:(rng.double()-0.5)*side});}},
 _=>return Err("unknown formation".into())}Ok(out)
}
fn formation(v:&Value)->Result<Value,String>{
 let kind=v.get("formation").and_then(Value::as_str).ok_or("missing formation")?;let count=i(v,"count")?;if count<0{return Err("negative count".into());}let n=count as usize;
 let spacing=n(v,"spacing")?;let cx=n(v,"cx")?;let cz=n(v,"cz")?;let yaw=n(v,"yaw")?;if spacing<=0.0||spacing>100.0{return Err("spacing out of range".into());}
 let s=slots(kind,n,spacing)?;let roles=v.get("roles").and_then(Value::as_array).ok_or("missing roles")?;let current=v.get("current_local").and_then(Value::as_array).ok_or("missing current_local")?;
 if (!roles.is_empty()&&(roles.len()!=n||current.len()!=n))||(!current.is_empty()&&current.len()!=n){return Err("role/current count mismatch".into());}
 let mut assign=Vec::<usize>::new();if !roles.is_empty(){let mut units:Vec<usize>=(0..n).collect();let mut positions:Vec<usize>=(0..n).collect();let mut side=vec![0.0;n];for k in 0..n{let p=current[k].as_array().ok_or("bad current point")?;side[k]=p.first().and_then(Value::as_f64).ok_or("bad local x")?;}
 let rank=|x:&Value|match x.as_str().unwrap_or(""){"melee"=>0,"cavalry"=>1,"ranged"=>2,_=>3};
 units.sort_by(|a,b|rank(&roles[*a]).cmp(&rank(&roles[*b])).then_with(||side[*a].total_cmp(&side[*b])));
 positions.sort_by(|a,b|s[*b].z.total_cmp(&s[*a].z).then_with(||s[*a].x.total_cmp(&s[*b].x)));assign=vec![0;n];for k in 0..n{assign[units[k]]=positions[k];}}
 let r=yaw.to_radians();let(fx,fz)=(-r.sin(),r.cos());let(rx,rz)=(-fz,fx);
 let local:Vec<[f64;2]>=s.iter().map(|p|[p.x,p.z]).collect();let world:Vec<[f64;2]>=s.iter().map(|p|[cx+rx*p.x+fx*p.z,cz+rz*p.x+fz*p.z]).collect();
 Ok(json!({"status":"ok","slots":local,"world":world,"assignment":assign}))
}
fn supply(v:&Value)->Result<Value,String>{
 let soldiers=i(v,"soldiers")?;let days=n(v,"days")?;let stock=n(v,"available_rations")?;let requested=n(v,"consumed")?;let readiness=n(v,"readiness")?;let prior_days=n(v,"days_without_full_rations")?;let total=n(v,"total_consumed")?;let persisted=b(v,"persisted_advance")?;
 if soldiers<0||days<0.0||days>365.0||stock<0.0||requested<0.0||!(0.0..=1.0).contains(&readiness)||prior_days<0.0||total<0.0{return Err("supply input out of range".into());}
 let needed=soldiers as f64*days;let used=if persisted{requested}else{stock.min(needed)};if used>needed+1e-8||used>stock+1e-8{return Err("ration settlement exceeds demand or stock".into());}
 let shortage=(needed-used).max(0.0);let(mut ready,mut days_short)=(readiness,prior_days);
 if needed>0.0&&shortage>1e-9{let f=shortage/needed;days_short+=days*f;ready=clamp(readiness-days*f*0.12,0.0,1.0);}else{days_short=(prior_days-days).max(0.0);ready=clamp(readiness+days*0.025,0.0,1.0);}
 Ok(json!({"status":"ok","consumed":used,"remaining_rations":(stock-used).max(0.0),"total_consumed":total+used,"readiness":ready,"days_without_full_rations":days_short,"shortage":shortage}))
}
pub fn compute(bytes:&[u8])->Result<Vec<u8>,String>{let v:Value=serde_json::from_slice(bytes).map_err(|e|e.to_string())?;if v.get("schema_version").and_then(Value::as_u64)!=Some(1){return Err("unsupported troop schema".into());}
 let op=v.get("operation").and_then(Value::as_str).ok_or("missing operation")?;let out=match op{"morale_step"=>morale(&v)?,"formation_plan"=>formation(&v)?,"supply"=>supply(&v)?,_=>return Err("unsupported troop operation".into())};serde_json::to_vec(&out).map_err(|e|e.to_string())}
#[cfg(test)]mod tests{use super::*;
 #[test]fn morale_formula_parity(){let v=json!({"morale":0.72,"alive_ratio":0.8,"lost_now":0.04,"outnumber":1.5,"leader":true,"enemies_near":true,"dt":0.5});let got=morale(&v).unwrap()["morale"].as_f64().unwrap();let mut e=0.72f64-0.04*1.2;let mut t=0.4+0.6*0.8-0.5*0.25+0.1;if e>t{e-=(e-t).min(0.15*0.5);}else{e+=(t-e).min(0.01*0.5);}assert!((got-e.clamp(0.0,1.0)).abs()<1e-12);}
 #[test]fn all_formations_keep_count(){for k in ["LINE","BLOCK","COLUMN","WEDGE","CIRCLE","LOOSE"]{for n in [1,5,17,40]{assert_eq!(slots(k,n,1.5).unwrap().len(),n);}}}
 #[test]fn rejects_bad_supply(){let v=json!({"schema_version":1,"operation":"supply","soldiers":-1,"days":1.0,"available_rations":1.0,"consumed":1.0,"readiness":1.0,"days_without_full_rations":0.0,"total_consumed":0.0,"persisted_advance":true});assert!(supply(&v).is_err());}
}
