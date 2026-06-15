#!/usr/bin/env bash
# 버스트 흡수 게이트: 베이스라인 지속부하 위에 "한 틱 배치 승격"(B명 순간 투입)을 쏘고,
#   트랜지언트를 SLO 안에서 흡수·회복하는 최대 B 를 찾는다.
#   → 이 B 가 곧 "대기열이 한 번에 승격해도 되는 최대 배치(승격 배치 상한)".
#     capacity(N)와는 별개의 손잡이다 — N 은 지속(soak)이 정하고, B 는 버스트가 정한다.
#
# 판정(스파이크별): 스파이크 p95<SPIKE_P95 & 진짜에러<1% & dropped==0 & 오버셀0
#                 & 회복시간(pending 이 베이스라인 근처로 복귀)≤ RECOVER_MAX.
#
# 사용: BASELINE_RATE=400 SPIKE_SIZES="50 100 200 400" loadtest/scripts/run-burst.sh
# 의존: curl, jq, k6, docker
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOADTEST_DIR="$(dirname "$SCRIPT_DIR")"

BASE_URL="${BASE_URL:-http://localhost:18080}"
USERS="${USERS:-3000}"
BASELINE_RATE="${BASELINE_RATE:-400}"   # 정상 운영점 ( 보통 λ_safe × 0.7 )
SPIKE_SIZES="${SPIKE_SIZES:-50 100 200 400}"
SPIKE_AT="${SPIKE_AT:-30s}"             # 베이스라인 데운 뒤 발사
DURATION="${DURATION:-90s}"            # 발사 후 회복까지 관측되도록 충분히
SPIKE_P95="${SPIKE_P95:-2000}"         # 스파이크 순간 허용 p95(ms)
RECOVER_MAX="${RECOVER_MAX:-15}"       # pending 회복 허용 시간(s)
SPIKE_SEAT_OFFSET="${SPIKE_SEAT_OFFSET:-500000}"
COOLDOWN="${COOLDOWN:-8}"

# 좌석: baseline(T1..) + spike(T{OFFSET..}) 영역이 모두 덮이게.
MAX_SPIKE=$(for b in $SPIKE_SIZES; do echo "$b"; done | sort -n | tail -1)
SEATS_DEFAULT=$(( SPIKE_SEAT_OFFSET + MAX_SPIKE + 10000 ))
SEATS="${SEATS:-$SEATS_DEFAULT}"

SPIKE_AT_SEC="${SPIKE_AT%s}"

RESULTS_DIR="$LOADTEST_DIR/results/burst-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"
echo "결과 디렉터리: $RESULTS_DIR  ( baseline=$BASELINE_RATE, spikes=[$SPIKE_SIZES], SEATS=$SEATS )"

echo "앱 기동 대기..."
until curl -sf --max-time 2 "$BASE_URL/actuator/health" >/dev/null 2>&1; do sleep 2; done
echo "앱 OK"

echo "시드: users=$USERS seats=$SEATS ..."
SEED_JSON=$(curl -s -X POST "$BASE_URL/reserve/init/bulk?users=$USERS&seats=$SEATS")
SCHEDULE_ID=$(echo "$SEED_JSON" | jq -r '.scheduleId')
if [[ -z "$SCHEDULE_ID" || "$SCHEDULE_ID" == "null" ]]; then
  echo "시드 실패: scheduleId 를 못 받았습니다." >&2; exit 1
fi
echo "scheduleId=$SCHEDULE_ID"

"$SCRIPT_DIR/monitor.sh" "$BASE_URL" "$RESULTS_DIR/metrics.csv" &
MON_PID=$!
trap 'kill $MON_PID 2>/dev/null || true' EXIT

