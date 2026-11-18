//! Map-first autonomous emergence layer.
//! No political or population entities are read from the map package. The only
//! initial conditions are geography, world seed and elapsed simulation time.
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet, HashMap};
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
    /// Earliest elapsed world year in which this community may begin to develop.
    /// Newly discovered vanilla terrain must not receive retroactive simulation.
    #[serde(default)]
    pub origin_year: u64,
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

/// A fully authored image map exposes its whole extent immediately, while a vanilla
/// dimension exposes only chunks the player has generated. Using the eventual vanilla
/// region bounds here makes local communities hundreds of blocks apart and prevents
/// any polity from forming until an unrealistic amount of terrain has been explored.
/// Scale settlement spacing to the currently observed viable land footprint instead.
fn observed_map_scale(map: &GeographicMap) -> u64 {
    let mut min_x = u32::MAX;
    let mut min_z = u32::MAX;
    let mut max_x = 0_u32;
    let mut max_z = 0_u32;
    let mut found = false;
    for cell in map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48) {
        found = true;
        min_x = min_x.min(cell.x);
        min_z = min_z.min(cell.z);
        max_x = max_x.max(cell.x);
        max_z = max_z.max(cell.z);
    }
    if !found { return map_scale(map); }
    let observed_span = u64::from(max_x.saturating_sub(min_x).max(max_z.saturating_sub(min_z)).saturating_add(1));
    let minimum_spacing = u64::from(map.sample_stride.max(1)) * 2;
    map_scale(map)
        .min((observed_span / 12).max(minimum_spacing))
        .min(256_u64.max(minimum_spacing))
        .max(minimum_spacing).max(1)
}

fn bootstrap(map: &GeographicMap, seed: u64, bands: &mut Vec<EmergentBand>, events: &mut Vec<EmergenceEvent>) {
    if map.cells.is_empty() { return; }
    let spacing = observed_map_scale(map);
    let mut candidates = map.cells.iter()
        .filter(|c| c.land && c.suitability >= 48)
        .collect::<Vec<_>>();
    candidates.sort_by(|a,b| {
        b.suitability.cmp(&a.suitability)
            .then_with(|| a.z.cmp(&b.z)).then_with(|| a.x.cmp(&b.x))
    });
    // Existing communities reserve their geographic catchment. Re-running bootstrap
    // against a growing chunk cache can therefore add newly discovered frontier sites
    // without duplicating settlements that were already founded in earlier snapshots.
    let mut occupied = bands.iter().filter(|band| band.map_id == map.map_id)
        .map(|band| (band.x, band.z)).collect::<Vec<_>>();
    for cell in candidates {
        let chance = u64::from(cell.suitability.saturating_sub(42)) * 55;
        let key = format!("{}:{}:{}", map.map_id, cell.x, cell.z);
        if roll(seed, "founder-band", &key, &cell.biome, 0) % 10_000 >= chance { continue; }
        if occupied.iter().any(|p| distance_sq(*p, (cell.x,cell.z)) < spacing * spacing) { continue; }
        let id = format!("band_{:016x}", roll(seed, "band-id", &key, "", 0));
        if bands.iter().any(|band| band.id == id) { continue; }
        let population = 2 + roll(seed, "founder-size", &key, "", 0) % 5;
        bands.push(EmergentBand { id: id.clone(), map_id: map.map_id.clone(), x: cell.x, z: cell.z,
            population, food_reserve: i64::from(cell.suitability) / 8, settled: false,
            founded_year: 0, hardship_years: 0, origin_year: cell.discovered_year });
        occupied.push((cell.x,cell.z));
        events.push(EmergenceEvent { year: 0, kind: "population_origin".into(), subject_id: id,
            text: format!("A small kin group emerges near {} terrain", cell.biome) });
    }
}

