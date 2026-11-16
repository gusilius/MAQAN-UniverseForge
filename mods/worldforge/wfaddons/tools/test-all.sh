#!/usr/bin/env bash
# Pure Java addon logic tests. A synthetic map fixture is supplied only to
# tests that explicitly need map data; it is not an autonomous world scenario.
set -euo pipefail
CORE="${1:-../wfmod}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

if [ -n "${2:-}" ]; then
  PACK="$2"
  [ -f "$PACK" ] || { echo "ERROR: supplied worldmap fixture not found: $PACK"; exit 2; }
else
  PACK="$OUT/worldforge-test.worldmap"
  python3 tests/create_world_fixture.py "$PACK"
fi

CP="$CORE/src/main/java"
mkdir -p "$OUT/classes"
mapfile -t SRC < <(find wf-*/src/main/java -name '*.java' -not -path '*/mc/*')
javac -proc:none -encoding UTF-8 -d "$OUT/classes" -sourcepath "$CP" "${SRC[@]}" tests/*.java tests/support/*.java

if [ "${3:-}" = "--image-identity-only" ]; then
  echo "== ImageWorldIdentityTest (actual map)"
  java -Dstdout.encoding=UTF-8 -cp "$OUT/classes" dev.worldforge.tests.ImageWorldIdentityTest "$PACK"
  exit $?
fi

passed=0
failed=0
skipped=0
for source in tests/*.java; do
  test_name="$(basename "$source" .java)"
  # This test is intentionally reserved for the dedicated real-image workflow.
  # The generic suite's generated fixture is named worldforge-test, not real-map;
  # running it here used to create a false failure before any identity assertion.
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

echo "WorldForge logic tests: passed=$passed skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ] || exit 1
