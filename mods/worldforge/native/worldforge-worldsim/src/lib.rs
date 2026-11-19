//! Autonomous WorldForge decision engine.
//!
//! Pure deterministic Rust domain layer. It receives immutable snapshots from
//! the Minecraft host and emits proposals; it never mutates Minecraft state.
//! Stable ordering and seeded hashing make identical snapshots reproducible.

pub mod jni;
pub mod worldgen;
pub mod emergence;
pub mod population;
pub mod army;
pub mod strategic_army;
pub mod troops;
pub mod trade;

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

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct GovernmentDefinition {
    pub id: String,
    pub name: String,
    pub category: String,
    pub succession: String,
    pub min_level: u32,
    #[serde(default)] pub req_races: Vec<String>,
    #[serde(default)] pub forbid_races: Vec<String>,
    #[serde(default)] pub req_flags: Vec<String>,
    #[serde(default)] pub req_terrain: BTreeMap<String, f64>,
    #[serde(default)] pub aff_races: BTreeMap<String, f64>,
    #[serde(default)] pub aff_terrain: BTreeMap<String, f64>,
    #[serde(default)] pub mods: BTreeMap<String, f64>,
    pub doctrine_slots: u32,
    pub law_slots: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct RealmGovernment {
    pub realm_id: String,
    pub government_id: String,
    pub level_id: String,
    pub doctrine_ids: Vec<String>,
    pub laws: BTreeMap<String, String>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PoliticalTitle {
    pub id: String,
    pub map_id: String,
    pub key: String,
    pub name: String,
    /// Sovereign owner; unclaimed:<map_id> denotes neutral geography, not a realm.
    pub realm_id: String,
    pub tier_index: u32,
    pub tier_key: String,
    pub parent_title_id: String,
    pub liege_title_id: String,
    pub holder_character_id: String,
    pub claims: Vec<String>,
    pub area_blocks: u64,
    pub neighbors: Vec<String>,
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
    pub watch_posts: u32,
    pub threatened: bool,
    pub roads: u32,
    pub local_pressure: i32,
    pub last_project_day: Day,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Character {
    pub id: String,
    pub realm_id: String,
    #[serde(default)] pub map_id: String,
    pub age: u16,
    pub alive: bool,
    pub married: bool,
    pub gender: u8,
    pub fertility: i32,
    pub prestige: i32,
    pub opinion: i32,
    pub house_id: String,
    #[serde(default)] pub display_name: String,
    #[serde(default)] pub race_id: String,
    #[serde(default)] pub culture_id: String,
    #[serde(default)] pub class_id: String,
    #[serde(default)] pub profession_id: String,
    #[serde(default)] pub trait_ids: Vec<String>,
    #[serde(default)] pub father_id: String,
    #[serde(default)] pub mother_id: String,
    #[serde(default)] pub spouse_id: String,
    #[serde(default)] pub child_ids: Vec<String>,
    #[serde(default)] pub role_ids: Vec<String>,
    #[serde(default)] pub title_ids: Vec<String>,
    #[serde(default)] pub skills: BTreeMap<String, i32>,
    #[serde(default)] pub needs: BTreeMap<String, i32>,
    #[serde(default)] pub duty_ids: Vec<String>,
    #[serde(default)] pub life_stage: String,
    #[serde(default)] pub last_intent: String,
    #[serde(default)] pub profession_history: Vec<String>,
    #[serde(default)] pub life_ai: serde_json::Value,
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
pub struct GeographicCell {
    pub x: u32,
    pub z: u32,
    pub biome: String,
    pub elevation: i32,
    pub river: u8,
    pub land: bool,
    pub suitability: u8,
    /// Empty for unclaimed land; otherwise the canonical map realm that owns this sample.
    /// Older snapshots omit it and remain readable through serde's default.
    #[serde(default)]
    pub owner_realm_id: String,
    /// Elapsed world year when this terrain cell first became observable.
    /// Image-map cells default to year zero; vanilla chunks persist their discovery year.
    #[serde(default)]
    pub discovered_year: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct GeographicMap {
    pub map_id: String,
    pub width_px: u32,
    pub height_px: u32,
    pub blocks_per_pixel_milli: u64,
    pub political_layers_present: bool,
    pub sample_stride: u32,
    pub cells: Vec<GeographicCell>,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct CapabilityDescriptor {
    pub module_id: String,
    pub schema_version: u32,
    pub available: bool,
    #[serde(default)] pub snapshot_received: bool,
    #[serde(default)] pub simulation_consumed: bool,
    #[serde(default)] pub apply_handler_available: bool,
    #[serde(default)] pub catalog_count: u32,
    #[serde(default)] pub entity_count: u32,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct CatalogEntry {
    pub module_id: String,
    pub catalog_id: String,
    pub entry_id: String,
    #[serde(default)] pub name: String,
    #[serde(default)] pub payload: serde_json::Value,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DiplomaticRelation {
    pub realm_a: String,
    pub realm_b: String,
    pub opinion: i32,
    #[serde(default)] pub treaties: Vec<String>,
    #[serde(default)] pub truce_until_day: Day,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct VassalContract {
    pub vassal_realm: String,
    pub liege_realm: String,
    pub subject_type: String,
    pub tax_share: f64,
    pub levy_share: f64,
    pub loyalty: i32,
    pub autonomy: i32,
    pub protected_by_liege: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WarState {
    pub id: String,
    pub attacker_realm: String,
    pub defender_realm: String,
    pub casus_belli: String,
    pub declared_by_character_id: String,
    pub war_score: i32,
    pub start_day: Day,
    pub status: String,
    pub outcome: String,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ArmyFormation {
    pub id: String,
    pub realm_id: String,
    pub name: String,
    pub commander_id: String,
    pub martial: i32,
    pub men: u32,
    pub morale: f64,
    pub supply: f64,
    pub location_title_id: String,
    #[serde(default)] pub unit_counts: BTreeMap<String, u32>,
    pub power: f64,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct SocialRelation {
    pub character_a: String,
    pub character_b: String,
    pub relationship: i32,
    pub love: i32,
    pub married: bool,
    pub dating: bool,
    pub employed: bool,
    pub last_contact_day: Day,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct NamedEntity {
    pub id: String,
    pub kind: String,
    pub name: String,
    #[serde(default)] pub parent_id: String,
    #[serde(default)] pub owner_id: String,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct WorldSnapshot {
    pub world_id: String,
    pub seed: u64,
    pub day: Day,
    pub realms: Vec<Realm>,
    /// Complete title tree from every loaded authored map, including neutral titles.
    #[serde(default)]
    pub titles: Vec<PoliticalTitle>,
    /// Complete data-pack government catalogue and each realm's live policy selection.
    #[serde(default)]
    pub government_definitions: Vec<GovernmentDefinition>,
    #[serde(default)]
    pub realm_governments: Vec<RealmGovernment>,
    pub borders: Vec<Border>,
    pub settlements: Vec<Settlement>,
    pub characters: Vec<Character>,
    pub workforce: Vec<Workforce>,
    /// Module-owned capability and definition catalogs. Entries are never truncated by category.
    #[serde(default)] pub capabilities: Vec<CapabilityDescriptor>,
    #[serde(default)] pub catalog_entries: Vec<CatalogEntry>,
    #[serde(default)] pub diplomatic_relations: Vec<DiplomaticRelation>,
    #[serde(default)] pub vassal_contracts: Vec<VassalContract>,
    #[serde(default)] pub wars: Vec<WarState>,
    #[serde(default)] pub armies: Vec<ArmyFormation>,
    #[serde(default)] pub social_relations: Vec<SocialRelation>,
    #[serde(default)] pub named_entities: Vec<NamedEntity>,
    /// Optional, political-overlay-free geography. Missing in legacy snapshots.
    #[serde(default)]
    pub geography: Vec<GeographicMap>,
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
    DuplicateSettlement(String),
    DuplicateCharacter(String),
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


fn realm_policy_modifier(snapshot: &WorldSnapshot, realm_id: &str, key: &str) -> f64 {
    let Some(selection) = snapshot.realm_governments.iter().find(|selection| selection.realm_id == realm_id) else {
        return 0.0;
    };
    let mut total = snapshot.government_definitions.iter()
        .find(|definition| definition.id == selection.government_id)
        .and_then(|definition| definition.mods.get(key)).copied().unwrap_or(0.0);
    if let Some(level) = snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == "wf_realms" && entry.catalog_id == "government_levels"
            && entry.entry_id == selection.level_id) {
        total += level.payload.get("mods").and_then(|mods| mods.get(key))
            .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
    }
    for doctrine in &selection.doctrine_ids {
        let (branch_id, node_id) = doctrine.split_once(':').unwrap_or(("", doctrine.as_str()));
        for branch in snapshot.catalog_entries.iter().filter(|entry|
            entry.module_id == "wf_realms" && entry.catalog_id == "doctrine_branches"
                && (branch_id.is_empty() || entry.entry_id == branch_id)) {
            if let Some(nodes) = branch.payload.get("nodes").and_then(serde_json::Value::as_array) {
                if let Some(node) = nodes.iter().find(|node|
                    node.get("key").or_else(|| node.get("id")).and_then(serde_json::Value::as_str) == Some(node_id)) {
                    total += node.get("mods").and_then(|mods| mods.get(key))
                        .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                    break;
                }
            }
        }
    }
    for (law_id, option_id) in &selection.laws {
        if let Some(law) = snapshot.catalog_entries.iter().find(|entry|
            entry.module_id == "wf_realms" && entry.catalog_id == "laws" && entry.entry_id == *law_id) {
            if let Some(options) = law.payload.get("options").and_then(serde_json::Value::as_array) {
                if let Some(option) = options.iter().find(|option|
                    option.get("id").and_then(serde_json::Value::as_str) == Some(option_id.as_str())) {
                    total += option.get("mods").and_then(|mods| mods.get(key))
                        .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                }
            }
        }
    }
    if total.is_finite() { total } else { 0.0 }
}

fn catalog_entry<'a>(snapshot: &'a WorldSnapshot, module: &str, catalog: &str, id: &str) -> Option<&'a CatalogEntry> {
    snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == module && entry.catalog_id == catalog && entry.entry_id == id)
}

fn origin_modifier(snapshot: &WorldSnapshot, character: &Character, key: &str) -> f64 {
    let mut total = 0.0;
    for (catalog, id) in [("races", character.race_id.as_str()), ("classes", character.class_id.as_str())] {
        if id.is_empty() { continue; }
        if let Some(entry) = catalog_entry(snapshot, "wf_origins", catalog, id) {
            total += entry.payload.get("mods").and_then(|mods| mods.get(key))
                .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
        }
    }
    if !character.profession_id.is_empty() {
        if let Some(profession) = catalog_entry(snapshot, "wf_origins", "origin_professions", &character.profession_id) {
            let rank = character.skills.get(&character.profession_id).copied().unwrap_or(0).max(0) as usize;
            if let Some(ranks) = profession.payload.get("ranks").and_then(serde_json::Value::as_array) {
                if let Some(selected) = rank.checked_sub(1).and_then(|index| ranks.get(index)) {
                    total += selected.get("mods").and_then(|mods| mods.get(key))
                        .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                }
            }
        }
    }
    for synergy in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_origins" && entry.catalog_id == "synergies") {
        let races = synergy.payload.get("races").and_then(serde_json::Value::as_array);
        let classes = synergy.payload.get("classes").and_then(serde_json::Value::as_array);
        let race_match = races.map(|values| values.is_empty()
            || values.iter().any(|value| value.as_str() == Some(character.race_id.as_str()))).unwrap_or(true);
        let class_match = classes.map(|values| values.is_empty()
            || values.iter().any(|value| value.as_str() == Some(character.class_id.as_str()))).unwrap_or(true);
        let flags_match = synergy.payload.get("flags").and_then(serde_json::Value::as_array)
            .map(|values| values.iter().filter_map(serde_json::Value::as_str)
                .all(|flag| character.trait_ids.iter().any(|trait_id| trait_id == flag))).unwrap_or(true);
        let professions_match = synergy.payload.get("profs").and_then(serde_json::Value::as_object)
            .map(|required| required.iter().all(|(profession, rank)|
                character.skills.get(profession).copied().unwrap_or(0) >= rank.as_i64().unwrap_or(0) as i32))
            .unwrap_or(true);
        if race_match && class_match && flags_match && professions_match {
            total += synergy.payload.get("mods").and_then(|mods| mods.get(key))
                .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
        }
    }
    if total.is_finite() { total } else { 0.0 }
}

fn realm_leader<'a>(snapshot: &'a WorldSnapshot, realm_id: &str) -> Option<&'a Character> {
    let title = snapshot.titles.iter().filter(|title|
        title.realm_id == realm_id && !title.holder_character_id.is_empty())
        .max_by_key(|title| title.tier_index)?;
    snapshot.characters.iter().find(|character| character.id == title.holder_character_id)
}

fn court_opinion_modifier(snapshot: &WorldSnapshot, first: &str, second: &str) -> i64 {
    let mut total = 0_i64;
    for entry in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_court" && entry.catalog_id == "court_states") {
        let world_year = entry.payload.get("world_year").and_then(serde_json::Value::as_u64).unwrap_or(0);
        if let Some(modifiers) = entry.payload.get("mods").and_then(serde_json::Value::as_array) {
            for modifier in modifiers {
                let Some(values) = modifier.as_array() else { continue; };
                if values.len() < 5 || values[0].as_str() != Some(first)
                    || values[1].as_str() != Some(second)
                    || values[4].as_u64().unwrap_or(0) < world_year { continue; }
                total += values[3].as_i64().unwrap_or(0);
            }
        }
    }
    total
}

fn court_instability_pressure(snapshot: &WorldSnapshot, realm_id: &str) -> i64 {
    let mut pressure = 0_i64;
    for entry in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_court" && entry.catalog_id == "court_states") {
        if let Some(regencies) = entry.payload.get("regencies").and_then(serde_json::Value::as_array) {
            for regency in regencies.iter().filter_map(serde_json::Value::as_array) {
                if regency.first().and_then(serde_json::Value::as_str) == Some(realm_id) {
                    let crisis = regency.get(4).and_then(serde_json::Value::as_i64).unwrap_or(0);
                    let stage = regency.get(5).and_then(serde_json::Value::as_i64).unwrap_or(0);
                    pressure += (crisis / 5).max(0) + stage.max(0) * 5;
                }
            }
        }
        if let Some(factions) = entry.payload.get("factions").and_then(serde_json::Value::as_array) {
            for faction in factions.iter() {
                if faction.get("liege").and_then(serde_json::Value::as_str) == Some(realm_id) {
                    let power = faction.get("power").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                    let progress = faction.get("progress").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                    if power.is_finite() && progress.is_finite() && progress > 0.0 {
                        pressure += (power.max(0.0) * (progress / 100.0).clamp(0.0, 1.0) * 20.0).round() as i64;
                    }
                }
            }
        }
    }
    pressure
}

fn has_active_truce(snapshot: &WorldSnapshot, a: &str, b: &str) -> bool {
    snapshot.diplomatic_relations.iter().any(|relation|
        ((relation.realm_a == a && relation.realm_b == b)
            || (relation.realm_a == b && relation.realm_b == a))
            && relation.truce_until_day > snapshot.day)
}

fn has_subject_dependency(snapshot: &WorldSnapshot, a: &str, b: &str) -> bool {
    snapshot.vassal_contracts.iter().any(|contract|
        (contract.liege_realm == a && contract.vassal_realm == b)
            || (contract.liege_realm == b && contract.vassal_realm == a))
}

fn blocks_autonomous_war(snapshot: &WorldSnapshot, a: &str, b: &str) -> bool {
    ["NON_AGGRESSION", "ALLIANCE", "DEFENSIVE_PACT", "FEUDAL_VASSAL",
        "TRIBUTARY", "PROTECTORATE", "OCCUPIED", "PERSONAL_UNION", "CONFEDERATION_MEMBER"]
        .iter().any(|treaty| has_treaty(snapshot, a, b, treaty))
        || has_active_truce(snapshot, a, b)
        || has_subject_dependency(snapshot, a, b)
}

fn realm_in_active_campaign(snapshot: &WorldSnapshot, realm_id: &str) -> bool {
    snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_war" && entry.catalog_id == "war_instances")
        .any(|entry| {
            let war = &entry.payload;
            if !war.get("active").and_then(serde_json::Value::as_bool).unwrap_or(false) { return false; }
            if war.get("attacker").and_then(serde_json::Value::as_str) == Some(realm_id)
                || war.get("defender").and_then(serde_json::Value::as_str) == Some(realm_id) { return true; }
            ["attacker_members", "defender_members"].iter().any(|field|
                war.get(*field).and_then(serde_json::Value::as_array)
                    .map(|members| members.iter().any(|member| member.as_str() == Some(realm_id)))
                    .unwrap_or(false))
        })
}

fn trade_conflict_penalty(snapshot: &WorldSnapshot, first: &str, second: &str) -> i64 {
    let Some(runtime) = catalog_entry(snapshot, "wf_trade", "runtime_state", "trade") else { return 0; };
    runtime.payload.get("caravans").and_then(serde_json::Value::as_array)
        .map(|caravans| caravans.iter().filter(|caravan| {
            let from = caravan.get("from").and_then(serde_json::Value::as_str).unwrap_or("");
            let to = caravan.get("to").and_then(serde_json::Value::as_str).unwrap_or("");
            let status = caravan.get("status").and_then(serde_json::Value::as_str).unwrap_or("");
            status == "в пути" && ((from == first && to == second) || (from == second && to == first))
        }).map(|caravan| {
            let cargo = caravan.get("cargo").and_then(serde_json::Value::as_object)
                .map(|items| items.values().filter_map(serde_json::Value::as_u64).sum::<u64>())
                .unwrap_or(0);
            5 + (cargo / 20).min(20) as i64
        }).sum()).unwrap_or(0)
}

fn market_fragility_penalty(snapshot: &WorldSnapshot, realm_id: &str) -> i64 {
    let Some(market) = catalog_entry(snapshot, "wf_trade", "markets", realm_id) else { return 0; };
    let Some(stock) = market.payload.get("stock").and_then(serde_json::Value::as_object) else { return 0; };
    if stock.is_empty() { return 0; }
    let empty = stock.values().filter(|value| value.as_f64().unwrap_or(0.0) <= 0.0).count();
    ((empty * 20) / stock.len()).min(20) as i64
}

fn realm_treasury_balance(snapshot: &WorldSnapshot, realm_id: &str) -> Option<f64> {
    catalog_entry(snapshot, "wf_realms", "realm_treasuries", realm_id)
        .and_then(|entry| entry.payload.get("balance"))
        .and_then(serde_json::Value::as_f64)
        .filter(|value| value.is_finite() && *value >= 0.0)
}

fn realm_intrigue_pressure(snapshot: &WorldSnapshot, realm_id: &str) -> i64 {
    snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_realms" && entry.catalog_id == "intrigue_plots")
        .filter(|entry| entry.payload.get("realm_id").and_then(serde_json::Value::as_str) == Some(realm_id))
        .filter(|entry| matches!(entry.payload.get("status").and_then(serde_json::Value::as_str),
            Some("GATHERING" | "ACTIVE")))
        .map(|entry| {
            let progress = entry.payload.get("progress").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
            let backers = entry.payload.get("backers").and_then(serde_json::Value::as_array).map(Vec::len).unwrap_or(0);
            (progress / 20.0).round() as i64 + backers.min(10) as i64
        }).sum::<i64>()
}

fn has_treaty(snapshot: &WorldSnapshot, a: &str, b: &str, treaty: &str) -> bool {
    snapshot.diplomatic_relations.iter().any(|relation|
        ((relation.realm_a == a && relation.realm_b == b)
            || (relation.realm_a == b && relation.realm_b == a))
            && relation.treaties.iter().any(|value| value == treaty))
}

fn settlement_allows_building(snapshot: &WorldSnapshot, settlement_id: &str, building: &str) -> bool {
    let instance = snapshot.catalog_entries.iter()
        .find(|entry| entry.module_id == "wf_settle"
            && entry.catalog_id == "settlement_instances" && entry.entry_id == settlement_id);
    let Some(instance_payload) = instance.map(|entry| &entry.payload) else { return true; };
    let type_id = instance_payload.get("type_id").and_then(serde_json::Value::as_str).unwrap_or("");
    let definition = snapshot.catalog_entries.iter()
        .find(|entry| entry.module_id == "wf_settle"
            && entry.catalog_id == "settlement_types" && entry.entry_id == type_id);
    let building_definition = snapshot.catalog_entries.iter()
        .find(|entry| entry.module_id == "wf_settle"
            && entry.catalog_id == "buildings" && entry.entry_id == building);
    let has_building_catalog = snapshot.catalog_entries.iter().any(|entry|
        entry.module_id == "wf_settle" && entry.catalog_id == "buildings");
    let Some(building_payload) = building_definition.map(|entry| &entry.payload) else {
        return !has_building_catalog;
    };
    if let Some(payload) = definition.map(|entry| &entry.payload) {
        let banned = payload.get("ban_buildings").or_else(|| payload.get("banBuildings")).or_else(|| payload.get("ban"))
            .and_then(serde_json::Value::as_array);
        if banned.map(|values| values.iter().any(|value| value.as_str() == Some(building))).unwrap_or(false) {
            return false;
        }
        let allowed = payload.get("allow_buildings").or_else(|| payload.get("allowBuildings")).or_else(|| payload.get("allow"))
            .and_then(serde_json::Value::as_array);
        let explicitly_listed = allowed.map(|values|
            values.iter().any(|value| value.as_str() == Some(building))).unwrap_or(false);
        let allowed_categories = payload.get("allow_categories").or_else(|| payload.get("allowCategories"))
            .and_then(serde_json::Value::as_array);
        if !explicitly_listed {
            let category = building_payload.get("category").and_then(serde_json::Value::as_str).unwrap_or("other");
            let category_allowed = allowed_categories.map(|values|
                values.iter().any(|value| value.as_str() == Some(category))).unwrap_or(false);
            if allowed.is_some_and(|values| !values.is_empty()) && !category_allowed { return false; }
            if allowed_categories.is_some_and(|values| !values.is_empty()) && !category_allowed { return false; }
        }
        if let Some(limit) = payload.get("limits").and_then(|limits| limits.get(building)).and_then(serde_json::Value::as_u64) {
            let count = instance_payload.get("building_counts").and_then(|counts| counts.get(building))
                .and_then(serde_json::Value::as_u64).unwrap_or(0);
            if limit > 0 && count >= limit { return false; }
        }
    }
    if let Some(required) = building_payload.get("requires").and_then(serde_json::Value::as_array) {
        let completed = instance_payload.get("completed_building_counts");
        for prerequisite in required.iter().filter_map(serde_json::Value::as_str) {
            if completed.and_then(|counts| counts.get(prerequisite))
                .and_then(serde_json::Value::as_u64).unwrap_or(0) == 0 { return false; }
        }
    }
    if let Some(limit) = building_payload.get("limit").and_then(serde_json::Value::as_u64) {
        let count = instance_payload.get("building_counts").and_then(|counts| counts.get(building))
            .and_then(serde_json::Value::as_u64).unwrap_or(0);
        if limit > 0 && count >= limit { return false; }
    }
    if let Some(costs) = building_payload.get("cost").and_then(serde_json::Value::as_object) {
        let stock = instance_payload.get("resource_stock");
        for (resource, required) in costs {
            let needed = required.as_f64().unwrap_or(0.0);
            let available = stock.and_then(|values| values.get(resource))
                .and_then(serde_json::Value::as_f64).unwrap_or(0.0);
            if needed > available { return false; }
        }
    }
    true
}

fn recruitment_choice(snapshot: &WorldSnapshot, settlement_id: &str, realm_id: &str) -> Option<(String, u32)> {
    let instance = snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == "wf_settle" && entry.catalog_id == "settlement_instances"
            && entry.entry_id == settlement_id)?;
    let economy = snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == "wf_army" && entry.catalog_id == "realm_economy"
            && entry.entry_id == realm_id)?;
    let pool = economy.payload.get("manpower_pool").and_then(serde_json::Value::as_f64)
        .filter(|value| value.is_finite() && *value > 0.0).map(|value| value.floor() as u64).unwrap_or(0);
    let gold = economy.payload.get("gold").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
    if pool < 10 || !gold.is_finite() || gold < 0.0 { return None; }

    let runtime = snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == "wf_troops" && entry.catalog_id == "runtime_state"
            && entry.entry_id == "military");
    let army_map = runtime.and_then(|entry| entry.payload.get("army_type_map"));
    let recruitment_buildings = runtime.and_then(|entry| entry.payload.get("recruitment_buildings"));
    let physical_defs = runtime.and_then(|entry| entry.payload.get("unit_definitions"));
    let trainer_counts = instance.payload.get("trainer_counts");
    let completed = instance.payload.get("completed_building_counts");

    let mut choices = Vec::new();
    for entry in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_army" && entry.catalog_id == "unit_types") {
        let payload = &entry.payload;
        let levy = payload.get("levy").and_then(serde_json::Value::as_bool).unwrap_or(false);
        let cost = payload.get("cost").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
        if !cost.is_finite() || cost < 0.0 { continue; }
        let physical_type = army_map.and_then(|mapping| mapping.get(&entry.entry_id))
            .and_then(serde_json::Value::as_str).unwrap_or(&entry.entry_id);
        if physical_defs.is_some() && physical_defs.and_then(|defs| defs.get(physical_type)).is_none() { continue; }
        let building = recruitment_buildings.and_then(|mapping| mapping.get(physical_type))
            .and_then(serde_json::Value::as_str).unwrap_or("barracks");
        let trainers = trainer_counts.and_then(|counts| counts.get(building))
            .and_then(serde_json::Value::as_u64).unwrap_or(0);
        let built = completed.and_then(|counts| counts.get(building))
            .and_then(serde_json::Value::as_u64).unwrap_or(0);
        if trainers == 0 || built == 0 { continue; }
        let gold_capacity = if cost <= f64::EPSILON { pool }
            else { ((gold / cost).floor().max(0.0) as u64).saturating_mul(10) };
        let men = pool.min(trainers.saturating_mul(10)).min(gold_capacity);
        let count = (men / 10).saturating_mul(10).min(u32::MAX as u64) as u32;
        if count < 10 { continue; }
        let attack = payload.get("attack").and_then(serde_json::Value::as_f64).unwrap_or(1.0);
        let defense = payload.get("defense").and_then(serde_json::Value::as_f64).unwrap_or(1.0);
        let siege = payload.get("siege").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
        let score = attack + defense + siege + if levy { 0.05 } else { 0.0 };
        choices.push((entry.entry_id.clone(), count, score));
    }
    choices.sort_by(|a, b| b.2.total_cmp(&a.2).then_with(|| a.0.cmp(&b.0)));
    choices.into_iter().next().map(|(unit_id, count, _)| (unit_id, count))
}


