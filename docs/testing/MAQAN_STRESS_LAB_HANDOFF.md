# MAQAN Stress Lab — durable continuation handoff

Read this before continuing in another chat. Do not ask the user to restate the repository, branch, target or contour list.

## Target
- Repository: gusilius/MAQAN-UniverseForge
- Branch: integration/worldgen-rts-unification
- All 12 contours are defined in docs/testing/MAQAN_RUNTIME_STRESS_MATRIX.md and docs/testing/MAQAN_STRESS_CONTOURS.json.
- User requires actual gameplay mechanisms and maximum stress, not simple scripts or fake results.
- Each contour must have a separate Forge Dedicated Server JVM, unique save, staged max-load, soak, stop/restart/recovery and evidence.
- Do not claim PASS without server evidence.

## Exact order
01 Server Core
02 Character / Population
03 Families / Social
04 Settlements / Labor
05 Economy / Trade / Treasury
06 Politics / States
07 Army / RTS
08 World Generation
09 Dimensions / Planets
10 Persistence / Recovery
11 Long-term Autonomous Life
12 Full Integration

## Last run diagnosis
Workflow run #45015494608 completed FAILURE on commit f5b109c3952235db5fae1249459eb5eb0040a498.
- Build core and all addons completed far enough to launch Forge server.
- Contour 01 and contour 02 result aggregation failed.
- world-runtime-diagnostics.py line 29 had an extra closing parenthesis: SyntaxError: unmatched ')'. Pending fix removes that extra parenthesis.
- Population stress genuinely hit Watchdog. Server-thread dump shows:
  FileChannelImpl.force → CharacterWal.appendBatch → CharacterWal.append → PeopleMod.persistCharacter → RuntimeStressLab.createOne → RuntimeStressLab.onServerTick.
  This proves synchronous WAL force per Character blocked the Server Thread. Do not classify as merely infrastructure timeout.
- During shutdown, Server thread was waiting in ChunkStorage. This is a secondary save path, not the primary stack at watchdog time.
- Pending stress-driver change routes accepted Character records through existing bounded CharacterWalWriter, with durable futures and queue backpressure. Ordinary transactional writes remain synchronous. Do not join futures on Server Thread.
- Population day-cursor rollover starvation fix and regression test are pending. It preserves unfinished scan progress across world-day changes, then performs a prefix pass before acknowledging completion.

## Current honest status
- 01: isolated lifecycle/restart driver; not maximum-load stress PASS.
- 02: canonical population driver; async stress WAL change pending; physical WFNpc stress and crash/recovery remain missing.
- 03: opt-in `SocialLifeStressLab` driver now exists; it uses canonical CharacterRegistry family mutations, production `NpcSocialSimulation`, FamilyPlanning pregnancies/births and PopulationSimulation deaths in bounded batches. It stores relationships in a separate stress-only Relations graph/checkpoint to avoid mutating live `wf_relations.json`. Status remains DRIVER_PRESENT_UNVERIFIED; no max-load PASS or same-save server restart/recovery evidence yet.
- 04–12: mapped to real production systems; no independent server drivers yet.
- Native scale gates and test-all are supplementary; they do not replace Forge Dedicated Server gameplay stress.

## Next steps
1. Finish review of diagnostics, population rollover, async WAL stress path and CI contour inventory.
2. Verify branch head has not moved; publish one coherent commit.
3. Let CI run; inspect server logs, watchdog dumps, diagnostics JSON and artifacts.
4. Fix compile/runtime regression before increasing load.
5. Validate the new contour-03 driver on a disposable Forge server; then add its same-save restart/recovery harness and combine contour-02 population scheduling with contour-03 life events without sharing or corrupting subsystem state. Continue 04–12 with subsystem-owned adapters calling exact production entrypoints and preserving cadence/side effects.
6. Keep this handoff and manifest current after each coherent batch.

## Critical correctness constraints
- Driver paces and observes; it cannot bypass production mechanisms.
- 1m canonical Characters and physical ticking Minecraft entities are separate.
- Async persistence queues must be bounded, expose queue depth/rejections, propagate durable receipts, drain before checkpoint/shutdown, and never silently drop writes.
- Do not change Watchdog thresholds to hide stalls.
- Never run all contours concurrently on a four-core runner; sequential isolated JVMs are default.