/// Vanilla worlds reveal a sparse set of chunks inside a much larger region.
/// Bucket-based grouping split that sparse sample into bins with fewer than four
/// candidates each. Select a deterministic set across the observed land instead.
fn bootstrap_vanilla_initial_civilizations(
    map: &GeographicMap,
    seed: u64,
    bands: &mut Vec<EmergentBand>,
    polities: &mut Vec<EmergentPolity>,
    events: &mut Vec<EmergenceEvent>,
) {
    if map.cells.is_empty() { return; }
    let mut candidates = map.cells.iter()
        .filter(|cell| cell.land && cell.suitability >= 36)
        .collect::<Vec<_>>();
    if candidates.is_empty() { return; }
    candidates.sort_by(|a, b| {
        b.suitability.cmp(&a.suitability)
            .then_with(|| roll(seed, "vanilla-initial-site-order", &map.map_id,
                &format!("{}:{}", a.x, a.z), 0)
                .cmp(&roll(seed, "vanilla-initial-site-order", &map.map_id,
                &format!("{}:{}", b.x, b.z), 0)))
            .then_with(|| a.z.cmp(&b.z)).then_with(|| a.x.cmp(&b.x))
    });

    // Vanilla observations are sparse samples of a huge world. Keep founding
    // cohorts local to the observed chunk neighbourhood, not to the eventual
    // planet span; cap the distance so a newly explored frontier can still found
    // several neighbouring communities and more than one independent polity.
    let minimum_spacing = u64::from(map.sample_stride.max(1)) * 2;
    let observed_scale = observed_map_scale(map);
    let separation = (observed_scale / 3).min(32).max(minimum_spacing).max(1);
    let bucket_span = separation.saturating_mul(4).min(u64::from(u32::MAX)) as u32;
    let bucket_span = bucket_span.max(1);
    let mut groups: BTreeMap<(u32, u32), Vec<&GeographicCell>> = BTreeMap::new();
    for cell in &candidates {
        groups.entry((cell.x / bucket_span, cell.z / bucket_span)).or_default().push(*cell);
    }
    let mut occupied = bands.iter().filter(|band| band.map_id == map.map_id)
        .map(|band| (band.x, band.z)).collect::<Vec<_>>();
    let separation_sq = separation.saturating_mul(separation);

    // Several bounded regional cohorts can found independent states in one world.
    // A settlement is assigned to at most one polity; new frontier observations can
    // later create additional states without rewriting existing sovereign identities.
    for ((_region_x, _region_z), regional_candidates) in groups {
        let mut selected: Vec<&GeographicCell> = Vec::new();
        for cell in regional_candidates {
            let point = (cell.x, cell.z);
            if occupied.iter().any(|other| distance_sq(*other, point) < separation_sq)
                || selected.iter().any(|other| distance_sq((other.x, other.z), point) < separation_sq) {
                continue;
            }
            selected.push(cell);
            if selected.len() == 8 { break; }
        }

        let mut settlement_ids = Vec::new();
        let mut population = 0u64;
        for cell in selected {
            let key = format!("{}:{}:{}", map.map_id, cell.x, cell.z);
            let id = format!("band_{:016x}", roll(seed, "vanilla-initial-band-id", &key, "", 0));
            if bands.iter().any(|band| band.id == id) { continue; }
            let residents = 32 + roll(seed, "vanilla-initial-community-size", &key, "", 0) % 9;
            bands.push(EmergentBand {
                id: id.clone(), map_id: map.map_id.clone(), x: cell.x, z: cell.z,
                population: residents, food_reserve: 18 + i64::from(cell.suitability) / 3,
                settled: true, founded_year: 0, hardship_years: 0, origin_year: cell.discovered_year,
            });
            occupied.push((cell.x, cell.z));
            population += residents;
            settlement_ids.push(id.clone());
            events.push(EmergenceEvent { year: 0, kind: "population_origin".into(), subject_id: id.clone(),
                text: format!("A self-sustaining community emerges in {} terrain", cell.biome) });
            events.push(EmergenceEvent { year: 0, kind: "settlement_founded".into(), subject_id: id,
                text: format!("A permanent settlement is founded (initial population {})", residents) });
        }
        settlement_ids.sort();
        settlement_ids.dedup();
        if settlement_ids.len() < 4 || population < 120 { continue; }
        if polities.iter().any(|polity| polity.map_id == map.map_id
            && polity.settlement_ids.iter().any(|id| settlement_ids.contains(id))) {
            continue;
        }
        let group_key = settlement_ids.join("|");
        let polity_id = format!("polity_{:016x}",
            roll(seed, "vanilla-initial-polity-id", &map.map_id, &group_key, 0));
        if polities.iter().any(|polity| polity.id == polity_id) { continue; }
        let dynasty_id = format!("house_{:016x}",
            roll(seed, "vanilla-initial-founding-house", &polity_id, "", 0));
        let name = format!("{} Realm", polity_id.chars().take(12).collect::<String>());
        polities.push(EmergentPolity {
            id: polity_id.clone(), map_id: map.map_id.clone(), name: name.clone(),
            settlement_ids, population, founded_year: 0, stability: 65,
            dynasty_id, dynasty_generations: 1, ruler_global_id: None, last_succession_year: 0,
            years_in_generation: 0, succession_pressure: 0, weak_years: 0,
        });
        events.push(EmergenceEvent { year: 0, kind: "state_founded".into(), subject_id: polity_id,
            text: format!("{} emerges from an independent regional settlement cluster", name) });
    }
}