fn effective_army_power(snapshot: &WorldSnapshot, realm_id: &str) -> Option<f64> {
    let mut strategic = 0.0;
    for army in snapshot.armies.iter().filter(|army| army.realm_id == realm_id) {
        strategic += army.power * army.supply.clamp(0.0, 1.0);
    }
    if strategic <= 0.0 { return None; }
    let physical = snapshot.catalog_entries.iter().find(|entry|
        entry.module_id == "wf_troops" && entry.catalog_id == "runtime_state"
            && entry.entry_id == "military")
        .and_then(|entry| entry.payload.get("live_troops"))
        .and_then(serde_json::Value::as_array);
    if let Some(troops) = physical {
        let readiness = troops.iter().filter(|troop|
            troop.get("realm_id").and_then(serde_json::Value::as_str) == Some(realm_id))
            .map(|troop| {
                let health = troop.get("health").and_then(serde_json::Value::as_f64).unwrap_or(1.0);
                let maximum = troop.get("max_health").and_then(serde_json::Value::as_f64).unwrap_or(health.max(1.0));
                // TroopEntity.dmgMult already combines commander morale and injury;
                // supply is a separate attribute modifier and must not be counted twice.
                let damage_multiplier = troop.get("damage_multiplier").and_then(serde_json::Value::as_f64).unwrap_or(1.0);
                let supply = troop.get("supply_combat_multiplier").and_then(serde_json::Value::as_f64).unwrap_or(1.0);
                let health_ratio = if maximum > 0.0 { (health / maximum).clamp(0.0, 1.0) } else { 1.0 };
                let equipment = troop.get("equipment").and_then(serde_json::Value::as_object);
                let equipment_factor = equipment.map(|items| {
                    let durability = items.values().map(|item| {
                        let damage = item.get("damage").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                        let maximum = item.get("max_damage").and_then(serde_json::Value::as_f64).unwrap_or(0.0);
                        if maximum > 0.0 { (1.0 - damage / maximum).clamp(0.0, 1.0) } else { 1.0 }
                    }).collect::<Vec<_>>();
                    if durability.is_empty() { 1.0 } else { durability.iter().sum::<f64>() / durability.len() as f64 }
                }).unwrap_or(1.0);
                health_ratio * damage_multiplier.clamp(0.0, 1.5)
                    * supply.clamp(0.0, 1.0) * equipment_factor.clamp(0.0, 1.0)
            }).collect::<Vec<_>>();
        if !readiness.is_empty() {
            let average = readiness.iter().sum::<f64>() / readiness.len() as f64;
            return Some(strategic * average.clamp(0.0, 1.0));
        }
    }
    Some(strategic)
}


