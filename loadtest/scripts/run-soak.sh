#!/usr/bin/env bash
# 지속(soak) 측정: 단일 고정 RATE 를 장시간 유지하며 "시간이 지나야 터지는 것"을 잡는다.
#   짧은 스윕(run-ceiling)이 못 보는 것 — Hikari 풀 누수, @Async 이메일 백로그, GC 누적, 락 누적 —
#   을 드러내, 참가열 capacity 산정의 입력인 "지속 가능한 처리량 λ_safe"를 신뢰성 있게 잡는다.
#
# 판정: 전 구간 SLO( reserve p95<1s & err<1% ) 유지 + 오버셀 0 + 자원 드리프트 없음.
#   → 통과한 최대 RATE 가 λ_safe 후보. ( N = λ_safe × W × 안전계수 는 compute-capacity.sh )
#
# 사용:   RATE=500 DURATION=30m loadtest/scripts/run-soak.sh
# 경합도 같이 보려면(선택): HOTSPOT_SEATS=20000 ...  ( 단 좌석 소진 안 나게 SEATS 충분히 )
# 의존: curl, jq, k6, docker
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(dirname "$SCRIPT_DIR")"

BASE_URL="${BASE_URL:-http://localhost:18080}"
RATE="${RATE:-500}"
DURATION="${DURATION:-30m}"
USERS="${USERS:-3000}"        # 동시 실행 수보다 충분히 커야 member 비관락이 가짜 병목이 안 됨
HOTSPOT_SEATS="${HOTSPOT_SEATS:-0}"   # 0=무경합(순수 지속 처리량). >0 이면 경합 포함(소진 주의)
HOTSPOT_RETRY="${HOTSPOT_RETRY:-3}"
WARMUP_RATE="${WARMUP_RATE:-300}"
WARMUP_DURATION="${WARMUP_DURATION:-60s}"

# 소진 방지: 좌석 ≥ RATE × soak_seconds × 1.3 ( 무경합일 때 매 요청이 좌석 1개를 영구 점유 ).
dur_to_sec() { case "$1" in *m) echo $(( ${1%m} * 60 ));; *s) echo "${1%s}";; *h) echo $(( ${1%h} * 3600 ));; *) echo "$1";; esac; }
SOAK_SEC="$(dur_to_sec "$DURATION")"
SEATS_DEFAULT=$(( RATE * SOAK_SEC * 13 / 10 ))
SEATS="${SEATS:-$SEATS_DEFAULT}"

RESULTS_DIR="$LOADTEST_DIR/results/soak-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"
echo "결과 디렉터리: $RESULTS_DIR  ( RATE=$RATE, DURATION=$DURATION, SEATS=$SEATS, HOTSPOT=$HOTSPOT_SEATS )"

echo "앱 기동 대기..."
until curl -sf --max-time 2 "$BASE_URL/actuator/health" >/dev/null 2>&1; do sleep 2; done
echo "앱 OK"

echo "시드: users=$USERS seats=$SEATS ..."
SEED_JSON=$(curl -s -X POST "$BASE_URL/reserve/init/bulk?users=$USERS&seats=$SEATS")
echo "$SEED_JSON" | tee "$RESULTS_DIR/seed.json"; echo
SCHEDULE_ID=$(echo "$SEED_JSON" | jq -r '.scheduleId')
if [[ -z "$SCHEDULE_ID" || "$SCHEDULE_ID" == "null" ]]; then
  echo "시드 실패: scheduleId 를 못 받았습니다." >&2; exit 1
fi
echo "scheduleId=$SCHEDULE_ID"

# 워밍업 ( 측정 제외 — 콜드 JIT 왜곡 방지 )
echo "=== 워밍업 ${WARMUP_RATE}rps/${WARMUP_DURATION} (측정 제외) ==="
curl -s -X POST "$BASE_URL/reserve/init/bulk/reset?scheduleId=$SCHEDULE_ID" >/dev/null
k6 run --env BASE_URL="$BASE_URL" --env RATE="$WARMUP_RATE" --env DURATION="$WARMUP_DURATION" \
  --env USERS="$USERS" --env SCHEDULE_ID="$SCHEDULE_ID" --env RUN_ID="warmup" \
  "$LOADTEST_DIR/k6/reserve-load.js" >/dev/null 2>&1 || true
echo "워밍업 완료"

# 모니터링 백그라운드 ( 드리프트 분석의 핵심 — 시계열 자원 지표 )
"$SCRIPT_DIR/monitor.sh" "$BASE_URL" "$RESULTS_DIR/metrics.csv" &
MON_PID=$!
trap 'kill $MON_PID 2>/dev/null || true' EXIT

