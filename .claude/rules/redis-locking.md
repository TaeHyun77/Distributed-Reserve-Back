---
globs:
  - src/main/kotlin/com/example/reserve/redis/**
---

# Redis 분산 락 규칙

## 아키텍처
- `RedisLockUtil` → `LockManager` → `RedissonClient`
- 단일 락: `acquireLockAndRun(key, task)`
- 멀티 락: `acquireMultiLockAndRun(keys, task)`

## 핵심 규칙
1. **키 정렬 필수**: `tryMultiLock()`에서 `keys.sorted()` 유지. 제거 시 데드락 위험
2. **leaseTime 미지정 유지**: watchdog(30초 자동 갱신)이 활성화됨. leaseTime 설정 시 watchdog 비활성화
3. **waitTime 기본값**: 5초 (`WAIT_TIME = 5L`)
4. **락 획득 실패**: `ReserveException(CONFLICT, FAILED_TO_ACQUIRED_LOCK)` 반환
5. **unlock 안전성**: `IllegalMonitorStateException` 캐치로 이미 해제된 락 처리
6. **try-finally**: `runWithLock()`에서 task 실행 후 반드시 finally 블록에서 unlock

## 키 패턴
- 좌석 락: `lock:{performanceScheduleId}:seat:{seatNumber}`
- 새로운 락 키 추가 시 동일한 `lock:` 접두사 + 도메인 구분 패턴 유지

## Redisson 설정
- `RedisConfig`: `redis://{host}:{port}` 단일 서버 모드
- Pub/Sub 기반 — 스핀락 대비 Redis 부하 최소화
