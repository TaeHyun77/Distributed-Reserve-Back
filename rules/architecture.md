# 아키텍처

## 기술 스택

- **언어 / 빌드**: Kotlin 1.9.25 (JVM 17), Gradle (Kotlin DSL)
- **프레임워크**: Spring Boot 3.5.0 (Web, Data JPA, Security, Mail, Thymeleaf, Data Redis)
- **DB / ORM**: MySQL 8 + Spring Data JPA + Hibernate + QueryDSL 5.0 (kapt)
- **캐시 / 락**: Redis + Redisson 3.27.2 (분산 락, 이메일 인증 토큰 저장)
- **인증**: Spring Security + JJWT 0.12.3 (Access / Refresh 이중 토큰)
- **메일**: spring-boot-starter-mail (SMTP) + Thymeleaf (HTML 템플릿)
- **로깅**: kotlin-logging-jvm 5.1.4


## 패키지 구조 (도메인 기반)

```
src/main/kotlin/com/example/reserve/
├── ReserveApplication.kt          # 진입점 (@SpringBootApplication, @EnableJpaAuditing 등)
├── BaseTime.kt                    # createdAt / modifiedAt 감사 (@MappedSuperclass)
│
├── config/                        # 횡단 설정
│   ├── SecurityConfig.kt          # SecurityFilterChain, CORS, BCryptPasswordEncoder
│   ├── QueryDslConfig.kt          # JPAQueryFactory Bean
│   ├── Loggable.kt                # 로깅 인터페이스 (val log by lazy)
│   ├── DataInitializer.kt         # 초기 데이터 주입
│   └── TestDataInitializer.kt     # 부하/통합 테스트용 데이터 초기화
│
├── jwt/                           # 인증 필터
│   ├── LoginFilter.kt             # POST /reserve/login → access + refresh 발급
│   ├── JwtFilter.kt               # access 검증 → SecurityContext 설정
│   ├── CustomLogoutFilter.kt      # refresh 폐기
│   ├── JwtUtil.kt                 # 토큰 생성/파싱 유틸
│   ├── CustomUserDetails.kt
│   └── CustomUserDetailService.kt
│
├── refresh/                       # refresh 토큰 DB 저장 도메인
│   ├── Refresh.kt
│   ├── RefreshRepository.kt
│   ├── ReissueController.kt       # POST /reserve/reToken
│   └── ReissueService.kt
│
├── redis/lock/                    # 분산 락
│   ├── RedisLockUtil.kt           # 단일/멀티 락 + try-finally 실행 래퍼
│   ├── LockManager.kt             # Redisson 호출 (waitTime=5s, leaseTime=3s)
│   └── config/RedisConfig.kt      # RedissonClient (단일 서버 모드)
│
├── idempotency/                   # 멱등성 처리
│   ├── Idempotency.kt             # @Entity (idempotencyKey unique, expiresAt)
│   ├── IdempotencyRepository.kt
│   ├── IdempotencyService.kt      # execute(key, method, task) 래퍼
│   └── dto/IdempotencyResponse.kt
│
├── reserveException/              # 글로벌 예외
│   ├── ReserveException.kt        # 비즈니스 예외 (status + errorCode)
│   ├── ErrorCode.kt               # enum (코드 + 메시지)
│   ├── ErrorCodeDto.kt            # 응답 변환
│   └── CustomExceptionHandler.kt  # @ControllerAdvice
│
├── email/                         # 이메일 (인증번호 + 예약 확인)
│   ├── EmailConfig.kt             # Async Executor, JavaMailSender
│   ├── EmailService.kt            # @Async 메일 발송 (Thymeleaf)
│   ├── EmailVerificationService.kt # Redis 기반 인증번호 발급/검증
│   ├── EmailVerificationController.kt
│   └── dto/
│
├── member/                        # 회원
│   ├── Member.kt                  # credit, reward 도메인 메서드 보유
│   ├── MemberController.kt
│   ├── MemberService.kt
│   ├── MemberRepository.kt        # findByUsernameWithLock (PESSIMISTIC_WRITE)
│   ├── CheckUsername.kt           # value object (정규화 / 검증)
│   ├── Role.kt                    # ADMIN, MEMBER
│   └── dto/
│
├── venue/                         # 공연장
├── performance/                   # 공연 (제목/타입/가격)
│   └── repository/                # QueryDSL Custom
├── performanceSchedule/           # 공연 스케줄 (venue + performance + seatList)
│   └── repository/                # QueryDSL Custom (fetch join 적용)
├── seat/                          # 좌석
│   ├── Seat.kt                    # occupy / release / isReserved
│   ├── SeatService.kt
│   └── repository/                # JPA + QueryDSL Custom (PESSIMISTIC_WRITE)
│
├── reserve/                       # 예약 (도메인 중심)
│   ├── Reserve.kt                 # cancel() 도메인 메서드
│   ├── ReserveStatus.kt           # RESERVED, CANCELLED
│   ├── ReserveController.kt
│   ├── ReserveFacadeService.kt    # 락 + 멱등성 + 트랜잭션 조립
│   ├── ReserveService.kt          # 비즈니스 로직 (@Transactional)
│   ├── repository/ReserveRepository.kt
│   └── dto/                       # ReserveRequest/Response, Payment, PaymentResult, Refund
│
└── util/AppUtils.kt
```