fn kinship_distance(a: &Character, b: &Character, characters: &BTreeMap<&str, &Character>) -> u32 {
    fn ancestors(person: &Character, characters: &BTreeMap<&str, &Character>) -> BTreeMap<String, u32> {
        let mut result = BTreeMap::new();
        let mut queue = std::collections::VecDeque::new();
        result.insert(person.id.clone(), 0);
        for parent in [&person.father_id, &person.mother_id] {
            if !parent.is_empty() { queue.push_back((parent.clone(), 1_u32)); }
        }
        while let Some((id, distance)) = queue.pop_front() {
            if distance > 6 || result.contains_key(&id) { continue; }
            result.insert(id.clone(), distance);
            if let Some(parent) = characters.get(id.as_str()) {
                for ancestor in [&parent.father_id, &parent.mother_id] {
                    if !ancestor.is_empty() { queue.push_back((ancestor.clone(), distance + 1)); }
                }
            }
        }
        result
    }
    let first = ancestors(a, characters);
    let second = ancestors(b, characters);
    first.iter().filter_map(|(id, distance)| second.get(id).map(|other| *distance + *other))
        .min().unwrap_or(u32::MAX)
}


fn resource_category<'a>(snapshot: &'a WorldSnapshot, resource: &str) -> &'a str {
    let food_kinds = catalog_entry(snapshot, "wf_settle", "food_kinds", "all_food_kinds")
        .and_then(|entry| entry.payload.as_array());
    if food_kinds.map(|values| values.iter().any(|value| value.as_str() == Some(resource))).unwrap_or(false) {
        return "food";
    }
    catalog_entry(snapshot, "wf_settle", "resources", resource)
        .and_then(|entry| entry.payload.get("category"))
        .and_then(serde_json::Value::as_str)
        .unwrap_or(match resource {
            "food" | "bread" | "fish" | "meat" | "grain" | "flour" => "food",
            "wood" | "planks" | "stone" | "iron" | "wool" => "material",
            "silver" => "money",
            _ => "other",
        })
}