/// Creates a playable, living baseline for a newly created world from geography alone.
/// This is an in-mod bootstrap, not a campaign or a scripted history: every map gets
/// its own independent communities and polity seeds, then normal simulation takes over.
fn bootstrap_initial_civilizations(
    map: &GeographicMap,
    seed: u64,
    bands: &mut Vec<EmergentBand>,
    polities: &mut Vec<EmergentPolity>,
    events: &mut Vec<EmergenceEvent>,
) {
    if map.map_id.starts_with("vanilla-world:") {
        bootstrap_vanilla_initial_civilizations(map, seed, bands, polities, events);
        return;
    }
    if map.cells.is_empty() || polities.iter().any(|polity| polity.map_id == map.map_id) {
        return;
    }

    // Vanilla geography is observed incrementally: the first useful sample may cover
    // only a small patch around the spawn point, while an image-backed planet is known
    // in full. Scale spacing to the currently observed land footprint, not the eventual
    // map dimensions, otherwise a small vanilla observation window can never found four
    // communities and the world remains politically empty forever.
    let min_x = map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48).map(|cell| cell.x).min();
    let max_x = map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48).map(|cell| cell.x).max();
    let min_z = map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48).map(|cell| cell.z).min();
    let max_z = map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48).map(|cell| cell.z).max();
    let observed_span = match (min_x, max_x, min_z, max_z) {
        (Some(x0), Some(x1), Some(z0), Some(z1)) =>
            u64::from((x1 - x0).max(z1 - z0).saturating_add(1)),
        _ => return,
    };
    let minimum_spacing = u64::from(map.sample_stride.max(1)) * 2;
    // Image-backed worlds need many local sovereign catchments, not a few
    // continent-sized founding buckets. Province-scale map geometry is retained;
    // only the population bootstrap cohort size is reduced.
    let separation = (observed_map_scale(map) / 3).max(minimum_spacing).max(1);
    let bucket = (separation.saturating_mul(6).min(u64::from(u32::MAX)) as u32).max(1);
    let separation_sq = separation.saturating_mul(separation);
    let mut groups: BTreeMap<(u32, u32), Vec<&GeographicCell>> = BTreeMap::new();
    for cell in map.cells.iter().filter(|cell| cell.land && cell.suitability >= 48) {
        groups.entry((cell.x / bucket, cell.z / bucket)).or_default().push(cell);
    }

    let mut occupied: Vec<(u32, u32)> = bands.iter()
        .filter(|band| band.map_id == map.map_id)
        .map(|band| (band.x, band.z)).collect();

    for (group_x, group_z, mut candidates) in groups.into_iter()
        .map(|((x, z), cells)| (x, z, cells))
    {
        candidates.sort_by(|a, b| {
            b.suitability.cmp(&a.suitability)
                .then_with(|| roll(seed, "initial-site-order", &map.map_id,
                    &format!("{}:{}", a.x, a.z), 0)
                    .cmp(&roll(seed, "initial-site-order", &map.map_id,
                    &format!("{}:{}", b.x, b.z), 0)))
                .then_with(|| a.z.cmp(&b.z))
                .then_with(|| a.x.cmp(&b.x))
        });

        let mut selected: Vec<&GeographicCell> = Vec::new();
        for cell in candidates {
            if occupied.iter().any(|point| distance_sq(*point, (cell.x, cell.z)) < separation_sq)
                || selected.iter().any(|other| distance_sq((other.x, other.z), (cell.x, cell.z)) < separation_sq)
            {
                continue;
            }
            selected.push(cell);
            if selected.len() == 6 { break; }
        }
        // A sovereign polity needs a real regional population base, not a single village.
        if selected.len() < 4 { continue; }

        let group_key = format!("{}:{}:{}", map.map_id, group_x, group_z);
        let mut settlement_ids = Vec::new();
        let mut population = 0_u64;
        for (ordinal, cell) in selected.into_iter().enumerate() {
            let location_key = format!("{}:{}:{}", group_key, cell.x, cell.z);
            let id = format!("band_{:016x}", roll(seed, "initial-band-id", &location_key, "", 0));
            if bands.iter().any(|band| band.id == id) { continue; }
            let residents = 32 + roll(seed, "initial-community-size", &location_key, "", 0) % 9;
            bands.push(EmergentBand {
                id: id.clone(),
                map_id: map.map_id.clone(),
                x: cell.x,
                z: cell.z,
                population: residents,
                food_reserve: 18 + i64::from(cell.suitability) / 3,
                settled: true,
                founded_year: 0,
                hardship_years: 0,
                origin_year: cell.discovered_year,
            });
            occupied.push((cell.x, cell.z));
            settlement_ids.push(id.clone());
            population = population.saturating_add(residents);
            events.push(EmergenceEvent {
                year: 0,
                kind: "population_origin".into(),
                subject_id: id.clone(),
                text: format!("A self-sustaining community forms in {} terrain", cell.biome),
            });
            events.push(EmergenceEvent {
                year: 0,
                kind: "settlement_founded".into(),
                subject_id: id,
                text: format!("A permanent settlement is founded (initial population {})", residents),
            });
            let _ = ordinal;
        }
        if settlement_ids.len() < 4 || population < 120 { continue; }

        let polity_id = format!("polity_{:016x}",
            roll(seed, "initial-polity-id", &map.map_id, &group_key, 0));
        if polities.iter().any(|polity| polity.id == polity_id) { continue; }
        let dynasty_id = format!("house_{:016x}",
            roll(seed, "initial-founding-house", &polity_id, "", 0));
        let name = format!("{} Realm", polity_id.chars().take(12).collect::<String>());
        polities.push(EmergentPolity {
            id: polity_id.clone(),
            map_id: map.map_id.clone(),
            name: name.clone(),
            settlement_ids,
            population,
            founded_year: 0,
            stability: 65,
            dynasty_id,
            dynasty_generations: 1,
            ruler_global_id: None,
            last_succession_year: 0,
            years_in_generation: 0,
            succession_pressure: 0,
            weak_years: 0,
        });
        events.push(EmergenceEvent {
            year: 0,
            kind: "state_founded".into(),
            subject_id: polity_id,
            text: format!("{} emerges from neighbouring settled communities", name),
        });
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
    // Founding catchments must stay local. Using three times the whole-map scale
    // collapsed distant communities into a few oversized states on authored maps.
    let spacing = (observed_map_scale(map) * 2).max(u64::from(map.sample_stride.max(1)) * 3);
    const MAX_FOUNDING_COMMUNITIES: usize = 8;
    let mut settled = bands.iter().filter(|b| {
        b.map_id == map.map_id && b.settled
            && cell_at(cells, b.x, b.z).is_some_and(|cell| cell.land)
    }).collect::<Vec<_>>();
    settled.sort_by(|a,b| a.id.cmp(&b.id));

    // Persisted sovereign assignments are immutable here. New states may only claim
    // currently unassigned communities; no pass may silently merge existing realms.
    let mut assigned = polities.iter().filter(|p| p.map_id == map.map_id)
        .flat_map(|p| p.settlement_ids.iter().cloned()).collect::<BTreeSet<_>>();

    for anchor in &settled {
        if assigned.contains(&anchor.id) { continue; }
        let mut cluster = settled.iter()
            .filter(|b| !assigned.contains(&b.id)
                && distance_sq((anchor.x,anchor.z),(b.x,b.z)) <= spacing.saturating_mul(spacing))
            .map(|b| (*b).clone()).collect::<Vec<_>>();
        cluster.sort_by(|a,b| distance_sq((anchor.x, anchor.z), (a.x, a.z))
            .cmp(&distance_sq((anchor.x, anchor.z), (b.x, b.z)))
            .then_with(|| a.id.cmp(&b.id)));
        cluster.dedup_by(|a,b| a.id == b.id);
        cluster.truncate(MAX_FOUNDING_COMMUNITIES);
        let population = cluster.iter().map(|b| b.population).sum::<u64>();
        let food_position = cluster.iter().map(|b| b.food_reserve).sum::<i64>();
        let viable_communities = cluster.iter().filter(|b| b.population >= 20 && b.hardship_years <= 4).count();
        if cluster.len() < 4 || population < 120 || viable_communities < 4 || food_position < -12 {
            continue;
        }
        let ids = cluster.iter().map(|b| b.id.clone()).collect::<Vec<_>>();
        let id = format!("polity_{:016x}", roll(seed, "polity-id", &map.map_id, &ids.join("|"), year));
        if polities.iter().any(|p| p.id == id) { continue; }
        let dynasty_id = format!("house_{:016x}", roll(seed, "founding-house", &id, "", year));
        let name = format!("{} Confederation", anchor.id.chars().take(8).collect::<String>());
        polities.push(EmergentPolity { id: id.clone(), map_id: map.map_id.clone(), name: name.clone(),
            settlement_ids: ids.clone(), population, founded_year: year, stability: 55, dynasty_id,
            dynasty_generations: 1, ruler_global_id: None, last_succession_year: year,
            years_in_generation: 0, succession_pressure: 0, weak_years: 0 });
        assigned.extend(ids);
        events.push(EmergenceEvent { year, kind: "state_founded".into(), subject_id: id,
            text: format!("{} forms through the association of neighbouring settlements (population {})", name, population) });
    }
}

