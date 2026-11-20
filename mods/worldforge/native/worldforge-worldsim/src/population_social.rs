//! Sparse, deterministic social interaction evaluation for population-scale simulation.
//!
//! Caller supplies candidate contacts from a spatial/settlement/relationship index.
//! This module never searches the whole population or creates all possible pairs.
//! Work is bounded by the caller's candidate list and explicit budget.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Profile {
    pub id: String,
    /// Fictional species/ancestry identity from the world's race catalogue.
    /// Empty means unknown/unassigned and never creates an automatic penalty.
    pub race_id: String,
    /// Culture is independent from race; shared culture can bridge differences.
    pub culture_id: String,
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
    /// Directed attitudes: (observer race, encountered race) -> opinion [-100, 100].
    /// These are world/culture-specific definitions, not universal race rules.
    pub intergroup_attitudes: BTreeMap<String, BTreeMap<String, i16>>,
    /// Only explicitly catalogued traits contribute to susceptibility to group bias.
    /// Values are signed/weighted; absent traits mean no such predisposition.
    pub bias_susceptibility_traits: BTreeMap<String, i16>,
    /// Cultural affinity can offset intergroup tension without erasing individual history.
    pub shared_culture_affinity: i16,
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


/// Directional intergroup attitude is applied per character, not as a blanket
/// rule for a race. Neutral/missing race IDs and missing catalogue entries add
/// zero. A character must have an explicitly configured susceptibility trait
/// before a negative group attitude can affect the contact score.
fn intergroup_score(observer: &Profile, encountered: &Profile, catalog: &Catalog) -> i32 {
    if observer.race_id.is_empty() || encountered.race_id.is_empty()
        || observer.race_id == encountered.race_id {
        return 0;
    }
    let attitude = catalog.intergroup_attitudes
        .get(&observer.race_id)
        .and_then(|targets| targets.get(&encountered.race_id))
        .copied().unwrap_or(0) as i32;
    if attitude == 0 { return 0; }
    let susceptibility: i32 = observer.traits.iter()
        .map(|trait_id| catalog.bias_susceptibility_traits.get(trait_id).copied().unwrap_or(0) as i32)
        .sum();
    let bias = attitude.saturating_mul(susceptibility) / 100;
    let cultural_bridge = if !observer.culture_id.is_empty()
        && observer.culture_id == encountered.culture_id {
        catalog.shared_culture_affinity as i32
    } else { 0 };
    bias.saturating_add(cultural_bridge)
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
    let mut index = HashMap::with_capacity(profiles.len());
    for profile in profiles {
        if profile.id.trim().is_empty() { return Err(Error::EmptyId); }
        if index.insert(profile.id.as_str(), profile).is_some() {
            return Err(Error::DuplicateProfile(profile.id.clone()));
        }
    }
    let mut seen = HashSet::with_capacity(budget.min(candidates.len()));
    let mut out = Vec::with_capacity(budget.min(candidates.len()));
    for contact in candidates.iter().take(budget) {
        if contact.a.trim().is_empty() || contact.b.trim().is_empty() { return Err(Error::EmptyId); }
        if contact.a == contact.b { return Err(Error::SelfContact(contact.a.clone())); }
        let Some((a_index, pa)) = index.get_key_value(contact.a.as_str()) else { continue };
        let Some((b_index, pb)) = index.get_key_value(contact.b.as_str()) else { continue };
        // Deduplicate using stable profile positions, avoiding per-contact String clones.
        let pair = if a_index < b_index { (a_index.as_ptr() as usize, b_index.as_ptr() as usize) }
            else { (b_index.as_ptr() as usize, a_index.as_ptr() as usize) };
        if !seen.insert(pair) { return Err(Error::DuplicateContact(contact.a.clone(), contact.b.clone())); }
        let (a, b) = if contact.a < contact.b { (&contact.a, &contact.b) } else { (&contact.b, &contact.a) };
        let mut score = catalog.context_base.get(&contact.context).copied().unwrap_or(0) as i32;
        score = score.saturating_add(profile_score(pa, catalog)).saturating_add(profile_score(pb, catalog));
        // Apply both directions independently: one character may be prejudiced
        // while the other is welcoming. No race-wide/global hostility is implied.
        score = score.saturating_add(intergroup_score(pa, pb, catalog));
        score = score.saturating_add(intergroup_score(pb, pa, catalog));
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


/// Reusable borrowed index over the canonical Character collection.
/// Build once per immutable snapshot and reuse for many bounded social batches.
/// It stores references, not cloned Characters or duplicate identity records.
pub struct CharacterProfileIndex<'a> {
    // One pointer per canonical character; lower overhead than a million-entry
    // hash table. Sorted once, then queried with allocation-free binary search.
    by_id: Vec<&'a crate::Character>,
}

impl<'a> CharacterProfileIndex<'a> {
    pub fn new(characters: &'a [crate::Character]) -> Result<Self, Error> {
        let mut by_id: Vec<&crate::Character> = Vec::with_capacity(characters.len());
        for character in characters {
            if character.id.trim().is_empty() { return Err(Error::EmptyId); }
            by_id.push(character);
        }
        by_id.sort_unstable_by(|a, b| a.id.cmp(&b.id));
        for pair in by_id.windows(2) {
            if pair[0].id == pair[1].id {
                return Err(Error::DuplicateProfile(pair[0].id.clone()));
            }
        }
        Ok(Self { by_id })
    }

    pub fn len(&self) -> usize { self.by_id.len() }
    pub fn is_empty(&self) -> bool { self.by_id.is_empty() }

    fn project(&self, id: &str) -> Option<Profile> {
        let position = self.by_id.binary_search_by(|character| character.id.as_str().cmp(id)).ok()?;
        let character = self.by_id[position];
        if !character.alive { return None; }
        Some(Profile {
            id: character.id.clone(),
            race_id: character.race_id.clone(),
            culture_id: character.culture_id.clone(),
            traits: character.trait_ids.iter().cloned().collect(),
            attributes: character.attributes.iter()
                .map(|(key, value)| (key.clone(), (*value).clamp(i16::MIN as i32, i16::MAX as i32) as i16))
                .collect(),
        })
    }
}

/// Fair bounded cursor. Advancing the cursor prevents a fixed prefix of the
/// relationship list from monopolizing every simulation batch.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct SocialCursor {
    next_relation: usize,
}