fn choose_building_for_need(snapshot: &WorldSnapshot, settlement: &Settlement, threatened: bool)
    -> Option<(String, i32, String)> {
    let mut candidates = Vec::new();
    for entry in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_settle" && entry.catalog_id == "buildings") {
        if !settlement_allows_building(snapshot, &settlement.id, &entry.entry_id) { continue; }
        let payload = &entry.payload;
        let category = payload.get("category").and_then(serde_json::Value::as_str).unwrap_or("other");
        let housing = payload.get("housing").and_then(serde_json::Value::as_i64).unwrap_or(0);
        let safety = payload.get("safety").and_then(serde_json::Value::as_i64).unwrap_or(0);
        let trade = payload.get("trade").and_then(serde_json::Value::as_i64).unwrap_or(0);
        let outputs = payload.get("out").and_then(serde_json::Value::as_object);
        let jobs = payload.get("jobs").and_then(serde_json::Value::as_object);
        let has_output = |needle: &str| outputs.map(|values| values.iter()
            .any(|(resource, amount)| amount.as_f64().unwrap_or(0.0) > 0.0
                && (resource == needle || (needle == "food" && resource_category(snapshot, resource) == "food"))))
            .unwrap_or(false);
        let has_job = |needles: &[&str]| jobs.map(|values| values.iter()
            .any(|(job, amount)| amount.as_f64().unwrap_or(0.0) > 0.0
                && needles.iter().any(|needle| job.contains(needle))))
            .unwrap_or(false);
        let mut priority = 0_i32;
        let reason;
        if threatened && (category == "military" || safety > 0 || has_job(&["guard", "soldier", "trainer"])) {
            priority = 88 + safety.clamp(0, 10) as i32;
            reason = "registered military building improves frontier security";
        } else if settlement.food < (settlement.population as i64 * 2)
            && (category == "food" || has_output("food") || has_job(&["farmer", "fisher", "herder"])) {
            priority = 95;
            reason = "registered food production covers the settlement food deficit";
        } else if settlement.housing > 0
            && settlement.housing_used.saturating_mul(100) >= settlement.housing.saturating_mul(85)
            && (housing > 0 || category == "housing") {
            priority = 82 + housing.min(10) as i32;
            reason = "registered housing capacity approaches saturation";
        } else if settlement.wood < 20
            && (has_output("wood") || has_job(&["lumber", "forester", "wood"])) {
            priority = 72;
            reason = "registered wood production covers the construction reserve";
        } else if settlement.stone < 15 && settlement.population > 30
            && (has_output("stone") || has_job(&["quarry", "miner", "stone"])) {
            priority = 58;
            reason = "registered stone production supports infrastructure";
        } else if settlement.workshops == 0 && settlement.population > 15
            && (category == "trade" || trade > 0 || category == "industry") {
            priority = 38 + trade.clamp(0, 10) as i32;
            reason = "registered trade or industry building expands specialist production";
        } else { continue; }
        let output = outputs.map(|values| values.values()
            .filter_map(serde_json::Value::as_f64).sum::<f64>()).unwrap_or(0.0);
        let work = payload.get("work").and_then(serde_json::Value::as_f64).unwrap_or(30.0).max(1.0);
        let efficiency = output / work;
        candidates.push((entry.entry_id.clone(), priority, reason.to_string(), efficiency));
    }
    candidates.sort_by(|a, b| b.1.cmp(&a.1)
        .then_with(|| b.3.total_cmp(&a.3)).then_with(|| a.0.cmp(&b.0)));
    candidates.into_iter().next().map(|(id, priority, reason, _)| (id, priority, reason))
}


fn registered_profession(snapshot: &WorldSnapshot, id: &str) -> bool {
    // wf_people owns canonical profession identity; wf_settle only references these
    // IDs from building job slots and may retain legacy local aliases.
    catalog_entry(snapshot, "wf_people", "professions", id).is_some()
}

fn choose_profession_for_output(snapshot: &WorldSnapshot, resource: &str) -> Option<String> {
    let mut choices = Vec::new();
    for building in snapshot.catalog_entries.iter().filter(|entry|
        entry.module_id == "wf_settle" && entry.catalog_id == "buildings") {
        let outputs = building.payload.get("out").and_then(serde_json::Value::as_object);
        let jobs = building.payload.get("jobs").and_then(serde_json::Value::as_object);
        let output_match = outputs.map(|values| values.iter().any(|(good, amount)| {
            if amount.as_f64().unwrap_or(0.0) <= 0.0 { return false; }
            match resource {
                "food" => resource_category(snapshot, good) == "food",
                "wood" => good == "wood" || good.contains("wood") || good.contains("lumber"),
                "stone" => good == "stone" || good.contains("stone"),
                _ => false,
            }
        })).unwrap_or(false);
        if !output_match { continue; }
        let output = outputs.map(|values| values.iter().filter_map(|(good, amount)|
            if resource == "food" && resource_category(snapshot, good) == "food"
                || resource == "wood" && (good == "wood" || good.contains("wood") || good.contains("lumber"))
                || resource == "stone" && (good == "stone" || good.contains("stone"))
            { amount.as_f64() } else { None }).sum::<f64>()).unwrap_or(0.0);
        if let Some(jobs) = jobs {
            for (profession, slots) in jobs {
                let capacity = slots.as_f64().unwrap_or(0.0);
                if capacity <= 0.0 || !registered_profession(snapshot, profession) { continue; }
                let work = building.payload.get("work").and_then(serde_json::Value::as_f64).unwrap_or(30.0).max(1.0);
                choices.push((profession.clone(), output / (capacity * work)));
            }
        }
    }
    choices.sort_by(|a, b| b.1.total_cmp(&a.1).then_with(|| a.0.cmp(&b.0)));
    choices.into_iter().next().map(|(profession, _)| profession)
}

fn choose_builder_profession(snapshot: &WorldSnapshot) -> Option<String> {
    let mut candidates = Vec::new();
    for entry in snapshot.catalog_entries.iter().filter(|entry|
        (entry.module_id == "wf_people" && entry.catalog_id == "professions")
            || (entry.module_id == "wf_settle" && entry.catalog_id == "building_professions")) {
        let id = entry.entry_id.as_str();
        if !registered_profession(snapshot, id) { continue; }
        let name = entry.payload.get("name").and_then(serde_json::Value::as_str).unwrap_or("");
        let group = entry.payload.get("group").and_then(serde_json::Value::as_str).unwrap_or("");
        let id_match = id.to_ascii_lowercase().contains("builder")
            || id.to_ascii_lowercase().contains("construction");
        let name_match = name.to_ascii_lowercase().contains("builder")
            || name.to_ascii_lowercase().contains("construction")
            || name.to_ascii_lowercase().contains("строител");
        let group_match = group.to_ascii_lowercase().contains("construction")
            || group.to_ascii_lowercase().contains("builder");
        if id_match || name_match || group_match { candidates.push(id.to_string()); }
    }
    candidates.sort(); candidates.dedup(); candidates.into_iter().next()
}