## 계층 구조

```
Controller
  ↓ (Request DTO + @AuthenticationPrincipal)
FacadeService  ── 트랜잭션 외부 횡단 관심사 조립 (분산 락 + 멱등성)
  ↓
Service        ── @Transactional 경계 (비즈니스 로직 + 도메인 메서드 호출)
  ↓
Repository     ── JpaRepository (+ QueryDSL Custom: *RepositoryCustom + *RepositoryImpl)
  ↓
Entity         ── 도메인 메서드 보유 (cancel, occupy, decreaseCreditAndReward …)
```

- **FacadeService는 트랜잭션을 갖지 않는다**. 분산 락 / 멱등성을 트랜잭션 바깥에서 적용해야 락 보유 구간을 짧게 가져갈 수 있고, 멱등성 응답 저장이 비즈니스 트랜잭션과 분리된다.
- **트랜잭션은 Service 단위**. 조회는 `@Transactional(readOnly = true)`, 변경은 `@Transactional`.
- **도메인 로직은 엔티티 안에**. 서비스는 엔티티의 도메인 메서드를 호출해 변경 → dirty checking으로 flush.


## 도메인 관계도

```
Venue ──┐
        ├─< PerformanceSchedule >─ Seat (OneToMany, cascade=ALL, orphanRemoval)
Performance ──┘                       │
                                      │ (occupy 시점에 reserve_id 채움)
                                      ▼
                          Member ──< Reserve
                          (1:N, cascade=ALL, orphanRemoval)
```

- `PerformanceSchedule`: 공연장(Venue) + 공연(Performance) + 좌석 목록(Seat).
- `Seat`: 스케줄에 속하며, 예약되면 `reserve` 외래키로 `Reserve`를 가리킨다 (점유 해제는 `reserve = null`).
- `Reserve`: 회원 1명에 속하고, 점유한 좌석 목록을 가진다 (좌석 측에서 mappedBy).
- `Member`: 잔액(credit) / 리워드(reward) / 일일 리워드 수령 일자(lastRewardDate) 보유.
- 모든 엔티티는 `BaseTime`을 상속해 `createdAt` / `modifiedAt`을 자동 관리.


## 횡단 흐름

### 예약 생성 (POST /reserve)
```
Controller
  └─ idempotency-key 헤더 추출 (없으면 400)
  └─ ReserveFacadeService.reserveSeat()
       └─ Redis 멀티 락 (lock:{scheduleId}:seat:{seatNumber}, 정렬된 키 기준)
            └─ Idempotency 체크 (key 캐시 hit → 캐시된 응답 반환)
                 └─ ReserveService.reserve() [@Transactional]
                      ├─ 스케줄 조회
                      ├─ 좌석 비관적 락 + 가용성 검증 (이미 예약된 좌석 차단)
                      ├─ Member 비관적 락 (credit / reward 동시성 보호)
                      ├─ 결제 계산 (총액, 리워드 할인, 최종 결제액)
                      ├─ Reserve 저장 + Seat.occupy(reserve)
                      └─ 이메일 비동기 발송 (@Async)
```

