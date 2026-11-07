//! MAQAN native core: exploration indexing, spatial queries and UI composition.
//!
//! Rust owns deterministic data-oriented work and builds a versioned UI command
//! frame. The Minecraft host still executes that frame against its live render
//! context; Rust does not create a window or replace Cosmic Horizons body assets.

pub mod ui;
pub mod motion;
pub mod layout;
pub mod jni;

use std::cmp::Reverse;
use std::collections::{BTreeMap, BTreeSet, BinaryHeap};

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub struct TileKey {
    pub dimension_hash: u64,
    pub x: i32,
    pub z: i32,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct SurfaceTile {
    pub biome_color: u32,
    pub height: i32,
    pub flags: u16,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ChunkRect {
    pub min_x: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_z: i32,
}

impl ChunkRect {
    pub fn new(min_x: i32, min_z: i32, max_x: i32, max_z: i32) -> Option<Self> {
        if min_x > max_x || min_z > max_z { return None; }
        Some(Self { min_x, min_z, max_x, max_z })
    }

    pub fn contains(&self, x: i32, z: i32) -> bool {
        x >= self.min_x && x <= self.max_x && z >= self.min_z && z <= self.max_z
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct ExplorationIndex {
    tiles: BTreeMap<TileKey, SurfaceTile>,
}

impl ExplorationIndex {
    pub fn record(&mut self, key: TileKey, tile: SurfaceTile) -> Option<SurfaceTile> {
        self.tiles.insert(key, tile)
    }

    pub fn get(&self, key: &TileKey) -> Option<&SurfaceTile> {
        self.tiles.get(key)
    }

    pub fn contains(&self, key: &TileKey) -> bool {
        self.tiles.contains_key(key)
    }

    pub fn len(&self) -> usize { self.tiles.len() }
    pub fn is_empty(&self) -> bool { self.tiles.is_empty() }

    pub fn visited_chunks(&self, dimension_hash: u64) -> BTreeSet<(i32, i32)> {
        self.tiles.keys()
            .filter(|key| key.dimension_hash == dimension_hash)
            .map(|key| (key.x, key.z))
            .collect()
    }

    pub fn bounds(&self, dimension_hash: u64) -> Option<ChunkRect> {
        let mut keys = self.tiles.keys().filter(|key| key.dimension_hash == dimension_hash);
        let first = keys.next()?;
        let (mut min_x, mut max_x, mut min_z, mut max_z) =
            (first.x, first.x, first.z, first.z);
        for key in keys {
            min_x = min_x.min(key.x);
            max_x = max_x.max(key.x);
            min_z = min_z.min(key.z);
            max_z = max_z.max(key.z);
        }
        ChunkRect::new(min_x, min_z, max_x, max_z)
    }

    /// Returns only already-explored chunks in the requested dimension and rectangle.
    /// This method never creates or reveals missing chunks.
    pub fn query_rect(&self, dimension_hash: u64, rect: ChunkRect) -> Vec<(TileKey, SurfaceTile)> {
        self.tiles.iter()
            .filter(|(key, _)| key.dimension_hash == dimension_hash && rect.contains(key.x, key.z))
            .map(|(key, tile)| (*key, *tile))
            .collect()
    }

    /// Dijkstra route over explored chunks only. Diagonal corner cutting is forbidden.
    /// Elevation changes add a bounded slope penalty to the movement cost.
    pub fn route(
        &self,
        dimension_hash: u64,
        start: (i32, i32),
        goal: (i32, i32),
        max_expansions: usize,
    ) -> RouteResult {
        let start_key = TileKey { dimension_hash, x: start.0, z: start.1 };
        let goal_key = TileKey { dimension_hash, x: goal.0, z: goal.1 };
        if !self.tiles.contains_key(&start_key) || !self.tiles.contains_key(&goal_key) {
            return RouteResult::not_found(0);
        }
        if start == goal {
            return RouteResult { found: true, points: vec![start_key], cost: 0, expanded: 0 };
        }

        let mut frontier = BinaryHeap::new();
        let mut costs: BTreeMap<TileKey, u64> = BTreeMap::new();
        let mut parents: BTreeMap<TileKey, TileKey> = BTreeMap::new();
        costs.insert(start_key, 0);
        frontier.push(Reverse((0_u64, start_key)));
        let mut expanded = 0_usize;

        const DIRECTIONS: [(i32, i32, u64); 8] = [
            (-1, 0, 10), (1, 0, 10), (0, -1, 10), (0, 1, 10),
            (-1, -1, 14), (-1, 1, 14), (1, -1, 14), (1, 1, 14),
        ];

        while let Some(Reverse((queued_cost, current))) = frontier.pop() {
            if costs.get(&current).copied() != Some(queued_cost) { continue; }
            if current == goal_key {
                let mut points = vec![goal_key];
                let mut cursor = goal_key;
                while cursor != start_key {
                    let Some(parent) = parents.get(&cursor).copied() else {
                        return RouteResult::not_found(expanded);
                    };
                    cursor = parent;
                    points.push(cursor);
                }
                points.reverse();
                return RouteResult {
                    found: true,
                    points,
                    cost: queued_cost,
                    expanded,
                };
            }
            if expanded >= max_expansions { break; }
            expanded += 1;

            let current_tile = self.tiles.get(&current).expect("frontier keys are explored");
            for (dx, dz, base_cost) in DIRECTIONS {
                let nx = current.x.saturating_add(dx);
                let nz = current.z.saturating_add(dz);
                let next = TileKey { dimension_hash, x: nx, z: nz };
                let Some(next_tile) = self.tiles.get(&next) else { continue; };

                if dx != 0 && dz != 0 {
                    let side_a = TileKey { dimension_hash, x: current.x.saturating_add(dx), z: current.z };
                    let side_b = TileKey { dimension_hash, x: current.x, z: current.z.saturating_add(dz) };
                    if !self.tiles.contains_key(&side_a) || !self.tiles.contains_key(&side_b) {
                        continue;
                    }
                }

                let slope = (i64::from(next_tile.height) - i64::from(current_tile.height))
                    .unsigned_abs().min(100) as u64;
                let step_cost = base_cost + slope * 2;
                let candidate = queued_cost.saturating_add(step_cost);
                if candidate < costs.get(&next).copied().unwrap_or(u64::MAX) {
                    costs.insert(next, candidate);
                    parents.insert(next, current);
                    frontier.push(Reverse((candidate, next)));
                }
            }
        }
        RouteResult::not_found(expanded)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RouteResult {
    pub found: bool,
    pub points: Vec<TileKey>,
    pub cost: u64,
    pub expanded: usize,
}

impl RouteResult {
    fn not_found(expanded: usize) -> Self {
        Self { found: false, points: Vec::new(), cost: 0, expanded }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(dimension_hash: u64, x: i32, z: i32) -> TileKey {
        TileKey { dimension_hash, x, z }
    }
    fn tile(height: i32) -> SurfaceTile {
        SurfaceTile { biome_color: 0x123456, height, flags: 0 }
    }

    #[test]
    fn records_and_replaces_a_surface_tile() {
        let mut index = ExplorationIndex::default();
        let original = tile(64);
        let updated = SurfaceTile { biome_color: 0x654321, height: 71, flags: 1 };
        assert_eq!(index.record(key(1, -4, 8), original), None);
        assert_eq!(index.record(key(1, -4, 8), updated), Some(original));
        assert_eq!(index.get(&key(1, -4, 8)), Some(&updated));
        assert_eq!(index.len(), 1);
    }

    #[test]
    fn keeps_dimensions_isolated() {
        let mut index = ExplorationIndex::default();
        index.record(key(1, 2, 3), tile(60));
        index.record(key(2, 2, 3), tile(70));
        assert_eq!(index.visited_chunks(1), BTreeSet::from([(2, 3)]));
        assert_eq!(index.visited_chunks(2), BTreeSet::from([(2, 3)]));
    }

    #[test]
    fn computes_bounds_for_only_the_requested_dimension() {
        let mut index = ExplorationIndex::default();
        index.record(key(9, -8, 4), tile(60));
        index.record(key(9, 5, -2), tile(70));
        index.record(key(10, 100, 100), tile(80));
        assert_eq!(index.bounds(9), ChunkRect::new(-8, -2, 5, 4));
        assert_eq!(index.bounds(10), ChunkRect::new(100, 100, 100, 100));
        assert_eq!(index.bounds(11), None);
    }

    #[test]
    fn rectangle_queries_return_only_explored_tiles_in_one_dimension() {
        let mut index = ExplorationIndex::default();
        index.record(key(1, 0, 0), tile(60));
        index.record(key(1, 1, 0), tile(61));
        index.record(key(1, 2, 0), tile(62));
        index.record(key(2, 1, 0), tile(90));
        let rect = ChunkRect::new(0, 0, 1, 1).unwrap();
        let found = index.query_rect(1, rect);
        assert_eq!(found.len(), 2);
        assert!(found.iter().all(|(key, _)| key.dimension_hash == 1));
    }

    #[test]
    fn routes_only_through_explored_tiles() {
        let mut index = ExplorationIndex::default();
        for x in 0..=3 { index.record(key(1, x, 0), tile(64)); }
        index.record(key(1, 1, 1), tile(64));
        let route = index.route(1, (0, 0), (3, 0), 100);
        assert!(route.found);
        assert_eq!(route.points.first(), Some(&key(1, 0, 0)));
        assert_eq!(route.points.last(), Some(&key(1, 3, 0)));
        assert!(route.points.iter().all(|point| index.contains(point)));
    }

    #[test]
    fn diagonal_routes_cannot_cut_unexplored_corners() {
        let mut index = ExplorationIndex::default();
        index.record(key(1, 0, 0), tile(64));
        index.record(key(1, 1, 1), tile(64));
        let route = index.route(1, (0, 0), (1, 1), 100);
        assert!(!route.found);
    }

    #[test]
    fn steep_routes_cost_more_than_flat_routes() {
        let mut flat = ExplorationIndex::default();
        flat.record(key(1, 0, 0), tile(64));
        flat.record(key(1, 1, 0), tile(64));
        let mut steep = ExplorationIndex::default();
        steep.record(key(1, 0, 0), tile(64));
        steep.record(key(1, 1, 0), tile(80));
        assert!(steep.route(1, (0, 0), (1, 0), 10).cost
            > flat.route(1, (0, 0), (1, 0), 10).cost);
    }

    #[test]
    fn route_respects_expansion_budget() {
        let mut index = ExplorationIndex::default();
        for x in 0..10 { index.record(key(1, x, 0), tile(64)); }
        let route = index.route(1, (0, 0), (9, 0), 1);
        assert!(!route.found);
        assert!(route.expanded <= 1);
    }
}
