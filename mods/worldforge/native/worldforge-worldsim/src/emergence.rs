//! Map-first autonomous emergence layer.
//! No political or population entities are read from the map package. The only
//! initial conditions are geography, world seed and elapsed simulation time.
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
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

fn cell_at<'a>(map: &'a GeographicMap, x: u32, z: u32) -> Option<&'a GeographicCell> {
    map.cells.iter().find(|c| c.x == x && c.z == z)
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
        .filter(|c| c.land && c.suitability >= 48)
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

fn maybe_form_polity(map: &GeographicMap, seed: u64, year: u64, bands: &[EmergentBand],
    polities: &mut Vec<EmergentPolity>, events: &mut Vec<EmergenceEvent>) {
    let spacing = map_scale(map) * 3;
    let mut settled = bands.iter().filter(|b| b.map_id == map.map_id && b.settled).collect::<Vec<_>>();
    settled.sort_by(|a,b| a.id.cmp(&b.id));
    for anchor in &settled {
        if polities.iter().any(|p| p.settlement_ids.contains(&anchor.id)) { continue; }
        let mut cluster = settled.iter().filter(|b| distance_sq((anchor.x,anchor.z),(b.x,b.z)) <= spacing*spacing)
            .map(|b| (*b).clone()).collect::<Vec<_>>();
        cluster.sort_by(|a,b| a.id.cmp(&b.id));
        cluster.dedup_by(|a,b| a.id == b.id);
        let population = cluster.iter().map(|b| b.population).sum::<u64>();
        // Statehood requires a real settlement network and surplus population; no realm count is supplied.
        if cluster.len() < 2 || population < 90 { continue; }
        let ids = cluster.iter().map(|b| b.id.clone()).collect::<Vec<_>>();
        let id = format!("polity_{:016x}", roll(seed, "polity-id", &map.map_id, &ids.join("|"), year));
        if polities.iter().any(|p| p.id == id || p.settlement_ids.iter().any(|s| ids.contains(s))) { continue; }
        let dynasty_id = format!("house_{:016x}", roll(seed, "founding-house", &id, "", year));
        let name = format!("{} Confederation", anchor.id.chars().take(8).collect::<String>());
        polities.push(EmergentPolity { id: id.clone(), map_id: map.map_id.clone(), name: name.clone(),
            settlement_ids: ids, population, founded_year: year, stability: 55, dynasty_id,
            dynasty_generations: 1, years_in_generation: 0, succession_pressure: 0, weak_years: 0 });
        events.push(EmergenceEvent { year, kind: "state_founded".into(), subject_id: id,
            text: format!("{} forms through the association of neighbouring settlements (population {})", name, population) });
        break;
    }
}

fn update_polities(seed: u64, year: u64, bands: &[EmergentBand], polities: &mut Vec<EmergentPolity>,
    events: &mut Vec<EmergenceEvent>) {
    for polity in polities.iter_mut() {
        let population = bands.iter().filter(|b| polity.settlement_ids.contains(&b.id) && b.settled)
            .map(|b| b.population).sum::<u64>();
        polity.population = population;
        if population < 45 { polity.weak_years = polity.weak_years.saturating_add(1); }
        else { polity.weak_years = polity.weak_years.saturating_sub(1); }
        polity.stability = (polity.stability + if population > 120 { 1 } else if population < 60 { -3 } else { 0 }).clamp(0,100);
        if population > 0 && polity.stability >= 15 {
            polity.years_in_generation += 1;
            // Generation turnover is gated by population and succession viability, not by a fixed dynasty script.
            if polity.years_in_generation >= 24 && population >= 45 {
                polity.years_in_generation = 0;
                polity.dynasty_generations += 1;
                polity.succession_pressure = (polity.succession_pressure + 12).min(100);
                events.push(EmergenceEvent { year, kind: "dynasty_generation".into(), subject_id: polity.id.clone(),
                    text: format!("{} enters dynasty generation {} through natural succession", polity.name, polity.dynasty_generations) });
            }
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

pub fn advance(request: EmergenceRequest) -> Result<EmergenceFrame, String> {
    request.snapshot.validate().map_err(|e| format!("snapshot:{e:?}"))?;
    let mut state = request.state.unwrap_or_else(|| EmergentWorld::new(request.snapshot.seed));
    if state.seed != request.snapshot.seed { return Err("emergence-world-seed-mismatch".into()); }
    state.validate()?;
    let target_year = request.snapshot.day / 365;
    let mut events = Vec::new();
    let mut diagnostics = Vec::new();
    let maps = &request.snapshot.geography;

    if state.bands.is_empty() {
        for map in maps { bootstrap(map, state.seed, &mut state.bands, &mut events); }
        state.bands.sort_by(|a,b| a.id.cmp(&b.id));
    }
    let mut years_advanced = 0u64;
    while state.last_year < target_year {
        let year = state.last_year + 1;
        for band in &mut state.bands {
            if let Some(map) = maps.iter().find(|m| m.map_id == band.map_id) {
                if let Some(cell) = cell_at(map, band.x, band.z) {
                    advance_band(band, cell, state.seed, year, &mut events);
                }
            }
        }
        for map in maps { maybe_form_polity(map, state.seed, year, &state.bands, &mut state.polities, &mut events); }
        update_polities(state.seed, year, &state.bands, &mut state.polities, &mut events);
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
                    elevation: 70, river: if x % 19 == 0 { 255 } else { 0 }, land: true, suitability: 92 });
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
    fn map_only_world_emerges_without_preseeded_entities() {
        let frame = advance(request(365 * 220, None)).unwrap();
        assert!(!frame.state.bands.is_empty());
        assert!(frame.state.polities.is_empty() || frame.state.polities.iter().all(|p| p.founded_year > 0));
        assert!(frame.events.iter().any(|e| e.kind == "population_origin"));
        assert!(frame.events.iter().any(|e| e.kind == "settlement_founded"));
        assert!(frame.state.polities.iter().all(|p| p.settlement_ids.len() >= 2));
    }

    #[test]
    fn four_generations_are_natural_and_follow_statehood() {
        let mut state = None;
        let mut founded = None;
        let mut generations = 0;
        for year in 1..=500 {
            let frame = advance(request(year * 365, state)).unwrap();
            state = Some(frame.state);
            if let Some(polity) = state.as_ref().unwrap().polities.first() {
                founded.get_or_insert(polity.founded_year);
                generations = polity.dynasty_generations;
                if generations >= 4 { break; }
            }
        }
        assert!(founded.is_some(), "statehood should emerge from map-only conditions");
        assert!(generations >= 4, "a surviving polity should experience four generations after statehood");
        assert!(state.as_ref().unwrap().polities[0].founded_year < state.as_ref().unwrap().last_year);
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
    fn no_viable_geography_does_not_invent_population_or_states() {
        let mut r = request(365 * 300, None);
        for c in &mut r.snapshot.geography[0].cells { c.land = false; c.suitability = 0; }
        let frame = advance(r).unwrap();
        assert!(frame.state.bands.is_empty());
        assert!(frame.state.polities.is_empty());
        assert!(frame.events.is_empty());
    }
}
