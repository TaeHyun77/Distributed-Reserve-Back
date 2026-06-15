# 부하 테스트 신뢰성 개선 + OFAT 튜닝 설계

작성일: 2026-06-12 · 대상: `loadtest/` 하니스 + 예약 처리량 천장 측정

## 배경 / 목표

flush=2 적용으로 처리량이 ~290 → ~822rps 로 뛰었지만, "병목이 Hikari 풀(30)이다"는
결론은 **검증되지 않았다**. `app CPU 2.4%` 는 "앱이 병목 아님"만 증명할 뿐, **풀 부족인지
DB 포화인지는 구분하지 못한다.** 이를 가르는 단 하나의 지표(MySQL 컨테이너 CPU)가 현재
`monitor.sh` 에 빠져 있다.

목표:
1. **신뢰할 수 있는 baseline 측정**을 먼저 만든다(하니스 신뢰성 수정).
2. 그 위에서 **튜닝 레버를 하나씩(OFAT)** 바꿔가며 **안정 경계(SLO 유지 최대 RATE)**가
   어디까지인지, 그리고 그 천장의 **정체(풀 vs DB)**가 무엇인지 데이터로 확정한다.

## 확정된 실험 셋업

| 항목 | 값 | 근거 |
|---|---|---|
| baseline 내구성 | `innodb_flush_log_at_trx_commit=2` | Mac Docker 에선 flush=1 이 VM 의 fsync 에뮬레이션 노이즈를 측정하는 꼴. flush 는 별도 레버. |
| 부하 생성기 | 같은 Mac (app 0-3 / mysql 4-5 / k6 나머지 핀) | 별도 머신 없음 → 신뢰 구간 ≤~800rps, 상대 비교 중심. |
| 경계(SLO) | `p95<1s` AND `error<1%` AND `dropped_iterations==0` | dropped>0 은 생성기 미달 → SLO 이전에 측정 무효. |
| 측정 방법 | 굵은 스윕 → 가는 스윕(50rps 간격) | 재현성·전후 비교 곡선. (이분탐색은 YAGNI) |

## Phase A — 하니스 신뢰성 수정 (4파일 + README)

1. **`scripts/monitor.sh`** — MySQL/app **컨테이너 CPU%** 추가(`mysql_cpu_pct`,`app_cpu_pct`).
   `docker stats` 는 호출당 ~1-2s 라 1s 루프를 막으므로, 백그라운드 `--no-stream` 리더가
   캐시 파일을 갱신하고 메인 루프는 논블로킹으로 읽는다. cpuset 핀이라 100%=1코어
   (mysql 최대 200%, app 최대 400%). **풀 vs DB 판별의 핵심 지표.**
2. **`scripts/run-ceiling.sh`** — `USERS` 기본 3000(member 락 충돌 억제), 경계 탐색용
   `RATES="400 600 700 750 800 850 900"`, `RATE>900` 경고, 스텝마다 `[OK]/[FAIL]/[INVALID]`
   자동 판정(jq), 종료 시 `verdicts.csv` + 안정 경계 요약 출력.
3. **`k6/reserve-load.js`** — `handleSummary` 한 줄 요약에 `[OK]/[FAIL]/[INVALID]` 프리픽스.
   `dropped>0` 이면 SLO 이전에 `INVALID`.
4. **`mysql/my.cnf`** — baseline `flush=2` + 주석(레버/프로덕션 차이 명시).
5. **`README.md`** — 결과 해석에 `mysql_cpu_pct` 기준 추가.

> 의도적 비범위: 자동 병목 분류기, SLO 리포트 테이블 자동화(선택 '핵심만' 밖).

## Phase B — OFAT 튜닝 런북 (한 번에 한 레버)

공통 절차: 리셋 → 가는 스윕 → 경계 RATE 확정 → (`mysql_cpu_pct`·`mysql_threads_running`·
`hikari_pending`·`proc_cpu` 동시 기록) → baseline 대비 비교.

| 순서 | 레버 | 가설 / 판정 |
|---|---|---|
| **L0** | baseline 경계 확정 (flush=2, pool=30, threads=200) | 기준선(예상 ~750–820) |
| **L1** | Hikari pool **8→16→30→50→80** | 위로 경계↑·`mysql_cpu_pct` 미포화 = 풀이 병목 / 정체·`mysql_cpu_pct`≈200% = **DB 가 벽, 30 이미 과다** → 피크 풀 채택 |
| **L2** | flush **1 vs 2** (피크 풀 고정) | Mac fsync 비용·내구성 트레이드오프 정량화 |
| **L3**(선택) | threads.max 16/100/200/400 | "스레드만으론 경계 안 오름" 평행선 — 리포트용, 개선 아님 |
| **(설계)** | idempotency commit 2→1 | 코드 변경 → 별도 브랜치 + 정합성 검토 후. 효과 크면 채택 |

**종료 판정**: `mysql_cpu_pct`≈200% 에 붙고 경계가 더 안 오르면 → **단일 DB 쓰기 처리량 = 천장**
확정 → 이후는 스케일아웃(분산)의 명분.

## 역할 / 산출물

- **나(Claude)**: 하니스 수정 + 각 런 결과 해석 + 다음 레버 결정. (로컬 docker/k6 직접 실행 불가)
- **당신**: `docker compose up` + `run-ceiling.sh` 실행 + 결과 공유.
- **레버별 기록 템플릿**: `레버 | 설정 | 경계 RATE(OK 최대) | mysql_cpu_pct | hikari_pending | proc_cpu | 비고`
