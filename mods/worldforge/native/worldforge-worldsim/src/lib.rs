pub mod worldgen;
//! Autonomous WorldForge decision engine.
//!
//! Pure deterministic Rust domain layer. It receives immutable snapshots from
//! the Minecraft host and emits proposals; it never mutates Minecraft state.
//! Stable ordering and seeded hashing make identical snapshots reproducible.

pub mod jni;

use std::collections::{BTreeMap, BTreeSet};

pub type Day = u64;
pub type Fixed = i64;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Realm {
    pub id: String,
    pub population: u64,
    pub treasury: Fixed,
    pub army_strength: u64,
    pub stability: i32,
    pub prestige: i32,
    pub expansion_pressure: i32,
    pub at_war: bool,
    pub last_war_day: Day,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Border {
    pub a: String,
    pub b: String,
    pub contested_claim: bool,
    pub strategic_value: i32,
    pub distance_cost: i32,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Settlement {
    pub id: String,
    pub realm_id: String,
    pub population: u64,
    pub food: Fixed,
    pub food_capacity: Fixed,
    pub wood: Fixed,
    pub stone: Fixed,
    pub housing: u32,
    pub housing_used: u32,
    pub farms: u32,
    pub foresters: u32,
    pub quarries: u32,
    pub workshops: u32,
    pub barracks: u32,
    pub roads: u32,
    pub local_pressure: i32,
    pub last_project_day: Day,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Character {
    pub id: String,
    pub realm_id: String,
    pub age: u16,
    pub alive: bool,
    pub married: bool,
    pub gender: u8,
    pub fertility: i32,
    pub prestige: i32,
    pub opinion: i32,
    pub house_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Workforce {
    pub settlement_id: String,
    pub farmers: u32,
    pub lumberjacks: u32,
    pub miners: u32,
    pub builders: u32,
    pub soldiers: u32,
    pub unemployed: u32,
    pub food_need: Fixed,
    pub food_output: Fixed,
    pub wood_need: Fixed,
    pub wood_output: Fixed,
    pub stone_need: Fixed,
    pub stone_output: Fixed,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WorldSnapshot {
    pub world_id: String,
    pub seed: u64,
    pub day: Day,
    pub realms: Vec<Realm>,
    pub borders: Vec<Border>,
    pub settlements: Vec<Settlement>,
    pub characters: Vec<Character>,
    pub workforce: Vec<Workforce>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ProposalKind {
    DeclareWar { attacker: String, defender: String, score: i32, reason: String },
    Build { settlement: String, building: String, priority: i32, reason: String },
    ReassignProfession { settlement: String, from: String, to: String, workers: u32, reason: String },
    Recruit { realm: String, settlement: String, unit_role: String, count: u32, reason: String },
    DynasticMarriage { first: String, second: String, score: i32, reason: String },
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Proposal {
    pub id: String,
    pub day: Day,
    pub kind: ProposalKind,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DecisionFrame {
    pub world_id: String,
    pub day: Day,
    pub proposals: Vec<Proposal>,
    pub diagnostics: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SimError {
    EmptyWorldId,
    DuplicateRealm(String),
    UnknownRealm(String),
    InvalidSnapshot(String),
}

fn stable_hash(seed: u64, domain: &str, a: &str, b: &str, day: Day) -> u64 {
    let mut h = seed ^ 0x9E37_79B9_7F4A_7C15;
    for byte in domain.bytes().chain([0xff]).chain(a.bytes()).chain([0xfe]).chain(b.bytes()).chain(day.to_le_bytes()) {
        h ^= byte as u64;
        h = h.wrapping_mul(0x100_0000_01B3);
        h ^= h >> 29;
    }
    h
}

fn proposal_id(seed: u64, day: Day, domain: &str, a: &str, b: &str) -> String {
    format!("{:016x}", stable_hash(seed, domain, a, b, day))
}

fn clamp_score(v: i64) -> i32 { v.clamp(-100, 100) as i32 }

impl WorldSnapshot {
    pub fn validate(&self) -> Result<(), SimError> {
        if self.world_id.trim().is_empty() { return Err(SimError::EmptyWorldId); }
        let mut realms = BTreeSet::new();
        for r in &self.realms {
            if !realms.insert(r.id.as_str()) { return Err(SimError::DuplicateRealm(r.id.clone())); }
        }
        for b in &self.borders {
            if !realms.contains(b.a.as_str()) { return Err(SimError::UnknownRealm(b.a.clone())); }
            if !realms.contains(b.b.as_str()) { return Err(SimError::UnknownRealm(b.b.clone())); }
            if b.a == b.b { return Err(SimError::InvalidSnapshot("self-border".into())); }
        }
        for s in &self.settlements {
            if !realms.contains(s.realm_id.as_str()) { return Err(SimError::UnknownRealm(s.realm_id.clone())); }
            if s.food < 0 || s.wood < 0 || s.stone < 0 || s.food_capacity < 0 {
                return Err(SimError::InvalidSnapshot(format!("negative stock in {}", s.id)));
            }
        }
        Ok(())
    }
}

pub fn decide(snapshot: &WorldSnapshot) -> Result<DecisionFrame, SimError> {
    snapshot.validate()?;
    let realms: BTreeMap<&str, &Realm> = snapshot.realms.iter().map(|r| (r.id.as_str(), r)).collect();
    let settlements: BTreeMap<&str, &Settlement> = snapshot.settlements.iter().map(|s| (s.id.as_str(), s)).collect();
    let mut proposals = Vec::new();
    let mut diagnostics = Vec::new();

    // Political AI: border wars emerge from pressure, claims, power balance,
    // stability and treasury. A war is never forced by the scenario.
    let mut borders = snapshot.borders.iter().collect::<Vec<_>>();
    borders.sort_by(|x, y| (&x.a, &x.b).cmp(&(&y.a, &y.b)));
    for border in borders {
        let a = realms[border.a.as_str()];
        let b = realms[border.b.as_str()];
        for (attacker, defender) in [(a, b), (b, a)] {
            if attacker.at_war || defender.at_war || snapshot.day.saturating_sub(attacker.last_war_day) < 180
                || attacker.treasury < 100 || attacker.stability < 20 { continue; }
            let power = if defender.army_strength == 0 { 100 } else {
                ((attacker.army_strength as i64 * 100) / defender.army_strength as i64).clamp(0, 200) as i32
            };
            let score = clamp_score(
                attacker.expansion_pressure as i64 / 2
                + if border.contested_claim { 28 } else { 0 }
                + border.strategic_value as i64 / 3
                + power as i64 / 4
                + attacker.prestige as i64 / 5
                - defender.stability as i64 / 4
                - border.distance_cost as i64
                - 10
            );
            let roll = (stable_hash(snapshot.seed, "war", &attacker.id, &defender.id, snapshot.day) % 100) as i32;
            if score >= 45 && roll < (score - 35).clamp(0, 65) {
                let reason = format!("expansion pressure; border value {}; claim={}; power ratio={}%; stability={}",
                    border.strategic_value, border.contested_claim, power, attacker.stability);
                proposals.push(Proposal {
                    id: proposal_id(snapshot.seed, snapshot.day, "war", &attacker.id, &defender.id),
                    day: snapshot.day,
                    kind: ProposalKind::DeclareWar { attacker: attacker.id.clone(), defender: defender.id.clone(), score, reason },
                });
                break;
            }
        }
    }

    // Settlement AI: independently selects a useful project from actual shortages
    // and capacity pressure. No building is preselected by the scenario.
    let mut ordered_settlements = snapshot.settlements.iter().collect::<Vec<_>>();
    ordered_settlements.sort_by(|a, b| a.id.cmp(&b.id));
    for s in ordered_settlements {
        if snapshot.day.saturating_sub(s.last_project_day) < 20 { continue; }
        let food_ratio = if s.food_capacity == 0 { 0 } else { s.food.saturating_mul(100) / s.food_capacity };
        let desired_farms = (s.population / 50).max(1) as u32;
        let desired_foresters = (s.population / 80).max(1) as u32;
        let desired_quarries = (s.population / 100).max(1) as u32;
        let (building, priority, reason) = if s.food < (s.population as i64 * 2) && s.farms < desired_farms {
            ("farm", 95, "food reserve below two days of population demand")
        } else if s.housing > 0 && s.housing_used.saturating_mul(100) >= s.housing.saturating_mul(85) {
            ("house", 82, "housing capacity approaching saturation")
        } else if s.wood < 20 && s.foresters < desired_foresters {
            ("lumber_camp", 72, "wood reserve below construction threshold")
        } else if s.stone < 15 && s.population > 30 && s.quarries < desired_quarries {
            ("quarry", 58, "stone reserve limits infrastructure")
        } else if s.workshops == 0 && s.population > 15 {
            ("market", 38, "population can support a local exchange and specialist jobs")
        } else {
            diagnostics.push(format!("settlement:{} no urgent project", s.id));
            continue;
        };
        let adjusted = priority + (100 - food_ratio as i32).max(0).min(20);
        proposals.push(Proposal {
            id: proposal_id(snapshot.seed, snapshot.day, "build", &s.id, building),
            day: snapshot.day,
            kind: ProposalKind::Build { settlement: s.id.clone(), building: building.into(), priority: adjusted, reason: reason.into() },
        });
    }

    // Workforce AI: only reallocates unemployed workers, and never removes
    // workers from food production while food is below the safety reserve.
    let mut workforce = snapshot.workforce.iter().collect::<Vec<_>>();
    workforce.sort_by(|a, b| a.settlement_id.cmp(&b.settlement_id));
    for w in workforce {
        let Some(s) = settlements.get(w.settlement_id.as_str()) else {
            diagnostics.push(format!("workforce:{} has no settlement snapshot", w.settlement_id));
            continue;
        };
        if w.unemployed == 0 { continue; }
        let target = if w.food_output < w.food_need { Some(("unemployed", "farmer", w.food_need - w.food_output)) }
            else if w.wood_output < w.wood_need { Some(("unemployed", "lumberjack", w.wood_need - w.wood_output)) }
            else if w.stone_output < w.stone_need { Some(("unemployed", "miner", w.stone_need - w.stone_output)) }
            else if s.housing_used < s.housing && s.population > 10 && w.builders == 0 { Some(("unemployed", "builder", 1)) }
            else { None };
        if let Some((from, to, deficit)) = target {
            proposals.push(Proposal {
                id: proposal_id(snapshot.seed, snapshot.day, "profession", &w.settlement_id, to),
                day: snapshot.day,
                kind: ProposalKind::ReassignProfession {
                    settlement: w.settlement_id.clone(), from: from.into(), to: to.into(),
                    workers: if deficit > 0 { 1 } else { 0 },
                    reason: format!("unemployed workforce assigned to cover {to} production deficit"),
                },
            });
        }
    }

    // Recruitment AI: only proposes recruitment when a settlement has barracks,
    // realm is threatened and treasury/food stocks can support the force.
    for s in &snapshot.settlements {
        let Some(r) = realms.get(s.realm_id.as_str()) else { continue; };
        let threatened = snapshot.borders.iter().any(|b| {
            (b.a == r.id || b.b == r.id) && b.contested_claim
        });
        if threatened && s.barracks > 0 && r.treasury >= 250 && s.food > (s.population as i64) {
            proposals.push(Proposal {
                id: proposal_id(snapshot.seed, snapshot.day, "recruit", &r.id, &s.id),
                day: snapshot.day,
                kind: ProposalKind::Recruit {
                    realm: r.id.clone(), settlement: s.id.clone(), unit_role: "militia".into(), count: 10,
                    reason: "contested frontier and sufficient treasury, food and barracks".into(),
                },
            });
        }
    }

    // Marriage AI: candidates are ranked by cross-realm benefit, not hard-coded pairings.
    let mut eligible = snapshot.characters.iter().filter(|c| c.alive && !c.married && c.age >= 16 && c.age <= 45).collect::<Vec<_>>();
    eligible.sort_by(|a, b| a.id.cmp(&b.id));
    let mut used = BTreeSet::new();
    for a in &eligible {
        if used.contains(a.id.as_str()) { continue; }
        let mut best: Option<(&Character, i32)> = None;
        for b in &eligible {
            if a.id == b.id || a.realm_id == b.realm_id || a.gender == b.gender || used.contains(b.id.as_str()) { continue; }
            let score = clamp_score(25 + (a.opinion + b.opinion) / 4
                + (a.prestige + b.prestige) / 8
                + if a.house_id != b.house_id { 10 } else { 0 }
                - (a.age as i32 - b.age as i32).abs());
            if score >= 35 && best.map(|(_, current)| score > current).unwrap_or(true) { best = Some((b, score)); }
        }
        if let Some((b, score)) = best {
            let roll = stable_hash(snapshot.seed, "marriage", &a.id, &b.id, snapshot.day) % 100;
            if roll < (score - 20).clamp(0, 70) as u64 {
                used.insert(a.id.as_str()); used.insert(b.id.as_str());
                proposals.push(Proposal {
                    id: proposal_id(snapshot.seed, snapshot.day, "marriage", &a.id, &b.id),
                    day: snapshot.day,
                    kind: ProposalKind::DynasticMarriage { first: a.id.clone(), second: b.id.clone(), score, reason: "highest available cross-realm dynastic compatibility".into() },
                });
            }
        }
    }

    proposals.sort_by(|a, b| a.id.cmp(&b.id));
    let mut unique = BTreeSet::new();
    proposals.retain(|p| unique.insert(p.id.clone()));
    Ok(DecisionFrame { world_id: snapshot.world_id.clone(), day: snapshot.day, proposals, diagnostics })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn realm(id: &str, army: u64) -> Realm {
        Realm { id:id.into(), population:1000, treasury:1000, army_strength:army, stability:60,
            prestige:50, expansion_pressure:100, at_war:false, last_war_day:0 }
    }
    fn base() -> WorldSnapshot {
        WorldSnapshot { world_id:"test-world".into(), seed:42, day:365,
            realms:vec![realm("north",200),realm("south",100)],
            borders:vec![Border{a:"north".into(),b:"south".into(),contested_claim:true,strategic_value:100,distance_cost:0}],
            settlements:vec![Settlement{id:"village".into(),realm_id:"north".into(),population:40,food:10,food_capacity:100,
                wood:30,stone:30,housing:100,housing_used:90,farms:0,foresters:0,quarries:0,workshops:0,barracks:1,roads:0,local_pressure:20,last_project_day:0}],
            characters:vec![],workforce:vec![] }
    }
    #[test] fn decisions_are_deterministic_and_idempotently_identified() {
        let s=base(); assert_eq!(decide(&s).unwrap(),decide(&s).unwrap());
    }
    #[test] fn war_is_emergent_not_mandatory() {
        let mut s=base(); s.realms[0].stability=0;
        assert!(!decide(&s).unwrap().proposals.iter().any(|p| matches!(p.kind,ProposalKind::DeclareWar{..})));
    }
    #[test] fn settlement_selects_farm_from_food_shortage() {
        let frame=decide(&base()).unwrap();
        assert!(frame.proposals.iter().any(|p| matches!(&p.kind,ProposalKind::Build{building,..} if building=="farm")));
    }
    #[test] fn workforce_moves_unemployed_into_food_deficit() {
        let mut s=base(); s.workforce.push(Workforce{settlement_id:"village".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,unemployed:3,food_need:100,food_output:0,wood_need:0,wood_output:0,stone_need:0,stone_output:0});
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(&p.kind,ProposalKind::ReassignProfession{to,..} if to=="farmer")));
    }
    #[test] fn marriage_requires_two_distinct_realms() {
        let mut s=base(); s.characters=vec![
            Character{id:"a".into(),realm_id:"north".into(),age:22,alive:true,married:false,gender:0,fertility:10,prestige:50,opinion:50,house_id:"A".into()},
            Character{id:"b".into(),realm_id:"south".into(),age:23,alive:true,married:false,gender:1,fertility:10,prestige:50,opinion:50,house_id:"B".into()}
        ];
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(p.kind,ProposalKind::DynasticMarriage{..})));
    }
    #[test] fn duplicate_realms_are_rejected() {
        let mut s=base(); s.realms.push(s.realms[0].clone());
        assert_eq!(s.validate(),Err(SimError::DuplicateRealm("north".into())));
    }
}
