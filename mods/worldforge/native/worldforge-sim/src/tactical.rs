//! Bounded, deterministic RTS tactical proposals.
//!
//! This module accepts copied aggregate snapshots only. It has no Minecraft,
//! entity, registry, persistence, clock, random, JNI, or networking dependency.
//! Java remains authoritative for command permissions and applying accepted orders.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandMode { PlayerDirect, Delegated, Autonomous }

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
    NoThreat,
    MoraleCritical,
    SupplyCritical,
    OutsideLeash,
    FormationDisrupted,
    FlankPressure,
    FavorableEngagement,
    EngagementTooRisky,
    PursuitNotAuthorized,
    RetreatingThreat,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TacticalSnapshot {
    /// Monotonic squad/order/policy revision supplied by the Java owner.
    pub revision: u64,
    pub mode: CommandMode,
    /// Normalized values in [0, 100].
    pub morale: u8,
    pub supply: u8,
    pub cohesion: u8,
    pub flank_pressure: u8,
    pub own_strength: u32,
    pub visible_threat_strength: u32,
    pub objective_distance_blocks: u16,
    /// Zero disables the leash.
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
    pub source_revision: u64,
    pub action: TacticalAction,
    /// Stable normalized ranking values, not probabilities.
    pub priority: u8,
    pub confidence: u8,
    /// Player-direct decisions are advisory and must never replace an order.
    pub advisory_only: bool,
    pub reason: DecisionReason,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatchError {
    OutputTooSmall { required: usize, available: usize },
}

#[inline]
fn decision(
    s: TacticalSnapshot,
    action: TacticalAction,
    priority: u8,
    confidence: u8,
    reason: DecisionReason,
) -> TacticalDecision {
    TacticalDecision {
        source_revision: s.revision,
        action,
        priority,
        confidence,
        advisory_only: s.mode == CommandMode::PlayerDirect,
        reason,
    }
}

/// Computes one deterministic proposal. It never mutates orders or world state.
pub fn evaluate_tactical(s: TacticalSnapshot) -> TacticalDecision {
    if s.mode == CommandMode::PlayerDirect {
        return decision(s, TacticalAction::MaintainOrder, 0, 100, DecisionReason::PlayerAuthority);
    }
    if s.morale <= 18 && s.allow_retreat {
        return decision(s, TacticalAction::Retreat, 100, 94, DecisionReason::MoraleCritical);
    }
    if s.supply <= 8 && s.morale <= 35 && s.allow_retreat {
        return decision(s, TacticalAction::Retreat, 92, 86, DecisionReason::SupplyCritical);
    }
    let within_leash = s.leash_blocks == 0 || s.objective_distance_blocks <= s.leash_blocks;
    if !within_leash && s.allow_reposition {
        return decision(s, TacticalAction::Reposition, 88, 91, DecisionReason::OutsideLeash);
    }
    if s.cohesion < 32 && s.allow_reform {
        return decision(s, TacticalAction::Reform, 78, 88, DecisionReason::FormationDisrupted);
    }
    if s.flank_pressure >= 72 && s.allow_reform {
        return decision(s, TacticalAction::Reform, 84, 82, DecisionReason::FlankPressure);
    }
    if !s.threat_visible || s.visible_threat_strength == 0 {
        return decision(s, TacticalAction::MaintainOrder, 12, 90, DecisionReason::NoThreat);
    }
    if s.threat_retreating {
        return if s.allow_pursuit && within_leash {
            decision(s, TacticalAction::Pursue, 62, 78, DecisionReason::RetreatingThreat)
        } else {
            decision(s, TacticalAction::MaintainOrder, 24, 92, DecisionReason::PursuitNotAuthorized)
        };
    }
    if !s.allow_engagement {
        return decision(s, TacticalAction::MaintainOrder, 28, 96, DecisionReason::EngagementTooRisky);
    }

    let base = match s.mode { CommandMode::PlayerDirect => 100_u64, CommandMode::Delegated => 112, CommandMode::Autonomous => 88 };
    let supply_penalty = if s.supply < 25 { 18 } else if s.supply < 45 { 8 } else { 0 };
    let required = base + supply_penalty;
    let own = (s.own_strength as u64).saturating_mul(100);
    let enemy = (s.visible_threat_strength as u64).saturating_mul(required);
    if own >= enemy {
        let margin = own.saturating_sub(enemy);
        let confidence = 68_u64.saturating_add((margin / 20_000).min(27)) as u8;
        let priority = 58_u64.saturating_add((s.flank_pressure as u64 / 5).min(18)) as u8;
        decision(s, TacticalAction::EngageVisibleThreat, priority, confidence, DecisionReason::FavorableEngagement)
    } else {
        decision(s, TacticalAction::MaintainOrder, 42, 90, DecisionReason::EngagementTooRisky)
    }
}

/// Allocation-free batch API. The caller owns and reuses the output buffer.
/// Input order is preserved; every result is validated independently by Java.
pub fn evaluate_tactical_batch_into(
    snapshots: &[TacticalSnapshot],
    output: &mut [TacticalDecision],
) -> Result<usize, BatchError> {
    if output.len() < snapshots.len() {
        return Err(BatchError::OutputTooSmall { required: snapshots.len(), available: output.len() });
    }
    for (slot, snapshot) in output.iter_mut().zip(snapshots.iter().copied()) {
        *slot = evaluate_tactical(snapshot);
    }
    Ok(snapshots.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(revision: u64) -> TacticalSnapshot {
        TacticalSnapshot {
            revision, mode: CommandMode::Delegated,
            morale: 80, supply: 90, cohesion: 90, flank_pressure: 0,
            own_strength: 120, visible_threat_strength: 100,
            objective_distance_blocks: 24, leash_blocks: 96,
            threat_visible: true, threat_retreating: false,
            allow_engagement: true, allow_pursuit: false, allow_retreat: false,
            allow_reform: true, allow_reposition: true,
        }
    }

    #[test]
    fn direct_control_is_advisory_only() {
        let mut s = sample(3);
        s.mode = CommandMode::PlayerDirect;
        let d = evaluate_tactical(s);
        assert_eq!(d.action, TacticalAction::MaintainOrder);
        assert!(d.advisory_only);
        assert_eq!(d.source_revision, 3);
    }

    #[test]
    fn delegated_and_autonomous_have_different_risk_thresholds() {
        let mut s = sample(1);
        s.own_strength = 90;
        assert_eq!(evaluate_tactical(s).action, TacticalAction::MaintainOrder);
        s.mode = CommandMode::Autonomous;
        assert_eq!(evaluate_tactical(s).action, TacticalAction::EngageVisibleThreat);
    }

    #[test]
    fn critical_morale_cannot_override_retreat_permission() {
        let mut s = sample(1);
        s.morale = 5;
        assert_ne!(evaluate_tactical(s).action, TacticalAction::Retreat);
        s.allow_retreat = true;
        assert_eq!(evaluate_tactical(s).action, TacticalAction::Retreat);
    }

    #[test]
    fn pursuit_requires_permission_and_leash() {
        let mut s = sample(1);
        s.threat_retreating = true;
        s.allow_pursuit = true;
        assert_eq!(evaluate_tactical(s).action, TacticalAction::Pursue);
        s.objective_distance_blocks = 120;
        assert_eq!(evaluate_tactical(s).action, TacticalAction::MaintainOrder);
    }

    #[test]
    fn batch_is_ordered_revision_safe_and_allocation_free() {
        let snapshots: Vec<_> = (0..4096).map(sample).collect();
        let mut output = vec![evaluate_tactical(sample(0)); snapshots.len()];
        assert_eq!(evaluate_tactical_batch_into(&snapshots, &mut output), Ok(4096));
        for (input, result) in snapshots.iter().zip(output.iter()) {
            assert_eq!(input.revision, result.source_revision);
            assert_eq!(*result, evaluate_tactical(*input));
        }
    }

    #[test]
    fn undersized_output_is_rejected_without_partial_work() {
        let snapshots = [sample(1), sample(2)];
        let mut output = [evaluate_tactical(sample(0))];
        assert_eq!(
            evaluate_tactical_batch_into(&snapshots, &mut output),
            Err(BatchError::OutputTooSmall { required: 2, available: 1 })
        );
        assert_eq!(output[0].source_revision, 0);
    }

    #[test]
    fn empty_batch_is_a_noop() {
        let mut output = [];
        assert_eq!(evaluate_tactical_batch_into(&[], &mut output), Ok(0));
    }
}
