#!/usr/bin/env bash
# 참가열 capacity(N) 산출 — 측정값을 입장 파라미터로 변환한다.
#
#   N = λ_safe × W × 안전계수
#     λ_safe : run-soak.sh 가 통과시킨 지속 가능 처리량 (예약/초)
#     W      : 예약 세션 시간 (입장→좌석조회→고민→결제→이탈, 초). 리틀의 법칙의 연결고리.
#     안전계수: knee≠안전운영점 격차 + 경합 retry 증폭 + 트래픽 분산 + 성장 여유 (기본 0.6)
#
# W 는 운영/프론트에서 재야 정확하다. 한 값에 베팅하지 않도록 W 표를 함께 출력한다.
#
# 사용: loadtest/scripts/compute-capacity.sh <λ_safe> [W초] [안전계수] [승격배치상한B]
# 예:   loadtest/scripts/compute-capacity.sh 500 120 0.6 200
set -euo pipefail

LAMBDA="${1:?사용: compute-capacity.sh <λ_safe> [W초] [안전계수] [배치상한B]}"
W="${2:-120}"
SAFETY="${3:-0.6}"
BATCH="${4:-}"

awk -v lam="$LAMBDA" -v w="$W" -v sf="$SAFETY" -v batch="$BATCH" 'BEGIN{
  printf "\n참가열 capacity 산출\n";
  printf "  λ_safe(지속처리량) = %s 예약/초\n", lam;
  printf "  안전계수           = %s\n", sf;
  printf "  공식               = N = λ_safe × W × 안전계수\n\n";

  n = lam * w * sf;
  printf "  ▶ 헤드라인 (W=%ss): 참가열 capacity N ≈ %d 명  ( = %s × %s × %s )\n\n", w, n, lam, w, sf;

  printf "  W 민감도 ( 실제 W 를 재서 꽂아라 ):\n";
  printf "    %-10s %-12s\n", "W(초)", "N(명)";
  split("30 60 120 180 300", arr, " ");
  for (i=1;i<=5;i++){ ww=arr[i]; printf "    %-10s %-12d\n", ww, lam*ww*sf }

  if (batch != "") {
    printf "\n  승격 배치 상한(B, run-burst.sh) = %s 명/틱\n", batch;
    printf "  → capacity 는 위 N 으로 두되, 한 번에 채우는 인원은 B 이하로 (스케줄러 틱당 승격 제한).\n";
  }
  printf "\n  주의: 이 N 은 [초기값 + 상한 가드레일]. 운영은 예약 p95/DB pending 으로\n";
  printf "        실시간 적응(AIMD)시키고, 매진 임박 시 승격을 0으로 — 정적 숫자에 의존 금지.\n\n";
}'
