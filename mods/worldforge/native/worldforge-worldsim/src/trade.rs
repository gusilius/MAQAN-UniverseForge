//! Pure deterministic trade-candidate ranking over immutable settlement market snapshots.
//! Java retains route creation, cargo escrow, stock mutation, treasury settlement and persistence.
use serde_json::{json, Value};
use std::cmp::Ordering;
use std::collections::{HashMap, HashSet};

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
    from: String,
    to: String,
    resource: String,
    score: f64,
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

    let mut candidates = Vec::<Candidate>::new();
    for from in &cities {
        if from.trade_rate <= 0.0 { continue; }
        for to in &cities {
            if from.id == to.id || to.trade_rate <= 0.0 { continue; }
            for source in &from.resources {
                if source.price <= 0.0 { continue; }
                let Some(destination_index) = to.by_resource.get(&source.id) else { continue; };
                let destination = &to.resources[*destination_index];
                let export_reserve = (20.0_f64).max(source.consumption * 120.0);
                if destination.price + 1e-12 < source.price * 1.08
                    || source.stock + 1e-9 < export_reserve
                    || destination.stock + 1e-9 >= source.stock * 0.5
                    || destination.capacity - destination.stock + 1e-9 < 1.0 {
                    continue;
                }
                candidates.push(Candidate {
                    from: from.id.clone(), to: to.id.clone(), resource: source.id.clone(),
                    score: destination.price / source.price,
                });
            }
        }
    }
    // Stable sort preserves the source iteration order for equal price ratios, matching Java's stable sort.
    candidates.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(Ordering::Equal));
    let result = candidates.into_iter().take(limit).map(|candidate| json!({
        "from": candidate.from, "to": candidate.to, "resource": candidate.resource, "score": candidate.score
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
    #[test] fn bounded_large_market_catalog_is_deterministic() {
        let mut cities = Vec::new();
        for i in 0..300 {
            cities.push(json!({"id":format!("city-{i:03}"),"trade_rate":1.0,"resources":[
                {"id":"wood","price":1.0 + i as f64 * 0.01,"stock":100.0,"capacity":200.0,"consumption":0.0}
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
