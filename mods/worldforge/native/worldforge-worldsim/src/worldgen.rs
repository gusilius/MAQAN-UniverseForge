//! Seeded macro-geography generator. It emits only geographic regions, provinces
//! and unassigned settlement-site candidates. Sovereignty, states, rulers, names
//! and dynasties are exclusively created by the installed WorldForge mods.
use serde::{Deserialize, Serialize};
use crate::stable_hash;
use std::collections::BTreeSet;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorldGenRequest {
    pub seed: u64,
    pub width: u32,
    pub height: u32,
    pub region_count: u32,
    pub provinces_per_region: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RegionSeed {
    pub id: String,
    pub x: u32,
    pub z: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProvinceSeed {
    pub id: String,
    pub region_id: String,
    pub x: u32,
    pub z: u32,
    pub elevation: i16,
    pub moisture: u8,
    pub temperature: i8,
    pub biome: String,
    pub food: u8,
    pub wood: u8,
    pub stone: u8,
    pub ore: u8,
    pub has_river: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettlementSeed {
    pub id: String,
    pub province_id: String,
    pub x: u32,
    pub z: u32,
    /// Generic geographic site hint. The settlement mod chooses the actual
    /// settlement class, name, population, culture and ownership at runtime.
    pub kind: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GeneratedWorld {
    pub seed: u64,
    pub width: u32,
    pub height: u32,
    pub regions: Vec<RegionSeed>,
    pub provinces: Vec<ProvinceSeed>,
    pub settlements: Vec<SettlementSeed>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum WorldGenError { InvalidDimensions, InvalidRegionCount, InvalidProvinceCount }

fn roll(seed: u64, domain: &str, index: u64, max: u64) -> u64 {
    if max == 0 { return 0; }
    stable_hash(seed, domain, &index.to_string(), "geography", 0) % max
}

fn unique_point(seed: u64, domain: &str, index: u64, width: u32, height: u32,
                used: &mut BTreeSet<(u32, u32)>) -> (u32, u32) {
    let area = u64::from(width) * u64::from(height);
    let start = roll(seed, domain, index, area);
    for offset in 0..area {
        let point = (start + offset) % area;
        let x = (point % u64::from(width)) as u32;
        let z = (point / u64::from(width)) as u32;
        if used.insert((x, z)) { return (x, z); }
    }
    unreachable!("validated geography dimensions provide enough unique points")
}

pub fn generate_world(request: &WorldGenRequest) -> Result<GeneratedWorld, WorldGenError> {
    if request.width < 64 || request.height < 64 || request.width > 8192 || request.height > 8192 {
        return Err(WorldGenError::InvalidDimensions);
    }
    if request.region_count == 0 || request.region_count > 128
        || u64::from(request.region_count) > u64::from(request.width) * u64::from(request.height) {
        return Err(WorldGenError::InvalidRegionCount);
    }
    if request.provinces_per_region == 0 || request.provinces_per_region > 64
        || u64::from(request.region_count) * u64::from(request.provinces_per_region)
            > u64::from(request.width) * u64::from(request.height) {
        return Err(WorldGenError::InvalidProvinceCount);
    }

    let mut used_points = BTreeSet::new();
    let mut regions = Vec::new();
    for index in 0..request.region_count {
        let (x, z) = unique_point(request.seed, "region-point", index as u64,
                                  request.width, request.height, &mut used_points);
        regions.push(RegionSeed { id: format!("region_{:04}", index + 1), x, z });
    }

    let mut provinces = Vec::new();
    for index in 0..(request.region_count * request.provinces_per_region) {
        let (x, z) = unique_point(request.seed, "province-point", index as u64,
                                  request.width, request.height, &mut used_points);
        let nearest = regions.iter().min_by_key(|region| {
            let dx = i64::from(region.x) - i64::from(x);
            let dz = i64::from(region.z) - i64::from(z);
            dx * dx + dz * dz
        }).expect("region count is validated");
        // Preserve a geographic seed in every region; all remaining provinces
        // are assigned by nearest terrain-space region center, never to a polity.
        let region = if index % request.provinces_per_region == 0 {
            &regions[(index / request.provinces_per_region) as usize]
        } else { nearest };
        let elevation = (roll(request.seed, "elevation", index as u64, 201) as i16) - 80;
        let moisture = roll(request.seed, "moisture", index as u64, 101) as u8;
        let temperature = (roll(request.seed, "temperature", index as u64, 101) as i8) - 50;
        let biome = if elevation < -35 { "ocean" }
            else if elevation > 90 { "mountain" }
            else if temperature < -20 { "tundra" }
            else if moisture < 20 { "steppe" }
            else if moisture > 75 { "forest" }
            else { "plains" };
        provinces.push(ProvinceSeed {
            id: format!("province_{:06}", index + 1), region_id: region.id.clone(), x, z,
            elevation, moisture, temperature, biome: biome.into(),
            food: (moisture / 2).saturating_add(if biome == "plains" { 35 } else { 0 }),
            wood: if biome == "forest" { 90 } else { moisture / 3 },
            stone: if biome == "mountain" { 95 } else { 20 + (elevation.max(0) as u8 / 4) },
            ore: if biome == "mountain" { 65 + (roll(request.seed, "ore", index as u64, 36) as u8) } else { roll(request.seed, "ore", index as u64, 25) as u8 },
            has_river: roll(request.seed, "river", index as u64, 100) < (moisture as u64 / 3),
        });
    }

    let mut settlements = Vec::new();
    for province in &provinces {
        if province.biome != "ocean" && province.food >= 45
            && roll(request.seed, "settlement-site", province.id.bytes()
                .fold(0_u64, |a,b| a.wrapping_mul(31).wrapping_add(b as u64)), 100) < 18 {
            settlements.push(SettlementSeed {
                id: format!("site_{}", province.id), province_id: province.id.clone(),
                x: province.x, z: province.z, kind: "site".into(),
            });
        }
    }
    settlements.sort_by(|a,b| a.id.cmp(&b.id));
    Ok(GeneratedWorld {
        seed: request.seed, width: request.width, height: request.height,
        regions, provinces, settlements,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_seed_generates_identical_geography() {
        let request = WorldGenRequest { seed: 77, width: 512, height: 512,
            region_count: 8, provinces_per_region: 6 };
        assert_eq!(generate_world(&request).unwrap(), generate_world(&request).unwrap());
    }

    #[test]
    fn generated_provinces_belong_to_geographic_regions_only() {
        let request = WorldGenRequest { seed: 11, width: 256, height: 256,
            region_count: 5, provinces_per_region: 8 };
        let world = generate_world(&request).unwrap();
        let ids: BTreeSet<_> = world.regions.iter().map(|r| r.id.as_str()).collect();
        let province_ids: BTreeSet<_> = world.provinces.iter().map(|p| p.id.as_str()).collect();
        assert_eq!(world.provinces.len(), 40);
        assert!(world.provinces.iter().all(|p| ids.contains(p.region_id.as_str())));
        assert!(world.settlements.iter().all(|s| province_ids.contains(s.province_id.as_str())));
        assert!(world.settlements.iter().all(|s| s.kind == "site"));
        assert!(world.regions.iter().all(|r| !r.id.contains("realm")));
    }

    #[test]
    fn rejects_unsafe_generation_sizes() {
        let request = WorldGenRequest { seed: 1, width: 16, height: 16,
            region_count: 3, provinces_per_region: 4 };
        assert_eq!(generate_world(&request), Err(WorldGenError::InvalidDimensions));
    }
}
