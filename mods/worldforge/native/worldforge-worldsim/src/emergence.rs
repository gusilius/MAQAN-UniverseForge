//! Map-first autonomous emergence layer.
//! No political or population entities are read from the map package. The only
//! initial conditions are geography, world seed and elapsed simulation time.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashMap};
use crate::{stable_hash, GeographicCell, GeographicMap, WorldSnapshot};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergentBand {
    pub id: String,
    pub map_id: String,
    pub x: u32,
    pub z: u32,
    pub population: u64,
    pub food_reserve: i64,
    pub settled: bool,
    pub founded_year: u64,
    pub hardship_years: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergentPolity {
    pub id: String,
    pub map_id: String,
    pub name: String,
    pub settlement_ids: Vec<String>,
    pub population: u64,
    pub founded_year: u64,
    pub stability: i32,
    pub dynasty_id: String,
    pub dynasty_generations: u32,
    /// Canonical wf-people global UUID of the current ruler. Generation count advances
    /// only when Java confirms a different living Character has actually succeeded.
    #[serde(default)]
    pub ruler_global_id: Option<String>,
    #[serde(default)]
    pub last_succession_year: u64,
    pub years_in_generation: u32,
    pub succession_pressure: i32,
    pub weak_years: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergentWorld {
    pub schema: u32,
    pub seed: u64,
    pub last_year: u64,
    pub bands: Vec<EmergentBand>,
    pub polities: Vec<EmergentPolity>,
    pub events_emitted: u64,
}

impl EmergentWorld {
    pub fn new(seed: u64) -> Self {
        Self { schema: 1, seed, last_year: 0, bands: Vec::new(), polities: Vec::new(), events_emitted: 0 }
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.schema != 1 { return Err("unsupported-emergence-schema".into()); }
        let mut ids = BTreeSet::new();
        for band in &self.bands {
            if band.id.is_empty() || band.map_id.is_empty() || band.population == 0
                || !ids.insert(band.id.as_str()) {
                return Err(format!("invalid-emergent-band:{}", band.id));
            }
        }
        let mut polity_ids = BTreeSet::new();
        for polity in &self.polities {
            if polity.id.is_empty() || polity.dynasty_id.is_empty()
                || !polity_ids.insert(polity.id.as_str())
                || polity.dynasty_generations == 0 {
                return Err(format!("invalid-emergent-polity:{}", polity.id));
            }
            if polity.settlement_ids.iter().any(|id| !ids.contains(id.as_str())) {
                return Err(format!("polity-references-unknown-settlement:{}", polity.id));
            }
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergenceEvent {
    pub year: u64,
    pub kind: String,
    pub subject_id: String,
    pub text: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct EmergenceFrame {
    pub state: EmergentWorld,
    pub events: Vec<EmergenceEvent>,
    pub diagnostics: Vec<String>,
}

#[derive(Deserialize)]
pub struct EmergenceRequest {
    pub snapshot: WorldSnapshot,
    pub state: Option<EmergentWorld>,
}

fn roll(seed: u64, domain: &str, a: &str, b: &str, year: u64) -> u64 {
    stable_hash(seed, domain, a, b, year)
}

/// Per-call coordinate index over the immutable snapshot. Stores references only:
/// no copied cell payload, persistent cache, or second source of geographic truth.
type CellIndex<'a> = HashMap<(u32, u32), &'a GeographicCell>;

fn index_cells(map: &GeographicMap) -> CellIndex<'_> {
    map.cells.iter().map(|cell| ((cell.x, cell.z), cell)).collect()
}

fn cell_at<'map>(cells: &CellIndex<'map>, x: u32, z: u32) -> Option<&'map GeographicCell> {
    cells.get(&(x, z)).copied()
}

fn distance_sq(a: (u32,u32), b: (u32,u32)) -> u64 {
    let dx = i64::from(a.0) - i64::from(b.0);
    let dz = i64::from(a.1) - i64::from(b.1);
    (dx * dx + dz * dz) as u64
}

fn map_scale(map: &GeographicMap) -> u64 {
    (u64::from(map.width_px.min(map.height_px)) / 12).max(u64::from(map.sample_stride) * 3).max(1)
}

fn bootstrap(map: &GeographicMap, seed: u64, bands: &mut Vec<EmergentBand>, events: &mut Vec<EmergenceEvent>) {
    if map.cells.is_empty() { return; }
    let spacing = map_scale(map);
    let mut candidates = map.cells.iter()
        .filter(|c| c.land && c.suitability >= 48 && c.owner_realm_id.trim().is_empty())
        .collect::<Vec<_>>();
    candidates.sort_by(|a,b| {
        b.suitability.cmp(&a.suitability)
            .then_with(|| a.z.cmp(&b.z)).then_with(|| a.x.cmp(&b.x))
    });
    let mut occupied = Vec::<(u32,u32)>::new();
    for cell in candidates {
        // Site probability is an environmental score, not a requested settlement count.
        let chance = u64::from(cell.suitability.saturating_sub(42)) * 55;
        let key = format!("{}:{}:{}", map.map_id, cell.x, cell.z);
        if roll(seed, "founder-band", &key, &cell.biome, 0) % 10_000 >= chance { continue; }
        if occupied.iter().any(|p| distance_sq(*p, (cell.x,cell.z)) < spacing * spacing) { continue; }
        let id = format!("band_{:016x}", roll(seed, "band-id", &key, "", 0));
        let population = 2 + roll(seed, "founder-size", &key, "", 0) % 5;
        bands.push(EmergentBand { id: id.clone(), map_id: map.map_id.clone(), x: cell.x, z: cell.z,
            population, food_reserve: i64::from(cell.suitability) / 8, settled: false,
            founded_year: 0, hardship_years: 0 });
        occupied.push((cell.x,cell.z));
        events.push(EmergenceEvent { year: 0, kind: "population_origin".into(), subject_id: id,
            text: format!("A small kin group emerges near {} terrain", cell.biome) });
    }
}

fn advance_band(band: &mut EmergentBand, cell: &GeographicCell, seed: u64, year: u64,
    events: &mut Vec<EmergenceEvent>) {
    let id = band.id.clone();
    let food_yield = i64::from(cell.suitability) / 18 + if cell.river > 0 { 1 } else { 0 };
    let demand = ((band.population + 3) / 4) as i64;
    band.food_reserve = (band.food_reserve + food_yield - demand).clamp(-40, 160);
    let birth_roll = roll(seed, "birth", &id, "", year) % 10_000;
    let birth_chance = (900 + u64::from(cell.suitability) * 18).min(2_600);
    if band.population >= 2 && band.food_reserve > 0 && birth_roll < birth_chance {
        band.population = band.population.saturating_add(1);
    }
    let mortality_roll = roll(seed, "mortality", &id, "", year) % 10_000;
    let mortality_chance = if band.food_reserve < -12 { 1_100 } else if band.food_reserve < 0 { 380 } else { 90 };
    if band.population > 1 && mortality_roll < mortality_chance {
        band.population -= 1;
    }
    if band.food_reserve < -8 {
        band.hardship_years = band.hardship_years.saturating_add(1);
    } else {
        band.hardship_years = band.hardship_years.saturating_sub(1);
    }
    let settlement_threshold = 16 + u64::from(100u8.saturating_sub(cell.suitability)) / 7;
    if !band.settled && band.population >= settlement_threshold {
        band.settled = true;
        band.founded_year = year;
        events.push(EmergenceEvent { year, kind: "settlement_founded".into(), subject_id: id.clone(),
            text: format!("A mobile kin group becomes a permanent settlement (population {})", band.population) });
    }
    if band.hardship_years > 4 && band.population > 4 {
        // Dispersal is represented by gradual household loss, not teleportation or a scripted migration.
        if roll(seed, "dispersal", &id, "", year) % 100 < 18 { band.population -= 1; }
    }
}

fn maybe_form_polity(map: &GeographicMap, cells: &CellIndex<'_>, seed: u64, year: u64, bands: &[EmergentBand],
    polities: &mut Vec<EmergentPolity>, events: &mut Vec<EmergenceEvent>) {
    let spacing = map_scale(map) * 3;
    // Legacy/persisted bands are rechecked against the live political raster before
    // they can become sovereign. A settlement on another realm's land is not a
    // legal seed for a new independent polity.
    let mut settled = bands.iter().filter(|b| {
        b.map_id == map.map_id && b.settled
            && cell_at(cells, b.x, b.z).is_some_and(|cell|
                cell.land && cell.owner_realm_id.trim().is_empty())
    }).collect::<Vec<_>>();
    settled.sort_by(|a,b| a.id.cmp(&b.id));
    for anchor in &settled {
        if polities.iter().any(|p| p.settlement_ids.contains(&anchor.id)) { continue; }
        let mut cluster = settled.iter().filter(|b| distance_sq((anchor.x,anchor.z),(b.x,b.z)) <= spacing*spacing)
            .map(|b| (*b).clone()).collect::<Vec<_>>();
        cluster.sort_by(|a,b| a.id.cmp(&b.id));
        cluster.dedup_by(|a,b| a.id == b.id);
        let population = cluster.iter().map(|b| b.population).sum::<u64>();
        // A village or a tiny cluster is not automatically a sovereign state.
        // The first polity requires a durable regional base: at least four settled
        // communities, a meaningful combined population, no single starving member,
        // and a cluster whose aggregate food position is not in sustained collapse.
        // These are founding gates, not permanent size caps: later growth is handled
        // by the canonical realm/government systems.
        let food_position = cluster.iter().map(|b| b.food_reserve).sum::<i64>();
        let viable_communities = cluster.iter().filter(|b| b.population >= 20 && b.hardship_years <= 4).count();
        if cluster.len() < 4 || population < 120 || viable_communities < 4 || food_position < -12 {
            continue;
        }
        let ids = cluster.iter().map(|b| b.id.clone()).collect::<Vec<_>>();
        let id = format!("polity_{:016x}", roll(seed, "polity-id", &map.map_id, &ids.join("|"), year));
        if polities.iter().any(|p| p.id == id || p.settlement_ids.iter().any(|s| ids.contains(s))) { continue; }
        let dynasty_id = format!("house_{:016x}", roll(seed, "founding-house", &id, "", year));
        let name = format!("{} Confederation", anchor.id.chars().take(8).collect::<String>());
        polities.push(EmergentPolity { id: id.clone(), map_id: map.map_id.clone(), name: name.clone(),
            settlement_ids: ids, population, founded_year: year, stability: 55, dynasty_id,
            dynasty_generations: 1, ruler_global_id: None, last_succession_year: year,
            years_in_generation: 0, succession_pressure: 0, weak_years: 0 });
        events.push(EmergenceEvent { year, kind: "state_founded".into(), subject_id: id,
            text: format!("{} forms through the association of neighbouring settlements (population {})", name, population) });
        break;
    }
}

fn update_polities(seed: u64, year: u64, bands: &[EmergentBand],
    cells_by_map: &HashMap<&str, CellIndex<'_>>,
    polities: &mut Vec<EmergentPolity>, events: &mut Vec<EmergenceEvent>) {
    // This index is scoped to this yearly reduction and points into the canonical bands.
    let bands_by_id: HashMap<&str, &EmergentBand> = bands.iter().map(|band| (band.id.as_str(), band)).collect();
    for polity in polities.iter_mut() {
        // If a province is transferred to an established realm, its settlement
        // leaves the emergent polity's sovereign core. Existing physical colonies
        // remain and are reconciled by the canonical map-ownership listener.
        if let Some(cells) = cells_by_map.get(polity.map_id.as_str()) {
            let own_realm_id = format!("emergent-{}", polity.id);
            let absorbed = polity.settlement_ids.iter().filter_map(|settlement_id| {
                let band = bands_by_id.get(settlement_id.as_str()).copied()?;
                let cell = cell_at(cells, band.x, band.z)?;
                let owner = cell.owner_realm_id.trim();
                if !owner.is_empty() && owner != own_realm_id.as_str() {
                    Some((settlement_id.clone(), owner.to_owned()))
                } else { None }
            }).collect::<Vec<_>>();
            for (settlement_id, owner) in absorbed {
                polity.settlement_ids.retain(|id| id != &settlement_id);
                events.push(EmergenceEvent {
                    year, kind: "settlement_absorbed".into(), subject_id: settlement_id.clone(),
                    text: format!("Settlement {} is brought under the authority of {} after a territorial change",
                        settlement_id, owner),
                });
            }
        }
        // Resolve only this polity's canonical settlement IDs. Scanning every band
        // and linearly searching settlement_ids made this O(all_bands × polity_members)
        // for every polity on every simulated year.
        let population = polity.settlement_ids.iter()
            .filter_map(|id| bands_by_id.get(id.as_str()).copied())
            .filter(|band| band.settled)
            .map(|band| band.population)
            .sum::<u64>();
        polity.population = population;
        if population < 45 { polity.weak_years = polity.weak_years.saturating_add(1); }
        else { polity.weak_years = polity.weak_years.saturating_sub(1); }
        polity.stability = (polity.stability + if population > 120 { 1 } else if population < 60 { -3 } else { 0 }).clamp(0,100);
        if population > 0 && polity.stability >= 15 {
            // This is elapsed time since statehood/succession, not a generation counter.
            // Only a new canonical wf-people ruler may advance dynasty_generations.
            polity.years_in_generation = polity.years_in_generation.saturating_add(1);
            polity.succession_pressure = (polity.succession_pressure + 1).min(100);
        } else if population > 0 {
            polity.succession_pressure = (polity.succession_pressure + 4).min(100);
        }
        if roll(seed, "succession-resolution", &polity.id, "", year) % 100 < 12 {
            polity.succession_pressure = polity.succession_pressure.saturating_sub(18);
        }
    }
    let dissolved = polities.iter().filter(|p| p.weak_years >= 12 || p.population == 0)
        .map(|p| p.id.clone()).collect::<Vec<_>>();
    for id in &dissolved {
        if let Some(p) = polities.iter().find(|p| &p.id == id) {
            events.push(EmergenceEvent { year, kind: "state_dissolved".into(), subject_id: id.clone(),
                text: format!("{} dissolves after prolonged demographic and institutional decline", p.name) });
        }
    }
    polities.retain(|p| !dissolved.contains(&p.id));
}


/// Records a real succession from the canonical Character registry.
/// Repeated reconciliation of the same ruler is idempotent and never inflates generations.
pub fn record_ruler_succession(
    state: &mut EmergentWorld,
    polity_id: &str,
    ruler_global_id: &str,
    year: u64,
) -> Result<Option<EmergenceEvent>, String> {
    if ruler_global_id.trim().is_empty() {
        return Err("empty-canonical-ruler-id".into());
    }
    let polity = state.polities.iter_mut().find(|polity| polity.id == polity_id)
        .ok_or_else(|| format!("unknown-polity:{polity_id}"))?;
    if polity.ruler_global_id.as_deref() == Some(ruler_global_id) {
        return Ok(None);
    }
    let first = polity.ruler_global_id.is_none();
    polity.ruler_global_id = Some(ruler_global_id.to_owned());
    polity.dynasty_generations = if first { 1 } else { polity.dynasty_generations.saturating_add(1) };
    polity.last_succession_year = year;
    polity.years_in_generation = 0;
    let kind = if first { "founding_ruler" } else { "dynastic_succession" };
    let text = if first {
        format!("{} receives its first canonical ruler", polity.name)
    } else {
        format!("{} enters canonical dynasty generation {}", polity.name, polity.dynasty_generations)
    };
    Ok(Some(EmergenceEvent {
        year, kind: kind.into(), subject_id: polity.id.clone(), text,
    }))
}

pub fn advance(request: EmergenceRequest) -> Result<EmergenceFrame, String> {
    request.snapshot.validate().map_err(|e| format!("snapshot:{e:?}"))?;
    let mut state = request.state.unwrap_or_else(|| EmergentWorld::new(request.snapshot.seed));
    if state.seed != request.snapshot.seed { return Err("emergence-world-seed-mismatch".into()); }
    state.validate()?;
    let target_year = request.snapshot.day / 365;
    let mut events = Vec::new();
    let mut diagnostics = Vec::new();
    let maps = &request.snapshot.geography;
    // Build lookup indexes once per compute request. The snapshot remains the only
    // owner of geography; these maps contain borrowed references and are discarded
    // when this deterministic compute call returns.
    let maps_by_id: HashMap<&str, &GeographicMap> =
        maps.iter().map(|map| (map.map_id.as_str(), map)).collect();
    let cells_by_map: HashMap<&str, CellIndex<'_>> =
        maps.iter().map(|map| (map.map_id.as_str(), index_cells(map))).collect();

    // A map may be loaded after the world has already developed. Bootstrap each map
    // independently instead of treating the first non-empty map as the whole universe.
    for map in maps {
        if !state.bands.iter().any(|band| band.map_id == map.map_id) {
            bootstrap(map, state.seed, &mut state.bands, &mut events);
        }
    }
    state.bands.sort_by(|a,b| a.id.cmp(&b.id));
    let mut years_advanced = 0u64;
    while state.last_year < target_year {
        let year = state.last_year + 1;
        for band in &mut state.bands {
            if let (Some(_map), Some(cells)) = (maps_by_id.get(band.map_id.as_str()), cells_by_map.get(band.map_id.as_str())) {
                if let Some(cell) = cell_at(cells, band.x, band.z) {
                    advance_band(band, cell, state.seed, year, &mut events);
                }
            }
        }
        for map in maps {
            if let Some(cells) = cells_by_map.get(map.map_id.as_str()) {
                maybe_form_polity(map, cells, state.seed, year, &state.bands, &mut state.polities, &mut events);
            }
        }
        update_polities(state.seed, year, &state.bands, &maps_by_id, &cells_by_map,
            &mut state.polities, &mut events);
        state.last_year = year;
        years_advanced += 1;
        if years_advanced > 20_000 { return Err("emergence-catch-up-exceeds-20000-years".into()); }
    }
    events.sort_by(|a,b| (a.year,&a.kind,&a.subject_id).cmp(&(b.year,&b.kind,&b.subject_id)));
    state.events_emitted = state.events_emitted.saturating_add(events.len() as u64);
    diagnostics.push(format!("emergence:year={}:bands={}:settlements={}:states={}:years_advanced={}",
        state.last_year, state.bands.len(), state.bands.iter().filter(|b| b.settled).count(),
        state.polities.len(), years_advanced));
    for p in &state.polities {
        diagnostics.push(format!("dynasty:{}:generations={}:years_since_foundation={}",
            p.id, p.dynasty_generations, state.last_year.saturating_sub(p.founded_year)));
    }
    state.validate()?;
    Ok(EmergenceFrame { state, events, diagnostics })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{GeographicCell, GeographicMap, WorldSnapshot};

    fn map() -> GeographicMap {
        let mut cells = Vec::new();
        for z in 0..128 {
            for x in 0..128 {
                cells.push(GeographicCell { x, z, biome: if (x / 16 + z / 16) % 2 == 0 { "plains" } else { "forest" }.into(),
                    elevation: 70, river: if x % 19 == 0 { 255 } else { 0 }, land: true, suitability: 92,
                    owner_realm_id: String::new() });
            }
        }
        GeographicMap { map_id:"planetos".into(), width_px:128, height_px:128,
            blocks_per_pixel_milli:16000, political_layers_present:false, sample_stride:1, cells }
    }
    fn request(day: u64, state: Option<EmergentWorld>) -> EmergenceRequest {
        EmergenceRequest { snapshot:WorldSnapshot { world_id:"map-only".into(),seed:8128,day,
            realms:vec![],borders:vec![],settlements:vec![],characters:vec![],workforce:vec![],geography:vec![map()] }, state }
    }

    #[test]
    fn occupied_territory_cannot_bootstrap_or_form_a_new_polity() {
        let mut geography = map();
        for cell in &mut geography.cells {
            cell.owner_realm_id = "established-kingdom".into();
        }

        let mut bands = Vec::new();
        let mut events = Vec::new();
        bootstrap(&geography, 8128, &mut bands, &mut events);
        assert!(bands.is_empty(), "owned provinces must not generate new autonomous founding bands");

        let make_band = |index: u32| EmergentBand {
            id: format!("legacy-settlement-{index}"), map_id: "planetos".into(),
            x: 10 + index * 4, z: 10, population: 30, food_reserve: 4,
            settled: true, founded_year: 20, hardship_years: 0,
        };
        let legacy_bands = vec![make_band(0), make_band(1), make_band(2), make_band(3)];
        let mut polities = Vec::new();
        let cells = index_cells(&geography);
        maybe_form_polity(&geography, &cells, 8128, 41, &legacy_bands, &mut polities, &mut events);
        assert!(polities.is_empty(), "persisted settlements inside an established realm cannot declare new statehood");
        assert!(events.is_empty(), "blocked founding must not emit a false state-founding event");
    }

    #[test]
    fn transferred_settlement_leaves_emergent_polity_core() {
        let mut geography = map();
        let occupied = geography.cells.iter_mut().find(|cell| cell.x == 10 && cell.z == 10).unwrap();
        occupied.owner_realm_id = "established-kingdom".into();
        let bands = vec![
            EmergentBand { id:"transferred".into(), map_id:"planetos".into(), x:10, z:10,
                population:30, food_reserve:4, settled:true, founded_year:20, hardship_years:0 },
            EmergentBand { id:"remaining".into(), map_id:"planetos".into(), x:20, z:10,
                population:40, food_reserve:8, settled:true, founded_year:20, hardship_years:0 },
        ];
        let mut polities = vec![EmergentPolity {
            id:"frontier-polity".into(), map_id:"planetos".into(), name:"Frontier".into(),
            settlement_ids:vec!["transferred".into(),"remaining".into()], population:70,
            founded_year:20, stability:55, dynasty_id:"frontier-house".into(),
            dynasty_generations:1, ruler_global_id:None, last_succession_year:20,
            years_in_generation:0, succession_pressure:0, weak_years:0,
        }];
        let mut events = Vec::new();
        let maps = vec![geography];
        let cells_by_map: HashMap<&str, CellIndex<'_>> =
            maps.iter().map(|map| (map.map_id.as_str(), index_cells(map))).collect();
        update_polities(8128, 41, &bands, &cells_by_map, &mut polities, &mut events);
        assert_eq!(polities.len(), 1);
        assert_eq!(polities[0].settlement_ids, vec!["remaining".to_string()]);
        assert_eq!(polities[0].population, 40);
        assert!(events.iter().any(|event| event.kind == "settlement_absorbed"
            && event.subject_id == "transferred"));
    }

    #[test]
    fn statehood_requires_four_viable_settled_communities() {
        let geography = map();
        let make_band = |index: u32, population: u64, food_reserve: i64| EmergentBand {
            id: format!("settlement-{index}"), map_id: "planetos".into(),
            x: 10 + index * 4, z: 10, population, food_reserve,
            settled: true, founded_year: 20, hardship_years: 0,
        };
        let mut events = Vec::new();
        let mut polities = Vec::new();
        let three = vec![
            make_band(0, 30, 4), make_band(1, 30, 4), make_band(2, 30, 4),
        ];
        let cells = index_cells(&geography);
        maybe_form_polity(&geography, &cells, 8128, 40, &three, &mut polities, &mut events);
        assert!(polities.is_empty(), "three settlements must not become a sovereign state");

        let four = vec![
            make_band(0, 30, 4), make_band(1, 30, 4),
            make_band(2, 30, 4), make_band(3, 30, 4),
        ];
        maybe_form_polity(&geography, &cells, 8128, 41, &four, &mut polities, &mut events);
        assert_eq!(polities.len(), 1, "four viable communities with 120 people may form a polity");

        let starving = vec![
            make_band(0, 30, -20), make_band(1, 30, -20),
            make_band(2, 30, -20), make_band(3, 30, -20),
        ];
        let mut starving_polities = Vec::new();
        maybe_form_polity(&geography, &cells, 8128, 42, &starving, &mut starving_polities, &mut Vec::new());
        assert!(starving_polities.is_empty(), "a starving regional cluster cannot found a viable state");
    }

    #[test]
    fn map_only_world_emerges_without_preseeded_entities() {
        let frame = advance(request(365 * 220, None)).unwrap();
        assert!(!frame.state.bands.is_empty());
        assert!(frame.state.polities.is_empty() || frame.state.polities.iter().all(|p| p.founded_year > 0));
        assert!(frame.events.iter().any(|e| e.kind == "population_origin"));
        assert!(frame.events.iter().any(|e| e.kind == "settlement_founded"));
        assert!(frame.state.polities.iter().all(|p| p.settlement_ids.len() >= 2));
    }

    #[test]
    fn dynasty_generation_advances_only_for_distinct_ruler_ids_after_statehood() {
        let frame = advance(request(365 * 220, None)).unwrap();
        let mut state = frame.state;
        let polity = state.polities.first().expect("statehood should emerge from map-only conditions");
        let polity_id = polity.id.clone();
        let founded = polity.founded_year;
        assert!(founded > 0 && founded < state.last_year);
        for generation in 1..=4 {
            let ruler_id = format!("00000000-0000-0000-0000-{generation:012}");
            let event = record_ruler_succession(&mut state, &polity_id, &ruler_id, founded + generation as u64 * 28).unwrap();
            assert!(event.is_some());
            assert_eq!(state.polities[0].dynasty_generations, generation);
        }
        let current_year = state.last_year;
        let repeated = record_ruler_succession(&mut state, &polity_id, "00000000-0000-0000-0000-000000000004", current_year).unwrap();
        assert!(repeated.is_none(), "reconciliation of the same canonical ruler is idempotent");
        assert_eq!(state.polities[0].dynasty_generations, 4);
    }

    #[test]
    fn dynasty_age_does_not_fake_a_succession() {
        let frame = advance(request(365 * 500, None)).unwrap();
        assert!(frame.state.polities.iter().all(|polity| polity.dynasty_generations == 1
            && polity.ruler_global_id.is_none()),
            "elapsed years alone must not invent canonical rulers or dynastic generations");
    }

    #[test]
    fn maps_loaded_later_receive_their_own_population_bootstrap() {
        let mut first = advance(request(365 * 120, None)).unwrap().state;
        let second_map = GeographicMap { map_id:"late-map".into(), ..map() };
        let snapshot = WorldSnapshot { world_id:"map-only".into(),seed:8128,day:365 * 121,
            realms:vec![],borders:vec![],settlements:vec![],characters:vec![],workforce:vec![],
            geography:vec![map(), second_map] };
        let frame = advance(EmergenceRequest { snapshot, state:Some(first.clone()) }).unwrap();
        assert!(frame.state.bands.iter().any(|band| band.map_id == "late-map"));
    }

    #[test]
    fn repeated_advancement_is_deterministic_and_idempotent() {
        let a = advance(request(365 * 180, None)).unwrap();
        let b = advance(request(365 * 180, None)).unwrap();
        assert_eq!(a, b);
        let c = advance(request(365 * 180, Some(a.state.clone()))).unwrap();
        assert_eq!(a.state, c.state);
        assert!(c.events.is_empty());
    }

    #[test]
    fn geography_lookup_is_independent_of_snapshot_cell_order() {
        let ordered = request(365 * 180, None);
        let mut shuffled = request(365 * 180, None);
        shuffled.snapshot.geography[0].cells.reverse();
        let a = advance(ordered).unwrap();
        let b = advance(shuffled).unwrap();
        assert_eq!(a, b, "coordinate indexing must not change deterministic emergence output");
    }

    #[test]
    fn no_viable_geography_does_not_invent_population_or_states() {
        let mut r = request(365 * 300, None);
        for c in &mut r.snapshot.geography[0].cells { c.land = false; c.suitability = 0; }
        let frame = advance(r).unwrap();
        assert!(frame.state.bands.is_empty());
        assert!(frame.state.polities.is_empty());
        assert!(frame.events.is_empty());
    }
}
