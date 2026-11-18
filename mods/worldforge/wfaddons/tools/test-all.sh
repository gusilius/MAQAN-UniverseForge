#!/usr/bin/env bash
# Runs addon logic tests against the real classes and dependency graph produced by
# tools/build-all.sh. Do not recompile addon production sources with bare javac:
# Forge/Minecraft-bound classes require the Gradle-resolved compile classpath.
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

# Build classpath from the already-built core/addons plus the exact dependency
# jars Gradle resolved on this runner. This includes Forge classes needed by
# adapters such as wf-relations' NpcSocialSimulation without fake stubs.
CP_PARTS=()
for dir in "$CORE/build/classes/java/main" wf-*/build/classes/java/main; do
  [ -d "$dir" ] && CP_PARTS+=("$dir")
done
while IFS= read -r jar; do CP_PARTS+=("$jar"); done < <(
  find wf-*/libs "$HOME/.gradle/caches/modules-2/files-2.1" -type f -name '*.jar' 2>/dev/null | sort -u
)
CP="$(IFS=:; echo "${CP_PARTS[*]}")"
[ -n "$CP" ] || { echo "ERROR: no compiled addon classes found; run tools/build-all.sh first"; exit 2; }

mkdir -p "$OUT/classes"
javac -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT/classes" tests/*.java tests/support/*.java

if [ "${3:-}" = "--image-identity-only" ]; then
  [ -n "$ACTUAL_PACK" ] || { echo "ERROR: --image-identity-only requires an actual .worldmap argument"; exit 2; }
  echo "== ImageWorldIdentityTest (actual map)"
  java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$CP" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"
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
  if java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$CP" "dev.worldforge.tests.$test_name" "$PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
done

if [ -n "$ACTUAL_PACK" ]; then
  echo "== ImageWorldIdentityTest (actual image-generated map)"
  if java -Dstdout.encoding=UTF-8 -cp "$OUT/classes:$CP" dev.worldforge.tests.ImageWorldIdentityTest "$ACTUAL_PACK"; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
  fi
fi

echo "WorldForge logic tests: passed=$passed skipped=$skipped failed=$failed"
[ "$failed" -eq 0 ]
