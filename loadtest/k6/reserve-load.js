// 분산 부하 시나리오 ( open model, constant-arrival-rate ).
//
// 목표: 좌석/유저 경합을 최소화한 "순수 처리량 천장"을 본다.
//   - 유저  : loadtest1..USERS 를 setup 에서 로그인해 토큰 캐시 → iteration 마다 round-robin
//   - 좌석  : iterationInTest(런 전역 카운터)로 T1.. 를 전역 유니크 배정 ( 같은 좌석 경합 0 )
//   - 키    : idempotency-key / reservationNumber 를 매 요청 유니크 생성 ( 멱등 캐시/유니크 위반 회피 )
//
// 측정은 예약 전용 커스텀 메트릭(reserve_*)으로 한다 → setup 로그인 요청이 SLO/요약을 오염시키지 않는다.
// 여러 RATE 로 반복 측정하며 SLO(reserve p95, reserve_fail)가 깨지는 지점을 한계로 본다.

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend, Rate, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '60s';
const USERS = parseInt(__ENV.USERS || '1000', 10);
const SCHEDULE_ID = parseInt(__ENV.SCHEDULE_ID || '1', 10);
const RUN_ID = __ENV.RUN_ID || 'run';
const PASSWORD = __ENV.PASSWORD || 'test1234';

// 경합(hotspot) 모드: HOTSPOT_SEATS>0 이면 인기 좌석 K개(T1..TK)를 다수가 두고 경쟁 + 재시도 ( 대기열 use case ).
// 0 이면 기존 무경합 모드(좌석 전역 유니크 = 순수 처리량 천장).
const HOTSPOT_SEATS = parseInt(__ENV.HOTSPOT_SEATS || '0', 10);
const HOTSPOT_RETRY = parseInt(__ENV.HOTSPOT_RETRY || '3', 10);

// 포화 근처에서 지연이 커지면 도착률 유지를 위해 VU 가 많이 필요하다.
// 부족하면 dropped_iterations 가 늘어난다 → 요약에서 반드시 확인할 것.
const PREALLOC = parseInt(__ENV.PREALLOC || String(Math.min(RATE * 2, 5000)), 10);
const MAXVU = parseInt(__ENV.MAXVU || String(Math.min(RATE * 10, 12000)), 10);

// 예약 요청 전용 메트릭 ( setup 로그인과 분리 )
const reserveDur = new Trend('reserve_duration', true);
const reserveFail = new Rate('reserve_fail');
const reserveOk = new Counter('reserve_ok');
const reserveConflict = new Counter('reserve_conflict'); // 매진/경합으로 끝까지 못 잡음 ( 실패 아님, 정상 거동 )

