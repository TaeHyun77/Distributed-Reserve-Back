---
globs:
  - src/main/kotlin/com/example/reserve/reserve/**
  - src/main/kotlin/com/example/reserve/seat/**
---

# 예약 흐름 규칙

## 예약 생성 흐름
```
POST /reserve (idempotency-key 헤더 필수)
  → ReserveFacadeService.reserveSeat()
    → lockKeys = reservedSeat.map { "lock:{scheduleId}:seat:{seatNumber}" }
    → acquireMultiLockAndRun(lockKeys)      # Redis 분산 락
    → idempotencyService.execute()           # 멱등성 체크
    → ReserveService.reserve()
      → getMemberByUsernameWithLock()         # JPA 비관적 락
      → getAvailableSeats() → 좌석 유효성 검증
      → processPayment() → credit/reward 차감
      → createReserve() + reserveSeats()
```

## 예약 취소 흐름
- `Reserve.cancel()` → 상태 변경(CANCELLED) + cancelledAt 기록
- `Member.increaseCreditAndReward()` → credit/reward 환불
- `SeatService.releaseSeats()` → 좌석 점유 해제
- 본인 검증: 요청자 username과 예약자 일치 확인

## 좌석 상태
- 점유: `Seat.occupy(reserve)` — reserve != null
- 해제: `Seat.release()` — reserve = null
- `Seat.isReserved` 프로퍼티로 점유 여부 확인

## 결제 계산
- `totalAmount = price * seatCount`
- `discountAmount = min(요청 리워드, 총액, 보유 리워드)`
- `finalAmount = totalAmount - discountAmount`

## 주의사항
- `ReserveStatus`: RESERVED, CANCELLED
- 이미 취소된 예약 재취소 시 `ALREADY_CANCELLED` 에러
- `reservationNumber`는 DTO 기본값으로 UUID 생성 (멱등성 키와 별개)
