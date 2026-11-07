#!/usr/bin/env bash
# Дымовой тест: ставит Forge-сервер 1.20.1, кладёт все jar, запускает, гоняет несколько команд и останавливает.
# Результат: сводка проблем из лога (ERROR / Exception / отказ загрузки мода). Запуск: smoke-server.sh <папка с jar>
set -uo pipefail
JARS="$(cd "$1" && pwd)"
W=$(mktemp -d); cd "$W"
FORGE=1.20.1-47.3.0
curl -fsSL -o installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/$FORGE/forge-$FORGE-installer.jar" || { echo "не скачался установщик Forge"; exit 2; }
java -jar installer.jar --installServer > install.log 2>&1 || { tail -40 install.log; echo "установка сервера не удалась"; exit 2; }
echo "eula=true" > eula.txt
mkdir -p mods && cp "$JARS"/*.jar mods/
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
( tail -n +1 -f in.txt | java -Xmx3G @user_jvm_args.txt @libraries/net/minecraftforge/forge/$FORGE/unix_args.txt nogui > server.log 2>&1 ) &
PID=$!
ok=0
for i in $(seq 1 90); do
  if grep -q 'Done (' server.log 2>/dev/null; then ok=1; break; fi
  if grep -qa 'Mod loading has failed\|Failed to start\|Exception in thread "main"\|java.lang.Error' server.log 2>/dev/null && ! pgrep -f unix_args >/dev/null; then break; fi
  sleep 4
done
if [ $ok = 1 ]; then
  for c in "list" "wftroops types" "wftroops fort list" "wftroops list" "wfsettle list" "wftrade ambush" "wfform" "worldforge" "wfaction list"; do echo "$c" >> in.txt; sleep 3; done
  sleep 5
  echo "stop" >> in.txt
  for i in $(seq 1 30); do pgrep -f unix_args >/dev/null || break; sleep 2; done
fi
pkill -f unix_args 2>/dev/null; pkill -f "tail -n +1 -f in.txt" 2>/dev/null
echo "=================== ИТОГ ЗАПУСКА: $([ $ok = 1 ] && echo 'сервер поднялся' || echo 'СЕРВЕР НЕ ПОДНЯЛСЯ')"
echo "--- загруженные моды:"; grep -a "Loading .* mods\|mods:" server.log | head -5
echo "--- ошибки и исключения:"
grep -aiE "\[(ERROR|FATAL)\]|Exception|Mod loading has failed|Caused by|missing|Failed to" server.log | grep -av "DEBUG" | head -80
echo "--- ответы команд:"; grep -aE "Soldier|солдат|Крепостей|Засад|поселен|There are" server.log | head -20
echo "--- хвост лога:"; tail -25 server.log
cp server.log /tmp/smoke-server.log 2>/dev/null || true
[ $ok = 1 ] && ! grep -aqE "\[(ERROR|FATAL)\]" server.log
