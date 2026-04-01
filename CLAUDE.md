# 분산 좌석 예약 시스템

Redis 분산 락 + JPA 비관적 락 기반의 동시성 제어 예약 백엔드

## 응답 언어 지침

- 모든 결과값, 설명, 주석, 커밋 메시지, PR 설명은 한글로 작성
- 식별자(변수, 함수, 클래스)는 영문 유지

## 프로젝트 개요

- **루트 패키지**: `com.example.reserve`
- **도메인 관계**: `Member(credit/reward)` → `Reserve` ← `Seat` ← `PerformanceSchedule(Performance + Venue)`
- **URL 접두사**: `/reserve/` (모든 컨트롤러 공통)

## 빌드 및 실행

```bash
./gradlew build          # 전체 빌드 (컴파일 + 테스트 + jar)
./gradlew clean          # 빌드 산출물 + QClass 삭제
./gradlew test           # 전체 테스트 (JUnit 5)
./gradlew bootRun        # 애플리케이션 실행
```

**필수 환경**: Java 17, MySQL localhost:3306 (`reserve_project`), Redis localhost:6379

**Docker**: `docker build -t reserve . && docker run reserve` (Amazon Corretto 17 Alpine)

## 기술 스택

| 분류 | 기술 |
|------|------|
| 프레임워크 | Spring Boot 3.5.0 / Kotlin 1.9.25 / Java 17 |
| 데이터 | MySQL 8.0 (HikariCP pool 20) / QueryDSL 5.0.0 (kapt) |
| 분산 | Redis (Redisson 3.18.0) — pub/sub 기반 분산 락 |
| 인증 | JWT (JJWT 0.12.3), 무상태 세션 |
| 빌드 | Gradle (Kotlin DSL), Docker (Amazon Corretto 17) |

## 아키텍처 핵심

### 트래픽 흐름

```
요청 → JwtFilter(인증) → Controller → FacadeService
  → RedisLockUtil(분산 락) → IdempotencyService(멱등성)
  → Service(@Transactional + 비관적 락) → Repository
```

### Redis 키 구조

| 용도 | 키 패턴 | 예시 |
|------|---------|------|
| 좌석 분산 락 | `lock:{scheduleId}:seat:{seatNumber}` | `lock:1:seat:A3` |

### 분산 안전성: 2계층 락

1. **Redis 분산 락** (좌석 단위): `LockManager`가 Redisson pub/sub 기반 락 관리. 멀티 좌석 예약 시 `keys.sorted()` 후 `RMultiLock`으로 원자적 획득
2. **JPA 비관적 락** (회원 크레딧): `MemberRepository.findByUsernameWithLock()` — `PESSIMISTIC_WRITE`
3. **멱등성**: DB 기반 (`Idempotency` 엔티티), `idempotency-key` 헤더로 10분간 응답 캐싱

### 보안

무상태 JWT: `LoginFilter`(발급) → `JwtFilter`(검증) → `CustomLogoutFilter`(폐기). 역할: `ADMIN`, `MEMBER`

## Gotchas

1. **락 키 정렬 필수** — `LockManager.tryMultiLock()`의 `keys.sorted()` 제거 금지. 정렬 없으면 데드락 발생
2. **락 순서 고정** — 반드시 Redis 분산 락 → JPA 비관적 락 순서. 역순 시 분산 락이 DB 커넥션을 보호하지 못함
3. **leaseTime 고정(3초)** — `LockManager`에서 `LEASE_TIME=3L`로 고정. Redis가 hard timeout을 보장하여 락이 무한 점유되지 않음. watchdog 미사용
4. **LazyInitializationException** — `@Transactional` 밖에서 LAZY 연관관계 접근 금지. 특히 `Reserve.member`, `Seat.performanceSchedule`
5. **JPA dirty checking** — Entity 필드 변경 시 `save()` 호출 없이도 트랜잭션 커밋 시점에 자동 UPDATE. `member.decreaseCreditAndReward()` 등이 이 방식
6. **saveAndFlush vs save** — `@Transactional` 내에서는 `save()`로 충분. `saveAndFlush()`는 불필요한 즉시 flush 유발
7. **QueryDSL Q-class** — `./gradlew clean` 실행 시 `src/main/generated/` 삭제됨. 빌드 후 재생성 필요
8. **CascadeType.ALL + orphanRemoval** — `Member.reserveList`, `PerformanceSchedule.seatList`에 설정됨. 부모 삭제 시 자식 연쇄 삭제
9. **멱등성 일시적 에러 비캐싱** — `IdempotencyService`에서 `ReserveException`이 아닌 예외는 캐싱하지 않고 즉시 재전파

## 코딩 컨벤션

- **로깅**: 모든 Service/Controller는 `Loggable` 인터페이스 구현 → `log.info { }` 사용
- **DTO 명명**: `*Request` / `*Response` 접미사. `companion object { fun from() }` 패턴으로 엔티티→DTO 변환
- **Custom Repository**: `*RepositoryCustom` 인터페이스 + `*RepositoryImpl` 구현 (QueryDSL `JPAQueryFactory`)
- **에러 처리**: `throw ReserveException(HttpStatus, ErrorCode)` → `CustomExceptionHandler`가 `ErrorCodeDto`로 변환
- **트랜잭션**: 조회 `@Transactional(readOnly = true)`, 변경 `@Transactional`
- **엔티티**: `BaseTime` 상속 (`createdAt`, `modifiedAt` 자동 관리)
- **패키지 구조**: 도메인별 패키지 (`member/`, `reserve/`, `seat/`), 하위에 `dto/`, `repository/`
- **텍스트**: 한글 주석, 영문 식별자

## Compact Instructions

> 컨텍스트 압축 시에도 반드시 보존되어야 할 핵심 규칙

- 한글 응답 필수 (식별자만 영문)
- 락 순서: Redis 분산 락 → JPA 비관적 락 (역순 금지)
- 멀티 락 키는 반드시 정렬 후 획득
- leaseTime=3초 고정 (Redis hard timeout 보장)
- `throw ReserveException(HttpStatus, ErrorCode)` 패턴 준수
- `Loggable` 인터페이스 구현 필수 (Service/Controller)
- DTO 변환은 `companion object { fun from() }` 패턴
- `@Transactional` 밖에서 LAZY 연관관계 접근 금지