echo "spike_size,base_p95,spike_p95,spike_max,max_pending,recover_s,real_err,dropped,oversell,verdict" > "$RESULTS_DIR/burst.csv"
BEST=0
for B in $SPIKE_SIZES; do
  echo ""
  echo "==================== baseline=$BASELINE_RATE + SPIKE=$B ===================="
  curl -s -X POST "$BASE_URL/reserve/init/bulk/reset?scheduleId=$SCHEDULE_ID" >/dev/null
  RUN_ID="b${B}-$(date +%s)"
  RUN_START=$(date +%s)

  k6 run \
    --env BASE_URL="$BASE_URL" --env USERS="$USERS" --env SCHEDULE_ID="$SCHEDULE_ID" --env RUN_ID="$RUN_ID" \
    --env BASELINE_RATE="$BASELINE_RATE" --env SPIKE_SIZE="$B" --env SPIKE_AT="$SPIKE_AT" \
    --env DURATION="$DURATION" --env SPIKE_P95="$SPIKE_P95" --env SPIKE_SEAT_OFFSET="$SPIKE_SEAT_OFFSET" \
    --env SUMMARY_OUT="$RESULTS_DIR/k6-spike-${B}.json" \
    "$LOADTEST_DIR/k6/reserve-burst.js" 2>&1 | tee "$RESULTS_DIR/k6-spike-${B}.log" || true

  # k6 집계
  JSON="$RESULTS_DIR/k6-spike-${B}.json"
  BP=$(jq -r '.metrics.reserve_base_duration.values["p(95)"] // 0 | floor' "$JSON" 2>/dev/null || echo 0)
  SP=$(jq -r '.metrics.reserve_spike_duration.values["p(95)"] // 0 | floor' "$JSON" 2>/dev/null || echo 0)
  SM=$(jq -r '.metrics.reserve_spike_duration.values.max // 0 | floor' "$JSON" 2>/dev/null || echo 0)
  FERR=$(jq -r '((.metrics.reserve_fail.values.rate // 0)*10000|floor)/100' "$JSON" 2>/dev/null || echo 0)
  DROP=$(jq -r '.metrics.dropped_iterations.values.count // 0' "$JSON" 2>/dev/null || echo 0)

  # 오버셀
  OV=$(curl -s -X POST "$BASE_URL/reserve/init/bulk/verify?scheduleId=$SCHEDULE_ID" | jq -r '.oversell')

  # 회복 시간: 스파이크 발사 epoch 이후, hikari_pending 이 "스파이크 직전 베이스라인" 근처로 복귀하기까지(s).
  SPIKE_EPOCH=$(( RUN_START + SPIKE_AT_SEC ))
  RECOVER=$(awk -F, -v sp="$SPIKE_EPOCH" '
    NR==1 { next }
    { ts[n]=$1; pend[n]=$3+0; n++ }
    END {
      base=0; bc=0;
      for(i=0;i<n;i++) if (ts[i]>=sp-5 && ts[i]<sp) { base+=pend[i]; bc++ }     # 발사 직전 5s 평균
      base = (bc>0)? base/bc : 0;
      maxp=0; for(i=0;i<n;i++) if (ts[i]>=sp) if (pend[i]>maxp) maxp=pend[i];
      rec=-1;
      for(i=0;i<n;i++) if (ts[i]>=sp && pend[i] <= base+2) { rec=ts[i]-sp; break }
      printf "%d %d", maxp, rec                                                  # max_pending, recover_s(-1=미회복)
    }' "$RESULTS_DIR/metrics.csv" 2>/dev/null || echo "0 -1")
  MAXPEND="${RECOVER% *}"; RECS="${RECOVER#* }"

  # 종합 판정
  VERDICT="OK"
  if [ "$DROP" != "0" ]; then VERDICT="INVALID(dropped=$DROP)";
  elif [ "$OV" = "true" ]; then VERDICT="FAIL(oversell)";
  elif awk -v f="$FERR" 'BEGIN{exit !(f>=1)}'; then VERDICT="FAIL(err=${FERR}%)";
  elif [ "$SP" -ge "$SPIKE_P95" ]; then VERDICT="FAIL(spike_p95=$SP)";
  elif [ "$RECS" = "-1" ]; then VERDICT="FAIL(미회복)";
  elif [ "$RECS" -gt "$RECOVER_MAX" ]; then VERDICT="FAIL(회복 ${RECS}s)";
  fi
  [ "$VERDICT" = "OK" ] && BEST="$B"

  echo "  → base_p95=${BP} spike_p95=${SP} spike_max=${SM}ms max_pending=${MAXPEND} 회복=${RECS}s err=${FERR}% oversell=${OV} → [$VERDICT]"
  echo "$B,$BP,$SP,$SM,$MAXPEND,$RECS,$FERR,$DROP,$OV,$VERDICT" >> "$RESULTS_DIR/burst.csv"
  sleep "$COOLDOWN"
done

echo ""
echo "==== 버스트 흡수 요약 ( baseline=$BASELINE_RATE ) ===="
cat "$RESULTS_DIR/burst.csv"
echo ""
echo "흡수 가능한 최대 배치(승격 배치 상한) ≈ ${BEST:-측정 구간 내 OK 없음}"
echo "결과: $RESULTS_DIR ( burst.csv=판정 / metrics.csv=트랜지언트 시계열 )"
