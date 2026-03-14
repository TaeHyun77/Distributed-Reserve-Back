# 프로젝트 분석 및 개선 계획

Distributed-Reserve-Back (Spring Boot 3.5.0 + Kotlin + MySQL + Redis)

---

## 1. 긴급 

### 1.1 Cookie HttpOnly=false → XSS 토큰 탈취 위험

**파일**: `src/main/kotlin/com/example/reserve/util/AppUtils.kt:18`

**현재 코드**:
```kotlin
isHttpOnly = false
```

**문제**: Refresh token이 담긴 쿠키에 `HttpOnly=false`로 설정되어 있어 JavaScript(`document.cookie`)로 접근 가능하므로 XSS 공격 시 토큰 탈취가 가능합니다.

**개선안**:
```kotlin
isHttpOnly = true
secure = true       // HTTPS 전용
// sameSite = "Strict"  // CSRF 방지 (필요 시)
```

---

## 3. 중간 (설계·성능)

### 3.1 예약 취소 반환값 무시

**파일**: `src/main/kotlin/com/example/reserve/reserve/ReserveController.kt:42-47`

**문제**: `cancelReserve()`가 `Refund` 객체를 반환하지만 Controller에서 무시하여 클라이언트가 환불 정보를 받을 수 없다.

**개선안**:
```kotlin
@DeleteMapping("/delete/{reserveNumber}")
fun cancelReservation(
    @PathVariable("reserveNumber") reserveNumber: String
): ResponseEntity<Refund> {
    return ResponseEntity.ok(reserveFacadeService.cancelReserve(reserveNumber))
}
```

---

### 3.2 에러 응답 형식 불일치 (멱등성 캐시)

**파일**: `src/main/kotlin/com/example/reserve/idempotency/IdempotencyService.kt:68-79`

**문제**: 성공 응답은 JSON으로 저장하고 실패 응답은 `errorCode.name` 문자열로만 저장한다. 중복 요청 시 응답 형식이 일관되지 않는다.

**개선안**: 에러 응답도 JSON 형식으로 직렬화하여 저장한다.

---

### 3.3 리워드 로직에서 Redis 락 + 멱등성 중복 사용

**파일**: `src/main/kotlin/com/example/reserve/member/MemberService.kt:64-77`

**현재 흐름**: Redis 분산 락 → 멱등성 체크 → 트랜잭션 처리

**문제**: 리워드는 1일 1회 지급이므로 멱등성만으로 충분하다. Redis 락까지 추가하면 불필요한 오버헤드가 발생한다. 다만 2.1의 멱등성 Race Condition이 해결되기 전까지는 Redis 락이 보호 역할을 하므로, 2.1 해결 후 제거를 검토한다.

---

### 3.4 member 비관적 락 보유 시간 최적화

**파일**: `src/main/kotlin/com/example/reserve/reserve/ReserveService.kt:34-40`

**현재 순서**: member 비관적 락 획득 → performanceSchedule 조회 → seats 조회 → 결제 처리

**문제**: member 락을 가장 먼저 획득하여 불필요하게 오래 보유한다.

**개선안**: performanceSchedule, seats를 먼저 조회한 뒤 member 락을 획득하면 락 보유 시간을 최소화할 수 있다.

---

### 3.5 PerformanceSchedule Venue N+1 가능성

**파일**: `src/main/kotlin/com/example/reserve/performanceSchedule/repository/PerformanceScheduleRepositoryImpl.kt:21`

**문제**: Performance만 fetch join하고 Venue는 LAZY 로딩이다. 응답에 Venue 정보가 포함되면 N+1 쿼리가 발생한다.

**개선안**: 응답에 Venue 정보가 필요한 경우 `.join(ps.venue).fetchJoin()` 추가

---

### 3.6 saveAndFlush 불필요 사용

**파일**: `src/main/kotlin/com/example/reserve/reserve/ReserveService.kt:65`

