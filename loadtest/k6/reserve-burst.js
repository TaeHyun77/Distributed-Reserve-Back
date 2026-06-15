// 버스트 흡수 테스트 ( 베이스라인 지속부하 + 한 번의 배치 스파이크 ).
//
// 목적: 대기열 스케줄러가 "한 틱에 B명"을 한꺼번에 참가열로 승격시킬 때(=배치 입장),
//       예약 시스템이 그 순간 트랜지언트를 SLO 안에서 흡수하고 곧 회복하는가를 본다.
//   - capacity(N) 가 아니라 "한 번에 승격해도 되는 최대 배치(B)"를 찾는 게 목적.
//   - run-burst.sh 가 B(SPIKE_SIZE)를 스윕하며 흡수 가능한 최대 B 를 찾는다.
//
// 부하 모델: 좌석 경합은 끄고( 전역 유니크 좌석 ) "순수 흡수력"만 본다.
//   - baseline : constant-arrival-rate (지속) — 정상 운영점 근처
//   - spike    : shared-iterations 로 startTime 에 B건을 ~순간 투입 (배치 승격 모사)
// 트랜지언트 형태(pending 급등 + 회복 시간)는 monitor.sh 의 metrics.csv 로 본다.

import http from 'k6/http';
import exec from 'k6/execution';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const DURATION = __ENV.DURATION || '90s';
const USERS = parseInt(__ENV.USERS || '2000', 10);
const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1', 10);
const RUN_ID = __ENV.RUN_ID || 'burst';
const PASSWORD = __ENV.PASSWORD || 'test1234';

// 베이스라인 지속 도착률 ( 보통 λ_safe × 0.7 정도의 정상 운영점 )
const BASELINE_RATE = parseInt(__ENV.BASELINE_RATE || '400', 10);
// 스파이크 = 한 틱에 한꺼번에 들어오는 인원(B). run-burst.sh 가 이 값을 스윕한다.
const SPIKE_SIZE = parseInt(__ENV.SPIKE_SIZE || '200', 10);
// 스파이크 발사 시점 ( 베이스라인이 데워진 뒤 ). 회복 분석 기준점이 된다.
const SPIKE_AT = __ENV.SPIKE_AT || '30s';
// 스파이크 트랜지언트 합격선: 순간 p95 는 정상 SLO(1s)보다 느슨하게 허용하되 회복돼야 함.
const SPIKE_P95 = parseInt(__ENV.SPIKE_P95 || '2000', 10);

// 좌석 충돌 회피: baseline 은 T1.. , spike 는 T{OFFSET+..} 로 영역 분리 ( 시드 SEATS 가 둘을 모두 덮어야 함 ).
const SPIKE_SEAT_OFFSET = parseInt(__ENV.SPIKE_SEAT_OFFSET || '500000', 10);

const PREALLOC = parseInt(__ENV.PREALLOC || String(Math.min(BASELINE_RATE * 2, 5000)), 10);
const MAXVU = parseInt(__ENV.MAXVU || String(Math.min(BASELINE_RATE * 10, 12000)), 10);
// 스파이크를 ~순간에 쏟으려면 VU 가 B 만큼 필요 ( 부족하면 1초 안에 다 못 나감 ).
const SPIKE_VUS = parseInt(__ENV.SPIKE_VUS || String(Math.min(SPIKE_SIZE, 3000)), 10);

