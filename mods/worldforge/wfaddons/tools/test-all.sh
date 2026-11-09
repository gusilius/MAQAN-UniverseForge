#!/usr/bin/env bash
# Pure Java addon tests. A deterministic synthetic world is generated when no
# external .worldmap is supplied; no tests are silently skipped for missing data.
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
mapfile -t SRC < <(find wf-*/src/main/java -name '*.java' -not -path '*/mc/*')
javac -proc:none -encoding UTF-8 -d "$OUT/classes" -sourcepath "$CP" "${SRC[@]}" tests/*.java

passed=0
failed=0
for source in tests/*.java; do
  test_name="$(basename "$source" .java)"
  echo "== $test_name"
  if java -Dstdout.encoding=UTF-8 -cp "$OUT/classes" "dev.worldforge.tests.$test_name" "$PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
done

echo "WorldForge tests: passed=$passed skipped=0 failed=$failed"
[ "$failed" -eq 0 ]
