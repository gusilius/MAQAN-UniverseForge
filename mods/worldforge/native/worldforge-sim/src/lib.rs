//! Deterministic, Minecraft-independent simulation primitives for WorldForge.
//!
//! This crate deliberately has no JNI, Forge, filesystem, clock, or random-number
//! dependency. The host supplies a snapshot and an explicit elapsed-tick count;
//! the same input therefore always produces the same output.

#![forbid(unsafe_code)]

/// Fixed-point resource quantity. One unit is one hundredth of a game resource unit.
pub type Amount = i64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SettlementSnapshot {
    pub population: u32,
    pub food: Amount,
    pub food_capacity: Amount,
    /// Food produced per simulation day by the currently assigned workforce.
    pub daily_food_production: Amount,
    /// Food consumed per 100 residents per simulation day.
    pub daily_food_per_100_people: Amount,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SettlementDelta {
    pub elapsed_days: u32,
    pub produced_food: Amount,
    pub consumed_food: Amount,
    pub food_after: Amount,
    pub shortage_days: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimulationError {
    NegativeFood,
    NegativeCapacity,
    NegativeProduction,
    NegativeConsumptionRate,
}

/// Advances a settlement's food ledger without mutating the source snapshot.
///
/// Consumption is calculated per day using integer fixed-point arithmetic.
/// Production is applied before consumption for each day; storage is capped.
/// Shortage is reported when demand exceeds available food on a given day.
pub fn advance_food(
    state: SettlementSnapshot,
    elapsed_days: u32,
) -> Result<SettlementDelta, SimulationError> {
    if state.food < 0 { return Err(SimulationError::NegativeFood); }
    if state.food_capacity < 0 { return Err(SimulationError::NegativeCapacity); }
    if state.daily_food_production < 0 { return Err(SimulationError::NegativeProduction); }
    if state.daily_food_per_100_people < 0 { return Err(SimulationError::NegativeConsumptionRate); }

    let demand_per_day = (state.population as i64)
        .saturating_mul(state.daily_food_per_100_people)
        .saturating_add(99) / 100;

    let mut stock = state.food.min(state.food_capacity);
    let mut produced_total: Amount = 0;
    let mut consumed_total: Amount = 0;
    let mut shortage_days = 0;

    for _ in 0..elapsed_days {
        let produced = state.daily_food_production.min(state.food_capacity.saturating_sub(stock));
        stock = stock.saturating_add(produced);
        produced_total = produced_total.saturating_add(produced);

        let consumed = demand_per_day.min(stock);
        stock -= consumed;
        consumed_total = consumed_total.saturating_add(consumed);
        if consumed < demand_per_day { shortage_days += 1; }
    }

    Ok(SettlementDelta {
        elapsed_days,
        produced_food: produced_total,
        consumed_food: consumed_total,
        food_after: stock,
        shortage_days,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> SettlementSnapshot {
        SettlementSnapshot {
            population: 100,
            food: 500,
            food_capacity: 1_000,
            daily_food_production: 30,
            daily_food_per_100_people: 20,
        }
    }

    #[test]
    fn production_and_consumption_are_applied_per_day() {
        let result = advance_food(sample(), 3).unwrap();
        assert_eq!(result.produced_food, 90);
        assert_eq!(result.consumed_food, 60);
        assert_eq!(result.food_after, 530);
        assert_eq!(result.shortage_days, 0);
    }

    #[test]
    fn shortage_is_reported_without_negative_stock() {
        let mut state = sample();
        state.food = 10;
        state.daily_food_production = 0;
        let result = advance_food(state, 2).unwrap();
        assert_eq!(result.food_after, 0);
        assert_eq!(result.shortage_days, 2);
    }

    #[test]
    fn stock_never_exceeds_capacity() {
        let mut state = sample();
        state.food = 990;
        state.daily_food_production = 500;
        state.daily_food_per_100_people = 0;
        let result = advance_food(state, 2).unwrap();
        assert_eq!(result.food_after, 1_000);
    }

    #[test]
    fn zero_elapsed_days_is_a_noop() {
        let result = advance_food(sample(), 0).unwrap();
        assert_eq!(result.food_after, 500);
        assert_eq!(result.produced_food, 0);
        assert_eq!(result.consumed_food, 0);
    }

    #[test]
    fn invalid_negative_values_are_rejected() {
        let mut state = sample();
        state.food = -1;
        assert_eq!(advance_food(state, 1), Err(SimulationError::NegativeFood));
    }
}
