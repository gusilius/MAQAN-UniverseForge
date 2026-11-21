#!/usr/bin/env bash
# Pure Java domain tests. Forge/Minecraft adapters are compiled by each addon's
# real Gradle build above; this harness compiles only framework-independent logic.
set -euo pipefail
CORE="${1:-../wfmod}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

ACTUAL_PACK="${2:-}"
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
  "$CORE/src/main/java/dev/worldforge/mod/economy/TreasuryArchiveMigration.java"
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

passed=0
failed=0
skipped=0
for source in tests/*.java; do
  test_name="$(basename "$source" .java)"
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

if [ -n "$ACTUAL_PACK" ]; then
  echo "== ImageWorldIdentityTest (actual image-generated map)"
  if python3 tools/run-java-test.py 120 "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
fi

echo "WorldForge logic tests: passed=$passed skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ]
