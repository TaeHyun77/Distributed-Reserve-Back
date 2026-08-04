# 좌석 임시 점유(2단계 예약) 설계

작성일: 2026-08-03 · 목적: "좌석 선택 → 결제창 진입 시 좌석 홀드 → 결제 완료 시 확정"의 2단계 예약 흐름 도입

## 결론 요약

현재 예약은 결제와 좌석 확정을 한 트랜잭션에 묶은 원샷이라, 사용자가 결제창에 머무는 동안 좌석을 잡아두는 UX가 불가능하다. 이를 홀드(임시 점유) → 확정 2단계로 분리한다.

- 핵심 오해 교정: 원하는 것은 DB 락이 아니라 "상태 기반 임시 점유(soft-lock)"다. DB 락은 트랜잭션 수명(수 ms) 동안만 유지되므로 사용자의 결제 시간(수 분)을 커버할 수 없다. 좌석 행에 `HELD` 상태 + 홀더 + 만료시각을 두고, 기존 조건부 UPDATE로 원자성을 강제한다.
- 기존 `UPDATE ... WHERE reserve IS NULL` 조건부 선점은 버리지 않고 확장한다 — 홀드·확정·만료가 모두 조건부 UPDATE 한 방으로 처리된다.

## 확정된 제약 / 결정

| 항목 | 결정 |
|---|---|
| 좌석 홀드 TTL | 5분 — 결제창 이동 순간 `now+5분`으로 시작, 만료 시 자동 해제 |
| 결제 방식 | 현행 유지(내부 크레딧/리워드 즉시 차감). 외부 PG는 추후 — 지금은 웹훅·재조정 미구현, 단 confirm 구조는 PG 교체가 쉽게 |
| 홀드 시점 | "결제창 이동" 버튼 클릭 시(좌석 클릭 순간이 아님). 크레딧 차감 없음 |
| 좌석 선택창 5분 타이머 | 이번 스펙 범위 밖 — 큐/세션 계층 소관. 좌석을 안 잠그며, 만료 시 홈 퇴장·큐 정보 삭제는 후속 스펙(§6) |
| 확정 시점 | 결제창 최종 결제 시. 이때 크레딧 차감 + 좌석 RESERVED |
| 홀드 상태 저장 | A안 — `Seat` 엔티티 확장(단일 소스, 조건부 UPDATE, 지연 만료) |
| 만료 처리 | 지연 만료(WHERE 절이 자동 처리) — 정합성엔 스케줄러 불필요 |
| 기존 원샷 `POST /reserve` | 제거하고 hold+confirm로 대체 |
| 명시적 해제 | `POST /reserve/release` 추가 — 결제창 이탈 시 즉시 FREE |

## 1. 상태 모델 / 스키마

`Seat`에 세 필드를 추가하고 기존 `reserve`는 확정 링크로 유지한다.

```
enum SeatStatus { FREE, HELD, RESERVED }

Seat:
  status: SeatStatus          // 신규 (기본 FREE, NOT NULL)
  heldBy: Member?             // 신규 — 홀더 (LAZY ManyToOne, nullable)
  heldUntil: LocalDateTime?   // 신규 — 만료시각 (nullable)
  reserve: Reserve?           // 기존 — 확정 시 연결
```

- 기존 `isReserved`(=`reserve != null`)는 `status == RESERVED`로 대체.
- 마이그레이션: 기존 데이터 `reserve != null → RESERVED`, 나머지 `FREE`.
- (선택) 좌석맵 조회 최적화를 위해 `(performanceSchedule_id, status)` 인덱스 검토.

### 상태 전이

```
FREE ──hold──▶ HELD ──confirm──▶ RESERVED ──cancel──▶ FREE
  ▲             │  │
  └──release────┘  │
  └──만료(heldUntil<now, 지연 처리)──┘
```

## 2. 흐름 / 엔드포인트

모든 엔드포인트는 인증 필요, 본인 소유 홀드만 조작 가능.

### ① 홀드 — `POST /reserve/hold`
- 요청: `{ performanceScheduleId, seatNumbers }` — member 락 없음, 크레딧 차감 없음(가벼움).
- 조건부 UPDATE:
  ```
  UPDATE Seat SET status=HELD, heldBy=:me, heldUntil=:now+5m
   WHERE performanceSchedule.id=:scheduleId AND seatNumber IN :seatNumbers
     AND ( status=FREE
        OR (status=HELD AND heldUntil < :now)      -- 만료된 남의 홀드 회수
        OR (status=HELD AND heldBy=:me) )          -- 내 홀드 재홀드/갱신(버튼 중복 클릭 대응)
  ```
- 매칭 수 == 요청 수 → 성공, `heldUntil` 반환(클라이언트 카운트다운용).
- 매칭 수 < 요청 수 → 원인 구분(없는 좌석 vs 남이 점유 중), 각각 `400 NOT_EXIST_SEAT_INFO` / `409 SEAT_ALREADY_HELD`.

