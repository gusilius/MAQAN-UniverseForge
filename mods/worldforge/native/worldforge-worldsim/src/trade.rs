//! Pure deterministic trade-candidate ranking over immutable settlement market snapshots.
//! Java retains route creation, cargo escrow, stock mutation, treasury settlement and persistence.
use serde_json::{json, Value};
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap, HashSet};

#[derive(Clone)]
struct Resource {
    id: String,
    price: f64,
    stock: f64,
    capacity: f64,
    consumption: f64,
}
struct City {
    id: String,
    trade_rate: f64,
    resources: Vec<Resource>,
    by_resource: HashMap<String, usize>,
}
struct Candidate {
    // Keep only stable indexes while ranking. Cloning three Strings for every
    // retained candidate creates avoidable allocator traffic in large markets.
    from_index: usize,
    to_index: usize,
    resource_index: usize,
    score: f64,
    sequence: usize,
}

// BinaryHeap keeps the worst retained candidate at its root. This lets the
// market scan retain only the requested top-K rows instead of allocating every
// possible city-pair/resource combination.
impl PartialEq for Candidate {
    fn eq(&self, other: &Self) -> bool {
        self.score.total_cmp(&other.score) == Ordering::Equal && self.sequence == other.sequence
    }
}
impl Eq for Candidate {}
impl PartialOrd for Candidate {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> { Some(self.cmp(other)) }
}
impl Ord for Candidate {
    fn cmp(&self, other: &Self) -> Ordering {
        other.score.total_cmp(&self.score).then_with(|| self.sequence.cmp(&other.sequence))
    }
}

fn number(v: &Value, key: &str) -> Result<f64, String> {
    let value = v.get(key).and_then(Value::as_f64).ok_or_else(|| format!("missing numeric field {key}"))?;
    if !value.is_finite() { return Err(format!("non-finite field {key}")); }
    Ok(value)
}

fn parse_city(value: &Value) -> Result<City, String> {
    let id = value.get("id").and_then(Value::as_str).filter(|s| !s.trim().is_empty())
        .ok_or("missing city id")?.to_owned();
    let trade_rate = number(value, "trade_rate")?;
    if trade_rate < 0.0 { return Err(format!("negative trade rate for {id}")); }
    let rows = value.get("resources").and_then(Value::as_array).ok_or("missing city resources")?;
    if rows.len() > 100_000 { return Err("too many resources in city".into()); }
    let mut resources = Vec::with_capacity(rows.len());
    let mut by_resource = HashMap::with_capacity(rows.len());
    for row in rows {
        let resource_id = row.get("id").and_then(Value::as_str).filter(|s| !s.trim().is_empty())
            .ok_or("missing resource id")?.to_owned();
        let resource = Resource {
            id: resource_id.clone(),
            price: number(row, "price")?,
            stock: number(row, "stock")?,
            capacity: number(row, "capacity")?,
            consumption: number(row, "consumption")?,
        };
        if resource.price < 0.0 || resource.stock < 0.0 || resource.capacity < 0.0 || resource.consumption < 0.0 {
            return Err(format!("negative market value for {id}/{resource_id}"));
        }
        if by_resource.insert(resource_id.clone(), resources.len()).is_some() {
            return Err(format!("duplicate resource {id}/{resource_id}"));
        }
        resources.push(resource);
    }
    Ok(City { id, trade_rate, resources, by_resource })
}

