//! Sparse, deterministic social interaction evaluation for population-scale simulation.
//!
//! Caller supplies candidate contacts from a spatial/settlement/relationship index.
//! This module never searches the whole population or creates all possible pairs.
//! Work is bounded by the caller's candidate list and explicit budget.

use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Profile {
    pub id: String,
    /// Catalog-defined traits. Trait names are not hardcoded here.
    pub traits: BTreeSet<String>,
    /// Catalog-defined attributes, normally normalized to a documented scale.
    pub attributes: BTreeMap<String, i16>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Contact {
    pub a: String,
    pub b: String,
    pub context: String,
    pub relationship: i16,
    pub last_contact_tick: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Catalog {
    /// Trait/attribute/context weights supplied by game catalogs.
    pub weights: BTreeMap<String, i16>,
    pub context_base: BTreeMap<String, i16>,
    pub positive_threshold: i16,
    pub negative_threshold: i16,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Outcome {
    pub a: String,
    pub b: String,
    pub context: String,
    pub score: i16,
    pub affinity_delta: i16,
    pub tick: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    EmptyId,
    SelfContact(String),
    DuplicateProfile(String),
    DuplicateContact(String, String),
}

fn stable_hash(seed: u64, tick: u64, a: &str, b: &str, context: &str) -> u64 {
    let mut h = 0xcbf29ce484222325u64 ^ seed ^ tick.rotate_left(17);
    for bytes in [a.as_bytes(), b.as_bytes(), context.as_bytes()] {
        for byte in bytes {
            h ^= *byte as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        h ^= 0xff;
        h = h.wrapping_mul(0x100000001b3);
    }
    h ^= h >> 33;
    h = h.wrapping_mul(0xff51afd7ed558ccd);
    h ^= h >> 33;
    h = h.wrapping_mul(0xc4ceb9fe1a85ec53);
    h ^ (h >> 33)
}

fn profile_score(profile: &Profile, catalog: &Catalog) -> i32 {
    let trait_score: i32 = profile.traits.iter()
        .map(|tag| catalog.weights.get(tag).copied().unwrap_or(0) as i32)
        .sum();
    let attr_score: i32 = profile.attributes.iter()
        .map(|(key, value)| catalog.weights.get(key).copied().unwrap_or(0) as i32
            * (*value as i32) / 100)
        .sum();
    trait_score.saturating_add(attr_score)
}

/// Processes only explicitly supplied unique candidate contacts up to budget.
/// Unknown profile IDs are skipped. No all-pairs expansion occurs.
pub fn evaluate(
    profiles: &[Profile],
    candidates: &[Contact],
    catalog: &Catalog,
    seed: u64,
    tick: u64,
    budget: usize,
) -> Result<Vec<Outcome>, Error> {
    if budget == 0 || candidates.is_empty() { return Ok(Vec::new()); }
    let mut index = BTreeMap::new();
    for profile in profiles {
        if profile.id.trim().is_empty() { return Err(Error::EmptyId); }
        if index.insert(profile.id.as_str(), profile).is_some() {
            return Err(Error::DuplicateProfile(profile.id.clone()));
        }
    }
    let mut seen = BTreeSet::new();
    let mut out = Vec::with_capacity(budget.min(candidates.len()));
    for contact in candidates.iter().take(budget) {
        if contact.a.trim().is_empty() || contact.b.trim().is_empty() { return Err(Error::EmptyId); }
        if contact.a == contact.b { return Err(Error::SelfContact(contact.a.clone())); }
        let (a, b) = if contact.a < contact.b { (&contact.a, &contact.b) } else { (&contact.b, &contact.a) };
        if !seen.insert((a.clone(), b.clone())) { return Err(Error::DuplicateContact(a.clone(), b.clone())); }
        let Some(pa) = index.get(a.as_str()) else { continue };
        let Some(pb) = index.get(b.as_str()) else { continue };
        let mut score = catalog.context_base.get(&contact.context).copied().unwrap_or(0) as i32;
        score = score.saturating_add(profile_score(pa, catalog)).saturating_add(profile_score(pb, catalog));
        score = score.saturating_add((contact.relationship as i32) / 10);
        // Deterministic variability (-5..+5), with no RNG state to persist.
        let jitter = (stable_hash(seed, tick, a, b, &contact.context) % 11) as i32 - 5;
        score = score.saturating_add(jitter).clamp(i16::MIN as i32, i16::MAX as i32);
        let score_i16 = score as i16;
        let affinity_delta = if score_i16 >= catalog.positive_threshold { 1 }
            else if score_i16 <= catalog.negative_threshold { -1 } else { 0 };
        out.push(Outcome { a: a.clone(), b: b.clone(), context: contact.context.clone(),
            score: score_i16, affinity_delta, tick });
    }
    Ok(out)
}


/// Adapter for the canonical WorldSnapshot model. It uses existing Character
/// trait/attribute data and SocialRelation records; it does not create a second
/// identity registry or scan all possible character pairs. Only endpoints in
/// the budgeted relationship slice are projected into temporary profiles.
pub fn evaluate_world_snapshot(
    snapshot: &crate::WorldSnapshot,
    catalog: &Catalog,
    budget: usize,
) -> Result<Vec<Outcome>, Error> {
    if budget == 0 { return Ok(Vec::new()); }
    let mut contacts = Vec::with_capacity(budget.min(snapshot.social_relations.len()));
    let mut required_ids = BTreeSet::new();
    for relation in snapshot.social_relations.iter().take(budget) {
        let context = if relation.married { "marriage" }
            else if relation.dating { "courtship" }
            else if relation.employed { "work" }
            else { "social" };
        contacts.push(Contact {
            a: relation.character_a.clone(),
            b: relation.character_b.clone(),
            context: context.into(),
            relationship: relation.relationship.saturating_add(relation.love).clamp(i16::MIN as i32, i16::MAX as i32) as i16,
            last_contact_tick: relation.last_contact_day,
        });
        required_ids.insert(relation.character_a.as_str());
        required_ids.insert(relation.character_b.as_str());
    }
    if contacts.is_empty() { return Ok(Vec::new()); }
    let mut profiles = Vec::with_capacity(required_ids.len());
    for character in &snapshot.characters {
        if !character.alive || !required_ids.contains(character.id.as_str()) { continue; }
        profiles.push(Profile {
            id: character.id.clone(),
            traits: character.trait_ids.iter().cloned().collect(),
            attributes: character.attributes.iter()
                .map(|(key, value)| (key.clone(), (*value).clamp(i16::MIN as i32, i16::MAX as i32) as i16))
                .collect(),
        });
    }
    evaluate(&profiles, &contacts, catalog, snapshot.seed, snapshot.day, budget)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn profile(id: &str, trait_id: &str) -> Profile {
        Profile { id: id.into(), traits: BTreeSet::from([trait_id.into()]), attributes: BTreeMap::new() }
    }
    fn contact(a: &str, b: &str, context: &str) -> Contact {
        Contact { a: a.into(), b: b.into(), context: context.into(), relationship: 0, last_contact_tick: 0 }
    }
    #[test]
    fn respects_candidate_budget_and_does_not_expand_pairs() {
        let people = vec![profile("a", "kind"), profile("b", "kind"), profile("c", "kind"), profile("d", "kind")];
        let candidates = vec![contact("a", "b", "talk"), contact("a", "c", "talk"), contact("a", "d", "talk")];
        let out = evaluate(&people, &candidates, &Catalog::default(), 10, 20, 2).unwrap();
        assert_eq!(out.len(), 2);
    }
    #[test]
    fn stable_output_is_independent_of_pair_orientation() {
        let people = vec![profile("a", "kind"), profile("b", "kind")];
        let catalog = Catalog { weights: BTreeMap::from([("kind".into(), 4)]),
            context_base: BTreeMap::from([("talk".into(), 2)]), positive_threshold: 1, negative_threshold: -1 };
        let ab = evaluate(&people, &[contact("a", "b", "talk")], &catalog, 7, 90, 1).unwrap();
        let ba = evaluate(&people, &[contact("b", "a", "talk")], &catalog, 7, 90, 1).unwrap();
        assert_eq!(ab, ba);
    }
    #[test]
    fn trait_catalog_weights_drive_affinity() {
        let people = vec![profile("a", "generous"), profile("b", "generous")];
        let catalog = Catalog { weights: BTreeMap::from([("generous".into(), 30)]),
            positive_threshold: 10, negative_threshold: -10, ..Default::default() };
        let out = evaluate(&people, &[contact("a", "b", "unspecified")], &catalog, 1, 5, 1).unwrap();
        assert_eq!(out[0].affinity_delta, 1);
    }
    #[test]
    fn duplicate_candidate_pairs_are_rejected() {
        let people = vec![profile("a", "kind"), profile("b", "kind")];
        assert!(matches!(evaluate(&people, &[contact("a", "b", "talk"), contact("b", "a", "talk")],
            &Catalog::default(), 0, 0, 10), Err(Error::DuplicateContact(_, _))));
    }
    #[test]
    fn missing_profiles_are_skipped_not_allocated() {
        let people = vec![profile("a", "kind")];
        let out = evaluate(&people, &[contact("a", "missing", "talk")], &Catalog::default(), 0, 0, 1).unwrap();
        assert!(out.is_empty());
    }
}
