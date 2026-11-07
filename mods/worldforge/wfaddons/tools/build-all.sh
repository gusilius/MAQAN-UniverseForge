#!/usr/bin/env bash
# Собирает ядро, затем аддоны в порядке зависимостей (tools/build_order.py); jar каждого собранного аддона кладётся в libs/ следующих.
# Не останавливается на первой ошибке: собирает всё, что можно, и в конце печатает итог. Запуск из папки wfaddons: tools/build-all.sh ../wfmod
set -uo pipefail
CORE="${1:-../wfmod}"
( cd "$CORE" && gradle build --no-daemon ) || { echo "ЯДРО НЕ СОБРАЛОСЬ"; exit 1; }
CORE_JAR=$(ls "$CORE"/build/libs/worldforge-*.jar | grep -v sources | head -1)
ORDER=$(python3 tools/build_order.py)
echo "Порядок: $ORDER"
FAILED=""; OK=""
for d in $ORDER; do
  mkdir -p "$d/libs"; cp "$CORE_JAR" "$d/libs/"
  for dep in wf-*/build/libs/wf_*.jar; do [ -e "$dep" ] && [ "$(dirname "$(dirname "$(dirname "$dep")")")" != "$d" ] && cp "$dep" "$d/libs/" || true; done
  echo "=== $d"
  if ( cd "$d" && gradle build --no-daemon -q ); then OK="$OK $d"; else FAILED="$FAILED $d"; fi
done
mkdir -p dist && cp "$CORE_JAR" dist/ && cp wf-*/build/libs/wf_*.jar dist/ 2>/dev/null; ls -la dist
echo "СОБРАЛИСЬ:$OK"
echo "НЕ СОБРАЛИСЬ:${FAILED:- нет}"
[ -z "$FAILED" ]
