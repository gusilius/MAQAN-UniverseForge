#!/usr/bin/env bash
# Pure Java domain tests. Forge/Minecraft adapters are compiled by each addon's
# real Gradle build above; this harness compiles only framework-independent logic.
set -euo pipefail
CORE="${1:-../wfmod}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

ACTUAL_PACK="${2:-}"
GROUP="${3:-all}"
case "$GROUP" in all|suite|npc|worldgen|politics|life|integration) ;; *) echo "ERROR: unknown test group '$GROUP'"; exit 2 ;; esac

test_group() {
  local name="$1"
  case "$name" in
    Character*|PeopleTest|PlayerTest|NameBankTest|MarriageTest|FamilyPlanningTest|EmergentHouseholdPlanTest|EmergentPopulationIndexTest|NpcSpawn*|MilitaryDeathLedgerTest) echo npc ;;
    Genesis*|ImageWorldIdentityTest|MapGeographyTest|MapSyncSafetyTest|PlanetActivationStateTest|ProceduralPlanetGenerationTest|ProvinceContourTest|RasterTileLayerTest|SettlementMapTest) echo worldgen ;;
    Army*|CityTaxTest|CourtTest|EconomyTest|HistoricalChronicleTest|LedgerTest|OperationalSupplyPersistenceTest|OrdersTest|PoliticalIdentitySynchronizerTest|RealmEconomyTest|RealmsTest|StatecraftTest|ThreatTest|Trade*|Treasury*|TroopsTest|WarTest) echo politics ;;
    PopulationSimulationTest|RelationsTest|ReputationTest|Resident*|SeasonTest|Settle*|ColonySimulationCadenceTest|ShopsTest) echo life ;;
    *) echo integration ;;
  esac
}
echo "WORLD_FORGE_TEST_GROUP=$GROUP"
if [ -n "$ACTUAL_PACK" ] && [ ! -f "$ACTUAL_PACK" ]; then
  echo "ERROR: supplied actual worldmap not found: $ACTUAL_PACK"
  exit 2
fi
PACK="$OUT/worldforge-test.worldmap"
python3 tests/create_world_fixture.py "$PACK"

mkdir -p "$OUT/classes"
# A source is eligible for this lightweight domain harness only when it has
# no direct Minecraft, Forge, or Brigadier dependency. Full Gradle builds
# compile every adapter against the real Minecraft/Forge classpath.
mapfile -t CANDIDATE_SRC < <(
  {
    find "$CORE/src/main/java" -name '*.java' -not -path '*/mc/*' \
      ! -path "$CORE/src/main/java/dev/worldforge/mod/economy/TreasuryArchiveMigration.java"
    find wf-*/src/main/java -name '*.java' -not -path '*/mc/*' \
      ! -path 'wf-relations/src/main/java/dev/worldforge/relations/NpcSocialSimulation.java'
  } | sort -u
)
SRC=()
for source in "${CANDIDATE_SRC[@]}"; do
  if grep -Eq '(^|[[:space:]])import[[:space:]]+(net\.minecraft|net\.minecraftforge|com\.mojang\.brigadier)(\.|;)|net\.minecraft\.|net\.minecraftforge\.|com\.mojang\.brigadier\.' "$source"; then
    continue
  fi
  SRC+=("$source")
done
# Explicitly include pure helpers that currently live in Minecraft adapter
# packages. Their tests are intentionally standalone and do not require MC types.
PURE_ADAPTER_SRC=(
  wf-settlements/src/main/java/dev/worldforge/settle/mc/ColonySimulationCadence.java
  wf-settlements/src/main/java/dev/worldforge/settle/mc/ResidentLifeBatch.java
  wf-people/src/main/java/dev/worldforge/people/mc/NpcSpawnSpatialIndex.java
  wf-people/src/main/java/dev/worldforge/people/mc/NpcSpawnTargetLookup.java
  "$CORE/src/main/java/dev/worldforge/mod/economy/TreasuryArchiveMigration.java"
  tests/support/dev/worldforge/people/mc/PeopleMod.java
)
for source in "${PURE_ADAPTER_SRC[@]}"; do
  if [ ! -f "$source" ]; then
    echo "ERROR: expected pure adapter helper is missing: $source"
    exit 2
  fi
  SRC+=("$source")
done
if [ "${#SRC[@]}" -eq 0 ]; then
  echo "ERROR: no framework-independent production sources discovered"
  exit 2
fi
echo "Compiling ${#SRC[@]} framework-independent production/testable sources (${#CANDIDATE_SRC[@]} filtered candidates plus ${#PURE_ADAPTER_SRC[@]} pure adapter helpers)"
javac -proc:none -encoding UTF-8 -d "$OUT/classes" "${SRC[@]}" tests/*.java tests/support/*.java

if [ "${3:-}" = "--image-identity-only" ]; then
  [ -n "$ACTUAL_PACK" ] || { echo "ERROR: --image-identity-only requires an actual .worldmap argument"; exit 2; }
  echo "== ImageWorldIdentityTest (actual map)"
  python3 tools/run-java-test.py 120 "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"
  exit $?
fi

groups_to_run=("$GROUP")
if [ "$GROUP" = "suite" ]; then groups_to_run=(npc worldgen politics life integration); fi
total_passed=0
total_failed=0
total_skipped=0
for active_group in "${groups_to_run[@]}"; do
  echo "===== WORLD_FORGE_TEST_GROUP=$active_group ====="
  passed=0
  failed=0
  skipped=0
  for source in tests/*.java; do
    test_name="$(basename "$source" .java)"
    assigned_group="$(test_group "$test_name")"
    if [ "$active_group" != "all" ] && [ "$active_group" != "$assigned_group" ]; then
      continue
    fi
    if [ "$test_name" = "ImageWorldIdentityTest" ]; then
      echo "== $test_name (skipped: requires the actual image-generated .worldmap)"
      skipped=$((skipped + 1))
      continue
    fi
    echo "== $test_name"
    if python3 tools/run-java-test.py 120 "$OUT/classes" "dev.worldforge.tests.$test_name" "$PACK"; then
      passed=$((passed + 1))
    else
      failed=$((failed + 1))
    fi
  done

  if [ -n "$ACTUAL_PACK" ] && { [ "$active_group" = "all" ] || [ "$active_group" = "worldgen" ]; }; then
    echo "== ImageWorldIdentityTest (actual image-generated map)"
    if python3 tools/run-java-test.py 120 "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"; then
      passed=$((passed + 1))
    else
      failed=$((failed + 1))
    fi
  fi
  echo "WORLD_FORGE_GROUP_RESULT=$active_group passed=$passed skipped=$skipped failed=$failed"
  total_passed=$((total_passed + passed))
  total_failed=$((total_failed + failed))
  total_skipped=$((total_skipped + skipped))
done

echo "WorldForge $GROUP logic tests: passed=$total_passed skipped=$total_skipped failed=$total_failed"
[ "$total_failed" -eq 0 ]
