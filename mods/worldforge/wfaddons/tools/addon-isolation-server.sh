#!/usr/bin/env bash
# Boot a core-only profile and one minimal mandatory-dependency Forge profile per addon.
# These are load-order/provider tests, not performance tests. Full Planetos remains unscaled.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JARS="$(cd "$1" && pwd)"
OUTPUT="$2"
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd)"
WORK="$(mktemp -d)"
FORGE="1.20.1-47.3.0"
SERVER_PID=""
SERVER_JAVA_PID=""
PROFILE_LOG=""
copy_diagnostics() {
  [ -f "$WORK/install.log" ] && cp "$WORK/install.log" "$OUTPUT/forge-install.log" 2>/dev/null || true
  [ -n "$PROFILE_LOG" ] && [ -f "$WORK/server.log" ] && cp "$WORK/server.log" "$OUTPUT/$PROFILE_LOG" 2>/dev/null || true
}
cleanup() {
  if [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || true
    SERVER_PID=""
  fi
  copy_diagnostics
  rm -rf "$WORK"
}
trap cleanup EXIT

python3 "$SCRIPT_DIR/addon-test-profiles.py" \
  --addons-root "$SCRIPT_DIR/.." --jars "$JARS" --output "$OUTPUT/profiles.json"
mapfile -t PROFILES < <(python3 "$SCRIPT_DIR/addon-test-profiles.py" \
  --addons-root "$SCRIPT_DIR/.." --jars "$JARS" --output "$OUTPUT/profiles.json" --lines)

cd "$WORK"
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar"
java -jar installer.jar --installServer > install.log 2>&1 || { tail -80 install.log; exit 2; }
echo "eula=true" > eula.txt

for profile in "${PROFILES[@]}"; do
  IFS='|' read -r profile_id jar_csv addon_csv <<< "$profile"
  [[ "$profile_id" =~ ^[a-zA-Z0-9_-]+$ ]] || { echo "Invalid profile id: $profile_id"; exit 2; }
  echo "=== ADDON_PROFILE_BEGIN=$profile_id addons=${addon_csv:-none}"
  PROFILE_LOG="profile-$profile_id.log"

  rm -rf mods config defaultconfigs logs "world-$profile_id"
  mkdir -p mods
  IFS=',' read -r -a profile_jars <<< "$jar_csv"
  for jar in "${profile_jars[@]}"; do
    [ -f "$JARS/$jar" ] || { echo "Missing staged jar: $jar"; exit 3; }
    cp "$JARS/$jar" mods/
  done

  cat > server.properties <<P
online-mode=false
level-name=world-$profile_id
level-type=minecraft\:flat
generate-structures=false
view-distance=3
simulation-distance=3
spawn-protection=0
enable-command-block=false
P
  : > in.txt
  rm -f server.log
  (tail -n +1 -f in.txt | java @user_jvm_args.txt -Xms512M -Xmx2G \
    @libraries/net/minecraftforge/forge/$FORGE/win_args.txt nogui > server.log 2>&1) &
  SERVER_PID=$!

  ready=0
  for _ in $(seq 1 90); do
    if grep -q 'Done (' server.log 2>/dev/null; then ready=1; break; fi
    if grep -aqE 'Mod loading has failed|Failed to start|Exception in thread "main"|java.lang.Error|A single server tick took' server.log 2>/dev/null; then
      tail -120 server.log
      copy_diagnostics
      exit 4
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || { tail -120 server.log; copy_diagnostics; exit 4; }
    sleep 2
  done
  if [ "$ready" -ne 1 ]; then
    tail -120 server.log
    copy_diagnostics
    echo "ERROR: profile $profile_id startup timed out"
    exit 5
  fi

  SERVER_JAVA_PID="$(jcmd -l | awk '/BootstrapLauncher|net.minecraft.server.Main/ {print $1; exit}')"
  if [ -z "$SERVER_JAVA_PID" ]; then
    echo "ERROR: could not identify the Forge server JVM for graceful shutdown"
    copy_diagnostics
    exit 5
  fi

  printf '%s\n' "list" "help" "stop" >> in.txt
  stopped=0
  for _ in $(seq 1 90); do
    if ! jcmd -l | awk '{print $1}' | grep -qx "$SERVER_JAVA_PID"; then stopped=1; break; fi
    sleep 1
  done
  if [ "$stopped" -ne 1 ]; then
    echo "ERROR: profile $profile_id did not shut down its Forge JVM cleanly"
    tail -100 server.log
    copy_diagnostics
    exit 6
  fi
  taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  copy_diagnostics
  if grep -aqE 'Mod loading has failed|Exception in thread|A single server tick took' server.log; then
    grep -aiE 'Mod loading has failed|Exception in thread|A single server tick took' server.log | head -80
    exit 7
  fi
  echo "ADDON_PROFILE_STATUS=PASS mod=$profile_id addons=${addon_csv:-none}"
done

echo "ADDON_ISOLATION_STATUS=PASS profiles=${#PROFILES[@]}"