fn update_polities(seed: u64, year: u64, bands: &[EmergentBand],
    _cells_by_map: &HashMap<&str, CellIndex<'_>>,
    polities: &mut Vec<EmergentPolity>, events: &mut Vec<EmergenceEvent>) {
    // Input-map ownership is not authoritative. Political changes are handled only
    // by generated realm/statecraft systems after world formation.
    let bands_by_id: HashMap<&str, &EmergentBand> = bands.iter().map(|band| (band.id.as_str(), band)).collect();
    for polity in polities.iter_mut() {
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
        // Give each newly discovered world-space an independent playable baseline
        // before ordinary demographic emergence begins. The baseline is generated
        // by the native simulation from terrain; no campaign or external script runs.
        bootstrap_initial_civilizations(map, state.seed, &mut state.bands, &mut state.polities, &mut events);
        // Geography is a growing set of observed cells, not a one-shot map import.
        // Reconcile on every immutable snapshot so newly generated vanilla chunks
        // can found additional communities in the same persistent world partition.
        bootstrap(map, state.seed, &mut state.bands, &mut events);
    }
    state.bands.sort_by(|a,b| a.id.cmp(&b.id));
    let mut years_advanced = 0u64;
    while state.last_year < target_year {
        let year = state.last_year + 1;
        for band in &mut state.bands {
            if year <= band.origin_year { continue; }
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
        update_polities(state.seed, year, &state.bands, &cells_by_map,
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
                    owner_realm_id: String::new(), discovered_year: 0 });
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
    fn observed_vanilla_frontier_spacing_stays_bounded_as_the_world_expands() {
        let map = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            width_px: 60_000_001, height_px: 60_000_001,
            blocks_per_pixel_milli: 1000, political_layers_present: false,
            sample_stride: 4,
            cells: vec![
                GeographicCell { x: 30_000_000, z: 30_000_000, biome: "minecraft:plains".into(),
                    elevation: 70, river: 0, land: true, suitability: 90,
                    owner_realm_id: String::new(), discovered_year: 0 },
                GeographicCell { x: 30_900_000, z: 30_900_000, biome: "minecraft:forest".into(),
                    elevation: 72, river: 0, land: true, suitability: 88,
                    owner_realm_id: String::new(), discovered_year: 0 },
            ],
        };
        assert!(observed_map_scale(&map) <= 256,
            "exploring more of a planet must not make new settlements thousands of blocks apart");
    }

    #[test]
    fn sparse_vanilla_spawn_samples_form_a_polity_without_bucket_alignment() {
        let mut cells = Vec::new();
        for index in 0..32_u32 {
            let x = 128 + (index % 8) * 420;
            let z = 128 + (index / 8) * 420;
            for dz in [0, 16, 32, 48] {
                for dx in [0, 16, 32, 48] {
                    cells.push(GeographicCell {
                        x: x + dx, z: z + dz, biome: "minecraft:plains".into(),
                        elevation: 70, river: 0, land: true, suitability: 90,
                        owner_realm_id: String::new(), discovered_year: 0,
                    });
                }
            }
        }
        let map = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            width_px: 60_000_001, height_px: 60_000_001,
            blocks_per_pixel_milli: 1000, political_layers_present: false,
            sample_stride: 4, cells,
        };
        let mut bands = Vec::new();
        let mut polities = Vec::new();
        let mut events = Vec::new();
        bootstrap_initial_civilizations(&map, 424242, &mut bands, &mut polities, &mut events);
        assert!(bands.len() >= 8, "sparse observed chunks should seed multiple regional communities");
        assert!(polities.len() >= 2,
            "separated regional clusters must form multiple sovereign polities, not one world-sized state");
        let mut owned = BTreeSet::new();
        for polity in &polities {
            assert!(polity.settlement_ids.len() >= 4, "every initial polity needs a viable local founding cohort");
            for settlement_id in &polity.settlement_ids {
                assert!(owned.insert(settlement_id.as_str()),
                    "one settlement cannot be assigned to multiple sovereign polities");
            }
        }
    }

    #[test]
    fn compact_vanilla_patch_keeps_founders_spatially_separated() {
        let cells = (0..4_u32).map(|index| GeographicCell {
            x: 30_000_000 + index * 8,
            z: 30_000_000,
            biome: "minecraft:plains".into(),
            elevation: 70,
            river: 0,
            land: true,
            suitability: 90,
            owner_realm_id: String::new(),
            discovered_year: 0,
        }).collect();
        let map = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            width_px: 60_000_001,
            height_px: 60_000_001,
            blocks_per_pixel_milli: 1000,
            political_layers_present: false,
            sample_stride: 4,
            cells,
        };
        let mut bands = Vec::new();
        let mut polities = Vec::new();
        bootstrap_initial_civilizations(&map, 424242, &mut bands, &mut polities, &mut Vec::new());
        assert_eq!(bands.iter().filter(|band| band.settled).count(), 4);
        assert_eq!(polities.len(), 1, "four viable, spatially separated cells may form one compact polity");
        assert_eq!(polities[0].settlement_ids.len(), 4);
    }

    #[test]
    fn small_incremental_vanilla_observation_can_found_a_polity() {
        let mut cells = Vec::new();
        for z in (2..=78).step_by(4) {
            for x in (2..=78).step_by(4) {
                cells.push(GeographicCell {
                    x, z, biome: "minecraft:plains".into(), elevation: 70,
                    river: 0, land: true, suitability: 90,
                    owner_realm_id: String::new(), discovered_year: 0,
                });
            }
        }
        let map = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            width_px: 256, height_px: 256, blocks_per_pixel_milli: 1000,
            political_layers_present: false, sample_stride: 4, cells,
        };
        let mut bands = Vec::new();
        let mut polities = Vec::new();
        let mut events = Vec::new();
        bootstrap_initial_civilizations(&map, 424242, &mut bands, &mut polities, &mut events);
        assert!(bands.len() >= 4, "observed land patch should produce several real communities");
        assert!(!polities.is_empty(), "a sufficiently large suitable land patch should found a polity");
        assert!(polities[0].settlement_ids.len() >= 4);
    }

    #[test]
    fn new_worlds_receive_independent_playable_civilizations_without_elapsed_years() {
        let first = map();
        let second = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            ..map()
        };
        let snapshot = WorldSnapshot {
            world_id: "one-server-two-worlds".into(),
            seed: 8128,
            day: 0,
            realms: vec![], borders: vec![], settlements: vec![],
            characters: vec![], workforce: vec![],
            geography: vec![first, second],
        };
        let frame = advance(EmergenceRequest { snapshot, state: None }).unwrap();
        for world_id in ["planetos", "vanilla-world:minecraft:overworld"] {
            let world_bands = frame.state.bands.iter()
                .filter(|band| band.map_id == world_id && band.settled).collect::<Vec<_>>();
            let world_polities = frame.state.polities.iter()
                .filter(|polity| polity.map_id == world_id).collect::<Vec<_>>();
            assert!(world_bands.len() >= 4, "{world_id} must start with real settled communities");
            assert!(!world_polities.is_empty(), "{world_id} must start with at least one polity");
            assert!(world_polities.iter().all(|polity| polity.population >= 120
                && polity.settlement_ids.len() >= 4));
        }
        let first_ids = frame.state.bands.iter()
            .filter(|band| band.map_id == "planetos").map(|band| band.id.as_str())
            .collect::<BTreeSet<_>>();
        let second_ids = frame.state.bands.iter()
            .filter(|band| band.map_id == "vanilla-world:minecraft:overworld")
            .map(|band| band.id.as_str()).collect::<BTreeSet<_>>();
        assert!(first_ids.is_disjoint(&second_ids), "different dimensions must not share community identity");
        assert_eq!(frame.state.last_year, 0, "initial civilization creation must not fast-forward the world clock");
    }

    #[test]
    fn political_overlay_does_not_block_geographic_emergence() {
        let mut geography = map();
        for cell in &mut geography.cells {
            cell.owner_realm_id = "established-kingdom".into();
        }

        let mut bands = Vec::new();
        let mut events = Vec::new();
        bootstrap(&geography, 8128, &mut bands, &mut events);
        assert!(!bands.is_empty(), "political overlays must not suppress viable founding sites");

        let make_band = |index: u32| EmergentBand {
            id: format!("legacy-settlement-{index}"), map_id: "planetos".into(),
            x: 10 + index * 2, z: 10, population: 30, food_reserve: 4,
            settled: true, founded_year: 20, hardship_years: 0, origin_year: 0,
        };
        let legacy_bands = vec![make_band(0), make_band(1), make_band(2), make_band(3)];
        let mut polities = Vec::new();
        let cells = index_cells(&geography);
        maybe_form_polity(&geography, &cells, 8128, 41, &legacy_bands, &mut polities, &mut events);
        assert_eq!(polities.len(), 1, "statehood depends on viable settlement clusters, not image sovereignty");
        assert!(events.iter().any(|event| event.kind == "state_founded"));
    }

    #[test]
    fn political_overlay_cannot_absorb_an_emergent_settlement() {
        let mut geography = map();
        let occupied = geography.cells.iter_mut().find(|cell| cell.x == 10 && cell.z == 10).unwrap();
        occupied.owner_realm_id = "established-kingdom".into();
        let bands = vec![
            EmergentBand { id:"transferred".into(), map_id:"planetos".into(), x:10, z:10,
                population:30, food_reserve:4, settled:true, founded_year:20, hardship_years: 0, origin_year: 0 },
            EmergentBand { id:"remaining".into(), map_id:"planetos".into(), x:20, z:10,
                population:40, food_reserve:8, settled:true, founded_year:20, hardship_years: 0, origin_year: 0 },
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
        assert_eq!(polities[0].settlement_ids, vec!["transferred".to_string(), "remaining".to_string()]);
        assert_eq!(polities[0].population, 70);
        assert!(!events.iter().any(|event| event.kind == "settlement_absorbed"));
    }

    #[test]
    fn statehood_requires_four_viable_settled_communities() {
        let geography = map();
        let make_band = |index: u32, population: u64, food_reserve: i64| EmergentBand {
            id: format!("settlement-{index}"), map_id: "planetos".into(),
            x: 10 + index * 4, z: 10, population, food_reserve,
            settled: true, founded_year: 20, hardship_years: 0, origin_year: 0,
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
    fn authored_map_bootstrap_produces_many_small_independent_polities() {
        let geography = map();
        let mut bands = Vec::new();
        let mut polities = Vec::new();
        let mut events = Vec::new();
        bootstrap_initial_civilizations(&geography, 8128, &mut bands, &mut polities, &mut events);
        assert!(polities.len() >= 12,
            "a large authored map must be fragmented into multiple local sovereign states");
        assert!(polities.iter().all(|polity| polity.settlement_ids.len() <= 8),
            "initial states must not absorb oversized settlement catchments");
        let mut assigned = BTreeSet::new();
        for polity in &polities {
            for settlement_id in &polity.settlement_ids {
                assert!(assigned.insert(settlement_id.as_str()),
                    "each settlement belongs to at most one sovereign state");
            }
        }
        assert_eq!(assigned.len(), polities.iter().map(|polity| polity.settlement_ids.len()).sum::<usize>());
    }

    #[test]
    fn independent_regional_clusters_found_separate_non_overlapping_polities_in_one_pass() {
        let geography = map();
        let mut bands = Vec::new();
        for (base_x, base_z) in [(10_u32, 10_u32), (70, 10), (10, 70)] {
            for index in 0..4_u32 {
                bands.push(EmergentBand {
                    id: format!("region-{base_x}-{base_z}-{index}"),
                    map_id: "planetos".into(),
                    x: base_x + index * 3,
                    z: base_z,
                    population: 35,
                    food_reserve: 8,
                    settled: true,
                    founded_year: 0,
                    hardship_years: 0,
                    origin_year: 0,
                });
            }
        }
        let cells = index_cells(&geography);
        let mut polities = Vec::new();
        let mut events = Vec::new();
        maybe_form_polity(&geography, &cells, 8128, 1, &bands, &mut polities, &mut events);
        assert_eq!(polities.len(), 3,
            "three geographically separate viable cohorts must not be collapsed into one state");
        let mut assigned = BTreeSet::new();
        for polity in &polities {
            assert_eq!(polity.settlement_ids.len(), 4);
            for settlement_id in &polity.settlement_ids {
                assert!(assigned.insert(settlement_id.as_str()),
                    "a community cannot belong to two newly founded sovereign states");
            }
        }
        assert_eq!(assigned.len(), 12);
        assert_eq!(events.iter().filter(|event| event.kind == "state_founded").count(), 3);
    }

    #[test]
    fn map_only_world_emerges_without_preseeded_entities() {
        let frame = advance(request(365 * 220, None)).unwrap();
        assert!(!frame.state.bands.is_empty());
        assert!(frame.state.polities.iter().all(|p| p.founded_year <= frame.state.last_year));
        assert!(frame.state.polities.iter().any(|p| p.founded_year == 0),
            "new worlds receive their initial polity baseline without waiting for elapsed years");
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
        assert!(founded <= state.last_year,
            "the first polity may be present at world creation and must never be future-dated");
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
    fn newly_observed_cells_in_existing_region_bootstrap_incrementally_without_duplicates() {
        let mut partial = map();
        partial.cells.truncate(64 * 128);
        let first_snapshot = WorldSnapshot {
            world_id: "incremental-vanilla".into(), seed: 8128, day: 0,
            realms: vec![], borders: vec![], settlements: vec![], characters: vec![], workforce: vec![],
            geography: vec![partial],
        };
        let first = advance(EmergenceRequest { snapshot: first_snapshot, state: None }).unwrap();
        let initial_count = first.state.bands.len();

        let expanded_snapshot = WorldSnapshot {
            world_id: "incremental-vanilla".into(), seed: 8128, day: 0,
            realms: vec![], borders: vec![], settlements: vec![], characters: vec![], workforce: vec![],
            geography: vec![map()],
        };
        let expanded = advance(EmergenceRequest { snapshot: expanded_snapshot.clone(), state: Some(first.state) }).unwrap();
        assert!(expanded.state.bands.len() > initial_count, "newly observed viable cells must create frontier communities");
        let unique = expanded.state.bands.iter().map(|band| band.id.as_str()).collect::<BTreeSet<_>>();
        assert_eq!(unique.len(), expanded.state.bands.len(), "incremental discovery must not duplicate band IDs");

        let repeated = advance(EmergenceRequest { snapshot: expanded_snapshot, state: Some(expanded.state.clone()) }).unwrap();
        assert_eq!(expanded.state, repeated.state, "replaying an unchanged geography snapshot must be idempotent");
    }

    #[test]
    fn newly_discovered_terrain_does_not_receive_retroactive_simulation() {
        let mut snapshot = request(365 * 100, None);
        for cell in &mut snapshot.snapshot.geography[0].cells {
            cell.discovered_year = 100;
        }
        let frame = advance(snapshot).unwrap();
        assert!(!frame.state.bands.is_empty());
        assert!(frame.state.bands.iter().all(|band| band.origin_year == 100));
        let before = frame.state.bands.iter().map(|band| (band.id.clone(), band.food_reserve)).collect::<HashMap<_, _>>();
        assert!(frame.state.bands.iter().all(|band| band.founded_year == 0),
            "newly discovered communities cannot have historical settlement ages");

        let next = advance(request(365 * 101, Some(frame.state))).unwrap();
        assert!(next.state.bands.iter().any(|band| before.get(&band.id).is_some_and(|food| *food != band.food_reserve)),
            "communities begin their normal simulation after discovery");
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
    fn same_coordinates_on_different_dimensions_remain_independent_world_spaces() {
        let first = map();
        let second = GeographicMap {
            map_id: "vanilla-world:minecraft:overworld".into(),
            ..map()
        };
        let snapshot = WorldSnapshot {
            world_id: "single-universe-two-worlds".into(),
            seed: 8128,
            day: 0,
            realms: vec![], borders: vec![], settlements: vec![],
            characters: vec![], workforce: vec![],
            geography: vec![first, second],
        };
        let frame = advance(EmergenceRequest { snapshot, state: None }).unwrap();
        let first_ids = frame.state.bands.iter()
            .filter(|band| band.map_id == "planetos")
            .map(|band| band.id.as_str()).collect::<BTreeSet<_>>();
        let second_ids = frame.state.bands.iter()
            .filter(|band| band.map_id == "vanilla-world:minecraft:overworld")
            .map(|band| band.id.as_str()).collect::<BTreeSet<_>>();
        assert!(!first_ids.is_empty(), "mapped planet must receive its own founding communities");
        assert!(!second_ids.is_empty(), "vanilla planet must receive its own founding communities");
        assert!(first_ids.is_disjoint(&second_ids),
            "equal local coordinates in different dimensions must never share a Band identity");
        assert!(frame.state.bands.iter().all(|band|
            band.map_id == "planetos" || band.map_id == "vanilla-world:minecraft:overworld"));
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
