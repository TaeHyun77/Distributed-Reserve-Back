---
globs:
  - src/main/kotlin/com/example/reserve/idempotency/**
---

# 멱등성 규칙

## 동작 방식
1. `idempotency-key` 헤더로 요청 식별
2. DB(`Idempotency` 엔티티)에 응답 캐싱, 10분 만료
3. 중복 요청 시 캐싱된 응답 반환 (상태 코드 포함)
4. 만료된 키는 삭제 후 재실행

## 에러 처리 분류
- `ReserveException` (비즈니스 에러): 응답 캐싱됨 → 동일 에러 재반환
- 그 외 예외 (일시적 에러): 캐싱 안 함 → 즉시 재전파 → 클라이언트 재시도 가능
- 판별 로직: `isTransientError()` — `ReserveException`이 아닌 모든 예외를 일시적으로 분류

## 적용 대상
- 예약 생성: `ReserveFacadeService.reserveSeat()`
- 새로운 멱등성 적용 시 `idempotencyService.execute(key, method) { ... }` 패턴 사용

## 주의사항
- 성공 응답은 JSON 직렬화하여 저장, 실패 응답은 `errorCode.name` 문자열로 저장 (형식 불일치 존재)
- 멱등성 키는 컨트롤러에서 헤더로부터 추출하여 FacadeService로 전달