const reserveDurBase = new Trend('reserve_base_duration', true);
const reserveDurSpike = new Trend('reserve_spike_duration', true);
const reserveFail = new Rate('reserve_fail');
const reserveOk = new Counter('reserve_ok');

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: BASELINE_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVU,
      exec: 'reserveIter',
    },
    spike: {
      executor: 'shared-iterations',
      vus: SPIKE_VUS,
      iterations: SPIKE_SIZE,
      maxDuration: '15s',
      startTime: SPIKE_AT,
      exec: 'reserveIter',
    },
  },
  // 합격선: 스파이크 순간 p95 < SPIKE_P95, 진짜 에러 < 1%, 생성기 정상(dropped==0).
  // 회복 시간(트랜지언트가 베이스라인으로 돌아오기까지)은 run-burst.sh 가 metrics.csv 로 판정.
  thresholds: {
    reserve_fail: ['rate<0.01'],
    reserve_spike_duration: [`p(95)<${SPIKE_P95}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const res = http.post(`${BASE_URL}/reserve/login`, { username: `loadtest${i}`, password: PASSWORD }, { tags: { kind: 'login' } });
    const token = res.headers['Access'] || res.headers['access'];
    if (res.status === 200 && token) tokens.push(token);
  }
  if (tokens.length === 0) {
    throw new Error('로그인 토큰 0개. 시드(/reserve/init/bulk)와 앱 기동을 확인하세요.');
  }
  console.log(`setup: ${tokens.length}/${USERS} 유저 로그인 완료 (baseline=${BASELINE_RATE}rps, spike=${SPIKE_SIZE} @ ${SPIKE_AT})`);
  return { tokens };
}

function doReserve(token, seat, uniq) {
  const body = JSON.stringify({
    reservationNumber: uniq,
    rewardDiscountAmount: 0,
    seatNumbers: [seat],
    performanceScheduleId: SCHEDULE_ID,
  });
  return http.post(`${BASE_URL}/reserve`, body, {
    headers: {
      Authorization: `Bearer ${token}`,
      'idempotency-key': uniq,
      'Content-Type': 'application/json',
    },
    tags: { kind: 'reserve' },
  });
}

export function reserveIter(data) {
  const name = exec.scenario.name;                 // 'baseline' | 'spike'
  const i = exec.scenario.iterationInTest;         // 시나리오별 0,1,2...
  const token = data.tokens[i % data.tokens.length];

  // 좌석 영역 분리로 두 시나리오가 같은 좌석을 다투지 않게 ( 경합 0 = 순수 흡수력 측정 )
  const seatIdx = (name === 'spike' ? SPIKE_SEAT_OFFSET + i : i) + 1;
  const uniq = `${RUN_ID}-${name}-${i}`;

  const res = doReserve(token, `T${seatIdx}`, uniq);
  const ok = res.status === 200;
  if (name === 'spike') reserveDurSpike.add(res.timings.duration);
  else reserveDurBase.add(res.timings.duration);
  reserveFail.add(!ok);
  if (ok) reserveOk.add(1);
}

export function handleSummary(data) {
  const m = data.metrics;
  const g = (name, key) => (m[name] && m[name].values && m[name].values[key] != null ? m[name].values[key] : 0);
  const dropped = g('dropped_iterations', 'count');
  const baseP95 = g('reserve_base_duration', 'p(95)');
  const spikeP95 = g('reserve_spike_duration', 'p(95)');
  const spikeMax = g('reserve_spike_duration', 'max');
  const failPct = g('reserve_fail', 'rate') * 100;
  // dropped>0 = 생성기 미달 → 측정 무효. 그 외 스파이크 p95/err 로 흡수 판정 ( 회복은 sh 에서 ).
  const verdict = dropped > 0 ? 'INVALID' : (spikeP95 >= SPIKE_P95 || failPct >= 1 ? 'FAIL' : 'OK');
  const line =
    `[${verdict}][baseline=${BASELINE_RATE} spike=${SPIKE_SIZE}] ` +
    `base_p95=${baseP95.toFixed(0)} spike_p95=${spikeP95.toFixed(0)} spike_max=${spikeMax.toFixed(0)}ms ` +
    `ok=${g('reserve_ok', 'count')} fail=${failPct.toFixed(2)}% dropped=${dropped}`;
  const out = { stdout: '\n' + line + '\n' };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(data);
  return out;
}
