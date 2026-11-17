# WorldForge Population Compute Contract v1

## Authority
- Java `wf-people` remains authoritative for Character UUIDs, mutable properties, family/lifecycle, persistence, and applying results.
- Rust receives immutable projections and returns proposals only. It never mutates Minecraft, Characters, entities, inventories, or settlements.
- This operation is distinct from macro-world emergence and strategic decision operations; it must not duplicate either.

## Request
`schema_version=1`, stable non-empty `world_id`, canonical `year`, positive `elapsed_days`, and a list of unique Character IDs with birth year, alive flag, needs, duties, profession, training target, command, and simulated-day count. Collections are sorted by stable ID by the Java adapter. Missing need values are zero; unknown need keys are preserved.

## Result
Returns per-Character derived needs, intent, age, life stage, simulated days, and last simulated year. Results are sorted by ID. Dead Characters receive no need drift and no simulated-day accumulation. Java remains responsible for applying all updates atomically.

## Parity invariants
- Daily drift: hunger +10, rest +6, social +2, purpose +1, leisure +3; existing Java cap and 0..100 clamp are retained.
- Intent priority: hunger, rest, safety, social, age restrictions, purpose/training, hero command, soldier patrol, training target, profession, worker/specialist, hero fallback, idle.
- Life stages: infant 0-5, child 6-12, adolescent 13-17, adult 18-59, elder 60+.
- Simulated-day count is a signed Java long and saturates at `Long.MAX_VALUE`, never unsigned `u64::MAX`.

## Cutover gates
1. Build a canonical immutable Character snapshot adapter and sort by global Character UUID.
2. Run Java/Rust parity against identical snapshots, including absent maps, all life stages, all duties, dead Characters, large elapsed periods, and long overflow.
3. Use the single shared compute runtime; no additional executor.
4. Bind world ID, lifecycle epoch, domain revision vector, schema, and ABI to every result.
5. Reject stale output before the first side effect; apply one complete frame inside one Character mutation batch while retaining lifecycle event semantics.
6. Persist pending elapsed days and acknowledge them only after successful application, including save/reload and restart cases.
7. Only then disable the Java calculation. Never execute both owners in production.
