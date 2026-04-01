import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

// ========================
// 리워드 하루 한 번 지급 동시성 테스트
// 동일 유저가 동시에 리워드 요청 시 1번만 지급되는지 검증
//
// [ 테스트 방법 ]
// k6 run -e TARGET_VUS=10 reward-test.js
//
// - N명의 VU가 동일 유저로 동시에 리워드 요청
// - 모든 VU가 동일 시각에 요청을 발사하도록 동기화
// - 기대 결과: granted=true 1건, granted=false 나머지
// ========================

const BASE_URL = __ENV.BASE_URL || "http://localhost:8079";
const TARGET_VUS = parseInt(__ENV.TARGET_VUS || "10");
const SYNC_DELAY_SEC = parseInt(__ENV.SYNC_DELAY_SEC || "3");
const USERNAME = "@Reward123";
const PASSWORD = "reward1234";

// ── 커스텀 메트릭 ──
const grantedTrue = new Counter("granted_true");
const grantedFalse = new Counter("granted_false");
const unexpectedStatus = new Counter("unexpected_status");
const rewardDuration = new Trend("reward_request_duration");

export const options = {
  scenarios: {
    reward_burst: {
      executor: "per-vu-iterations",
      vus: TARGET_VUS,
      iterations: 1,
      maxDuration: "30s",
    },
  },
  thresholds: {
    granted_true: ["count==1"],
    unexpected_status: ["count==0"],
  },
};

// ── setup: 테스트 유저 초기화 + 로그인 + 동기화 시각 설정 ──
export function setup() {
  // 1) 테스트 유저 생성/초기화 (리워드 이력 포함)
  const initRes = http.post(`${BASE_URL}/reserve/init/reward-test`);
  const initOk = check(initRes, {
    "setup: 테스트 유저 초기화 성공": (r) => r.status === 200,
  });
  if (!initOk) {
    throw new Error(
      `테스트 유저 초기화 실패. status=${initRes.status}, body=${initRes.body}`
    );
  }

  // 2) 로그인 (LoginFilter는 form 파라미터 사용: request.getParameter)
  const loginRes = http.post(`${BASE_URL}/reserve/login`, {
    username: USERNAME,
    password: PASSWORD,
  });
  const loginOk = check(loginRes, {
    "setup: 로그인 성공": (r) => r.status === 200,
  });
  if (!loginOk) {
    throw new Error(
      `로그인 실패. status=${loginRes.status}, body=${loginRes.body}`
    );
  }

  // 3) access 토큰 추출
  const accessToken = loginRes.headers["Access"];
  if (!accessToken) {
    throw new Error(
      `access 토큰 획득 실패. status=${loginRes.status}, ` +
        `headers=${JSON.stringify(loginRes.headers)}`
    );
  }

  // 4) 동기화 목표 시각 설정 (현재 시각 + N초 뒤)
  const fireTime = Date.now() + SYNC_DELAY_SEC * 1000;
  console.log(
    `setup 완료. VU=${TARGET_VUS}, 동시 발사 시각=${new Date(fireTime).toISOString()}`
  );

  return { accessToken, fireTime };
}

// ── default: 동기화 대기 후 동시에 리워드 요청 ──
export default function (data) {
  // 모든 VU가 fireTime까지 대기하여 동시 요청 보장
  const now = Date.now();
  if (now < data.fireTime) {
    const waitSec = (data.fireTime - now) / 1000;
    sleep(waitSec);
  }

  // 리워드 지급 요청
  const res = http.post(`${BASE_URL}/reserve/member/get/reward`, null, {
    headers: {
      Authorization: `Bearer ${data.accessToken}`,
    },
    tags: { name: "reward_request" },
  });

  // 응답 시간 기록
  rewardDuration.add(res.timings.duration);

  // 응답 처리
  if (res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      if (body.granted === true) {
        grantedTrue.add(1);
      } else {
        grantedFalse.add(1);
      }
    } catch (e) {
      console.error(`응답 파싱 실패: ${res.body}`);
      unexpectedStatus.add(1);
    }
  } else if (res.status === 409 || res.status === 429) {
    // 서버가 Conflict / Too Many Requests로 중복 거부하는 경우
    grantedFalse.add(1);
  } else {
    console.error(
      `예상치 못한 응답. VU=${__VU}, status=${res.status}, body=${res.body}`
    );
    unexpectedStatus.add(1);
  }
}

// ── teardown: 테스트 유저 삭제 ──
export function teardown() {
  const cleanupRes = http.post(
    `${BASE_URL}/reserve/init/reward-test/cleanup`
  );
  check(cleanupRes, {
    "teardown: 테스트 유저 삭제 성공": (r) => r.status === 200,
  });
}

// ── handleSummary: 결과 요약 출력 ──
export function handleSummary(data) {
  const grantedCount = data.metrics.granted_true
    ? data.metrics.granted_true.values.count
    : 0;

  const deniedCount = data.metrics.granted_false
    ? data.metrics.granted_false.values.count
    : 0;

  const errorCount = data.metrics.unexpected_status
    ? data.metrics.unexpected_status.values.count
    : 0;

  const avgDuration = data.metrics.reward_request_duration
    ? data.metrics.reward_request_duration.values.avg.toFixed(1)
    : "N/A";

  const p95Duration = data.metrics.reward_request_duration
    ? data.metrics.reward_request_duration.values["p(95)"].toFixed(1)
    : "N/A";

  const totalResponses = grantedCount + deniedCount + errorCount;
  const passed = grantedCount === 1 && errorCount === 0;

  const summary = `
========================================
  리워드 동시성 테스트 결과
========================================
  동시 요청 수 (VU):  ${TARGET_VUS}
  총 응답 수:         ${totalResponses}
  ----------------------------------------
  granted=true:       ${grantedCount}건
  granted=false:      ${deniedCount}건
  예상치 못한 응답:    ${errorCount}건
  ----------------------------------------
  평균 응답 시간:      ${avgDuration}ms
  P95 응답 시간:       ${p95Duration}ms
  ----------------------------------------
  결과: ${
    passed
      ? "통과 - 하루 1회 지급 정상 동작"
      : "실패 - " +
        (grantedCount !== 1
          ? `동시성 제어 문제 (granted=true가 ${grantedCount}건)`
          : `예상치 못한 오류 ${errorCount}건 발생`)
  }
========================================
`;

  return {
    stdout: summary,
  };
}