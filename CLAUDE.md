# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 언어 규칙

모든 결과값, 설명, 주석, 커밋 메시지, PR 설명 등은 반드시 **한글**로 작성한다.

## Commands

```bash
# Build
./gradlew build

# Run
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.example.kotlin.ReserveTest"

# Clean (also removes generated QueryDSL Q-classes in src/main/generated)
./gradlew clean

# Docker build (requires JAR at build/libs/kotlin-0.0.1-SNAPSHOT.jar)
docker build -t integrated-reserve .
```

## Prerequisites

- Java 17
- MySQL 8 running on `localhost:3306`, database: `reserve_project`
- Redis running on `localhost:6379`
- Schema is auto-managed by Hibernate (`ddl-auto=update`)

## Architecture Overview

This is a **Kotlin + Spring Boot 3.5.0** ticket reservation system solving the "따닥 이슈" (double-click/duplicate request problem) through two complementary mechanisms:

1. **Redis Distributed Locking** (Redisson Mutex) — prevents concurrent access to the same seat/operation at the application layer, failing fast before executing business logic
2. **Idempotency Service** — caches responses by idempotency key (HTTP header) in the DB for 10 minutes, so duplicate requests return the same result

### Request Flow

```
Controller → ReserveFacadeService → (Redis Lock + IdempotencyService) → ReserveService → JPA/DB
```

`ReserveFacadeService` orchestrates the lock acquisition and idempotency check before delegating to the core `ReserveService`. This Facade pattern separates cross-cutting concerns from business logic.

### Key Packages

| Package | Responsibility |
|---|---|
| `reserve/` | Core reservation domain: entity, service, facade, controller, DTOs |
| `idempotency/` | Request deduplication: entity, service, repository |
| `redis/lock/` | Redisson config, `LockManager` (tryLock/tryMultiLock/unlock), `RedisLockUtil` (higher-level wrappers) |
| `member/` | Member registration, credit/reward management |
| `performanceSchedule/` | Performance scheduling with QueryDSL custom queries |
| `seat/` | Seat entity with QueryDSL (`SeatRepositoryImpl`) |
| `jwt/` | Stateless JWT auth: `JwtFilter`, `LoginFilter`, `CustomLogoutFilter` |
| `reserveException/` | `ErrorCode` enum, `ReserveException`, `CustomExceptionHandler` |

### Locking Strategy

- **Single lock:** per `performanceScheduleId` for seat reservations
- **Multi-lock:** multiple seats acquired simultaneously to prevent partial locks
- Lock wait: 3s, lease: 5s (defined in `RedisLockUtil`)

### Idempotency Flow

`IdempotencyService` checks for a cached `Idempotency` entity matching the request key. If found and not expired (10 min), returns the cached HTTP status + response body. If expired or missing, executes the operation and persists the result. Both success and failure responses are cached.

### QueryDSL

Q-classes are auto-generated via KAPT into `src/main/generated`. Custom repository implementations follow the `*RepositoryCustom` / `*RepositoryImpl` pattern (e.g., `SeatRepositoryCustom`, `SeatRepositoryImpl`).

### Error Handling

All business exceptions use `ReserveException(httpStatus, errorCode)` with ~20 `ErrorCode` enum values (e.g., `SEAT_ALREADY_RESERVED`, `NOT_ENOUGH_CREDIT`). `CustomExceptionHandler` catches these globally.

### Security

- JWT stateless auth; access tokens + refresh tokens stored in `Refresh` entity
- CORS allowed origins: `http://localhost:3000`, `http://localhost:8080`
- `BaseTime` abstract class provides `createdAt`/`modifiedAt` via JPA Auditing on all entities

## API Endpoints

- `POST /api/reserve` — Reserve seats (requires `idempotency-key` header)
- `DELETE /api/reserve/delete/{reserveNumber}` — Cancel reservation
- `GET /api/reserve/get/list/{username}` — List user reservations
- `POST /api/member/create` — Register member
- `GET /api/member/check/validation/{username}` — Check username availability
- `POST /api/member/get/reward` — Claim daily reward (idempotency-protected)
- `POST /login` / `POST /logout` — JWT auth
