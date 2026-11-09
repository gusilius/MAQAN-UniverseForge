#!/usr/bin/env bash
# Собирает ядро, затем аддоны в порядке зависимостей (tools/build_order.py); jar каждого собранного аддона кладётся в libs/ следующих.
# Не останавливается на первой ошибке: собирает всё, что можно, и в конце печатает итог. Запуск из папки wfaddons: tools/build-all.sh ../wfmod
set -uo pipefail
CORE="${1:-../wfmod}"
( cd "$CORE" && gradle build --no-daemon ) || { echo "ЯДРО НЕ СОБРАЛОСЬ"; exit 1; }
CORE_JAR=$(find "$CORE/build/libs" -maxdepth 1 -type f -name 'worldforge-*.jar' ! -name '*-sources.jar' -print -quit)
if [ -z "$CORE_JAR" ]; then echo "ERROR: ядро собрано без worldforge JAR"; exit 1; fi
ORDER=$(python3 tools/build_order.py) || { echo "ERROR: не удалось построить граф зависимостей аддонов"; exit 1; }
echo "Порядок: $ORDER"
declare -A DEP_MAP=()
while IFS='|' read -r module dep_list; do
  [ -n "$module" ] || continue
  DEP_MAP["$module"]="$dep_list"
done < <(python3 tools/build_order.py --deps) || { echo "ERROR: не удалось прочитать зависимости аддонов"; exit 1; }
FAILED=""; BLOCKED=""; OK=""
for d in $ORDER; do
  dep_list="${DEP_MAP[$d]-}"
  dep_array=()
  if [ -n "$dep_list" ]; then IFS=',' read -r -a dep_array <<< "$dep_list"; fi
  blocked_by=""
  for dep in "${dep_array[@]}"; do
    [ -n "$dep" ] || continue
    case " $FAILED " in *" $dep "*) blocked_by="$blocked_by $dep" ;; esac
  done
  if [ -n "$blocked_by" ]; then
    echo "=== SKIP $d (failed dependency:$blocked_by)"
    BLOCKED="$BLOCKED $d"; FAILED="$FAILED $d"; continue
  fi
  mkdir -p "$d/libs"
  rm -f "$d"/libs/wf_*.jar
  cp "$CORE_JAR" "$d/libs/"
  missing_dependency=""
  for dep in "${dep_array[@]}"; do
    [ -n "$dep" ] || continue
    found=0
    for jar in "$dep"/build/libs/wf_*.jar; do
      [ -f "$jar" ] || continue
      case "$jar" in *-sources.jar) continue ;; esac
      cp "$jar" "$d/libs/"
      found=1
    done
    if [ "$found" -eq 0 ]; then
      echo "ERROR: dependency $dep has no built addon jar required by $d"
      missing_dependency="$missing_dependency $dep"
    fi
  done
  if [ -n "$missing_dependency" ]; then
    echo "=== SKIP $d (missing dependency jar:$missing_dependency)"
    BLOCKED="$BLOCKED $d"; FAILED="$FAILED $d"; continue
  fi
  echo "=== $d"
  if ( cd "$d" && gradle build --no-daemon -q ); then
    OK="$OK $d"
  else
    FAILED="$FAILED $d"
  fi
done
mkdir -p dist
rm -f dist/wf_*.jar
cp "$CORE_JAR" dist/
for d in $OK; do
  for jar in "$d"/build/libs/wf_*.jar; do
    [ -f "$jar" ] || continue
    case "$jar" in *-sources.jar) continue ;; esac
    cp "$jar" dist/
  done
done
ls -la dist
echo "СОБРАЛИСЬ:$OK"
echo "НЕ СОБРАЛИСЬ:${FAILED:- нет}"
echo "ЗАБЛОКИРОВАНЫ ЗАВИСИМОСТЯМИ:${BLOCKED:- нет}"
[ -z "$FAILED" ]
