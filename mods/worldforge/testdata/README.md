# Optional WorldForge integration-test fixture

The supplied UniverseForge archive does not contain `game_of_thrones_datapack.zip`.

The pure-logic test suite can run without it. Tests that load a real datapack/map fixture are explicitly reported as `SKIP` by `wfaddons/tools/test-all.sh` until the fixture is supplied. Do not replace this fixture with a synthetic map and treat that as an integration pass.

Expected path when supplied:

`mods/worldforge/testdata/game_of_thrones_datapack.zip`
