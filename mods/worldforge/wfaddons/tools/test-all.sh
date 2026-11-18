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
mapfile -t SRC < <(
  {
    find "$CORE/src/main/java" -name '*.java' -not -path '*/mc/*'
    find wf-*/src/main/java -name '*.java' -not -path '*/mc/*' \
      ! -path 'wf-relations/src/main/java/dev/worldforge/relations/NpcSocialSimulation.java'
  } | sort -u
)
if [ "${#SRC[@]}" -eq 0 ]; then
  echo "ERROR: no framework-independent production sources discovered"
  exit 2
fi
echo "Compiling ${#SRC[@]} pure production sources and domain tests"
javac -proc:none -encoding UTF-8 -d "$OUT/classes" "${SRC[@]}" tests/*.java tests/support/*.java

if [ "${3:-}" = "--image-identity-only" ]; then
  [ -n "$ACTUAL_PACK" ] || { echo "ERROR: --image-identity-only requires an actual .worldmap argument"; exit 2; }
  echo "== ImageWorldIdentityTest (actual map)"
  java -Dstdout.encoding=UTF-8 -cp "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"
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
  if java -Dstdout.encoding=UTF-8 -cp "$OUT/classes" "dev.worldforge.tests.$test_name" "$PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
done

if [ -n "$ACTUAL_PACK" ]; then
  echo "== ImageWorldIdentityTest (actual image-generated map)"
  if java -Dstdout.encoding=UTF-8 -cp "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
fi

echo "WorldForge logic tests: passed=$passed skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ]
