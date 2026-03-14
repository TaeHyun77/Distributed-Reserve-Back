# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 언어 규칙

- 모든 결과값, 설명, 주석, 커밋 메시지, PR 설명 등은 반드시 한글로 작성한다.
- 코드 내 변수명, 함수명, 클래스명 등 식별자는 영문을 유지하되, 그 외 사람이 읽는 텍스트는 한글로 작성한다.

## Build & Run Commands

```bash
./gradlew build          # Full build (compile + test + jar)
./gradlew clean          # Clean build artifacts + generated QClass files
./gradlew test           # Run all tests (JUnit 5)
./gradlew bootRun        # Run Spring Boot application
```

**Prerequisites**: Java 17, MySQL on localhost:3306 (database: `reserve_project`), Redis on localhost:6379.

**Docker**: `docker build -t reserve . && docker run reserve` (Amazon Corretto 17 Alpine)

## Architecture

Distributed reservation system: **Spring Boot 3.5.0 + Kotlin + MySQL + Redis (Redisson)**

Root package: `com.example.reserve`

### Domain Model

- **Member** → has credit (payment balance) and reward (daily discount)
- **Venue** → theater/location
- **Performance** → show with type, title, price
- **PerformanceSchedule** → links Performance + Venue + time slot
- **Seat** → belongs to a PerformanceSchedule, can be occupied by a Reserve
- **Reserve** → reservation record linking Member to Seats

### Concurrency Control (Two-Layer Locking)

1. **Redisson distributed locks** (seat-level): `RedisLockUtil` → `LockManager` wraps Redisson's pub/sub-based locks. Multi-lock acquisition with sorted keys prevents deadlocks when reserving multiple seats.
2. **JPA pessimistic locking** (member credit): `MemberRepository.findByUsernameWithLock()` uses `PESSIMISTIC_WRITE` because credit is a shared resource across seat-level locks.

### Idempotency

`IdempotencyService` caches responses by `idempotency-key` request header for 10 minutes. Duplicate requests return cached results with `request-Replayed: true` header. Used in reserve and daily reward flows.

### Key Flow: Reservation

```
POST /api/reserve (idempotency-key header)
  → ReserveFacadeService.reserveSeat()
    → acquireMultiLockAndRun(seatLockKeys)   # Distributed lock
    → idempotencyService.execute()            # Dedup check
    → ReserveService.reserve()
      → getMemberByUsernameWithLock()          # Pessimistic lock
      → calculate payment (totalAmount - reward discount)
      → validate credit, decrease credit/reward
      → create Reserve + occupy Seats
```

### Security

Stateless JWT authentication (JJWT). `LoginFilter` issues tokens, `JwtFilter` validates on each request, `CustomLogoutFilter` revokes refresh tokens (stored in DB). Role-based auth: `ADMIN`, `USER`.

### QueryDSL

Used for complex queries in `Performance`, `PerformanceSchedule`, and `Seat` domains. Custom repository pattern: `*RepositoryCustom` interface + `*RepositoryImpl` with `JPAQueryFactory`.

### Data Initialization

`POST /api/init` creates test data: 3 venues, 9 performances, 9 schedules, 225 seats (25 per schedule).
