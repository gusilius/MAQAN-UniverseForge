//! Deterministic, allocation-free spatial hash for RTS proximity queries.
//! The host supplies immutable position snapshots and caller-owned scratch buffers.
//! This module only produces candidate indices; Java resolves entities, dimensions,
//! visibility, line-of-sight, teams and final gameplay permissions.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SpatialError {
    InvalidCellSize,
    InvalidPosition { index: usize },
    InvalidQuery,
    GridTooSmall { required: usize, available: usize },
    ScratchTooSmall { required: usize, available: usize },
    QueryTooBroad,
    OutputTooSmall { required: usize, available: usize },
}

#[inline]
fn cell(value: f64, size: f64) -> Option<i64> {
    let scaled = (value / size).floor();
    if !scaled.is_finite() || scaled < i64::MIN as f64 || scaled >= i64::MAX as f64 { None } else { Some(scaled as i64) }
}

#[inline]
fn hash(x: i64, z: i64) -> u64 {
    let mut v = (x as u64).wrapping_mul(0x9E3779B185EBCA87) ^ (z as u64).wrapping_mul(0xC2B2AE3D27D4EB4F);
    v ^= v >> 30; v = v.wrapping_mul(0xBF58476D1CE4E5B9);
    v ^= v >> 27; v = v.wrapping_mul(0x94D049BB133111EB);
    v ^ (v >> 31)
}

#[inline]
fn find_slot(x: i64, z: i64, cell_x: &[i64], cell_z: &[i64], heads: &[i32]) -> Option<usize> {
    if heads.is_empty() { return None; }
    let mask = heads.len() - 1;
    let mut slot = hash(x, z) as usize & mask;
    for _ in 0..heads.len() {
        let head = heads[slot];
        if head == -1 { return None; }
        if cell_x[slot] == x && cell_z[slot] == z { return Some(slot); }
        slot = (slot + 1) & mask;
    }
    None
}

/// Builds an open-addressed grid. heads must have a power-of-two length and
/// at least twice the number of points. next has one entry per point.
/// Empty buckets are represented by -1. Iteration order within a cell is stable.
pub fn build_grid(
    x: &[f64], z: &[f64], cell_size: f64,
    cell_x: &mut [i64], cell_z: &mut [i64], heads: &mut [i32], next: &mut [i32],
) -> Result<usize, SpatialError> {
    if !cell_size.is_finite() || cell_size <= 0.0 { return Err(SpatialError::InvalidCellSize); }
    if heads.is_empty() || !heads.len().is_power_of_two() || x.len().saturating_mul(2) > heads.len() {
        return Err(SpatialError::GridTooSmall { required: x.len().saturating_mul(2).max(1), available: heads.len() });
    }
    if z.len() < x.len() || next.len() < x.len() || cell_x.len() < heads.len() || cell_z.len() < heads.len() {
        return Err(SpatialError::ScratchTooSmall { required: x.len().max(heads.len()), available: z.len().min(next.len()).min(cell_x.len()).min(cell_z.len()) });
    }
    heads.fill(-1);
    for i in 0..x.len() {
        let cx = cell(x[i], cell_size).ok_or(SpatialError::InvalidPosition { index: i })?;
        let cz = cell(z[i], cell_size).ok_or(SpatialError::InvalidPosition { index: i })?;
        let mask = heads.len() - 1;
        let mut slot = hash(cx, cz) as usize & mask;
        loop {
            if heads[slot] == -1 || (cell_x[slot] == cx && cell_z[slot] == cz) { break; }
            slot = (slot + 1) & mask;
        }
        if heads[slot] == -1 { cell_x[slot] = cx; cell_z[slot] = cz; }
        next[i] = heads[slot];
        heads[slot] = i as i32;
    }
    Ok(x.len())
}

#[inline]
fn visit_count(
    min_x: i64, max_x: i64, min_z: i64, max_z: i64, qx: f64, qz: f64, radius_sq: f64,
    x: &[f64], z: &[f64], cell_x: &[i64], cell_z: &[i64], heads: &[i32], next: &[i32],
) -> usize {
    let mut found = 0usize;
    let mut cz = min_z;
    loop {
        let mut cx = min_x;
        loop {
            if let Some(slot) = find_slot(cx, cz, cell_x, cell_z, heads) {
                let mut entry = heads[slot];
                while entry >= 0 {
                    let i = entry as usize;
                    let dx = x[i] - qx; let dz = z[i] - qz;
                    if dx * dx + dz * dz <= radius_sq { found += 1; }
                    entry = next[i];
                }
            }
            if cx == max_x { break; } cx += 1;
        }
        if cz == max_z { break; } cz += 1;
    }
    found
}