impl WorldSnapshot {
    pub fn validate(&self) -> Result<(), SimError> {
        if self.world_id.trim().is_empty() { return Err(SimError::EmptyWorldId); }
        let mut map_ids = BTreeSet::new();
        for map in &self.geography {
            if map.map_id.trim().is_empty() || map.width_px == 0 || map.height_px == 0 || map.sample_stride == 0
                || map.blocks_per_pixel_milli == 0 {
                return Err(SimError::InvalidSnapshot("invalid-geographic-map-metadata".into()));
            }
            if !map_ids.insert(map.map_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("duplicate-geographic-map:{}", map.map_id)));
            }
            let mut cells = BTreeSet::new();
            for cell in &map.cells {
                if cell.x >= map.width_px || cell.z >= map.height_px || cell.biome.trim().is_empty()
                    || cell.suitability > 100 || !cells.insert((cell.x, cell.z)) {
                    return Err(SimError::InvalidSnapshot(format!("invalid-geographic-cell:{}", map.map_id)));
                }
            }
        }
        let mut realms = BTreeSet::new();
        for r in &self.realms {
            if r.id.trim().is_empty() { return Err(SimError::InvalidSnapshot("empty-realm-id".into())); }
            if !realms.insert(r.id.as_str()) { return Err(SimError::DuplicateRealm(r.id.clone())); }
        }
        let mut government_ids = BTreeSet::new();
        for government in &self.government_definitions {
            if government.id.trim().is_empty() || !government_ids.insert(government.id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-government:{}", government.id)));
            }
            if government.mods.values().chain(government.req_terrain.values())
                .chain(government.aff_races.values()).chain(government.aff_terrain.values())
                .any(|value| !value.is_finite()) {
                return Err(SimError::InvalidSnapshot(format!("non-finite-government-data:{}", government.id)));
            }
        }
        let mut realm_government_ids = BTreeSet::new();
        for selection in &self.realm_governments {
            if !realms.contains(selection.realm_id.as_str()) {
                return Err(SimError::UnknownRealm(selection.realm_id.clone()));
            }
            if !selection.government_id.is_empty() && !government_ids.contains(selection.government_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("unknown-government:{}:{}", selection.realm_id, selection.government_id)));
            }
            if !realm_government_ids.insert(selection.realm_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("duplicate-realm-government:{}", selection.realm_id)));
            }
        }
        let mut title_ids = BTreeSet::new();
        for title in &self.titles {
            if title.id.trim().is_empty() || title.map_id.trim().is_empty()
                || title.key.trim().is_empty() || title.tier_key.trim().is_empty()
                || title.realm_id.trim().is_empty() {
                return Err(SimError::InvalidSnapshot("invalid-political-title".into()));
            }
            if !title_ids.insert(title.id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("duplicate-political-title:{}", title.id)));
            }
        }
        for title in &self.titles {
            if !title.parent_title_id.is_empty() && !title_ids.contains(title.parent_title_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("unknown-title-parent:{}:{}", title.id, title.parent_title_id)));
            }
            if !title.liege_title_id.is_empty() && !title_ids.contains(title.liege_title_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("unknown-title-liege:{}:{}", title.id, title.liege_title_id)));
            }
        }
        let mut border_pairs = BTreeSet::new();
        for b in &self.borders {
            if !realms.contains(b.a.as_str()) { return Err(SimError::UnknownRealm(b.a.clone())); }
            if !realms.contains(b.b.as_str()) { return Err(SimError::UnknownRealm(b.b.clone())); }
            if b.a == b.b { return Err(SimError::InvalidSnapshot("self-border".into())); }
            let pair = if b.a < b.b { (b.a.as_str(), b.b.as_str()) } else { (b.b.as_str(), b.a.as_str()) };
            if !border_pairs.insert(pair) { return Err(SimError::InvalidSnapshot(format!("duplicate-border:{}:{}", pair.0, pair.1))); }
        }
        let mut settlements = BTreeSet::new();
        for s in &self.settlements {
            if s.id.trim().is_empty() { return Err(SimError::InvalidSnapshot("empty-settlement-id".into())); }
            if !settlements.insert(s.id.as_str()) { return Err(SimError::DuplicateSettlement(s.id.clone())); }
            if !realms.contains(s.realm_id.as_str()) { return Err(SimError::UnknownRealm(s.realm_id.clone())); }
            if s.food < 0 || s.wood < 0 || s.stone < 0 || s.food_capacity < 0 {
                return Err(SimError::InvalidSnapshot(format!("negative stock in {}", s.id)));
            }
        }
        let mut characters = BTreeSet::new();
        for c in &self.characters {
            if c.id.trim().is_empty() { return Err(SimError::InvalidSnapshot("empty-character-id".into())); }
            if !characters.insert(c.id.as_str()) { return Err(SimError::DuplicateCharacter(c.id.clone())); }
            if !c.realm_id.is_empty() && !realms.contains(c.realm_id.as_str()) { return Err(SimError::UnknownRealm(c.realm_id.clone())); }
            // MAQAN supports fantasy species with multi-century lifespans. Keep a hard
            // corruption bound, but do not reject valid long-lived canonical Characters.
            if c.age > 1_000 { return Err(SimError::InvalidSnapshot(format!("implausible-character-age:{}:{}", c.id, c.age))); }
        }
        let mut workforce = BTreeSet::new();
        for w in &self.workforce {
            if !settlements.contains(w.settlement_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("workforce-without-settlement:{}", w.settlement_id)));
            }
            if !workforce.insert(w.settlement_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("duplicate-workforce:{}", w.settlement_id)));
            }
        }
        let mut capability_ids = BTreeSet::new();
        for capability in &self.capabilities {
            if capability.module_id.trim().is_empty() || capability.schema_version == 0
                || !capability_ids.insert(capability.module_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-capability:{}", capability.module_id)));
            }
        }
        let mut catalog_keys = BTreeSet::new();
        let mut actual_catalog_counts: BTreeMap<&str, u32> = BTreeMap::new();
        for entry in &self.catalog_entries {
            if entry.module_id.trim().is_empty() || entry.catalog_id.trim().is_empty() || entry.entry_id.trim().is_empty()
                || !catalog_keys.insert((entry.module_id.as_str(), entry.catalog_id.as_str(), entry.entry_id.as_str())) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-catalog-entry:{}:{}:{}", entry.module_id, entry.catalog_id, entry.entry_id)));
            }
            *actual_catalog_counts.entry(entry.module_id.as_str()).or_default() += 1;
        }
        for (module, count) in &actual_catalog_counts {
            let Some(capability) = self.capabilities.iter().find(|capability| capability.module_id == *module) else {
                return Err(SimError::InvalidSnapshot(format!("catalog-without-capability:{module}")));
            };
            if !capability.available || !capability.snapshot_received || !capability.simulation_consumed {
                return Err(SimError::InvalidSnapshot(format!("catalog-not-transferred-or-consumed:{module}")));
            }
            let expected = *count + if *module == "wf_realms" { self.government_definitions.len() as u32 } else { 0 };
            if capability.catalog_count != expected {
                return Err(SimError::InvalidSnapshot(format!("catalog-count-mismatch:{module}:declared={}:actual={expected}", capability.catalog_count)));
            }
        }
        for capability in &self.capabilities {
            if capability.snapshot_received && !capability.available {
                return Err(SimError::InvalidSnapshot(format!("snapshot-from-unavailable-module:{}", capability.module_id)));
            }
            if capability.simulation_consumed && !capability.snapshot_received {
                return Err(SimError::InvalidSnapshot(format!("consumed-without-snapshot:{}", capability.module_id)));
            }
            if capability.apply_handler_available && !capability.simulation_consumed {
                return Err(SimError::InvalidSnapshot(format!("apply-handler-without-consumer:{}", capability.module_id)));
            }
            let actual = actual_catalog_counts.get(capability.module_id.as_str()).copied().unwrap_or(0)
                + if capability.module_id == "wf_realms" { self.government_definitions.len() as u32 } else { 0 };
            if capability.catalog_count != actual {
                return Err(SimError::InvalidSnapshot(format!("capability-catalog-count-mismatch:{}:declared={}:actual={actual}", capability.module_id, capability.catalog_count)));
            }
        }
        let mut selected_realms = BTreeSet::new();
        for selection in &self.realm_governments {
            if !realms.contains(selection.realm_id.as_str()) || !selected_realms.insert(selection.realm_id.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-realm-government:{}", selection.realm_id)));
            }
            if !selection.government_id.is_empty()
                && !self.government_definitions.iter().any(|definition| definition.id == selection.government_id) {
                return Err(SimError::InvalidSnapshot(format!("unknown-government:{}:{}", selection.realm_id, selection.government_id)));
            }
            if !selection.level_id.is_empty()
                && self.catalog_entries.iter().any(|entry| entry.module_id == "wf_realms" && entry.catalog_id == "government_levels")
                && catalog_entry(self, "wf_realms", "government_levels", &selection.level_id).is_none() {
                return Err(SimError::InvalidSnapshot(format!("unknown-government-level:{}:{}", selection.realm_id, selection.level_id)));
            }
            for doctrine in &selection.doctrine_ids {
                let (branch, node) = doctrine.split_once(':').unwrap_or(("", doctrine.as_str()));
                let found = self.catalog_entries.iter().filter(|entry|
                    entry.module_id == "wf_realms" && entry.catalog_id == "doctrine_branches"
                        && (branch.is_empty() || entry.entry_id == branch))
                    .any(|entry| entry.payload.get("nodes").and_then(serde_json::Value::as_array)
                        .map(|nodes| nodes.iter().any(|value| value.get("key").or_else(|| value.get("id")).and_then(serde_json::Value::as_str) == Some(node)))
                        .unwrap_or(false));
                if !found { return Err(SimError::InvalidSnapshot(format!("unknown-doctrine:{}:{}", selection.realm_id, doctrine))); }
            }
            for (law_id, option_id) in &selection.laws {
                let found = catalog_entry(self, "wf_realms", "laws", law_id)
                    .and_then(|entry| entry.payload.get("options"))
                    .and_then(serde_json::Value::as_array)
                    .map(|options| options.iter().any(|option|
                        option.get("id").and_then(serde_json::Value::as_str) == Some(option_id.as_str())))
                    .unwrap_or(false);
                if !found { return Err(SimError::InvalidSnapshot(format!("unknown-law-option:{}:{}:{}", selection.realm_id, law_id, option_id))); }
            }
        }
        for relation in &self.diplomatic_relations {
            if relation.realm_a == relation.realm_b || !realms.contains(relation.realm_a.as_str())
                || !realms.contains(relation.realm_b.as_str()) {
                return Err(SimError::InvalidSnapshot(format!("invalid-diplomatic-relation:{}:{}", relation.realm_a, relation.realm_b)));
            }
        }
        let mut vassals = BTreeSet::new();
        for contract in &self.vassal_contracts {
            if contract.vassal_realm == contract.liege_realm || !realms.contains(contract.vassal_realm.as_str())
                || !realms.contains(contract.liege_realm.as_str()) || !vassals.insert(contract.vassal_realm.as_str())
                || !contract.tax_share.is_finite() || !contract.levy_share.is_finite()
                || !(0.0..=1.0).contains(&contract.tax_share) || !(0.0..=1.0).contains(&contract.levy_share)
                || !(0..=100).contains(&contract.loyalty) || !(0..=100).contains(&contract.autonomy) {
                return Err(SimError::InvalidSnapshot(format!("invalid-vassal-contract:{}", contract.vassal_realm)));
            }
        }
        let mut war_ids = BTreeSet::new();
        for war in &self.wars {
            if war.id.trim().is_empty() || !war_ids.insert(war.id.as_str())
                || (war.status == "ACTIVE" && (!realms.contains(war.attacker_realm.as_str())
                    || !realms.contains(war.defender_realm.as_str())))
                || war.attacker_realm == war.defender_realm {
                return Err(SimError::InvalidSnapshot(format!("invalid-war:{}", war.id)));
            }
        }
        let mut army_ids = BTreeSet::new();
        for army in &self.armies {
            if army.id.trim().is_empty() || !army_ids.insert(army.id.as_str())
                || !realms.contains(army.realm_id.as_str()) || !army.morale.is_finite()
                || !army.supply.is_finite() || !army.power.is_finite()
                || !(0.0..=1.0).contains(&army.morale) || !(0.0..=1.0).contains(&army.supply)
                || army.power < 0.0 || army.unit_counts.values().any(|count| *count == 0) {
                return Err(SimError::InvalidSnapshot(format!("invalid-army-formation:{}", army.id)));
            }
        }
        for relation in &self.social_relations {
            if relation.character_a == relation.character_b || !characters.contains(relation.character_a.as_str())
                || !characters.contains(relation.character_b.as_str()) || !(-100..=100).contains(&relation.relationship)
                || !(0..=100).contains(&relation.love) {
                return Err(SimError::InvalidSnapshot(format!("invalid-social-relation:{}:{}", relation.character_a, relation.character_b)));
            }
        }
        let mut named_ids = BTreeSet::new();
        for entity in &self.named_entities {
            if entity.id.trim().is_empty() || entity.kind.trim().is_empty()
                || !named_ids.insert((entity.kind.as_str(), entity.id.as_str())) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-named-entity:{}:{}", entity.kind, entity.id)));
            }
        }
        Ok(())
    }
}