curl -s -X POST "$BASE_URL/reserve/init/bulk/reset?scheduleId=$SCHEDULE_ID" >/dev/null
RUN_ID="soak${RATE}-$(date +%s)"
echo "phase,ts_epoch" > "$RESULTS_DIR/steps.csv"
SOAK_START=$(date +%s)
echo "start,$SOAK_START" >> "$RESULTS_DIR/steps.csv"

echo ""
echo "==================== SOAK RATE=$RATE / $DURATION 시작 ===================="
k6 run \
  --env BASE_URL="$BASE_URL" --env RATE="$RATE" --env DURATION="$DURATION" \
  --env USERS="$USERS" --env SCHEDULE_ID="$SCHEDULE_ID" --env RUN_ID="$RUN_ID" \
  --env HOTSPOT_SEATS="$HOTSPOT_SEATS" --env HOTSPOT_RETRY="$HOTSPOT_RETRY" \
  --env SUMMARY_OUT="$RESULTS_DIR/k6-soak.json" \
  "$LOADTEST_DIR/k6/reserve-load.js" 2>&1 | tee "$RESULTS_DIR/k6-soak.log" || true
SOAK_END=$(date +%s)
echo "end,$SOAK_END" >> "$RESULTS_DIR/steps.csv"

# 1) 오버셀 검증
V=$(curl -s -X POST "$BASE_URL/reserve/init/bulk/verify?scheduleId=$SCHEDULE_ID")
OV=$(echo "$V" | jq -r '.oversell')

# 2) 전구간 SLO ( k6 집계 )
JSON="$RESULTS_DIR/k6-soak.json"
SLO=$(jq -r '
  (.metrics.dropped_iterations.values.count // 0) as $d
  | (.metrics.reserve_duration.values["p(95)"] // 0) as $p
  | (.metrics.reserve_fail.values.rate // 0) as $f
  | (if $d > 0 then "INVALID dropped=\($d)"
     elif $p >= 1000 then "FAIL p95=\($p|floor)ms"
     elif $f >= 0.01 then "FAIL err=\(($f*10000|floor)/100)%"
     else "OK" end) + " | p95=\($p|floor)ms err=\(($f*10000|floor)/100)%"' "$JSON" 2>/dev/null || echo "PARSE_ERR")

# 3) 자원 드리프트 ( metrics.csv 의 soak 구간을 앞 1/3 vs 뒤 1/3 으로 비교 ).
#    hikari_pending / app_cpu 가 시간이 갈수록 오르면 = 누수·백로그 의심 ( 짧은 테스트가 못 보는 것 ).
#    metrics.csv 헤더: ts,hikari_active,hikari_pending,...,proc_cpu,... ,app_cpu_pct(마지막)
DRIFT=$(awk -F, -v s="$SOAK_START" -v e="$SOAK_END" '
  NR==1 { next }
  $1>=s && $1<=e { ts[n]=$1; pend[n]=$3+0; appcpu[n]=$(NF)+0; n++ }
  END {
    if (n < 6) { print "n/a (샘플 부족)"; exit }
    t=int(n/3);
    for(i=0;i<t;i++){p1+=pend[i]; c1+=appcpu[i]}
    for(i=n-t;i<n;i++){p2+=pend[i]; c2+=appcpu[i]}
    p1/=t; p2/=t; c1/=t; c2/=t;
    flag = (p2 > p1 + 1 && p2 > p1 * 1.5) || (c2 > c1 * 1.3 && c2 > 50) ? "DRIFT" : "stable";
    printf "%s | pending 앞%.1f→뒤%.1f, app_cpu%% 앞%.0f→뒤%.0f", flag, p1, p2, c1, c2
  }' "$RESULTS_DIR/metrics.csv" 2>/dev/null || echo "n/a")

echo ""
echo "==================== SOAK 판정 ===================="
echo "  SLO(전구간) : $SLO"
echo "  오버셀       : $OV"
echo "  자원 드리프트 : $DRIFT"
echo ""
if [[ "$SLO" == OK* && "$OV" == "false" && "$DRIFT" != DRIFT* ]]; then
  echo "✅ PASS — RATE=$RATE 는 지속 가능. λ_safe 후보로 채택 가능."
  echo "   다음: loadtest/scripts/compute-capacity.sh $RATE  ( N = λ_safe × W × 안전계수 )"
else
  echo "❌ 미달 — 이 RATE 는 지속 불가( 위 항목 확인 ). 더 낮은 RATE 로 재측정."
fi
echo "결과: $RESULTS_DIR ( k6-soak.json=집계 / metrics.csv=시계열·드리프트 / steps.csv=구간경계 )"
