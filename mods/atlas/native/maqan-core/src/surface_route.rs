//! A* over the player's persisted explored surface only. No chunk reads or discovery.
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap, HashSet};

#[derive(Clone, Copy, Debug)]
pub struct Cell { pub x:i32, pub z:i32, pub height:i32, pub biome:u8 }

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Mode { Walk, Vehicle, Flight }
impl Mode { pub fn from_code(v:i32)->Option<Self>{match v{0=>Some(Self::Walk),1=>Some(Self::Vehicle),2=>Some(Self::Flight),_=>None}} }

#[derive(Clone, Debug, PartialEq)]
pub struct Result { pub found:bool, pub points:Vec<(i32,i32)>, pub cost:f64, pub expanded:usize }

#[derive(Clone, Copy)]
struct Entry { x:i32,z:i32,score:f64,sequence:u64 }
impl PartialEq for Entry { fn eq(&self,o:&Self)->bool{self.x==o.x&&self.z==o.z&&self.score.to_bits()==o.score.to_bits()&&self.sequence==o.sequence} }
impl Eq for Entry {}
impl PartialOrd for Entry { fn partial_cmp(&self,o:&Self)->Option<Ordering>{Some(self.cmp(o))} }
impl Ord for Entry {
 fn cmp(&self,o:&Self)->Ordering{o.score.total_cmp(&self.score).then_with(||o.sequence.cmp(&self.sequence))}
}
const DIRECTIONS:[(i32,i32);8]=[(-1,-1),(0,-1),(1,-1),(-1,0),(1,0),(-1,1),(0,1),(1,1)];
fn heuristic(x:i32,z:i32,gx:i32,gz:i32)->f64{
 let dx=(i64::from(x)-i64::from(gx)).unsigned_abs() as f64;
 let dz=(i64::from(z)-i64::from(gz)).unsigned_abs() as f64;
 dx.max(dz)+(std::f64::consts::SQRT_2-1.0)*dx.min(dz)
}
fn biome_cost(biome:u8,mode:Mode)->f64{match biome{
 1=>if mode==Mode::Flight{2.0}else{80.0},
 2=>match mode{Mode::Vehicle=>35.0,Mode::Walk=>8.0,Mode::Flight=>0.5},
 3=>match mode{Mode::Vehicle=>4.0,Mode::Walk=>2.0,Mode::Flight=>0.25},
 _=>0.0
}}
fn height_cost(delta:i64,mode:Mode)->f64{let factor=match mode{Mode::Walk=>0.035,Mode::Vehicle=>0.075,Mode::Flight=>0.002};delta as f64*factor}

pub fn find(cells:&[Cell],start:(i32,i32),goal:(i32,i32),mode:Mode,max_expansions:usize)->Result{
 let known:HashMap<(i32,i32),Cell>=cells.iter().copied().map(|c|((c.x,c.z),c)).collect();
 if !known.contains_key(&start)||!known.contains_key(&goal){return Result{found:false,points:Vec::new(),cost:0.0,expanded:0};}
 if start==goal{return Result{found:true,points:vec![start],cost:0.0,expanded:0};}
 let mut g:HashMap<(i32,i32),f64>=HashMap::new();
 let mut parents:HashMap<(i32,i32),(i32,i32)>=HashMap::new();
 let mut closed:HashSet<(i32,i32)>=HashSet::new();
 let mut open=BinaryHeap::new();let mut sequence=0_u64;let mut expanded=0_usize;
 g.insert(start,0.0);open.push(Entry{x:start.0,z:start.1,score:heuristic(start.0,start.1,goal.0,goal.1),sequence});
 while let Some(entry)=open.pop(){
  let current=(entry.x,entry.z);
  if closed.contains(&current){continue;} closed.insert(current);expanded+=1;
  if current==goal{
   let mut points=vec![goal];let mut cursor=goal;
   while cursor!=start{let Some(parent)=parents.get(&cursor).copied()else{return Result{found:false,points:Vec::new(),cost:0.0,expanded};};cursor=parent;points.push(cursor);}
   points.reverse();return Result{found:true,points,cost:*g.get(&goal).unwrap_or(&0.0),expanded};
  }
  if expanded>=max_expansions{break;}
  let from=known[&current];
  for (dx,dz) in DIRECTIONS{
   let next=(current.0.saturating_add(dx),current.1.saturating_add(dz));
   let Some(to)=known.get(&next)else{continue;};if closed.contains(&next){continue;}
   let diagonal=dx!=0&&dz!=0;
   if diagonal&&(!known.contains_key(&(current.0.saturating_add(dx),current.1))
    ||!known.contains_key(&(current.0,current.1.saturating_add(dz)))){continue;}
   let mut step=if diagonal{std::f64::consts::SQRT_2}else{1.0};
   let delta=(i64::from(to.height)-i64::from(from.height)).abs();
   step+=height_cost(delta,mode)+biome_cost(to.biome,mode);
   let tentative=g[&current]+step;
   if tentative<*g.get(&next).unwrap_or(&f64::INFINITY){
    parents.insert(next,current);g.insert(next,tentative);sequence=sequence.wrapping_add(1);
    open.push(Entry{x:next.0,z:next.1,score:tentative+heuristic(next.0,next.1,goal.0,goal.1),sequence});
   }
  }
 }
 Result{found:false,points:Vec::new(),cost:0.0,expanded}
}

#[cfg(test)]
mod tests{
 use super::*;
 fn cells()->Vec<Cell>{(0..5).flat_map(|x|(0..3).map(move|z|Cell{x,z,height:64,biome:0})).collect()}
 #[test]fn finds_shortest_explored_corridor_and_preserves_endpoints(){
  let r=find(&cells(),(0,1),(4,1),Mode::Walk,100);
  assert!(r.found);assert_eq!(r.points.first(),Some(&(0,1)));assert_eq!(r.points.last(),Some(&(4,1)));
  assert!((r.cost-4.0).abs()<1e-9);
 }
 #[test]fn diagonal_corner_cutting_is_forbidden(){
  let c=vec![Cell{x:0,z:0,height:64,biome:0},Cell{x:1,z:1,height:64,biome:0}];
  assert!(!find(&c,(0,0),(1,1),Mode::Walk,100).found);
 }
 #[test]fn biome_and_mode_change_route_cost_without_changing_exploration(){
  let c=vec![Cell{x:0,z:0,height:64,biome:0},Cell{x:1,z:0,height:64,biome:2},Cell{x:2,z:0,height:64,biome:0},
   Cell{x:0,z:1,height:64,biome:0},Cell{x:1,z:1,height:64,biome:0},Cell{x:2,z:1,height:64,biome:0}];
  let walk=find(&c,(0,0),(2,0),Mode::Walk,100);let flight=find(&c,(0,0),(2,0),Mode::Flight,100);
  assert!(walk.found&&flight.found);assert!(walk.cost>flight.cost);
 }
 #[test]fn refuses_unexplored_endpoints_and_obeys_expansion_budget(){
  assert!(!find(&cells(),(-1,0),(4,2),Mode::Walk,100).found);
  assert!(!find(&cells(),(0,0),(4,2),Mode::Walk,1).found);
 }
}