pub fn compute(bytes: &[u8]) -> Result<Vec<u8>, String> {
    let request: Value = serde_json::from_slice(bytes).map_err(|e| e.to_string())?;
    if request.get("schema_version").and_then(Value::as_u64) != Some(1) {
        return Err("unsupported trade schema".into());
    }
    if request.get("operation").and_then(Value::as_str) != Some("suggestions") {
        return Err("unsupported trade operation".into());
    }
    let limit = request.get("limit").and_then(Value::as_u64).ok_or("missing suggestion limit")? as usize;
    if limit > 100_000 { return Err("suggestion limit exceeds bound".into()); }
    let rows = request.get("cities").and_then(Value::as_array).ok_or("missing cities")?;
    if rows.len() > 10_000 { return Err("too many cities in trade snapshot".into()); }
    let mut cities = Vec::with_capacity(rows.len());
    let mut ids = HashSet::with_capacity(rows.len());
    for row in rows {
        let city = parse_city(row)?;
        if !ids.insert(city.id.clone()) { return Err(format!("duplicate city id {}", city.id)); }
        cities.push(city);
    }

    if limit == 0 {
        return serde_json::to_vec(&json!({"status":"ok","suggestions":[]})).map_err(|e| e.to_string());
    }

    let mut candidates = BinaryHeap::<Candidate>::with_capacity(limit.min(4096));
    let mut sequence = 0usize;
    for (from_index, from) in cities.iter().enumerate() {
        if from.trade_rate <= 0.0 { continue; }
        for (to_index, to) in cities.iter().enumerate() {
            if from_index == to_index || to.trade_rate <= 0.0 { continue; }
            for (resource_index, source) in from.resources.iter().enumerate() {
                if source.price <= 0.0 { continue; }
                let Some(destination_index) = to.by_resource.get(&source.id) else { continue; };
                let destination = &to.resources[*destination_index];
                let export_reserve = (20.0_f64).max(source.consumption * 120.0);
                // Match the Java market policy exactly at every boundary. Epsilon
                // tolerances here can create routes Java would reject (or vice versa).
                if destination.price < source.price * 1.08
                    || source.stock < export_reserve
                    || destination.stock >= source.stock * 0.5
                    || destination.capacity - destination.stock < 1.0 {
                    continue;
                }
                let score = destination.price / source.price;
                // Both prices can be finite while their ratio overflows. Never
                // serialize a non-finite ranking score into a malformed result.
                if !score.is_finite() { continue; }
                candidates.push(Candidate {
                    from_index, to_index, resource_index, score, sequence,
                });
                sequence = sequence.saturating_add(1);
                if candidates.len() > limit { candidates.pop(); }
            }
        }
    }
    // Restore descending score and original snapshot traversal order for ties,
    // exactly matching Java's stable sort while keeping memory bounded by limit.
    let mut selected = candidates.into_vec();
    selected.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.sequence.cmp(&b.sequence)));
    let result = selected.into_iter().map(|candidate| json!({
        "from": cities[candidate.from_index].id,
        "to": cities[candidate.to_index].id,
        "resource": cities[candidate.from_index].resources[candidate.resource_index].id,
        "score": candidate.score
    })).collect::<Vec<_>>();
    serde_json::to_vec(&json!({"status":"ok","suggestions":result})).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn snapshot() -> Vec<u8> {
        br#"{"schema_version":1,"operation":"suggestions","limit":10,"cities":[
            {"id":"A","trade_rate":1.0,"resources":[{"id":"wood","price":1.0,"stock":150.0,"capacity":200.0,"consumption":0.0}]},
            {"id":"B","trade_rate":1.0,"resources":[{"id":"wood","price":2.0,"stock":12.0,"capacity":200.0,"consumption":0.0}]},
            {"id":"C","trade_rate":1.0,"resources":[{"id":"wood","price":3.0,"stock":100.0,"capacity":200.0,"consumption":0.0}]}
        ]}"#.to_vec()
    }
    #[test] fn ranks_valid_supply_routes_and_rejects_overstocked_destination() {
        let output: Value = serde_json::from_slice(&compute(&snapshot()).unwrap()).unwrap();
        let rows = output["suggestions"].as_array().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["from"], "A");
        assert_eq!(rows[0]["to"], "B");
        assert_eq!(rows[0]["resource"], "wood");
        assert_eq!(rows[0]["score"].as_f64().unwrap(), 2.0);
    }
    #[test] fn stable_ties_keep_snapshot_order() {
        let input = br#"{"schema_version":1,"operation":"suggestions","limit":10,"cities":[
          {"id":"A","trade_rate":1,"resources":[{"id":"wood","price":1,"stock":100,"capacity":200,"consumption":0},{"id":"stone","price":1,"stock":100,"capacity":200,"consumption":0}]},
          {"id":"B","trade_rate":1,"resources":[{"id":"wood","price":2,"stock":0,"capacity":200,"consumption":0},{"id":"stone","price":2,"stock":0,"capacity":200,"consumption":0}]}
        ]}"#;
        let output: Value = serde_json::from_slice(&compute(input).unwrap()).unwrap();
        let rows = output["suggestions"].as_array().unwrap();
        assert_eq!(rows[0]["resource"], "wood");
        assert_eq!(rows[1]["resource"], "stone");
    }
    #[test] fn rejects_duplicate_ids_and_invalid_market_values() {
        let duplicate = br#"{"schema_version":1,"operation":"suggestions","limit":5,"cities":[{"id":"A","trade_rate":1,"resources":[]},{"id":"A","trade_rate":1,"resources":[]}]}"#;
        assert!(compute(duplicate).is_err());
        let bad = br#"{"schema_version":1,"operation":"suggestions","limit":5,"cities":[{"id":"A","trade_rate":1,"resources":[{"id":"wood","price":-1,"stock":1,"capacity":2,"consumption":0}]}]}"#;
        assert!(compute(bad).is_err());
    }
    #[test] fn zero_limit_returns_empty_without_candidate_materialization() {
        let input = br#"{"schema_version":1,"operation":"suggestions","limit":0,"cities":[
          {"id":"A","trade_rate":1,"resources":[{"id":"wood","price":1,"stock":100,"capacity":200,"consumption":0}]},
          {"id":"B","trade_rate":1,"resources":[{"id":"wood","price":2,"stock":0,"capacity":200,"consumption":0}]}
        ]}"#;
        let output: Value = serde_json::from_slice(&compute(input).unwrap()).unwrap();
        assert_eq!(output["suggestions"].as_array().unwrap().len(), 0);
    }
    #[test] fn bounded_top_k_keeps_earliest_candidate_for_equal_scores() {
        let input = br#"{"schema_version":1,"operation":"suggestions","limit":1,"cities":[
          {"id":"A","trade_rate":1,"resources":[{"id":"wood","price":1,"stock":100,"capacity":200,"consumption":0}]},
          {"id":"B","trade_rate":1,"resources":[{"id":"wood","price":2,"stock":0,"capacity":200,"consumption":0}]},
          {"id":"C","trade_rate":1,"resources":[{"id":"wood","price":2,"stock":0,"capacity":200,"consumption":0}]}
        ]}"#;
        let output: Value = serde_json::from_slice(&compute(input).unwrap()).unwrap();
        let rows = output["suggestions"].as_array().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0]["from"], "A");
        assert_eq!(rows[0]["to"], "B");
    }
    #[test] fn skips_non_finite_price_ratio() {
        let input = br#"{"schema_version":1,"operation":"suggestions","limit":5,"cities":[
          {"id":"A","trade_rate":1,"resources":[{"id":"wood","price":1e-308,"stock":100,"capacity":200,"consumption":0}]},
          {"id":"B","trade_rate":1,"resources":[{"id":"wood","price":1e308,"stock":0,"capacity":200,"consumption":0}]}
        ]}"#;
        let output: Value = serde_json::from_slice(&compute(input).unwrap()).unwrap();
        assert_eq!(output["suggestions"].as_array().unwrap().len(), 0);
    }
    #[test] fn strict_market_thresholds_match_java_policy() {
        fn request(source_price:f64, destination_price:f64, source_stock:f64,
                   destination_stock:f64, destination_capacity:f64) -> Vec<u8> {
            serde_json::to_vec(&json!({"schema_version":1,"operation":"suggestions","limit":5,"cities":[
                {"id":"A","trade_rate":1.0,"resources":[{"id":"wood","price":source_price,"stock":source_stock,"capacity":200.0,"consumption":0.0}]},
                {"id":"B","trade_rate":1.0,"resources":[{"id":"wood","price":destination_price,"stock":destination_stock,"capacity":destination_capacity,"consumption":0.0}]}
            ]})).unwrap()
        }
        let below_spread = request(100.0, 108.0 - 5e-13, 100.0, 0.0, 200.0);
        let below_reserve = request(100.0, 108.0, 20.0 - 5e-10, 0.0, 200.0);
        let below_half_stock = request(100.0, 108.0, 100.0, 50.0 - 5e-10, 200.0);
        let below_capacity = request(100.0, 108.0, 100.0, 0.0, 1.0 - 5e-10);
        let count = |bytes:&[u8]| -> usize {
            let value:Value = serde_json::from_slice(&compute(bytes).unwrap()).unwrap();
            value["suggestions"].as_array().unwrap().len()
        };
        assert_eq!(count(&below_spread), 0, "price spread below 1.08 is rejected without epsilon");
        assert_eq!(count(&below_reserve), 0, "source stock below reserve is rejected without epsilon");
        assert_eq!(count(&below_half_stock), 1, "destination below half source stock remains eligible");
        assert_eq!(count(&below_capacity), 0, "free capacity below one unit is rejected without epsilon");
    }
    #[test] fn bounded_large_market_catalog_is_deterministic() {
        let mut cities = Vec::new();
        for i in 0..300 {
            // First half exports from a stocked market; second half is genuinely undersupplied.
            let stock = if i < 150 { 100.0 } else { 0.0 };
            cities.push(json!({"id":format!("city-{i:03}"),"trade_rate":1.0,"resources":[
                {"id":"wood","price":1.0 + i as f64 * 0.01,"stock":stock,"capacity":200.0,"consumption":0.0}
            ]}));
        }
        let request = json!({"schema_version":1,"operation":"suggestions","limit":100,"cities":cities});
        let bytes = serde_json::to_vec(&request).unwrap();
        let first = compute(&bytes).unwrap();
        let second = compute(&bytes).unwrap();
        assert_eq!(first, second);
        let result: Value = serde_json::from_slice(&first).unwrap();
        assert_eq!(result["suggestions"].as_array().unwrap().len(), 100);
    }
}