**문제**: `@Transactional` 내에서 `saveAndFlush()`는 불필요한 즉시 flush를 발생시킨다. JPA가 트랜잭션 커밋 시 자동 flush하므로 `save()`로 충분하다.

**개선안**: `saveAndFlush()` → `save()` 변경

---

### 3.7 Member.reserveList Nullable

**파일**: `src/main/kotlin/com/example/reserve/member/Member.kt:32`

**현재 코드**:
```kotlin
val reserveList: List<Reserve>? = null
```

**개선안**: `val reserveList: MutableList<Reserve> = mutableListOf()`로 변경하여 NPE 위험 제거

---

### 3.8 ErrorCode 명명 불일치

**파일**: `src/main/kotlin/com/example/reserve/reserveException/ErrorCode.kt:61`

**문제**: enum 이름 `FAILED_TO_ACQUIRED_LOCK`과 errorCode 문자열 `REDIS_FAILED_TO_ACQUIRED_LOCK`이 불일치한다. 또한 문법적으로 `ACQUIRED` → `ACQUIRE`가 올바르다.

**개선안**: `FAILED_TO_ACQUIRE_LOCK("FAILED_TO_ACQUIRE_LOCK", "...")`으로 통일

---

### 3.9 락 획득 실패 HTTP 상태 부적절

**파일**: `src/main/kotlin/com/example/reserve/redis/lock/RedisLockUtil.kt`

**문제**: 락 획득 실패 시 409(Conflict)를 반환하는데, 일시적 서버 과부하이므로 429(Too Many Requests) 또는 503(Service Unavailable)이 더 적절하다.

---

### 3.10 SecurityConfig 과도한 permitAll

**파일**: `src/main/kotlin/com/example/reserve/config/SecurityConfig.kt`

**문제**: `/api/init` 등 관리 엔드포인트가 인증 없이 접근 가능하다.

**개선안**: 관리용 API는 `ADMIN` 권한 체크 또는 프로필 조건(`@Profile("dev")`) 추가

---

### 3.11 JwtFilter에서 매 요청마다 Member DB 조회

**파일**: `src/main/kotlin/com/example/reserve/jwt/JwtFilter.kt:47`

**문제**: 모든 인증 요청마다 `memberRepository.findByUsername()`을 호출한다.

**개선안**: JWT에 필요한 정보(username, role)가 이미 포함되어 있으므로, DB 조회 없이 토큰 정보만으로 `Authentication` 객체를 생성하는 방안 검토

---

## 4. 우선순위 요약

| 우선순위 | 항목 | 영향도 |
|---------|------|--------|
| 🔴 긴급 | 1.1 Cookie HttpOnly | 보안 - XSS 토큰 탈취 |
| 🔴 긴급 | 1.2 JWT password 저장 | 보안 - 자격증명 노출 |
| 🔴 긴급 | 1.3 평문 비밀번호 로깅 | 보안 - 자격증명 노출 |
| 🟠 높음 | 2.1 멱등성 Race Condition | 기능 - 중복 처리 가능 |
| 🟠 높음 | 2.2 락 타임아웃 10초 | 기능 - 중복 예약 가능 |
| 🟠 높음 | 2.3 좌석 DB 락 부재 | 기능 - 방어적 이중 검증 부재 |
| 🟠 높음 | 2.4 Fetch Join 불일치 | 성능 - 불필요한 데이터 로드 |
| 🟡 중간 | 3.1 취소 반환값 무시 | 기능 - 클라이언트 정보 부족 |
| 🟡 중간 | 3.2 에러 응답 형식 불일치 | 일관성 |
| 🟡 중간 | 3.3 리워드 락+멱등성 중복 | 성능 - 불필요한 오버헤드 |
| 🟡 중간 | 3.4 member 락 보유 시간 | 성능 - 동시성 저하 |
| 🟡 중간 | 3.5~3.11 기타 | 설계·코드 품질 |