export const options = {
  scenarios: {
    reserve: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PREALLOC,
      maxVUs: MAXVU,
    },
  },
  // SLO: 이 임계를 유지하는 최대 RATE 가 신뢰성 있는 "한계" 정의 ( 예약 요청 기준 )
  thresholds: {
    reserve_fail: ['rate<0.01'],
    reserve_duration: ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// setup: USERS 명 로그인 → 토큰 배열 캐시 ( 토큰 TTL 10분 > 한 스텝 길이 )
export function setup() {
  const tokens = [];
  for (let i = 1; i <= USERS; i++) {
    const res = http.post(`${BASE_URL}/reserve/login`, { username: `loadtest${i}`, password: PASSWORD }, { tags: { kind: 'login' } });
    const token = res.headers['Access'] || res.headers['access'];
    if (res.status === 200 && token) tokens.push({ u: `loadtest${i}`, t: token });
  }
  if (tokens.length === 0) {
    throw new Error('로그인으로 받은 토큰이 0개입니다. 시드(/reserve/init/bulk)가 됐는지, 앱이 떴는지 확인하세요.');
  }
  console.log(`setup: ${tokens.length}/${USERS} 유저 로그인 완료`);
  return { tokens };
}

// 단일 예약 POST
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

// 토큰 만료(401) 시 해당 유저 재로그인 후 1회 재시도 ( 액세스 TTL 10분 초과 soak 대응; 실패로 집계하지 않음 )
function relogin(username) {
  const r = http.post(`${BASE_URL}/reserve/login`, { username, password: PASSWORD }, { tags: { kind: 'login' } });
  return r.status === 200 ? (r.headers['Access'] || r.headers['access']) : null;
}
function reserveWithAuth(data, idx, seat, uniq) {
  let res = doReserve(data.tokens[idx].t, seat, uniq);
  if (res.status === 401) {                       // 토큰 만료 → 재로그인 후 1회 재시도
    const nt = relogin(data.tokens[idx].u);
    if (nt) { data.tokens[idx].t = nt; res = doReserve(nt, seat, uniq); }
  }
  return res;
}

export default function (data) {
  const i = exec.scenario.iterationInTest; // 런 전역 0,1,2...
  const idx = i % data.tokens.length;

  if (HOTSPOT_SEATS > 0) {
    // 경합 모드: 인기 좌석 T1..TK 를 두고 재시도하며 경쟁 ( 좌석은 영구 점유 → 매진 발생 )
    let got = false;
    let realError = false;
    for (let a = 0; a <= HOTSPOT_RETRY; a++) {
      const seatNum = 1 + Math.floor(Math.random() * HOTSPOT_SEATS); // 인기 좌석 무작위 선택
      const uniq = `${RUN_ID}-${i}-${a}`;                            // 시도마다 유니크 키
      const res = reserveWithAuth(data, idx, `T${seatNum}`, uniq);
      reserveDur.add(res.timings.duration);
      if (res.status === 200) { got = true; break; }       // 좌석 확보
      if (res.status !== 409) { realError = true; break; }  // 200 도 409(좌석경합) 도 아니면 진짜 에러
      // 409 = 좌석 이미 점유 → 다른 좌석 재시도
    }
    reserveOk.add(got ? 1 : 0);
    reserveFail.add(realError);                     // 진짜 에러만 SLO 실패로 집계
    if (!got && !realError) reserveConflict.add(1); // 끝까지 못 잡음 = 매진/경합 ( 정상 )
    check(null, { 'no real error (200 또는 좌석경합만)': () => !realError });
  } else {
    // 무경합 모드: 좌석 전역 유니크 ( 순수 처리량 천장 )
    const uniq = `${RUN_ID}-${i}`;
    const res = reserveWithAuth(data, idx, `T${i + 1}`, uniq);
    const ok = res.status === 200;
    reserveDur.add(res.timings.duration);
    reserveFail.add(!ok);
    if (ok) reserveOk.add(1);
    check(res, { 'reserve 200': (r) => r.status === 200 });
  }
}

// 스텝 요약을 stdout(한 줄) + JSON 파일(SUMMARY_OUT)로 출력 ( 예약 전용 메트릭 기준 )
export function handleSummary(data) {
  const m = data.metrics;
  const g = (name, key) => (m[name] && m[name].values && m[name].values[key] != null ? m[name].values[key] : 0);
  const dropped = g('dropped_iterations', 'count');
  const p95 = g('reserve_duration', 'p(95)');
  const failPct = g('reserve_fail', 'rate') * 100;
  // SLO: p95<1s & err<1% & dropped==0. dropped>0 은 생성기 미달이라 SLO 이전에 '측정 무효'.
  const verdict = dropped > 0 ? 'INVALID' : (p95 >= 1000 || failPct >= 1 ? 'FAIL' : 'OK');
  const line =
    `[${verdict}][RATE=${RATE}] reserve_ok=${g('reserve_ok', 'count')} conflict=${g('reserve_conflict', 'count')} ` +
    `p50=${g('reserve_duration', 'med').toFixed(0)} p95=${p95.toFixed(0)} ` +
    `p99=${g('reserve_duration', 'p(99)').toFixed(0)} max=${g('reserve_duration', 'max').toFixed(0)}ms ` +
    `fail=${failPct.toFixed(2)}% dropped=${dropped}`;
  const out = { stdout: '\n' + line + '\n' };
  if (__ENV.SUMMARY_OUT) out[__ENV.SUMMARY_OUT] = JSON.stringify(data);
  return out;
}