pub fn decide(snapshot: &WorldSnapshot) -> Result<DecisionFrame, SimError> {
    snapshot.validate()?;
    let realms: BTreeMap<&str, &Realm> = snapshot.realms.iter().map(|r| (r.id.as_str(), r)).collect();
    let settlements: BTreeMap<&str, &Settlement> = snapshot.settlements.iter().map(|s| (s.id.as_str(), s)).collect();
    let characters: BTreeMap<&str, &Character> = snapshot.characters.iter().map(|character| (character.id.as_str(), character)).collect();
    let mut proposals = Vec::new();
    let mut diagnostics = snapshot.capabilities.iter().map(|capability| format!(
        "capability:{} available={} snapshot_received={} simulation_consumed={} apply_handler_available={} catalogs={} entities={}",
        capability.module_id, capability.available, capability.snapshot_received,
        capability.simulation_consumed, capability.apply_handler_available,
        capability.catalog_count, capability.entity_count)).collect::<Vec<_>>();
    let mut titles_by_realm: BTreeMap<&str, usize> = BTreeMap::new();
    let mut claims_by_realm: BTreeMap<&str, usize> = BTreeMap::new();
    let mut titles_by_map: BTreeMap<&str, usize> = BTreeMap::new();
    for title in &snapshot.titles {
        if !title.realm_id.starts_with("unclaimed:") {
            *titles_by_realm.entry(title.realm_id.as_str()).or_default() += 1;
            *claims_by_realm.entry(title.realm_id.as_str()).or_default() += title.claims.len();
        }
        *titles_by_map.entry(title.map_id.as_str()).or_default() += 1;
    }
    diagnostics.push(format!("government_catalog:definitions={}:realm_selections={}",
        snapshot.government_definitions.len(), snapshot.realm_governments.len()));
    let mut catalog_counts: BTreeMap<(&str, &str), usize> = BTreeMap::new();
    for entry in &snapshot.catalog_entries {
        *catalog_counts.entry((&entry.module_id, &entry.catalog_id)).or_default() += 1;
    }
    for ((module, catalog), count) in &catalog_counts {
        diagnostics.push(format!("catalog:{}:{}:entries={}", module, catalog, count));
    }
    diagnostics.push(format!("world_capabilities:{} diplomacy:{} vassals:{} wars:{} armies:{} social_relations:{} named_entities:{}",
        snapshot.capabilities.len(), snapshot.diplomatic_relations.len(), snapshot.vassal_contracts.len(),
        snapshot.wars.len(), snapshot.armies.len(), snapshot.social_relations.len(), snapshot.named_entities.len()));
    let mut actual_army_power: BTreeMap<&str, f64> = BTreeMap::new();
    for realm in &snapshot.realms {
        if let Some(power) = effective_army_power(snapshot, &realm.id) {
            actual_army_power.insert(realm.id.as_str(), power);
        }
    }
    for (map_id, count) in &titles_by_map {
        let tiers = snapshot.titles.iter().filter(|title| title.map_id == *map_id)
            .map(|title| title.tier_index).collect::<BTreeSet<_>>().len();
        diagnostics.push(format!("political_titles:{}:count={}:tiers={}", map_id, count, tiers));
    }
    // Geography is observational input only in this integration pass: it is never
    // converted into pre-seeded realms, rulers, settlements or population counts.
    for map in &snapshot.geography {
        let land_cells = map.cells.iter().filter(|cell| cell.land).count();
        let viable_cells = map.cells.iter().filter(|cell| cell.land && cell.suitability >= 55).count();
        let river_cells = map.cells.iter().filter(|cell| cell.river > 0).count();
        diagnostics.push(format!("geography:{}:land_samples={}:viable_samples={}:river_samples={}:political_layers_ignored={}",
            map.map_id, land_cells, viable_cells, river_cells, map.political_layers_present));
    }

    // Political AI: border wars emerge from pressure, claims, power balance,
    // stability and treasury. A war is never forced by the scenario.
    // Border direction is not semantically meaningful. Normalize endpoints before
    // evaluating either direction so input ordering cannot change proposal order.
    let mut borders = snapshot.borders.iter().map(|border| {
        if border.a <= border.b {
            border.clone()
        } else {
            Border {
                a: border.b.clone(), b: border.a.clone(),
                contested_claim: border.contested_claim,
                strategic_value: border.strategic_value, distance_cost: border.distance_cost,
            }
        }
    }).collect::<Vec<_>>();
    borders.sort_by(|x, y| (&x.a, &x.b).cmp(&(&y.a, &y.b)));
    // Recruitment only needs to know whether a realm touches any contested
    // border. Derive that membership once instead of rescanning every border
    // for every settlement in the recruitment pass.
    let contested_realms: BTreeSet<&str> = borders.iter()
        .filter(|border| border.contested_claim)
        .flat_map(|border| [border.a.as_str(), border.b.as_str()])
        .collect();
    for border in &borders {
        let a = realms[border.a.as_str()];
        let b = realms[border.b.as_str()];
        for (attacker, defender) in [(a, b), (b, a)] {
            let treasury = realm_treasury_balance(snapshot, &attacker.id).unwrap_or(attacker.treasury as f64);
            let intrigue_penalty = realm_intrigue_pressure(snapshot, &attacker.id);
            let court_penalty = court_instability_pressure(snapshot, &attacker.id);
            let trade_penalty = trade_conflict_penalty(snapshot, &attacker.id, &defender.id)
                + if has_treaty(snapshot, &attacker.id, &defender.id, "TRADE_PACT") { 12 } else { 0 }
                + market_fragility_penalty(snapshot, &attacker.id);
            if attacker.at_war || defender.at_war
                || realm_in_active_campaign(snapshot, &attacker.id) || realm_in_active_campaign(snapshot, &defender.id)
                || snapshot.day.saturating_sub(attacker.last_war_day) < 180
                || treasury < 100.0 || attacker.stability < 20
                || blocks_autonomous_war(snapshot, &attacker.id, &defender.id)
                || snapshot.wars.iter().any(|war| war.status == "ACTIVE"
                    && ((war.attacker_realm == attacker.id && war.defender_realm == defender.id)
                        || (war.attacker_realm == defender.id && war.defender_realm == attacker.id))) { continue; }
            let attacker_power = actual_army_power.get(attacker.id.as_str()).copied()
                .unwrap_or(attacker.army_strength as f64);
            let defender_power = actual_army_power.get(defender.id.as_str()).copied()
                .unwrap_or(defender.army_strength as f64);
            let power = if defender_power <= 0.0 { if attacker_power > 0.0 { 100 } else { 50 } }
                else { ((attacker_power * 100.0 / defender_power).round() as i64).clamp(0, 200) as i32 };
            let diplomatic_opinion = snapshot.diplomatic_relations.iter()
                .find(|relation| (relation.realm_a == attacker.id && relation.realm_b == defender.id)
                    || (relation.realm_a == defender.id && relation.realm_b == attacker.id))
                .map(|relation| relation.opinion).unwrap_or(0);
            let subject_penalty = snapshot.vassal_contracts.iter()
                .find(|contract| contract.vassal_realm == attacker.id)
                .map(|contract| (100 - contract.loyalty) / 4).unwrap_or(0);
            let government_modifier = ["war_score", "military", "warfare", "expansion"]
                .iter().map(|key| realm_policy_modifier(snapshot, &attacker.id, key))
                .sum::<f64>().round() as i64;
            let leader_modifier = realm_leader(snapshot, &attacker.id).map(|leader|
                origin_modifier(snapshot, leader, "war_score") + origin_modifier(snapshot, leader, "military"))
                .unwrap_or(0.0).round() as i64;
            let score = clamp_score(
                attacker.expansion_pressure as i64 / 2
                + if border.contested_claim { 28 } else { 0 }
                + border.strategic_value as i64 / 3
                + power as i64 / 4
                + attacker.prestige as i64 / 5
                + government_modifier
                + leader_modifier
                + diplomatic_opinion as i64 / 10
                - intrigue_penalty
                - subject_penalty as i64
                + (titles_by_realm.get(attacker.id.as_str()).copied().unwrap_or(0).min(40) / 4) as i64
                + (claims_by_realm.get(attacker.id.as_str()).copied().unwrap_or(0).min(20) / 2) as i64
                - defender.stability as i64 / 4
                - border.distance_cost as i64
                - 10
            );
            let roll = (stable_hash(snapshot.seed, "war", &attacker.id, &defender.id, snapshot.day) % 100) as i32;
            if score >= 45 && roll < (score - 35).clamp(0, 65) {
                let reason = format!("expansion pressure; border value {}; claim={}; actual army power ratio={}%; diplomacy opinion={}; government modifier={}",
                    border.strategic_value, border.contested_claim, power, diplomatic_opinion, government_modifier);
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
        let threatened = realms.get(s.realm_id.as_str())
            .map(|realm| contested_realms.contains(realm.id.as_str())).unwrap_or(false);
        if let Some((building, priority, reason)) = choose_building_for_need(snapshot, s, threatened) {
            proposals.push(Proposal {
                id: proposal_id(snapshot.seed, snapshot.day, "build", &s.id, &building),
                day: snapshot.day,
                kind: ProposalKind::Build { settlement: s.id.clone(), building, priority, reason },
            });
        } else {
            diagnostics.push(format!("settlement:{} no registered project fits current needs and restrictions", s.id));
        }
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
        let target = if w.food_output < w.food_need {
                choose_profession_for_output(snapshot, "food").map(|to| (to, w.food_need - w.food_output))
            } else if w.wood_output < w.wood_need {
                choose_profession_for_output(snapshot, "wood").map(|to| (to, w.wood_need - w.wood_output))
            } else if w.stone_output < w.stone_need {
                choose_profession_for_output(snapshot, "stone").map(|to| (to, w.stone_need - w.stone_output))
            } else if s.housing_used < s.housing && s.population > 10 && w.builders == 0 {
                choose_builder_profession(snapshot).map(|to| (to, 1))
            } else { None };
        if let Some((to, deficit)) = target {
            proposals.push(Proposal {
                id: proposal_id(snapshot.seed, snapshot.day, "profession", &w.settlement_id, &to),
                day: snapshot.day,
                kind: ProposalKind::ReassignProfession {
                    settlement: w.settlement_id.clone(), from: "unemployed".into(), to: to.clone(),
                    workers: if deficit > 0 { 1 } else { 0 },
                    reason: format!("registered profession {to} selected from building job definitions to cover production deficit"),
                },
            });
        }
    }

    // Recruitment AI: requires the module-defined recruitment building, active trainers,
    // actual manpower pool, registered unit type, treasury and food support.
    let mut recruitment_settlements = snapshot.settlements.iter().collect::<Vec<_>>();
    recruitment_settlements.sort_by(|a, b| a.id.cmp(&b.id));
    for s in recruitment_settlements {
        let Some(r) = realms.get(s.realm_id.as_str()) else { continue; };
        let threatened = contested_realms.contains(r.id.as_str());
        if threatened && s.food > (s.population as i64) {
            if let Some((unit_role, count)) = recruitment_choice(snapshot, &s.id, &r.id) {
                proposals.push(Proposal {
                    id: proposal_id(snapshot.seed, snapshot.day, "recruit", &r.id, &s.id),
                    day: snapshot.day,
                    kind: ProposalKind::Recruit {
                        realm: r.id.clone(), settlement: s.id.clone(), unit_role, count,
                        reason: "contested frontier; selected from registered levy/recruitment catalogs, staffed building, manpower and treasury".into(),
                    },
                });
            }
        }
    }

    // Marriage AI: candidates are ranked by cross-realm benefit, not hard-coded pairings.
    let mut eligible = snapshot.characters.iter().filter(|c| c.alive && !c.married && c.age >= 18 && c.age <= 70).collect::<Vec<_>>();
    eligible.sort_by(|a, b| a.id.cmp(&b.id));
    let mut used = BTreeSet::new();
    for a in &eligible {
        if used.contains(a.id.as_str()) { continue; }
        let mut best: Option<(&Character, i32, i32)> = None;
        for b in &eligible {
            if a.id == b.id || a.realm_id.is_empty() || b.realm_id.is_empty() || a.realm_id == b.realm_id || a.map_id != b.map_id || a.gender == b.gender || used.contains(b.id.as_str()) { continue; }
            let Some(social) = snapshot.social_relations.iter().find(|relation|
                (relation.character_a == a.id && relation.character_b == b.id)
                    || (relation.character_a == b.id && relation.character_b == a.id)) else { continue; };
            let kinship = kinship_distance(a, b, &characters);
            if kinship <= 2 || social.married || social.relationship < 35 || social.love < 20 { continue; }
            let social_bonus = social.relationship / 5 + social.love / 10;
            let kinship_penalty = if (3..=5).contains(&kinship) { 10 } else { 0 };
            let dynastic_policy = (realm_policy_modifier(snapshot, &a.realm_id, "marriage")
                + realm_policy_modifier(snapshot, &b.realm_id, "marriage")
                + realm_policy_modifier(snapshot, &a.realm_id, "diplomacy")
                + realm_policy_modifier(snapshot, &b.realm_id, "diplomacy")).round() as i64;
            let score = clamp_score(25_i64 + ((a.opinion + b.opinion) / 4) as i64
                + ((a.prestige + b.prestige) / 8) as i64
                + if a.house_id != b.house_id { 10 } else { 0 }
                + social_bonus as i64 + dynastic_policy + court_opinion_modifier(snapshot, &a.id, &b.id)
                + origin_modifier(snapshot, a, "marriage").round() as i64
                + origin_modifier(snapshot, b, "marriage").round() as i64
                - kinship_penalty as i64
                - (a.age as i32 - b.age as i32).abs() as i64);
            if score >= 35 && best.map(|(_, current, _)| score > current).unwrap_or(true) { best = Some((b, score, social_bonus)); }
        }
        if let Some((b, score, social_bonus)) = best {
            let roll = stable_hash(snapshot.seed, "marriage", &a.id, &b.id, snapshot.day) % 100;
            if roll < (score - 20).clamp(0, 70) as u64 {
                used.insert(a.id.as_str()); used.insert(b.id.as_str());
                proposals.push(Proposal {
                    id: proposal_id(snapshot.seed, snapshot.day, "marriage", &a.id, &b.id),
                    day: snapshot.day,
                    kind: ProposalKind::DynasticMarriage { first: a.id.clone(), second: b.id.clone(), score, reason: format!("cross-realm dynastic compatibility; social bond contribution={}", social_bonus) },
                });
            }
        }
    }

    proposals.sort_by(|a, b| a.id.cmp(&b.id));
    diagnostics.sort();
    let mut unique = BTreeSet::new();
    proposals.retain(|p| unique.insert(p.id.clone()));
    Ok(DecisionFrame { world_id: snapshot.world_id.clone(), day: snapshot.day, proposals, diagnostics })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn active_alliance_prevents_autonomous_war_proposal() {
        let mut snapshot = base();
        snapshot.diplomatic_relations.push(DiplomaticRelation {
            realm_a: "north".into(), realm_b: "south".into(), opinion: 80,
            treaties: vec!["ALLIANCE".into()], truce_until_day: 0,
        });
        let frame = decide(&snapshot).unwrap();
        assert!(!frame.proposals.iter().any(|proposal|
            matches!(proposal.kind, ProposalKind::DeclareWar { .. })));
    }

    #[test]
    fn settlement_type_and_building_catalogs_constrain_projects() {
        let mut snapshot = base();
        snapshot.catalog_entries.push(CatalogEntry {
            module_id: "wf_settle".into(), catalog_id: "settlement_instances".into(),
            entry_id: "village".into(), name: "Village".into(),
            payload: serde_json::json!({"type_id":"hamlet","building_counts":{},"completed_building_counts":{},"resource_stock":{}}),
        });
        snapshot.catalog_entries.push(CatalogEntry {
            module_id: "wf_settle".into(), catalog_id: "settlement_types".into(),
            entry_id: "hamlet".into(), name: "Hamlet".into(),
            payload: serde_json::json!({"allowBuildings":["market"],"allowCategories":["trade"]}),
        });
        snapshot.catalog_entries.push(CatalogEntry {
            module_id: "wf_settle".into(), catalog_id: "buildings".into(),
            entry_id: "farm".into(), name: "Farm".into(),
            payload: serde_json::json!({"category":"food","cost":{},"requires":[]}),
        });
        snapshot.catalog_entries.push(CatalogEntry {
            module_id: "wf_settle".into(), catalog_id: "buildings".into(),
            entry_id: "market".into(), name: "Market".into(),
            payload: serde_json::json!({"category":"trade","cost":{},"requires":[]}),
        });
        assert!(!settlement_allows_building(&snapshot, "village", "farm"));
        assert!(settlement_allows_building(&snapshot, "village", "market"));
    }

    #[test]
    fn recruitment_uses_registered_unit_mapping_and_real_staffed_building_capacity() {
        let mut snapshot = base();
        snapshot.catalog_entries.extend(vec![
            CatalogEntry { module_id:"wf_settle".into(), catalog_id:"settlement_instances".into(),
                entry_id:"village".into(), name:"Village".into(),
                payload:serde_json::json!({"trainer_counts":{"training_hall":2},
                    "completed_building_counts":{"training_hall":1}}) },
            CatalogEntry { module_id:"wf_army".into(), catalog_id:"realm_economy".into(),
                entry_id:"north".into(), name:"North".into(),
                payload:serde_json::json!({"manpower_pool":100,"gold":0}) },
            CatalogEntry { module_id:"wf_army".into(), catalog_id:"unit_types".into(),
                entry_id:"levy".into(), name:"Levy".into(),
                payload:serde_json::json!({"levy":true,"cost":0,"attack":1.0,"defense":1.0,"siege":0.0}) },
            CatalogEntry { module_id:"wf_troops".into(), catalog_id:"runtime_state".into(),
                entry_id:"military".into(), name:"Military".into(),
                payload:serde_json::json!({"army_type_map":{"levy":"spear"},
                    "recruitment_buildings":{"spear":"training_hall"},
                    "unit_definitions":{"spear":{"role":"melee"}}}) },
        ]);
        assert_eq!(recruitment_choice(&snapshot, "village", "north"), Some(("levy".into(), 20)));
    }

    #[test]
    fn physical_troop_condition_reduces_effective_strategic_army_power() {
        let mut snapshot = base();
        snapshot.armies.push(ArmyFormation {
            id:"north:1".into(), realm_id:"north".into(), name:"Army".into(),
            commander_id:"".into(), martial:0, men:100, morale:1.0, supply:0.5,
            location_title_id:"".into(), unit_counts:BTreeMap::new(), power:100.0,
        });
        snapshot.catalog_entries.push(CatalogEntry {
            module_id:"wf_troops".into(), catalog_id:"runtime_state".into(),
            entry_id:"military".into(), name:"Military".into(),
            payload:serde_json::json!({"live_troops":[{"realm_id":"north","health":50.0,"max_health":100.0,
                "morale":50,"damage_multiplier":0.8,"injury_combat_multiplier":0.8,
                "supply_combat_multiplier":0.5,"equipment":{"mainhand":{"damage":5,"max_damage":10}}}]}),
        });
        let power = effective_army_power(&snapshot, "north").unwrap();
        assert!((power - 5.0).abs() < 1e-9);
    }

    fn realm(id: &str, army: u64) -> Realm {
        Realm { id:id.into(), population:1000, treasury:1000, army_strength:army, stability:60,
            prestige:50, expansion_pressure:100, at_war:false, last_war_day:0 }
    }
    fn base() -> WorldSnapshot {
        WorldSnapshot { world_id:"test-world".into(), seed:42, day:365,
            realms:vec![realm("north",200),realm("south",100)],
            titles:vec![], government_definitions:vec![], realm_governments:vec![],
            borders:vec![Border{a:"north".into(),b:"south".into(),contested_claim:true,strategic_value:100,distance_cost:0}],
            settlements:vec![Settlement{id:"village".into(),realm_id:"north".into(),population:40,food:10,food_capacity:100,
                wood:30,stone:30,housing:100,housing_used:90,farms:0,foresters:0,quarries:0,workshops:0,barracks:1,watch_posts:0,threatened:false,roads:0,local_pressure:20,last_project_day:0}],
            characters:vec![],workforce:vec![],capabilities:vec![],catalog_entries:vec![],diplomatic_relations:vec![],vassal_contracts:vec![],wars:vec![],armies:vec![],social_relations:vec![],named_entities:vec![],geography:vec![] }
    }
    #[test] fn decisions_are_invariant_to_snapshot_collection_and_border_order() {
        let mut original = base();
        let mut south = original.settlements[0].clone();
        south.id = "south-town".into();
        south.realm_id = "south".into();
        south.last_project_day = 0;
        original.settlements.push(south);
        original.workforce = vec![
            Workforce{settlement_id:"village".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,
                unemployed:2,food_need:100,food_output:0,wood_need:10,wood_output:0,stone_need:0,stone_output:0},
            Workforce{settlement_id:"south-town".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,
                unemployed:1,food_need:0,food_output:0,wood_need:0,wood_output:0,stone_need:10,stone_output:0},
        ];
        original.characters = vec![
            Character{id:"person-a".into(),realm_id:"north".into(),age:22,alive:true,married:false,
                gender:0,fertility:50,prestige:50,opinion:50,house_id:"A".into(),..Default::default()},
            Character{id:"person-b".into(),realm_id:"south".into(),age:23,alive:true,married:false,
                gender:1,fertility:50,prestige:50,opinion:50,house_id:"B".into(),..Default::default()},
        ];

        let expected = decide(&original).unwrap();
        let mut permuted = original.clone();
        permuted.realms.reverse();
        permuted.borders.reverse();
        for border in &mut permuted.borders { std::mem::swap(&mut border.a, &mut border.b); }
        permuted.settlements.reverse();
        permuted.characters.reverse();
        permuted.workforce.reverse();

        assert_eq!(expected, decide(&permuted).unwrap());
    }

    #[test] fn long_lived_fantasy_characters_are_valid_but_corrupt_ages_are_rejected() {
        let mut s = base();
        s.characters.push(Character{id:"ancient-elf".into(),realm_id:"north".into(),age:306,alive:true,married:false,
            gender:0,fertility:10,prestige:500,opinion:50,house_id:"Eldergrove".into(),..Default::default()});
        assert!(s.validate().is_ok(), "multi-century lifespans are valid in MAQAN");
        s.characters[0].age = 1_001;
        assert!(matches!(s.validate(), Err(SimError::InvalidSnapshot(message)) if message.contains("implausible-character-age")));
    }

    #[test] fn decisions_are_deterministic_and_idempotently_identified() {
        let s=base(); assert_eq!(decide(&s).unwrap(),decide(&s).unwrap());
    }
    #[test] fn war_is_emergent_not_mandatory() {
        let mut s=base(); s.realms[0].stability=0;
        assert!(!decide(&s).unwrap().proposals.iter().any(|p| matches!(p.kind,ProposalKind::DeclareWar{..})));
    }
    fn add_test_catalog(snapshot: &mut WorldSnapshot, module: &str, catalog: &str, id: &str, payload: serde_json::Value) {
        snapshot.catalog_entries.push(CatalogEntry {
            module_id: module.into(), catalog_id: catalog.into(), entry_id: id.into(),
            name: id.into(), payload,
        });
        let count = snapshot.catalog_entries.iter().filter(|entry| entry.module_id == module).count() as u32
            + if module == "wf_realms" { snapshot.government_definitions.len() as u32 } else { 0 };
        if let Some(capability) = snapshot.capabilities.iter_mut().find(|item| item.module_id == module) {
            capability.catalog_count = count;
        } else {
            snapshot.capabilities.push(CapabilityDescriptor {
                module_id: module.into(), schema_version: 1, available: true,
                snapshot_received: true, simulation_consumed: true,
                apply_handler_available: true, catalog_count: count, entity_count: 0,
            });
        }
    }

    #[test] fn settlement_selects_registered_food_building_from_food_shortage() {
        let mut snapshot = base();
        add_test_catalog(&mut snapshot, "wf_settle", "buildings", "farm",
            serde_json::json!({"category":"food","out":{"food":5.0},"jobs":{"farmer":2.0},"work":10.0,"cost":{},"requires":[]}));
        let frame=decide(&snapshot).unwrap();
        assert!(frame.proposals.iter().any(|p| matches!(&p.kind,ProposalKind::Build{building,..} if building=="farm")));
    }
    #[test] fn workforce_uses_registered_profession_and_building_job_for_food_deficit() {
        let mut s=base();
        s.workforce.push(Workforce{settlement_id:"village".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,unemployed:3,food_need:100,food_output:0,wood_need:0,wood_output:0,stone_need:0,stone_output:0});
        add_test_catalog(&mut s, "wf_settle", "building_professions", "farmer",
            serde_json::json!({"id":"farmer","name":"Farmer"}));
        add_test_catalog(&mut s, "wf_people", "professions", "farmer",
            serde_json::json!({"id":"farmer","name":"Farmer","group":"agriculture"}));
        add_test_catalog(&mut s, "wf_settle", "buildings", "farm",
            serde_json::json!({"category":"food","out":{"food":5.0},"jobs":{"farmer":2.0},"work":10.0,"cost":{},"requires":[]}));
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(&p.kind,ProposalKind::ReassignProfession{to,..} if to=="farmer")));
    }
    #[test] fn recruitment_uses_contested_border_membership() {
        let mut s = base();
        s.settlements[0].barracks = 1;
        s.settlements[0].food = 100;
        s.realms[0].treasury = 500;
        add_test_catalog(&mut s, "wf_settle", "settlement_instances", "village",
            serde_json::json!({"trainer_counts":{"barracks":1},"completed_building_counts":{"barracks":1}}));
        add_test_catalog(&mut s, "wf_army", "realm_economy", "north",
            serde_json::json!({"manpower_pool":100.0,"gold":500.0}));
        add_test_catalog(&mut s, "wf_army", "unit_types", "levy",
            serde_json::json!({"levy":true,"cost":0.0,"attack":1.0,"defense":1.0,"siege":0.0}));
        let frame = decide(&s).unwrap();
        assert!(frame.proposals.iter().any(|p| matches!(&p.kind,
            ProposalKind::Recruit { realm, settlement, .. } if realm == "north" && settlement == "village")));

        s.borders[0].contested_claim = false;
        let frame = decide(&s).unwrap();
        assert!(!frame.proposals.iter().any(|p| matches!(p.kind, ProposalKind::Recruit { .. })),
            "a realm without contested borders must not recruit from this rule");
    }

    #[test] fn marriage_requires_two_distinct_realms() {
        let mut s=base(); s.seed=0; s.characters=vec![
            Character{id:"a".into(),realm_id:"north".into(),age:22,alive:true,married:false,gender:0,fertility:10,prestige:50,opinion:50,house_id:"A".into(),..Default::default()},
            Character{id:"b".into(),realm_id:"south".into(),age:23,alive:true,married:false,gender:1,fertility:10,prestige:50,opinion:50,house_id:"B".into(),..Default::default()}
        ];
        s.social_relations.push(SocialRelation {
            character_a: "a".into(), character_b: "b".into(), relationship: 75, love: 65,
            married: false, dating: true, employed: false, last_contact_day: s.day,
        });
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(p.kind,ProposalKind::DynasticMarriage{..})));
    }
    #[test]
    fn map_first_geography_is_valid_without_preseeded_polities_or_settlements() {
        let mut snapshot = WorldSnapshot {
            world_id: "map-first".into(), seed: 9, day: 0,
            realms: vec![], titles: vec![], government_definitions: vec![], realm_governments: vec![], borders: vec![], settlements: vec![], characters: vec![], workforce: vec![], capabilities: vec![], catalog_entries: vec![], diplomatic_relations: vec![], vassal_contracts: vec![], wars: vec![], armies: vec![], social_relations: vec![], named_entities: vec![],
            geography: vec![GeographicMap {
                map_id: "planetos".into(), width_px: 128, height_px: 96,
                blocks_per_pixel_milli: 16000, political_layers_present: true, sample_stride: 4,
                cells: vec![
                    GeographicCell { x: 4, z: 8, biome: "plains".into(), elevation: 70,
                        river: 0, land: true, suitability: 82, owner_realm_id: String::new(), discovered_year: 0 },
                    GeographicCell { x: 8, z: 8, biome: "forest".into(), elevation: 72,
                        river: 255, land: true, suitability: 90, owner_realm_id: String::new(), discovered_year: 0 },
                ],
            }],
        };
        assert_eq!(snapshot.validate(), Ok(()));
        let frame = decide(&snapshot).unwrap();
        assert!(frame.proposals.is_empty());
        assert!(frame.diagnostics.iter().any(|line| line.contains("geography:planetos:land_samples=2")));
        assert!(snapshot.realms.is_empty() && snapshot.settlements.is_empty(),
            "geography alone must not synthesize political or population entities");
        snapshot.geography[0].cells[0].suitability = 101;
        assert!(snapshot.validate().is_err());
    }

    #[test]
    fn complete_title_tree_and_government_catalogue_reach_native_decisions() {
        let mut snapshot = base();
        let mut titles = Vec::new();
        for tier in 0..7u32 {
            titles.push(PoliticalTitle {
                id: format!("title-{tier}"), map_id: "authored-world".into(),
                key: format!("tier-{tier}"), name: format!("Tier {tier}"),
                realm_id: "north".into(), tier_index: tier, tier_key: format!("tier-{tier}"),
                parent_title_id: if tier < 6 { format!("title-{}", tier + 1) } else { String::new() },
                liege_title_id: if tier < 6 { format!("title-{}", tier + 1) } else { String::new() },
                holder_character_id: format!("holder-{tier}"), claims: vec![],
                area_blocks: 100, neighbors: vec![],
            });
        }
        titles.push(PoliticalTitle {
            id: "neutral".into(), map_id: "authored-world".into(), key: "neutral".into(),
            name: "Neutral".into(), realm_id: "unclaimed:authored-world".into(),
            tier_index: 0, tier_key: "province".into(), parent_title_id: String::new(),
            liege_title_id: String::new(), holder_character_id: String::new(),
            claims: vec![], area_blocks: 1, neighbors: vec![],
        });
        snapshot.titles = titles;
        snapshot.government_definitions = vec![GovernmentDefinition {
            id: "merchant_republic".into(), name: "Merchant Republic".into(),
            category: "republic".into(), succession: "elective".into(), min_level: 0,
            req_races: vec![], forbid_races: vec![], req_flags: vec![],
            req_terrain: BTreeMap::new(), aff_races: BTreeMap::new(),
            aff_terrain: BTreeMap::new(), mods: BTreeMap::new(),
            doctrine_slots: 3, law_slots: 4,
        }];
        add_test_catalog(&mut snapshot, "wf_realms", "doctrine_branches", "trade",
            serde_json::json!({"nodes":[{"id":"charters","mods":{"trade_pct":10.0}}]}));
        add_test_catalog(&mut snapshot, "wf_realms", "government_levels", "city_league",
            serde_json::json!({"mods":{}}));
        add_test_catalog(&mut snapshot, "wf_realms", "laws", "succession",
            serde_json::json!({"options":[{"id":"elective","mods":{}}]}));
        for (catalog, id, payload) in [
            ("doctrine_branches", "trade", serde_json::json!({"nodes":[{"id":"charters","mods":{"trade_pct":10.0}}]})),
            ("government_levels", "city_league", serde_json::json!({"mods":{}})),
            ("laws", "succession", serde_json::json!({"options":[{"id":"elective","mods":{}}]})),
        ] {
            snapshot.catalog_entries.push(CatalogEntry {
                module_id: "wf_realms".into(), catalog_id: catalog.into(),
                entry_id: id.into(), name: id.into(), payload,
            });
        }
        snapshot.capabilities.push(CapabilityDescriptor {
            module_id: "wf_realms".into(), schema_version: 1, available: true,
            snapshot_received: true, simulation_consumed: true, apply_handler_available: true,
            catalog_count: snapshot.catalog_entries.iter().filter(|entry| entry.module_id == "wf_realms").count() as u32
                + snapshot.government_definitions.len() as u32,
            entity_count: 0,
        });
        snapshot.realm_governments = vec![RealmGovernment {
            realm_id: "north".into(), government_id: "merchant_republic".into(),
            level_id: "city_league".into(), doctrine_ids: vec!["trade:charters".into()],
            laws: BTreeMap::from([("succession".into(), "elective".into())]),
        }];
        assert_eq!(snapshot.validate(), Ok(()));
        let frame = decide(&snapshot).unwrap();
        assert!(frame.diagnostics.iter().any(|line|
            line.contains("political_titles:authored-world:count=8:tiers=7")));
        assert!(frame.diagnostics.iter().any(|line|
            line.contains("government_catalog:definitions=1:realm_selections=1")));
    }

    #[test] fn duplicate_realms_are_rejected() {
        let mut s=base(); s.realms.push(s.realms[0].clone());
        assert_eq!(s.validate(),Err(SimError::DuplicateRealm("north".into())));
    }
    #[test] fn duplicate_settlement_ids_are_rejected() {
        let mut s=base(); s.settlements.push(s.settlements[0].clone());
        assert_eq!(s.validate(),Err(SimError::DuplicateSettlement("village".into())));
    }
    #[test] fn duplicate_global_character_ids_are_rejected() {
        let mut s=base();
        let person=Character{id:"global-person-1".into(),realm_id:"north".into(),age:24,alive:true,married:false,
            gender:0,fertility:50,prestige:0,opinion:0,house_id:"North".into(),..Default::default()};
        s.characters=vec![person.clone(),person];
        assert_eq!(s.validate(),Err(SimError::DuplicateCharacter("global-person-1".into())));
    }
    #[test] fn character_cannot_reference_an_unknown_realm() {
        let mut s=base();
        s.characters.push(Character{id:"global-person-2".into(),realm_id:"missing".into(),age:24,alive:true,married:false,
            gender:0,fertility:50,prestige:0,opinion:0,house_id:"North".into(),..Default::default()});
        assert_eq!(s.validate(),Err(SimError::UnknownRealm("missing".into())));
    }
    #[test] fn workforce_requires_one_known_settlement_partition() {
        let mut s=base();
        s.workforce.push(Workforce{settlement_id:"missing".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,
            unemployed:0,food_need:0,food_output:0,wood_need:0,wood_output:0,stone_need:0,stone_output:0});
        assert!(matches!(s.validate(),Err(SimError::InvalidSnapshot(reason)) if reason.starts_with("workforce-without-settlement")));
    }
}
