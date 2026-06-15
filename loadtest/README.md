# 부하 테스트 하니스 (단일 서버)

예약 시스템의 **처리량 천장(한계)** 을 신뢰성 있게 측정하기 위한 구성.
1차 목표는 **분산 부하 모델**(유저/좌석 경합을 최소화한 순수 처리량 천장)이며,
이후 분산 서버 구성과 비교하기 위한 단일 서버 베이스라인을 만든다.

## 측정 모델 / 한계 정의

- **부하 모델**: 유저(`loadtest1..N`)와 좌석(`T1..M`)을 매 요청 **전역 유니크**하게 배정 → member 비관 락·좌석 row 락 경합 제거. 막히는 곳은 **Hikari 풀(기본 30) / DB 쓰기**가 되도록 설계.
- **부하 형태**: k6 `constant-arrival-rate`(open model). 도착률(RATE)을 계단식으로 올리며 각 스텝을 steady-state 로 측정.
- **한계의 정의**: 박스가 죽을 때까지가 아니라 **SLO(`p95 < 1s`, `error < 1%`)를 유지하는 최대 RATE**. (임계값은 `reserve-load.js` thresholds 에서 조정)

## 사전 준비 (한 번)

1. **Docker Desktop 자원**: Settings > Resources 에서 **CPU 6 / Memory 12GB** 로 설정.
   - app `cpuset 0-2`, mysql `cpuset 3-5` (3:3 대칭) 로 핀 → DB 가 인위적 병목이 안 되게. 남는 vCPU 6-9 + 호스트가 k6 몫.
   - **부하 생성기는 컨테이너가 아니라 호스트에서 실행**해 측정 대상과 CPU 를 분리한다.
2. **호스트 도구 설치**: `brew install k6 jq`

## 실행

```bash
# 1) app + mysql 기동 (앱 이미지 빌드 포함)
docker compose -f loadtest/docker-compose.loadtest.yml up --build -d

# 2) 천장 측정 (헬스 대기 → 시드 → 레이트별 스텝)  ※ 저장소 루트에서 실행
loadtest/scripts/run-ceiling.sh

# 레이트/규모/스텝 길이 조정
RATES="200 400 600 800 1000 1200 1500" DURATION=90s USERS=2000 SEATS=200000 \
  loadtest/scripts/run-ceiling.sh

# 종료
docker compose -f loadtest/docker-compose.loadtest.yml down -v
```

수동 시드/리셋(드라이버 없이):

```bash
curl -X POST "localhost:18080/reserve/init/bulk?users=2000&seats=200000"
curl -X POST "localhost:18080/reserve/init/bulk/reset?scheduleId=1"
```

## 참가열 capacity 측정 (방법 A — 지속/버스트/산출)

`run-ceiling`(짧은 천장 탐색)과 달리, 참가열 입장 인원 산정용. **capacity 는 지속(soak)이 정하고, 버스트는 "승격 배치 상한"을 따로 정한다.** 설계: `docs/queue-capacity-test-design.md`.

```bash
# 1) 지속(soak): 한 RATE 를 장시간 유지 → 전구간 SLO + 오버셀 + 자원 드리프트 판정 → λ_safe
RATE=500 DURATION=30m loadtest/scripts/run-soak.sh

# 2) 버스트 게이트: 베이스라인 + "한 틱 배치 승격"(B명) 스윕 → 흡수·회복되는 최대 배치 B
BASELINE_RATE=350 SPIKE_SIZES="50 100 200 400" loadtest/scripts/run-burst.sh

# 3) 산출: N = λ_safe × W × 안전계수 (+ W 민감도 표). W=세션시간(초), 미측정 시 표로 가늠.
loadtest/scripts/compute-capacity.sh 500 120 0.6 200   # <λ_safe> [W] [안전계수] [배치상한B]
```

- soak 결과: `results/soak-<ts>/` ( `k6-soak.json`=집계, `metrics.csv`=시계열·드리프트 )
- burst 결과: `results/burst-<ts>/burst.csv` ( spike별 p95·회복시간·오버셀·판정 )

## 결과 해석 (`loadtest/results/<timestamp>/`)

- `k6-rate-<RATE>.json` — 스텝별 **p95/p99·RPS·error rate**. 여기서 SLO 가 깨지는 RATE = **한계**.
  - 함께 볼 것: `dropped_iterations`(>0 이면 k6 가 도착률을 못 채운 것 → `MAXVU` 상향 또는 생성기 포화 의심), 호스트 `top` 의 k6 CPU.
- `metrics.csv` — app/MySQL 지표 시계열. `steps.csv` 의 스텝 경계 ts 와 정렬해 **어느 계층이 먼저 포화되는지** 확인.
  - `hikari_pending` ↑ + `mysql_cpu_pct` 미포화 → 풀이 병목. `mysql_cpu_pct`≈200%(2코어 포화) → **DB CPU 가 천장**(풀 올려도 안 오름). `mysql_threads_running`/`row_lock_waits` ↑ → DB 락/IO 병목. `proc_cpu`≈1 또는 `app_cpu_pct`≈400% → app CPU 병목.
- MySQL 슬로우 쿼리: `docker exec reserve-mysql cat /var/lib/mysql/slow.log`

## 다음 단계 (P2 튜닝)

천장에서의 병목 계층에 따라 **Hikari 풀 ↔ Tomcat 스레드 ↔ MySQL `max_connections` 를 함께** 조정하며 전후 비교.
설정 위치: `application-loadtest.properties`(풀/스레드 baseline), `mysql/my.cnf`. **Tomcat 스레드만 단독으로 올리지 말 것** — 풀(30)이 병목이면 blocked 스레드만 늘어난다.

## 주의 / 한계

- **Mac + Docker = VM**: 절대 수치(`N TPS`)는 프로덕션 Linux 를 대표하지 못한다. **튜닝 전후·단일 vs 분산 같은 상대 비교**에 신뢰를 둘 것. 절대 한계가 필요하면 별도 Linux 호스트로 이전.
- **이메일**: `loadtest` 프로파일은 발송을 무력화(`NoOpMailSender`)한다. 외부 Gmail 로 실제 발송/스로틀이 일어나지 않음. (템플릿 렌더링 CPU 비용은 유지)
- **좌석 재고 ≥ peak_rate × step_seconds**: 기본 `SEATS=100000` 는 약 1600 RPS × 60s 까지 커버. 더 높은 RATE 를 길게 돌리면 `SEATS` 를 키울 것(아니면 좌석 소진→`SEAT_ALREADY_RESERVED` 를 처리량으로 오인).
- **actuator 노출**은 `health,metrics` 로만 한정(프로파일 설정). `env`/`configprops` 는 비밀 유출 방지를 위해 노출하지 않는다.
