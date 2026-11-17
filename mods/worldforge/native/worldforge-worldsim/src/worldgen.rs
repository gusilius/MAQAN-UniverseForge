//! Seeded macro-world generator. Produces geographic and settlement proposals;
//! the Minecraft host materializes the resulting terrain and political data.
use serde::{Deserialize, Serialize};
use crate::stable_hash;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorldGenRequest {
    pub seed: u64,
    pub width: u32,
    pub height: u32,
    pub realm_count: u32,
    pub provinces_per_realm: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct RealmSeed {
    pub id: String,
    pub capital_province: String,
    pub x: u32,
    pub z: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProvinceSeed {
    pub id: String,
    pub realm_id: String,
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
    pub realm_id: String,
    pub province_id: String,
    pub x: u32,
    pub z: u32,
    pub kind: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct GeneratedWorld {
    pub seed: u64,
    pub width: u32,
    pub height: u32,
    pub realms: Vec<RealmSeed>,
    pub provinces: Vec<ProvinceSeed>,
    pub settlements: Vec<SettlementSeed>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum WorldGenError { InvalidDimensions, InvalidRealmCount, InvalidProvinceCount }

fn roll(seed: u64, domain: &str, index: u64, max: u64) -> u64 {
    if max == 0 { return 0; }
    stable_hash(seed, domain, &index.to_string(), "worldgen", 0) % max
}

pub fn generate_world(request: &WorldGenRequest) -> Result<GeneratedWorld, WorldGenError> {
    if request.width < 64 || request.height < 64 || request.width > 8192 || request.height > 8192 {
        return Err(WorldGenError::InvalidDimensions);
    }
    if request.realm_count == 0 || request.realm_count > 128 { return Err(WorldGenError::InvalidRealmCount); }
    if request.provinces_per_realm == 0 || request.provinces_per_realm > 64 { return Err(WorldGenError::InvalidProvinceCount); }

    let mut realms = Vec::new();
    for index in 0..request.realm_count {
        let x = roll(request.seed, "realm-x", index as u64, request.width as u64) as u32;
        let z = roll(request.seed, "realm-z", index as u64, request.height as u64) as u32;
        realms.push(RealmSeed { id: format!("realm_{:03}", index + 1),
            capital_province: format!("province_{:04}", index * request.provinces_per_realm + 1), x, z });
    }

    let mut provinces = Vec::new();
    for index in 0..(request.realm_count * request.provinces_per_realm) {
        let x = roll(request.seed, "province-x", index as u64, request.width as u64) as u32;
        let z = roll(request.seed, "province-z", index as u64, request.height as u64) as u32;
        let nearest = realms.iter().min_by_key(|r| {
            let dx = i64::from(r.x) - i64::from(x);
            let dz = i64::from(r.z) - i64::from(z);
            dx * dx + dz * dz
        }).expect("realm count is validated");
        // The first province in each realm's group is its guaranteed capital province.
        let owner = if index % request.provinces_per_realm == 0 {
            &realms[(index / request.provinces_per_realm) as usize]
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
            id: format!("province_{:04}", index + 1), realm_id: owner.id.clone(), x, z,
            elevation, moisture, temperature, biome: biome.into(),
            food: (moisture / 2).saturating_add(if biome == "plains" { 35 } else { 0 }),
            wood: if biome == "forest" { 90 } else { moisture / 3 },
            stone: if biome == "mountain" { 95 } else { 20 + (elevation.max(0) as u8 / 4) },
            ore: if biome == "mountain" { 65 + (roll(request.seed, "ore", index as u64, 36) as u8) } else { roll(request.seed, "ore", index as u64, 25) as u8 },
            has_river: roll(request.seed, "river", index as u64, 100) < (moisture as u64 / 3),
        });
    }

    let mut settlements = Vec::new();
    for realm in &realms {
        if let Some(capital) = provinces.iter().find(|p| p.id == realm.capital_province) {
            settlements.push(SettlementSeed { id: format!("settlement_{}_capital", realm.id), realm_id: realm.id.clone(),
                province_id: capital.id.clone(), x: capital.x, z: capital.z, kind: "capital".into() });
        }
    }
    for province in &provinces {
        if province.biome != "ocean" && province.food >= 45
            && roll(request.seed, "settlement-site", province.id.bytes().fold(0_u64, |a,b| a.wrapping_mul(31).wrapping_add(b as u64)), 100) < 18 {
            settlements.push(SettlementSeed { id: format!("settlement_{}", province.id), realm_id: province.realm_id.clone(),
                province_id: province.id.clone(), x: province.x, z: province.z, kind: "village_site".into() });
        }
    }
    settlements.sort_by(|a,b| a.id.cmp(&b.id));
    Ok(GeneratedWorld { seed: request.seed, width: request.width, height: request.height, realms, provinces, settlements })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn same_seed_generates_identical_world() {
        let request=WorldGenRequest{seed:77,width:512,height:512,realm_count:8,provinces_per_realm:6};
        assert_eq!(generate_world(&request).unwrap(),generate_world(&request).unwrap());
    }
    #[test] fn generated_provinces_belong_to_existing_realms() {
        let request=WorldGenRequest{seed:11,width:256,height:256,realm_count:5,provinces_per_realm:8};
        let world=generate_world(&request).unwrap();
        let ids:std::collections::BTreeSet<_>=world.realms.iter().map(|r|r.id.as_str()).collect();
        assert_eq!(world.provinces.len(),40);
        assert!(world.provinces.iter().all(|p|ids.contains(p.realm_id.as_str())));
        assert!(world.settlements.iter().all(|s|ids.contains(s.realm_id.as_str())));
    }
    #[test] fn rejects_unsafe_generation_sizes() {
        let request=WorldGenRequest{seed:1,width:16,height:16,realm_count:3,provinces_per_realm:4};
        assert_eq!(generate_world(&request),Err(WorldGenError::InvalidDimensions));
    }
}