/// Writes exact-radius candidate indices. Performs a count pass first, so an
/// undersized output buffer is never partially written. max_cells bounds work.
pub fn query_radius(
    qx: f64, qz: f64, radius: f64, cell_size: f64, max_cells: usize,
    x: &[f64], z: &[f64], cell_x: &[i64], cell_z: &[i64], heads: &[i32], next: &[i32],
    output: &mut [usize],
) -> Result<usize, SpatialError> {
    if !qx.is_finite() || !qz.is_finite() || !radius.is_finite() || radius < 0.0
        || !cell_size.is_finite() || cell_size <= 0.0 || heads.is_empty() || !heads.len().is_power_of_two() {
        return Err(SpatialError::InvalidQuery);
    }
    if z.len() < x.len() || next.len() < x.len() || cell_x.len() < heads.len() || cell_z.len() < heads.len() {
        return Err(SpatialError::ScratchTooSmall { required: x.len().max(heads.len()), available: z.len().min(next.len()).min(cell_x.len()).min(cell_z.len()) });
    }
    let min_x = cell(qx - radius, cell_size).ok_or(SpatialError::InvalidQuery)?;
    let max_x = cell(qx + radius, cell_size).ok_or(SpatialError::InvalidQuery)?;
    let min_z = cell(qz - radius, cell_size).ok_or(SpatialError::InvalidQuery)?;
    let max_z = cell(qz + radius, cell_size).ok_or(SpatialError::InvalidQuery)?;
    let width = (max_x as i128 - min_x as i128 + 1) as u128;
    let depth = (max_z as i128 - min_z as i128 + 1) as u128;
    if width.saturating_mul(depth) > max_cells as u128 { return Err(SpatialError::QueryTooBroad); }
    let radius_sq = radius * radius;
    let required = visit_count(min_x, max_x, min_z, max_z, qx, qz, radius_sq, x, z, cell_x, cell_z, heads, next);
    if output.len() < required { return Err(SpatialError::OutputTooSmall { required, available: output.len() }); }

    let mut written = 0usize;
    let mut cz = min_z;
    loop {
        let mut cx = min_x;
        loop {
            if let Some(slot) = find_slot(cx, cz, cell_x, cell_z, heads) {
                let mut entry = heads[slot];
                while entry >= 0 {
                    let i = entry as usize;
                    let dx = x[i] - qx; let dz = z[i] - qz;
                    if dx * dx + dz * dz <= radius_sq { output[written] = i; written += 1; }
                    entry = next[i];
                }
            }
            if cx == max_x { break; } cx += 1;
        }
        if cz == max_z { break; } cz += 1;
    }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn build(points: &[(f64, f64)], size: f64) -> (Vec<i64>, Vec<i64>, Vec<i32>, Vec<i32>, Vec<f64>, Vec<f64>) {
        let n = points.len(); let cap = (n.max(1) * 2).next_power_of_two();
        let x: Vec<_> = points.iter().map(|p| p.0).collect();
        let z: Vec<_> = points.iter().map(|p| p.1).collect();
        let mut cx = vec![0; cap]; let mut cz = vec![0; cap];
        let mut heads = vec![-1; cap]; let mut next = vec![-1; n];
        build_grid(&x, &z, size, &mut cx, &mut cz, &mut heads, &mut next).unwrap();
        (cx, cz, heads, next, x, z)
    }

    #[test]
    fn radius_query_matches_brute_force() {
        let points: Vec<_> = (0..1000).map(|i| (((i * 37) % 113) as f64 - 56.0, ((i * 71) % 127) as f64 - 63.0)).collect();
        let (cx, cz, heads, next, x, z) = build(&points, 8.0); let mut out = vec![0; points.len()];
        let count = query_radius(3.0, -4.0, 13.0, 8.0, 100, &x, &z, &cx, &cz, &heads, &next, &mut out).unwrap();
        let expected: Vec<_> = points.iter().enumerate().filter(|(_, p)| (p.0 - 3.0).powi(2) + (p.1 + 4.0).powi(2) <= 169.0).map(|(i, _)| i).collect();
        let mut actual = out[..count].to_vec(); actual.sort_unstable(); assert_eq!(actual, expected);
    }

    #[test]
    fn supports_coincident_units_and_negative_coordinates() {
        let points = [(-0.1, -0.1), (-0.1, -0.1), (0.0, 0.0), (9.0, 9.0)];
        let (cx, cz, heads, next, x, z) = build(&points, 4.0); let mut out = [0; 4];
        let count = query_radius(-0.1, -0.1, 0.0, 4.0, 4, &x, &z, &cx, &cz, &heads, &next, &mut out).unwrap();
        assert_eq!(count, 2);
    }

    #[test]
    fn undersized_output_is_rejected_without_partial_writes() {
        let points = [(0.0, 0.0), (0.1, 0.1)];
        let (cx, cz, heads, next, x, z) = build(&points, 4.0); let mut out = [77usize; 1];
        assert_eq!(query_radius(0.0, 0.0, 1.0, 4.0, 4, &x, &z, &cx, &cz, &heads, &next, &mut out),
            Err(SpatialError::OutputTooSmall { required: 2, available: 1 }));
        assert_eq!(out, [77]);
    }

    #[test]
    fn broad_queries_are_bounded() {
        let points = [(0.0, 0.0)];
        let (cx, cz, heads, next, x, z) = build(&points, 1.0); let mut out = [0usize; 1];
        assert_eq!(query_radius(0.0, 0.0, 1000.0, 1.0, 100, &x, &z, &cx, &cz, &heads, &next, &mut out), Err(SpatialError::QueryTooBroad));
    }

    #[test]
    fn batch_build_handles_four_thousand_npcs() {
        let points: Vec<_> = (0..4096).map(|i| ((i % 128) as f64 * 3.0, (i / 128) as f64 * 3.0)).collect();
        let (cx, cz, heads, next, x, z) = build(&points, 6.0); let mut out = vec![0; points.len()];
        let count = query_radius(96.0, 48.0, 18.0, 6.0, 100, &x, &z, &cx, &cz, &heads, &next, &mut out).unwrap();
        assert!(count > 0 && count < points.len());
    }
}