### 예약 취소 (DELETE /reserve/delete/{reserveNumber})
```
Controller
  └─ ReserveService.cancelReserve() [@Transactional]
       ├─ Reserve + seatList fetch join 조회
       ├─ Reserve.cancel() (상태 변경 + cancelledAt)
       ├─ Seat 일괄 release
       ├─ Member 비관적 락 → credit / reward 환불
       └─ 이메일 비동기 발송
```
> 취소 흐름에는 분산 락이 없다. 좌석 해제 → 환불 순서이므로 동일 예약을 동시에 두 번 취소하려는 경우만 문제이고, 이는 `Reserve.cancel()`에서 이미 CANCELLED 상태일 때 `ALREADY_CANCELLED` 예외로 차단한다. 회원 잔액 동시 변경은 `findByUsernameWithLock`의 비관적 락이 직렬화한다.


## 동시성 보호 전략 (Defense in Depth)

좌석 1건당 두 단계로 동시성을 보호한다.

1. **Redis 분산 락 (1차 / 인스턴스 간 차단)**
   - 키: `lock:{scheduleId}:seat:{seatNumber}` → Redisson `MultiLock`로 묶어서 all-or-nothing 획득.
   - 키 정렬(`keys.sorted()`)로 다른 좌석 조합과의 데드락 회피.
   - leaseTime=3s 고정 (watchdog 미사용), waitTime=5s.

2. **DB 비관적 락 (2차 / Redis 만료 안전망)**
   - `SeatRepository.findAllByPerformanceScheduleIdAndSeatNumbersWithLock` — `PESSIMISTIC_WRITE` + `order by s.id asc`.
   - `MemberRepository.findByUsernameWithLock` — credit/reward 갱신 직렬화.
   - **순서**: 항상 Redis 락 → DB 락. 역순 시 데드락 위험.


## 멱등성

- `idempotency-key` 헤더 기반, DB(`Idempotency` 엔티티)에 응답 JSON + 상태 코드 캐싱, 만료 10분.
- `ReserveException`(비즈니스 실패)은 캐싱해 동일 결과 재반환, 그 외 예외(일시적 실패)는 캐싱하지 않고 재전파해 클라이언트 재시도 허용.
- 적용 위치는 FacadeService — 락 안쪽, 비즈니스 트랜잭션 바깥.


## 보안 / 인증

- **STATELESS**, JWT 이중 토큰.
- 필터 체인: `CustomLogoutFilter` → `JwtFilter` → `LoginFilter`(UsernamePasswordAuthenticationFilter 위치).
- `Refresh` 토큰만 DB에 저장 (재발급 / 강제 로그아웃 가능).
- 인가는 SecurityConfig에서 URL + HTTP Method + Role(`ADMIN`, `MEMBER`) 매트릭스로 선언.


## QueryDSL

- 모든 도메인이 사용하지는 않고, **fetch join 필요 / 동적 조회**가 있는 도메인만 Custom Repository 패턴 적용.
- 현재 적용: `Performance`, `PerformanceSchedule`, `Seat`.
- Q 클래스는 kapt가 `build/generated/source/kapt/main`에 생성. `clean` 후 `build` 필요.


## 비동기 / 메일

- `@EnableAsync` + `EmailConfig`의 `emailTaskExecutor`로 분리된 풀에서 메일 발송.
- 메일 실패는 catch + 로깅 (예약 트랜잭션을 롤백시키지 않음 — 예약은 성립, 메일은 best-effort).
- 이메일 인증번호는 Redis에 5분 TTL로 저장 (`email:verify:{email}` → 6자리 코드, 검증 성공 시 `email:verified:{email}` 토큰 10분 TTL 발급, 회원가입 시 소비).


## 예외 처리

- 비즈니스 예외는 `ReserveException(HttpStatus, ErrorCode)`만 사용.
- `CustomExceptionHandler`가 `@ControllerAdvice`로 가로채 `ErrorCodeDto`로 변환해 응답.
- 상세 규칙은 `rules/errorHandler.md` 참조.
