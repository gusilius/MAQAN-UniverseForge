#!/usr/bin/env bash
# Windows x64 Forge smoke test. Runs the full staged addon set and exits cleanly.
set -euo pipefail
JARS="$(cd "$1" && pwd)"
WORK="$(mktemp -d)"
FORGE="1.20.1-47.3.0"
SERVER_PID=""
cleanup() {
  if [ -n "$SERVER_PID" ]; then
    printf '%s\n' "stop" >> "$WORK/in.txt" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 2
    done
    taskkill.exe //PID "$SERVER_PID" //T //F >/dev/null 2>&1 || true
  fi
  if [ -f "$WORK/server.log" ]; then
    echo "--- Forge server log tail ---"
    tail -60 "$WORK/server.log"
    cp "$WORK/server.log" "$RUNNER_TEMP/worldforge-smoke-server.log" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT
cd "$WORK"
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar"
java -jar installer.jar --installServer > install.log 2>&1 || { tail -60 install.log; exit 2; }
echo "eula=true" > eula.txt
mkdir -p mods
cp "$JARS"/*.jar mods/
cat > server.properties <<'P'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
enable-command-block=false
P
: > in.txt
(tail -n +1 -f in.txt | java -Xmx3G @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/win_args.txt nogui > server.log 2>&1) &
SERVER_PID=$!
ready=0
for _ in $(seq 1 120); do
  if grep -q 'Done (' server.log 2>/dev/null; then ready=1; break; fi
  if grep -aqE 'Mod loading has failed|Failed to start|Exception in thread "main"|java.lang.Error' server.log 2>/dev/null; then
    tail -120 server.log
    exit 3
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -120 server.log; exit 3; }
  sleep 3
done
[ "$ready" = 1 ] || { tail -120 server.log; echo "ERROR: Windows Forge startup timed out"; exit 3; }
for command in "list" "wftroops types" "wftroops fort list" "wftroops list" "wfsettle list" "wftrade ambush" "wfform" "worldforge" "wfaction list" "wfworldsim status"; do
  printf '%s\n' "$command" >> in.txt
  sleep 3
done
sleep 5
if grep -aqE '\[(ERROR|FATAL)\]|Mod loading has failed|Exception in thread|Caused by:' server.log; then
  grep -aiE '\[(ERROR|FATAL)\]|Mod loading has failed|Exception in thread|Caused by:' server.log | head -80
  exit 4
fi
echo "WINDOWS_FORGE_ADDON_SMOKE=PASS"
