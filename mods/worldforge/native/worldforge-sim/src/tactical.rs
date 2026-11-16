//! Deterministic tactical decision proposals for the RTS command layer.
//!
//! This module is deliberately a pure function over an immutable snapshot.
//! It never issues Minecraft orders, resolves command authority, looks up
//! entities, or mutates world state. The Java host must validate the echoed
//! revision, commander authority, permissions, targets, and current squad state
//! before applying any proposal.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandMode {
    /// Player owns the order. Rust may only provide a non-binding suggestion.
    PlayerDirect,
    /// Commander may adapt tactically, but should prefer preserving force.
    Delegated,
    /// Commander may take greater tactical risk within the supplied permissions.
    Autonomous,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CurrentOrder {
    Hold,
    Move,
    Attack,
    AttackMove,
    Patrol,
    Follow,
    Guard,
    Retreat,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TacticalAction {
    MaintainOrder,
    Reform,
    EngageVisibleThreat,
    Pursue,
    Retreat,
    Reposition,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DecisionReason {
    PlayerAuthority,
    NoActionableThreat,
    ObjectiveOutsideLeash,
    FormationDisrupted,
    FlankPressure,
    MoraleCritical,
    SupplyCritical,
    FavorableEngagement,
    EngagementTooRisky,
    RetreatingThreat,
    PursuitNotAuthorized,
    ObjectiveSecure,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TacticalSnapshot {
    /// Monotonic source revision. The host must reject results for stale revisions.
    pub revision: u64,
    pub mode: CommandMode,
    pub current_order: CurrentOrder,
    /// Normalized [0, 100] values supplied by the host.
    pub morale: u8,
    pub supply: u8,
    pub cohesion: u8,
    pub flank_pressure: u8,
    pub own_strength: u32,
    pub visible_threat_strength: u32,
    pub objective_distance_blocks: u16,
    /// Zero means no distance leash is configured.
    pub leash_blocks: u16,
    pub threat_visible: bool,
    pub threat_retreating: bool,
    pub allow_engagement: bool,
    pub allow_pursuit: bool,
    pub allow_retreat: bool,
    pub allow_reform: bool,
    pub allow_reposition: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TacticalDecision {
    /// Must match TacticalSnapshot::revision exactly.
    pub source_revision: u64,
    pub action: TacticalAction,
    /// Stable normalized scores for UI ordering, not probabilities.
    pub priority: u8,
    pub confidence: u8,
    /// True only for PLAYER_DIRECT; this proposal must never replace the player's order.
    pub advisory_only: bool,
    pub reason: DecisionReason,
}

const fn clamp_score(value: u16) -> u8 {
    if value > 100 { 100 } else { value as u8 }
}

fn decision(
    snapshot: TacticalSnapshot,
    action: TacticalAction,
    priority: u16,
    confidence: u16,
    reason: DecisionReason,
) -> TacticalDecision {
    TacticalDecision {
        source_revision: snapshot.revision,
        action,
        priority: clamp_score(priority),
        confidence: clamp_score(confidence),
        advisory_only: snapshot.mode == CommandMode::PlayerDirect,
        reason,
    }
}

fn within_leash(snapshot: TacticalSnapshot) -> bool {
    snapshot.leash_blocks == 0
        || snapshot.objective_distance_blocks <= snapshot.leash_blocks
}

/// Computes one conservative, deterministic tactical proposal from a frozen squad snapshot.
///
/// Policy permissions are included as input to avoid proposing disallowed actions, but
/// they are not an authority boundary: Java must re-check the current policy at apply time.
/// No target/entity references or world mutation cross this boundary.
pub fn evaluate_tactical(snapshot: TacticalSnapshot) -> TacticalDecision {
    if snapshot.mode == CommandMode::PlayerDirect {
        return decision(
            snapshot,
            TacticalAction::MaintainOrder,
            0,
            100,
            DecisionReason::PlayerAuthority,
        );
    }

    if snapshot.morale <= 18 && snapshot.allow_retreat {
        return decision(
            snapshot,
            TacticalAction::Retreat,
            100,
            94,
            DecisionReason::MoraleCritical,
        );
    }

    if snapshot.supply <= 8 && snapshot.morale <= 35 && snapshot.allow_retreat {
        return decision(
            snapshot,
            TacticalAction::Retreat,
            92,
            86,
            DecisionReason::SupplyCritical,
        );
    }

    if !within_leash(snapshot) && snapshot.allow_reposition {
        return decision(
            snapshot,
            TacticalAction::Reposition,
            88,
            91,
            DecisionReason::ObjectiveOutsideLeash,
        );
    }

    if snapshot.cohesion < 32 && snapshot.allow_reform {
        return decision(
            snapshot,
            TacticalAction::Reform,
            78,
            88,
            DecisionReason::FormationDisrupted,
        );
    }

    if snapshot.flank_pressure >= 72 && snapshot.allow_reform {
        return decision(
            snapshot,
            TacticalAction::Reform,
            84,
            82,
            DecisionReason::FlankPressure,
        );
    }

    if !snapshot.threat_visible || snapshot.visible_threat_strength == 0 {
        return decision(
            snapshot,
            TacticalAction::MaintainOrder,
            12,
            90,
            DecisionReason::NoActionableThreat,
        );
    }

    if snapshot.threat_retreating {
        if snapshot.allow_pursuit && within_leash(snapshot) {
            return decision(
                snapshot,
                TacticalAction::Pursue,
                62,
                78,
                DecisionReason::RetreatingThreat,
            );
        }
        return decision(
            snapshot,
            TacticalAction::MaintainOrder,
            24,
            92,
            DecisionReason::PursuitNotAuthorized,
        );
    }

    if !snapshot.allow_engagement {
        return decision(
            snapshot,
            TacticalAction::MaintainOrder,
            28,
            96,
            DecisionReason::EngagementTooRisky,
        );
    }

    // Delegated commanders require a clear advantage. Autonomous commanders accept
    // narrower margins, while low supply raises the required margin for both modes.
    let base_percent: u64 = match snapshot.mode {
        CommandMode::PlayerDirect => 100,
        CommandMode::Delegated => 112,
        CommandMode::Autonomous => 88,
    };
    let supply_penalty: u64 = if snapshot.supply < 25 { 18 } else if snapshot.supply < 45 { 8 } else { 0 };
    let required_percent = base_percent + supply_penalty;
    let own_scaled = (snapshot.own_strength as u64).saturating_mul(100);
    let enemy_required = (snapshot.visible_threat_strength as u64).saturating_mul(required_percent);

    if own_scaled >= enemy_required {
        let margin = own_scaled.saturating_sub(enemy_required);
        let confidence = 68_u16.saturating_add((margin / 20_000).min(27) as u16);
        let priority = 58_u16.saturating_add((snapshot.flank_pressure as u16 / 5).min(18));
        return decision(
            snapshot,
            TacticalAction::EngageVisibleThreat,
            priority,
            confidence,
            DecisionReason::FavorableEngagement,
        );
    }

    decision(
        snapshot,
        TacticalAction::MaintainOrder,
        42,
        90,
        DecisionReason::EngagementTooRisky,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> TacticalSnapshot {
        TacticalSnapshot {
            revision: 42,
            mode: CommandMode::Delegated,
            current_order: CurrentOrder::Hold,
            morale: 80,
            supply: 90,
            cohesion: 90,
            flank_pressure: 0,
            own_strength: 120,
            visible_threat_strength: 100,
            objective_distance_blocks: 24,
            leash_blocks: 96,
            threat_visible: true,
            threat_retreating: false,
            allow_engagement: true,
            allow_pursuit: false,
            allow_retreat: false,
            allow_reform: true,
            allow_reposition: true,
        }
    }

    #[test]
    fn direct_player_control_never_replaces_the_order() {
        let mut state = sample();
        state.mode = CommandMode::PlayerDirect;
        let result = evaluate_tactical(state);
        assert_eq!(result.action, TacticalAction::MaintainOrder);
        assert!(result.advisory_only);
        assert_eq!(result.reason, DecisionReason::PlayerAuthority);
    }

    #[test]
    fn delegated_commander_requires_a_clear_advantage() {
        let mut state = sample();
        state.own_strength = 110;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::MaintainOrder);
        state.own_strength = 112;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::EngageVisibleThreat);
    }

    #[test]
    fn autonomous_commander_accepts_a_narrower_margin() {
        let mut state = sample();
        state.mode = CommandMode::Autonomous;
        state.own_strength = 90;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::EngageVisibleThreat);
    }

    #[test]
    fn low_supply_raises_the_engagement_threshold() {
        let mut state = sample();
        state.supply = 20;
        state.own_strength = 120;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::MaintainOrder);
    }

    #[test]
    fn critical_morale_requests_retreat_only_when_permitted() {
        let mut state = sample();
        state.morale = 10;
        state.allow_retreat = false;
        assert_ne!(evaluate_tactical(state).action, TacticalAction::Retreat);
        state.allow_retreat = true;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::Retreat);
    }

    #[test]
    fn objective_leash_blocks_reposition_without_permission() {
        let mut state = sample();
        state.objective_distance_blocks = 120;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::Reposition);
        state.allow_reposition = false;
        assert_ne!(evaluate_tactical(state).action, TacticalAction::Reposition);
    }

    #[test]
    fn formation_and_flank_pressure_trigger_reform() {
        let mut state = sample();
        state.cohesion = 20;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::Reform);
        state.cohesion = 90;
        state.flank_pressure = 80;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::Reform);
    }

    #[test]
    fn retreating_enemy_is_not_pursued_without_permission_or_beyond_leash() {
        let mut state = sample();
        state.threat_retreating = true;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::MaintainOrder);
        state.allow_pursuit = true;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::Pursue);
        state.objective_distance_blocks = 140;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::MaintainOrder);
    }

    #[test]
    fn every_result_echoes_the_snapshot_revision() {
        let mut state = sample();
        state.revision = u64::MAX;
        assert_eq!(evaluate_tactical(state).source_revision, u64::MAX);
    }

    #[test]
    fn absent_threat_never_creates_an_attack_order() {
        let mut state = sample();
        state.threat_visible = false;
        assert_eq!(evaluate_tactical(state).action, TacticalAction::MaintainOrder);
        assert_eq!(evaluate_tactical(state).reason, DecisionReason::NoActionableThreat);
    }
}
