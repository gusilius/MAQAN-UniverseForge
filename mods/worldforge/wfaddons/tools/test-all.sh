#!/usr/bin/env bash
# Pure Java addon tests. Map/datapack integration tests are skipped only when
# the optional fixture archive is absent; they run normally when a path is supplied.
set -euo pipefail
CORE="${1:-../wfmod}"
PACK="${2:-}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
CP="$CORE/src/main/java"
mapfile -t SRC < <(find wf-*/src/main/java -name '*.java' -not -path '*/mc/*')
javac -proc:none -encoding UTF-8 -d "$OUT" -sourcepath "$CP" "${SRC[@]}" tests/*.java

passed=0
skipped=0
failed=0
for source in tests/*.java; do
  test_name="$(basename "$source" .java)"
  if grep -Eq 'a\[0\]|args\[0\]' "$source" && { [ -z "$PACK" ] || [ ! -f "$PACK" ]; }; then
    echo "SKIP $test_name — datapack fixture not supplied"
    skipped=$((skipped + 1))
    continue
  fi
  echo "== $test_name"
  if [ -n "$PACK" ]; then
    if java -Dstdout.encoding=UTF-8 -cp "$OUT" "dev.worldforge.tests.$test_name" "$PACK"; then passed=$((passed + 1)); else failed=$((failed + 1)); fi
  else
    if java -Dstdout.encoding=UTF-8 -cp "$OUT" "dev.worldforge.tests.$test_name"; then passed=$((passed + 1)); else failed=$((failed + 1)); fi
  fi
done

echo "WorldForge tests: passed=$passed skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ]
