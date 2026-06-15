#!/usr/bin/env bash
# 측정 중 app(actuator) + MySQL(SHOW GLOBAL STATUS) + 컨테이너 CPU 지표를 1초 간격으로 타임스탬프 CSV 로 떨군다.
# k6 스텝 경계(steps.csv)와 ts 로 정렬하면 "RATE=X 에서 어느 지표가 먼저 포화되는지" 상관분석이 된다.
#
# 사용: loadtest/scripts/monitor.sh [BASE_URL] [OUT_CSV]
# 의존: curl, jq, docker
set -uo pipefail

BASE_URL="${1:-http://localhost:18080}"
OUT="${2:-metrics.csv}"
INTERVAL="${INTERVAL:-1}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-reserve-mysql}"
APP_CONTAINER="${APP_CONTAINER:-reserve-app}"
MYSQL_PW="${MYSQL_PW:-0136}"
ACT="$BASE_URL/actuator/metrics"

# 컨테이너 CPU%( docker stats )는 호출당 ~1-2s 가 걸려 1s 메인 루프를 막는다.
# → 백그라운드에서 --no-stream 을 반복 호출해 캐시 파일을 갱신하고, 메인 루프는 캐시를 논블로킹으로 읽는다.
# CPU% 기준: cpuset 핀이므로 100%=1코어. mysql(4-5)=최대 200%, app(0-3)=최대 400%.
#   ⇒ 풀 vs DB 판별의 핵심 지표: mysql_cpu_pct≈200% 면 DB CPU 가 천장(풀을 올려도 안 오름).
CPU_CACHE="$(mktemp)"
( while true; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' \
      "$MYSQL_CONTAINER" "$APP_CONTAINER" 2>/dev/null > "$CPU_CACHE.tmp" \
      && mv "$CPU_CACHE.tmp" "$CPU_CACHE" || sleep 1   # docker 장애 시 busy-spin 방지
  done ) &
CPU_PID=$!
cleanup() { kill "$CPU_PID" 2>/dev/null; rm -f "$CPU_CACHE" "$CPU_CACHE.tmp"; }
trap cleanup EXIT
trap 'exit 0' INT TERM

# actuator 단일 메트릭 값 (없으면 0)
m() { curl -s --max-time 2 "$ACT/$1" | jq -r '.measurements[0].value // 0' 2>/dev/null || echo 0; }
# MySQL 글로벌 상태값
s() { docker exec "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PW" -N -s \
        -e "SHOW GLOBAL STATUS LIKE '$1'" 2>/dev/null | awk '{print $2}'; }
# 컨테이너 CPU%( 백그라운드 캐시에서 논블로킹 읽기, 없으면 0 )
cpu() {
  [ -s "$CPU_CACHE" ] || { echo 0; return; }
  awk -v c="$1" '$1==c{gsub(/%/,"",$2); print $2+0; f=1} END{if(!f) print 0}' "$CPU_CACHE"
}

echo "ts,hikari_active,hikari_pending,hikari_idle,tomcat_busy,jvm_threads,proc_cpu,sys_cpu,mysql_threads_running,mysql_threads_connected,mysql_row_lock_waits,com_insert,com_update,mysql_cpu_pct,app_cpu_pct" > "$OUT"

echo "monitor: $OUT 에 ${INTERVAL}s 간격 기록 시작 (Ctrl-C 로 종료)"
while true; do
  TS=$(date +%s)
  HA=$(m hikaricp.connections.active);  HP=$(m hikaricp.connections.pending);  HI=$(m hikaricp.connections.idle)
  TB=$(m tomcat.threads.busy);          JT=$(m jvm.threads.live)
  PC=$(m process.cpu.usage);            SC=$(m system.cpu.usage)
  TR=$(s Threads_running);              TC=$(s Threads_connected)
  RL=$(s Innodb_row_lock_current_waits)
  CI=$(s Com_insert);                   CU=$(s Com_update)
  MC=$(cpu "$MYSQL_CONTAINER");         AC=$(cpu "$APP_CONTAINER")
  echo "${TS},${HA},${HP},${HI},${TB},${JT},${PC},${SC},${TR:-},${TC:-},${RL:-},${CI:-},${CU:-},${MC:-0},${AC:-0}" >> "$OUT"
  sleep "$INTERVAL"
done