impl SocialCursor {
    pub fn position(&self) -> usize { self.next_relation }
    pub fn reset(&mut self) { self.next_relation = 0; }
}

/// Evaluates at most budget relationships using a reusable Character index.
/// Cost per batch is O(budget + number of distinct endpoints), not O(population).
pub fn evaluate_indexed_snapshot(
    snapshot: &crate::WorldSnapshot,
    index: &CharacterProfileIndex<'_>,
    catalog: &Catalog,
    cursor: &mut SocialCursor,
    budget: usize,
) -> Result<Vec<Outcome>, Error> {
    let total = snapshot.social_relations.len();
    if budget == 0 || total == 0 { return Ok(Vec::new()); }
    let count = budget.min(total);
    let start = cursor.next_relation % total;
    let mut contacts = Vec::with_capacity(count);
    let mut profiles_by_id: HashMap<String, Profile> = HashMap::with_capacity(count.saturating_mul(2));
    for offset in 0..count {
        let relation = &snapshot.social_relations[(start + offset) % total];
        let context = if relation.married { "marriage" }
            else if relation.dating { "courtship" }
            else if relation.employed { "work" }
            else { "social" };
        contacts.push(Contact {
            a: relation.character_a.clone(),
            b: relation.character_b.clone(),
            context: context.into(),
            relationship: relation.relationship.saturating_add(relation.love)
                .clamp(i16::MIN as i32, i16::MAX as i32) as i16,
            last_contact_tick: relation.last_contact_day,
        });
        for id in [&relation.character_a, &relation.character_b] {
            if !profiles_by_id.contains_key(id.as_str()) {
                if let Some(profile) = index.project(id) {
                    profiles_by_id.insert(id.clone(), profile);
                }
            }
        }
    }
    cursor.next_relation = (start + count) % total;
    let profiles: Vec<Profile> = profiles_by_id.into_values().collect();
    evaluate(&profiles, &contacts, catalog, snapshot.seed, snapshot.day, budget)
}

