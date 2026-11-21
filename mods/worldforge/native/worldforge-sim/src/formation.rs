//! Allocation-free RTS formation geometry and role-aware slot assignment.
//!
//! Minecraft entity lookup, collision/path validation and final movement remain
//! Java-owned. This module handles only deterministic numeric formation planning.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FormationKind { Line, Block, Column, Wedge, Circle, Loose }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnitRole { Melee, Cavalry, Ranged, Support }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FormationError {
    OutputTooSmall { required: usize, available: usize },
    ScratchTooSmall { required: usize, available: usize },
    NonFiniteSpacing,
}

#[inline]
fn centered(index: usize, width: usize, spacing: f64) -> f64 {
    (index as f64 - (width.saturating_sub(1) as f64 / 2.0)) * spacing
}

/// Writes local-space (x,z) slot coordinates into caller-owned buffers.
/// No allocation, randomness, Minecraft access or hidden global state.
pub fn fill_slots(
    kind: FormationKind,
    count: usize,
    spacing: f64,
    out_x: &mut [f64],
    out_z: &mut [f64],
) -> Result<usize, FormationError> {
    if !spacing.is_finite() { return Err(FormationError::NonFiniteSpacing); }
    if out_x.len() < count { return Err(FormationError::OutputTooSmall { required: count, available: out_x.len() }); }
    if out_z.len() < count { return Err(FormationError::OutputTooSmall { required: count, available: out_z.len() }); }
    if count == 0 { return Ok(0); }

    match kind {
        FormationKind::Line | FormationKind::Block | FormationKind::Column => {
            let per_row = match kind {
                FormationKind::Line => count.min(12),
                FormationKind::Column => count.min(2),
                FormationKind::Block => ((count as f64 * 1.4).sqrt().ceil() as usize).max(1),
                _ => unreachable!(),
            };
            let rows = count / per_row + usize::from(count % per_row != 0);
            for i in 0..count {
                let row = i / per_row;
                let col = i % per_row;
                let in_row = per_row.min(count - row * per_row);
                out_x[i] = centered(col, in_row, spacing);
                out_z[i] = ((rows - 1) as f64 / 2.0 - row as f64) * spacing;
            }
        }
        FormationKind::Wedge => {
            let mut row = 0usize;
            let mut placed = 0usize;
            let mut sum_z = 0.0;
            while placed < count {
                let in_row = row + 1;
                for col in 0..in_row {
                    if placed == count { break; }
                    out_x[placed] = centered(col, in_row, spacing);
                    out_z[placed] = -(row as f64) * spacing;
                    sum_z += out_z[placed];
                    placed += 1;
                }
                row += 1;
            }
            let mean_z = sum_z / count as f64;
            for z in out_z.iter_mut().take(count) { *z -= mean_z; }
        }
        FormationKind::Circle => {
            let mut ring = 0usize;
            let mut placed = 0usize;
            while placed < count {
                let capacity = if ring == 0 { 1 } else { ring * 6 };
                for i in 0..capacity {
                    if placed == count { break; }
                    let angle = 2.0 * std::f64::consts::PI * i as f64 / capacity as f64;
                    let radius = ring as f64 * spacing;
                    out_x[placed] = angle.sin() * radius;
                    out_z[placed] = angle.cos() * radius;
                    placed += 1;
                }
                ring += 1;
            }
        }
        FormationKind::Loose => {
            // Stable integer hash gives a repeatable loose arrangement without RNG state.
            let side = (count as f64).sqrt().max(2.0) * spacing * 1.6;
            for i in 0..count {
                let a = mix64((i as u64).wrapping_mul(2).wrapping_add(count as u64 * 31 + 7));
                let b = mix64((i as u64).wrapping_mul(2).wrapping_add(1 + count as u64 * 31 + 7));
                let ux = (a >> 11) as f64 / ((1u64 << 53) as f64);
                let uz = (b >> 11) as f64 / ((1u64 << 53) as f64);
                out_x[i] = (ux - 0.5) * side;
                out_z[i] = (uz - 0.5) * side;
            }
        }
    }
    Ok(count)
}

#[inline]
fn mix64(mut value: u64) -> u64 {
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d049bb133111eb);
    value ^ (value >> 31)
}

/// Rotates local slots into world X/Z coordinates. Output buffers must be sized first.
pub fn place_slots(
    local_x: &[f64],
    local_z: &[f64],
    count: usize,
    center_x: f64,
    center_z: f64,
    yaw_degrees: f64,
    world_x: &mut [f64],
    world_z: &mut [f64],
) -> Result<usize, FormationError> {
    if world_x.len() < count { return Err(FormationError::OutputTooSmall { required: count, available: world_x.len() }); }
    if world_z.len() < count { return Err(FormationError::OutputTooSmall { required: count, available: world_z.len() }); }
    if local_x.len() < count { return Err(FormationError::ScratchTooSmall { required: count, available: local_x.len() }); }
    if local_z.len() < count { return Err(FormationError::ScratchTooSmall { required: count, available: local_z.len() }); }
    let radians = yaw_degrees.to_radians();
    let fx = -radians.sin();
    let fz = radians.cos();
    let rx = -fz;
    let rz = fx;
    for i in 0..count {
        world_x[i] = center_x + rx * local_x[i] + fx * local_z[i];
        world_z[i] = center_z + rz * local_x[i] + fz * local_z[i];
    }
    Ok(count)
}

