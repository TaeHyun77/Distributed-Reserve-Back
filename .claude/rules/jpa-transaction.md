---
globs:
  - src/main/kotlin/com/example/reserve/member/**
  - src/main/kotlin/com/example/reserve/reserve/**
  - src/main/kotlin/com/example/reserve/seat/**
  - src/main/kotlin/com/example/reserve/performanceSchedule/**
  - src/main/kotlin/com/example/reserve/performance/**
  - src/main/kotlin/com/example/reserve/venue/**
---

# JPA / 트랜잭션 규칙

## 트랜잭션 패턴
- 조회: `@Transactional(readOnly = true)`
- 변경: `@Transactional`
- FacadeService는 `@Transactional` 미사용 — 내부 Service에서 트랜잭션 관리

## 비관적 락
- `MemberRepository.findByUsernameWithLock()`: PESSIMISTIC_WRITE
- `ReserveRepository.findByReservationNumberWithLock()`: PESSIMISTIC_WRITE
- 비관적 락은 반드시 Redis 분산 락 내부에서 획득 (역순 금지)

## 엔티티 주의사항
- dirty checking: Entity 필드 변경 시 트랜잭션 커밋 시점에 자동 UPDATE (`save()` 불필요)
- `save()` vs `saveAndFlush()`: `@Transactional` 내에서는 `save()`로 충분
- LAZY 연관관계: `@Transactional` 밖에서 접근 금지 (LazyInitializationException)
- CascadeType.ALL + orphanRemoval: `Member→Reserve`, `PerformanceSchedule→Seat`

## BaseTime
- 모든 엔티티는 `BaseTime` 상속 (`createdAt`, `modifiedAt` 자동 관리)
- `@EntityListeners(AuditingEntityListener::class)` 사용