/// Convenience adapter for one-off use. Repeated callers should construct
/// CharacterProfileIndex once and use evaluate_indexed_snapshot instead.
pub fn evaluate_world_snapshot(
    snapshot: &crate::WorldSnapshot,
    catalog: &Catalog,
    budget: usize,
) -> Result<Vec<Outcome>, Error> {
    let index = CharacterProfileIndex::new(&snapshot.characters)?;
    evaluate_indexed_snapshot(snapshot, &index, catalog, &mut SocialCursor::default(), budget)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn profile(id: &str, trait_id: &str) -> Profile {
        Profile { id: id.into(), traits: BTreeSet::from([trait_id.into()]), attributes: BTreeMap::new(), ..Default::default() }
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
            context_base: BTreeMap::from([("talk".into(), 2)]), positive_threshold: 1, negative_threshold: -1, ..Default::default() };
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

    #[test]
    fn canonical_snapshot_adapter_uses_traits_attributes_and_work_context() {
        use crate::{Character, SocialRelation, WorldSnapshot};
        let mut a = Character { id: "a".into(), realm_id: "r".into(), alive: true, ..Default::default() };
        a.trait_ids.push("generous".into());
        a.attributes.insert("empathy".into(), 100);
        let mut b = Character { id: "b".into(), realm_id: "r".into(), alive: true, ..Default::default() };
        b.trait_ids.push("generous".into());
        let snapshot = WorldSnapshot {
            world_id: "w".into(), seed: 9, day: 12, realms: vec![], titles: vec![],
            government_definitions: vec![], realm_governments: vec![], borders: vec![],
            settlements: vec![], characters: vec![a,b], workforce: vec![], capabilities: vec![],
            catalog_entries: vec![], diplomatic_relations: vec![], vassal_contracts: vec![],
            wars: vec![], armies: vec![],
            social_relations: vec![SocialRelation { character_a: "a".into(), character_b: "b".into(),
                relationship: 40, love: 10, married: false, dating: false, employed: true, last_contact_day: 8 }],
            named_entities: vec![], geography: vec![],
        };
        let catalog = Catalog { weights: BTreeMap::from([("generous".into(), 20), ("empathy".into(), 20)]),
            context_base: BTreeMap::from([("work".into(), 5)]), positive_threshold: 1, negative_threshold: -1, ..Default::default() };
        let outcomes = evaluate_world_snapshot(&snapshot, &catalog, 10).unwrap();
        assert_eq!(outcomes.len(), 1);
        assert_eq!(outcomes[0].context, "work");
        assert_eq!(outcomes[0].affinity_delta, 1);
    }

    #[test]
    fn snapshot_adapter_limits_relationships_before_processing() {
        use crate::{Character, SocialRelation, WorldSnapshot};
        let chars = (0..20).map(|i| Character { id: format!("p{i}"), realm_id: "r".into(), alive: true, ..Default::default() }).collect();
        let relations = (0..20).map(|i| SocialRelation { character_a: format!("p{i}"), character_b: format!("p{}", i+1),
            relationship: 0, love: 0, married: false, dating: false, employed: false, last_contact_day: 0 }).collect();
        let snapshot = WorldSnapshot { world_id: "w".into(), seed: 1, day: 1, realms: vec![],
            titles: vec![], government_definitions: vec![], realm_governments: vec![], borders: vec![],
            settlements: vec![], characters: chars, workforce: vec![], capabilities: vec![],
            catalog_entries: vec![], diplomatic_relations: vec![], vassal_contracts: vec![],
            wars: vec![], armies: vec![], social_relations: relations, named_entities: vec![], geography: vec![] };
        let out = evaluate_world_snapshot(&snapshot, &Catalog::default(), 3).unwrap();
        assert_eq!(out.len(), 3);
    }


    #[test]
    fn intergroup_attitude_only_affects_characters_with_susceptibility_traits() {
        let mut a = profile("a", "prejudiced");
        a.race_id = "folk_a".into();
        let mut b = profile("b", "kind");
        b.race_id = "folk_b".into();
        let catalog = Catalog {
            intergroup_attitudes: BTreeMap::from([("folk_a".into(), BTreeMap::from([("folk_b".into(), -80)]))]),
            bias_susceptibility_traits: BTreeMap::from([("prejudiced".into(), 100)]),
            positive_threshold: 1, negative_threshold: -1, ..Default::default()
        };
        let out = evaluate(&[a.clone(), b.clone()], &[contact("a", "b", "talk")], &catalog, 5, 10, 1).unwrap();
        a.traits.clear();
        let neutral = evaluate(&[a, b], &[contact("a", "b", "talk")], &catalog, 5, 10, 1).unwrap();
        assert!(out[0].score < neutral[0].score, "explicit prejudice should lower the contact score");
    }

    #[test]
    fn shared_culture_can_bridge_but_not_erase_intergroup_tension() {
        let mut a = profile("a", "prejudiced");
        a.race_id = "folk_a".into();
        a.culture_id = "river_culture".into();
        let mut b = profile("b", "kind");
        b.race_id = "folk_b".into();
        b.culture_id = "river_culture".into();
        let catalog = Catalog {
            intergroup_attitudes: BTreeMap::from([("folk_a".into(), BTreeMap::from([("folk_b".into(), -50)]))]),
            bias_susceptibility_traits: BTreeMap::from([("prejudiced".into(), 100)]),
            shared_culture_affinity: 20,
            positive_threshold: 1, negative_threshold: -1, ..Default::default()
        };
        let out = evaluate(&[a,b], &[contact("a", "b", "talk")], &catalog, 3, 4, 1).unwrap();
        assert_eq!(out[0].affinity_delta, -1);
    }

    #[test]
    fn missing_race_or_attitude_catalog_is_neutral() {
        let mut a = profile("a", "prejudiced");
        a.race_id = "folk_a".into();
        let b = profile("b", "kind");
        let catalog = Catalog { bias_susceptibility_traits: BTreeMap::from([("prejudiced".into(), 100)]),
            positive_threshold: 1, negative_threshold: -1, ..Default::default() };
        let out = evaluate(&[a.clone(),b.clone()], &[contact("a", "b", "talk")], &catalog, 1, 1, 1).unwrap();
        let baseline = evaluate(&[profile("a", "prejudiced"),b], &[contact("a", "b", "talk")],
            &Catalog::default(), 1, 1, 1).unwrap();
        assert_eq!(out[0].score, baseline[0].score, "missing intergroup data must be neutral");
    }


    #[test]
    fn reusable_index_and_cursor_process_all_relationships_without_starvation() {
        use crate::{Character, SocialRelation, WorldSnapshot};
        let chars = (0..6).map(|i| Character { id: format!("p{i}"), realm_id: "r".into(), alive: true, ..Default::default() }).collect::<Vec<_>>();
        let relations = (0..6).map(|i| SocialRelation { character_a: format!("p{i}"),
            character_b: format!("p{}", (i+1)%6), relationship: 0, love: 0,
            married: false, dating: false, employed: false, last_contact_day: 0 }).collect();
        let snapshot = WorldSnapshot { world_id: "w".into(), seed: 1, day: 1, realms: vec![],
            titles: vec![], government_definitions: vec![], realm_governments: vec![], borders: vec![],
            settlements: vec![], characters: chars, workforce: vec![], capabilities: vec![],
            catalog_entries: vec![], diplomatic_relations: vec![], vassal_contracts: vec![],
            wars: vec![], armies: vec![], social_relations: relations, named_entities: vec![], geography: vec![] };
        let index = CharacterProfileIndex::new(&snapshot.characters).unwrap();
        assert_eq!(index.len(), 6);
        let mut cursor = SocialCursor::default();
        let first = evaluate_indexed_snapshot(&snapshot, &index, &mut Catalog::default(), &mut cursor, 2).unwrap();
        let second = evaluate_indexed_snapshot(&snapshot, &index, &mut Catalog::default(), &mut cursor, 2).unwrap();
        let third = evaluate_indexed_snapshot(&snapshot, &index, &mut Catalog::default(), &mut cursor, 2).unwrap();
        assert_eq!(first.len()+second.len()+third.len(), 6);
        assert_eq!(cursor.position(), 0);
    }

    #[test]
    fn reusable_index_rejects_duplicate_canonical_ids() {
        use crate::Character;
        let chars = vec![
            Character { id: "same".into(), ..Default::default() },
            Character { id: "same".into(), ..Default::default() },
        ];
        assert!(matches!(CharacterProfileIndex::new(&chars), Err(Error::DuplicateProfile(_))));
    }

}