#[inline]
fn role_rank(role: UnitRole) -> u8 {
    match role { UnitRole::Melee => 0, UnitRole::Cavalry => 1, UnitRole::Ranged => 2, UnitRole::Support => 3 }
}

/// Assigns units to slots using caller-owned index scratch buffers.
/// Melee/cavalry are placed forward; ranged/support rearward. Stable tie-breaks
/// preserve the current lateral ordering supplied by local_x.
pub fn assign_slots(
    roles: &[UnitRole],
    current_local_x: &[f64],
    slot_x: &[f64],
    slot_z: &[f64],
    unit_order: &mut [usize],
    slot_order: &mut [usize],
    assignment: &mut [usize],
) -> Result<usize, FormationError> {
    let count = roles.len();
    for (available, required) in [
        (current_local_x.len(), count), (slot_x.len(), count), (slot_z.len(), count),
        (unit_order.len(), count), (slot_order.len(), count), (assignment.len(), count),
    ] {
        if available < required { return Err(FormationError::ScratchTooSmall { required, available }); }
    }
    for i in 0..count { unit_order[i] = i; slot_order[i] = i; }
    unit_order[..count].sort_unstable_by(|a, b| {
        role_rank(roles[*a]).cmp(&role_rank(roles[*b]))
            .then_with(|| current_local_x[*a].total_cmp(&current_local_x[*b]))
            .then_with(|| a.cmp(b))
    });
    slot_order[..count].sort_unstable_by(|a, b| {
        slot_z[*b].total_cmp(&slot_z[*a])
            .then_with(|| slot_x[*a].total_cmp(&slot_x[*b]))
            .then_with(|| a.cmp(b))
    });
    for i in 0..count { assignment[unit_order[i]] = slot_order[i]; }
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_and_column_match_expected_row_geometry() {
        let mut x = [0.0; 5];
        let mut z = [0.0; 5];
        fill_slots(FormationKind::Line, 5, 2.0, &mut x, &mut z).unwrap();
        assert_eq!(x, [-4.0, -2.0, 0.0, 2.0, 4.0]);
        assert_eq!(z, [0.0; 5]);
        fill_slots(FormationKind::Column, 5, 2.0, &mut x, &mut z).unwrap();
        assert_eq!(z, [2.0, 2.0, 0.0, 0.0, -2.0]);
    }

    #[test]
    fn wedge_is_centered_and_circle_has_stable_rings() {
        let mut x = [0.0; 7];
        let mut z = [0.0; 7];
        fill_slots(FormationKind::Wedge, 7, 1.0, &mut x, &mut z).unwrap();
        assert!((z.iter().sum::<f64>()).abs() < 1e-9);
        fill_slots(FormationKind::Circle, 7, 2.0, &mut x, &mut z).unwrap();
        assert_eq!(x[0], 0.0);
        assert_eq!(z[0], 0.0);
        assert!((x[1].hypot(z[1]) - 2.0).abs() < 1e-9);
    }

    #[test]
    fn loose_formation_is_deterministic() {
        let mut ax = [0.0; 32]; let mut az = [0.0; 32];
        let mut bx = [0.0; 32]; let mut bz = [0.0; 32];
        fill_slots(FormationKind::Loose, 32, 1.7, &mut ax, &mut az).unwrap();
        fill_slots(FormationKind::Loose, 32, 1.7, &mut bx, &mut bz).unwrap();
        assert_eq!(ax, bx); assert_eq!(az, bz);
    }

    #[test]
    fn placement_rotates_and_translates_slots() {
        let lx = [1.0]; let lz = [0.0];
        let mut wx = [0.0]; let mut wz = [0.0];
        place_slots(&lx, &lz, 1, 10.0, 20.0, 0.0, &mut wx, &mut wz).unwrap();
        assert_eq!(wx[0], 10.0);
        assert_eq!(wz[0], 19.0);
    }

    #[test]
    fn role_assignment_places_melee_forward_and_ranged_rearward() {
        let roles = [UnitRole::Ranged, UnitRole::Melee, UnitRole::Support];
        let current = [0.0, 0.0, 0.0];
        let sx = [0.0, 1.0, 2.0];
        let sz = [1.0, 0.0, -1.0];
        let mut units = [0; 3]; let mut slots = [0; 3]; let mut assigned = [0; 3];
        assign_slots(&roles, &current, &sx, &sz, &mut units, &mut slots, &mut assigned).unwrap();
        assert_eq!(assigned[1], 0);
        assert_eq!(assigned[0], 2);
        assert_eq!(assigned[2], 1);
    }

    #[test]
    fn batch_geometry_handles_four_thousand_units_without_heap_buffers() {
        let count = 4096;
        let mut x = vec![0.0; count]; let mut z = vec![0.0; count];
        assert_eq!(fill_slots(FormationKind::Block, count, 1.7, &mut x, &mut z), Ok(count));
        let mut wx = vec![0.0; count]; let mut wz = vec![0.0; count];
        assert_eq!(place_slots(&x, &z, count, 100.0, -50.0, 35.0, &mut wx, &mut wz), Ok(count));
        assert!(wx.iter().all(|v| v.is_finite()) && wz.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn undersized_buffers_are_rejected_before_writes() {
        let mut x = [9.0; 1]; let mut z = [8.0; 2];
        assert_eq!(fill_slots(FormationKind::Block, 2, 1.0, &mut x, &mut z),
            Err(FormationError::OutputTooSmall { required: 2, available: 1 }));
        assert_eq!(x, [9.0]);
    }
}
