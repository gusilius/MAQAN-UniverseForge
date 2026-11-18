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

fn has_treaty(snapshot: &WorldSnapshot, a: &str, b: &str, treaty: &str) -> bool {
    snapshot.diplomatic_relations.iter().any(|relation|
        ((relation.realm_a == a && relation.realm_b == b)
            || (relation.realm_a == b && relation.realm_b == a))
            && relation.treaties.iter().any(|value| value == treaty))
}

fn settlement_allows_building(snapshot: &WorldSnapshot, settlement_id: &str, building: &str) -> bool {
    let type_id = snapshot.catalog_entries.iter()
        .find(|entry| entry.module_id == "wf_settlements"
            && entry.catalog_id == "settlement_instances" && entry.entry_id == settlement_id)
        .and_then(|entry| entry.payload.get("type_id"))
        .and_then(serde_json::Value::as_str).unwrap_or("");
    if type_id.is_empty() { return true; }
    let definition = snapshot.catalog_entries.iter()
        .find(|entry| entry.module_id == "wf_settlements"
            && entry.catalog_id == "settlement_types" && entry.entry_id == type_id);
    let Some(payload) = definition.map(|entry| &entry.payload) else { return true; };
    let banned = payload.get("ban_buildings").or_else(|| payload.get("ban"))
        .and_then(serde_json::Value::as_array);
    if banned.map(|values| values.iter().any(|value| value.as_str() == Some(building))).unwrap_or(false) {
        return false;
    }
    let allowed = payload.get("allow_buildings").or_else(|| payload.get("allow"))
        .and_then(serde_json::Value::as_array);
    allowed.map(|values| values.is_empty() || values.iter().any(|value| value.as_str() == Some(building)))
        .unwrap_or(true)
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
            if !realms.contains(c.realm_id.as_str()) { return Err(SimError::UnknownRealm(c.realm_id.clone())); }
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
        for entry in &self.catalog_entries {
            if entry.module_id.trim().is_empty() || entry.catalog_id.trim().is_empty() || entry.entry_id.trim().is_empty()
                || !catalog_keys.insert((entry.module_id.as_str(), entry.catalog_id.as_str(), entry.entry_id.as_str())) {
                return Err(SimError::InvalidSnapshot(format!("invalid-or-duplicate-catalog-entry:{}:{}:{}", entry.module_id, entry.catalog_id, entry.entry_id)));
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
                || !realms.contains(war.attacker_realm.as_str()) || !realms.contains(war.defender_realm.as_str())
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
            if entity.id.trim().is_empty() || entity.kind.trim().is_empty() || entity.name.trim().is_empty()
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
    let mut proposals = Vec::new();
    let mut diagnostics = Vec::new();
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
    for army in &snapshot.armies {
        *actual_army_power.entry(army.realm_id.as_str()).or_default() += army.power;
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
            if attacker.at_war || defender.at_war || snapshot.day.saturating_sub(attacker.last_war_day) < 180
                || attacker.treasury < 100 || attacker.stability < 20
                || has_treaty(snapshot, &attacker.id, &defender.id, "ALLIANCE")
                || has_treaty(snapshot, &attacker.id, &defender.id, "DEFENSIVE_PACT")
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
            let government_modifier = snapshot.realm_governments.iter()
                .find(|selection| selection.realm_id == attacker.id)
                .and_then(|selection| snapshot.government_definitions.iter()
                    .find(|definition| definition.id == selection.government_id))
                .map(|definition| definition.mods.get("war_score").or_else(|| definition.mods.get("military"))
                    .copied().unwrap_or(0.0).round() as i64).unwrap_or(0);
            let score = clamp_score(
                attacker.expansion_pressure as i64 / 2
                + if border.contested_claim { 28 } else { 0 }
                + border.strategic_value as i64 / 3
                + power as i64 / 4
                + attacker.prestige as i64 / 5
                + government_modifier
                + diplomatic_opinion as i64 / 10
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
        let food_ratio = if s.food_capacity == 0 { 0 } else { s.food.saturating_mul(100) / s.food_capacity };
        let desired_farms = (s.population / 50).max(1) as u32;
        let desired_foresters = (s.population / 80).max(1) as u32;
        let desired_quarries = (s.population / 100).max(1) as u32;
        let (building, priority, reason) = if s.threatened && s.watch_posts == 0 {
            ("watch_post", 88, "frontier threat requires a completed watch post")
        } else if s.threatened && s.watch_posts > 0 && s.barracks == 0 {
            ("barracks", 84, "frontier threat requires a trained garrison")
        } else if s.food < (s.population as i64 * 2) && s.farms < desired_farms {
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
        if !settlement_allows_building(snapshot, &s.id, building) {
            diagnostics.push(format!("settlement:{} catalog forbids or does not allow building:{}", s.id, building));
            continue;
        }
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
    let mut recruitment_settlements = snapshot.settlements.iter().collect::<Vec<_>>();
    recruitment_settlements.sort_by(|a, b| a.id.cmp(&b.id));
    for s in recruitment_settlements {
        let Some(r) = realms.get(s.realm_id.as_str()) else { continue; };
        let threatened = contested_realms.contains(r.id.as_str());
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
            let social = snapshot.social_relations.iter().find(|relation|
                (relation.character_a == a.id && relation.character_b == b.id)
                    || (relation.character_a == b.id && relation.character_b == a.id));
            let social_bonus = social.map(|relation| relation.relationship / 5 + relation.love / 10).unwrap_or(0);
            let kinship_penalty = if a.father_id == b.id || a.mother_id == b.id || b.father_id == a.id || b.mother_id == a.id
                || a.child_ids.contains(&b.id) || b.child_ids.contains(&a.id) { 100 } else { 0 };
            let score = clamp_score(25_i64 + ((a.opinion + b.opinion) / 4) as i64
                + ((a.prestige + b.prestige) / 8) as i64
                + if a.house_id != b.house_id { 10 } else { 0 }
                + social_bonus as i64
                - kinship_penalty as i64
                - (a.age as i32 - b.age as i32).abs() as i64);
            if score >= 35 && best.map(|(_, current)| score > current).unwrap_or(true) { best = Some((b, score)); }
        }
        if let Some((b, score)) = best {
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
            characters:vec![],workforce:vec![],geography:vec![] }
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
    #[test] fn settlement_selects_farm_from_food_shortage() {
        let frame=decide(&base()).unwrap();
        assert!(frame.proposals.iter().any(|p| matches!(&p.kind,ProposalKind::Build{building,..} if building=="farm")));
    }
    #[test] fn workforce_moves_unemployed_into_food_deficit() {
        let mut s=base(); s.workforce.push(Workforce{settlement_id:"village".into(),farmers:0,lumberjacks:0,miners:0,builders:0,soldiers:0,unemployed:3,food_need:100,food_output:0,wood_need:0,wood_output:0,stone_need:0,stone_output:0});
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(&p.kind,ProposalKind::ReassignProfession{to,..} if to=="farmer")));
    }
    #[test] fn recruitment_uses_contested_border_membership() {
        let mut s = base();
        s.settlements[0].barracks = 1;
        s.settlements[0].food = 100;
        s.realms[0].treasury = 500;
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
        assert!(decide(&s).unwrap().proposals.iter().any(|p| matches!(p.kind,ProposalKind::DynasticMarriage{..})));
    }
    #[test]
    fn map_first_geography_is_valid_without_preseeded_polities_or_settlements() {
        let mut snapshot = WorldSnapshot {
            world_id: "map-first".into(), seed: 9, day: 0,
            realms: vec![], titles: vec![], government_definitions: vec![], realm_governments: vec![], borders: vec![], settlements: vec![], characters: vec![], workforce: vec![],
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