### ② 확정 — `POST /reserve/confirm`
- 요청: `{ reservationNumber, performanceScheduleId, seatNumbers, rewardDiscountAmount }` + `idempotency-key` 헤더 — 기존 멱등성 인프라 재사용.
- 순서(단일 `@Transactional`):
  1. member 비관락(`getMemberByUsernameWithLock`) — 크레딧/리워드 동시성.
  2. 결제 처리(`processPayment`) — 크레딧/리워드 차감.
  3. Reserve INSERT(`status=RESERVED`) — FK용 id 확보.
  4. 조건부 UPDATE:
     ```
     UPDATE Seat SET status=RESERVED, reserve=:r
      WHERE performanceSchedule.id=:scheduleId AND seatNumber IN :seatNumbers
        AND status=HELD AND heldBy=:me AND heldUntil > :now
     ```
  5. 매칭 수 < 요청 수(만료됐거나 탈취) → 예외 → 트랜잭션 롤백으로 크레딧·Reserve 자동 원복. 사용자는 재선택.
  6. 이메일 발송(커밋 후 비동기).

### ③ 해제 — `POST /reserve/release`
- 요청: `{ performanceScheduleId, seatNumbers }` → `204 No Content`.
- 조건부 UPDATE: `WHERE status=HELD AND heldBy=:me → status=FREE, heldBy=null, heldUntil=null`.
- 이미 확정(RESERVED)됐거나 내 홀드가 아니면 아무것도 안 함(멱등).

## 3. 만료 처리 (스케줄러 없이)

- 지연 만료: 위 모든 조건부 UPDATE의 WHERE가 만료된 HELD를 FREE처럼 취급 → 정합성엔 스케줄러 불필요.
- 읽기 경로만 반영: 좌석맵 조회(`SeatResponse`)에서 판매가능 여부 = `status==FREE || (status==HELD && heldUntil < now)`. 만료 HELD를 "빈 좌석"으로 노출.
- (선택, 추후) 정리 스케줄러: 만료 HELD를 주기적으로 FREE로 되돌려 좌석맵 조회를 단순화. 정합성 목적이 아니라 가시성/조회 단순화 목적.

## 4. 정합성 포인트

- 홀드·확정 경쟁 모두 조건부 UPDATE 원자성으로 1인만 성공 → 오버셀 0 유지.
- 결제(크레딧)와 좌석 확정이 한 트랜잭션 → 좌석 확정 실패 시 크레딧 롤백(원자적).
- member 비관락은 confirm에만. hold/release는 좌석 행 원자성만으로 충분.
- 기존 오버셀 검증(`reservedSeats == reserveCount`)은 Reserve가 확정 때만 생기므로 그대로 유효. HELD 좌석은 Reserve가 없어 검증에 안 잡힘.
- 취소 경로(`cancelReserve`)의 `releaseSeats`는 `reserve=null`뿐 아니라 `status=FREE`도 함께 되돌리도록 수정.

## 5. 테스트

- 동시 홀드 N명 → 1명만 성공, 오버셀 0.
- 내 홀드 재홀드(버튼 중복) → 성공 + `heldUntil` 갱신.
- 홀드 후 5분 경과 → confirm 실패 + 크레딧 원복.
- 만료 경계: A 만료 → B 재홀드 성공.
- 남의 홀드 confirm 시도 → 실패.
- release 후 즉시 다른 사용자 홀드 성공.
- 만료 HELD가 좌석맵에서 판매가능으로 노출.
- 기존 예약/취소·동시성 테스트를 confirm 경로로 이관.

## 6. 영향 범위 / 후속 (범위 밖)

- 큐 세션 만료·강제 퇴장(후속 스펙): 좌석 선택창/결제창 각 5분 데드라인을 못 넘기면 홈으로 강제 이동하고 대기열·참가열 사용자 정보를 삭제하는 요구가 있으나, 이는 좌석이 아니라 큐/세션 계층의 일이고 현재 코드에 큐가 없어 별도 스펙으로 분리한다.
  - 선택창 타이머는 클라이언트 표시용일 뿐, 만료 강제는 서버(큐 세션) 가 한다(클라 시계는 신뢰 불가).
  - 이번 스펙과의 접점: 세션이 만료되면 → (이번 스펙의) 좌석 반납 + (후속의) 큐 세션 종료·정보 삭제 가 함께 일어난다. 이번 스펙은 좌석 반납(`heldUntil` 만료 + `release`)까지만 책임진다.
- 부하 테스트 하니스 갱신 필요: `loadtest/k6/reserve-load.js`가 원샷 `POST /reserve`를 호출하므로, 2단계(hold→confirm)로 바꿔야 capacity 측정이 실제 흐름과 일치한다. 별도 작업으로 분리.
- 외부 PG 연동(추후): confirm의 결제 단계를 인터페이스로 두면 내부 크레딧 → 외부 PG 교체 시 좌석 상태 로직을 건드리지 않아도 된다. 지금은 인터페이스만 염두, 구현은 미룸(YAGNI).

## 트레이드오프 (A안 채택 이유)

- A안(Seat 확장) 채택: 기존 단일 소스·조건부 UPDATE 모델의 최소 확장. 만료가 WHERE 절에서 저절로 처리돼 스케줄러 없이 정합성 유지. Reserve는 확정 때만 생성되어 기존 불변식·오버셀 검증 무변경.
- B안(PENDING Reserve)은 "예약 의도"를 처음부터 기록하는 DDD적 장점이 있으나 PENDING 생명주기·정리 스케줄러·불변식 수정이 늘어 개발 단계에 과함.
- C안(Redis 홀드)은 방금 걷어낸 Redis를 다시 들이고 좌석 진실을 Redis/DB 2원 저장해 정합성 위험을 만든다(`f3762dc` 역행).
