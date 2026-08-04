# 이메일 트랜잭셔널 아웃박스 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예약/취소 확인 이메일 발송을 인메모리 `@Async` 이벤트에서 트랜잭셔널 아웃박스(DB 테이블 + 폴링 워커)로 전환해, 유실 방지·재시도·버스트 완충·요청 경로 보호를 확보한다.

**Architecture:** `confirm`/`cancel` 트랜잭션 안에서 발송 의도를 `email_outbox` 테이블에 같은 커밋으로 INSERT(원자성)한다. 별도 `@Scheduled` 워커가 `SELECT ... FOR UPDATE SKIP LOCKED`로 발송 대기 행을 선점해 SMTP로 발송하고, 성공 시 행을 삭제, 실패 시 지수 백오프로 재시도하며 최대 재시도 초과 시 `DEAD`로 전환한다. 기존 `@Async` 스레드풀·이벤트 리스너는 제거한다.

**Tech Stack:** Kotlin, Spring Boot(JPA/Scheduling), MySQL 8.0(`SKIP LOCKED`), Jackson(payload JSON), Testcontainers(실제 MySQL 통합 테스트).

> **REVISION (구현 시 반영, Tier 2 → Tier 1)**: 재검토 결과 재시도 기계장치가 과설계로 판단되어 구현에서 걷어냈다. 제거: `EmailOutboxStatus`(enum·`status` 컬럼), `retryCount`, `lastError`, `DEAD` 전환, 지수 백오프. 유지: `nextAttemptAt` 단일 필드(실패 시 고정 지연 `email.outbox.retry-delay-seconds`만큼 미뤄 재시도 — 실패 행의 큐 선두 독점만 방지). 이유: 요구사항(유실 방지·재시작 생존·재시도·버스트 완충·요청 보호·다중 인스턴스 안전)은 그대로 충족하면서 상태 필드 4개를 1개로 줄임. "영구 실패 후 포기(DEAD)"는 poison 메시지가 실제로 나타나면 그때 추가. 아래 Task 본문은 원안(Tier 2) 기준이므로 실제 코드와 다를 수 있다.

---

## 사전 메모 (실행 전 반드시 읽기)

- **브랜치**: 현재 `test/reserve-capacity-loadtest`에 미커밋 변경이 다수 있다. 권장은 `main`에서 `feat/email-outbox`를 파는 것이나, 로드테스트 컨텍스트 위에서 이어가려면 현재 브랜치에 커밋해도 된다. 실행 시작 전 사용자와 확정한다.
- **커밋 규칙**: `<타입> <한글 설명>`, 제목 50자 이내, 마침표 없음. 커밋 메시지는 각 Task의 마지막 스텝에 명시했다.
- **락 유지 방식 채택**: 워커는 배치를 선점한 트랜잭션 안에서 그대로 발송한다(구현 단순). 발송 지연 × 배치 크기만큼 락을 쥐므로 `batch-size`를 작게(기본 20) 둔다. 드레인 속도가 부족해지면 이후 claim-release(별도 `SENDING` 상태 + reaper)로 승격한다 — 이번 범위 밖.
- **보존 정책**: 발송 성공 행은 즉시 DELETE 한다(테이블을 in-flight + 실패분으로만 유지 → soak 중 무한 증가 방지). 발송 감사 기록은 애플리케이션 로그로 대체한다.

---

## 파일 구조

**신규 생성**
- `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxStatus.kt` — 아웃박스 상태 enum(`PENDING`, `DEAD`)
- `src/main/kotlin/com/example/reserve/email/outbox/EmailOutbox.kt` — 아웃박스 엔티티(JSON payload + 재시도 필드)
- `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxRepository.kt` — `SKIP LOCKED` 선점 조회
- `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxService.kt` — `enqueue`(in-txn 저장) + `dispatchBatch`(발송/재시도)
- `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxWorker.kt` — `@Scheduled` 폴링 트리거
- `src/test/kotlin/com/example/reserve/email/outbox/EmailOutboxTest.kt` — 통합 테스트

**수정**
- `src/main/kotlin/com/example/reserve/email/dto/ReservationEmailData.kt` — `from()` 정적 팩토리 추가
- `src/main/kotlin/com/example/reserve/email/EmailService.kt` — `@Async`/이벤트 발행 제거, 발송 실패를 삼키지 않고 전파
- `src/main/kotlin/com/example/reserve/reserve/ReserveService.kt` — 이메일 이벤트 발행 → 아웃박스 `enqueue`로 교체
- `src/main/resources/application.properties` — 아웃박스 워커 설정 추가
- `src/test/kotlin/com/example/reserve/support/IntegrationTestSupport.kt` — 테스트에서 스케줄러 비활성화 + 아웃박스 리포지토리 정리
- `src/main/kotlin/com/example/reserve/config/LoadTestSupport.kt` — 스테일 주석("@Async 경로") 갱신

**삭제**
- `src/main/kotlin/com/example/reserve/email/EmailConfig.kt` — `@EnableAsync` + 이메일 스레드풀(더 이상 불필요)
- `src/main/kotlin/com/example/reserve/email/ReservationEmailEvent.kt`
- `src/main/kotlin/com/example/reserve/email/ReservationEmailEventListener.kt`

---

## Task 1: 아웃박스 상태 enum + 엔티티

**Files:**
- Create: `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxStatus.kt`
- Create: `src/main/kotlin/com/example/reserve/email/outbox/EmailOutbox.kt`

- [ ] **Step 1: 상태 enum 작성**

`EmailOutboxStatus.kt`:

```kotlin
package com.example.reserve.email.outbox

// 아웃박스 발송 상태: PENDING(발송 대기/재시도), DEAD(최대 재시도 초과로 포기)
// 발송 성공 행은 즉시 삭제하므로 SENT 상태는 두지 않는다.
enum class EmailOutboxStatus {
    PENDING, DEAD
}
```

- [ ] **Step 2: 엔티티 작성**

`EmailOutbox.kt`:

```kotlin
package com.example.reserve.email.outbox

import com.example.reserve.BaseTime
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

// 발송할 이메일의 의도를 예약과 같은 커밋으로 기록하는 아웃박스.
// 인덱스: 워커의 (status, next_attempt_at) 조회가 SENT/DEAD를 스캔하지 않고 발송 대기 행으로 직행하도록 한다.
@Entity
@Table(
    name = "email_outbox",
    indexes = [Index(name = "idx_outbox_dispatch", columnList = "status, next_attempt_at")]
)
class EmailOutbox(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "email_outbox_id")
    val id: Long? = null,

    // ReservationEmailData 를 직렬화한 JSON 스냅샷 (확정 시점 상태를 그대로 담는다)
    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: EmailOutboxStatus = EmailOutboxStatus.PENDING,

    @Column(nullable = false)
    var retryCount: Int = 0,

    // 이 시각 이후에 발송을 시도한다 (지수 백오프로 갱신)
    @Column(nullable = false)
    var nextAttemptAt: LocalDateTime,

    @Column(columnDefinition = "TEXT")
    var lastError: String? = null,
) : BaseTime() {

    // 발송 실패 시 재시도 예약 또는 DEAD 전환. nextAttempt/maxRetry 는 호출자가 계산해 전달한다(테스트 용이).
    fun fail(error: String, maxRetry: Int, nextAttempt: LocalDateTime) {
        retryCount += 1
        lastError = error
        if (retryCount >= maxRetry) {
            status = EmailOutboxStatus.DEAD
        } else {
            nextAttemptAt = nextAttempt
        }
    }

    companion object {
        fun pending(payload: String, now: LocalDateTime): EmailOutbox =
            EmailOutbox(payload = payload, nextAttemptAt = now)
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxStatus.kt \
        src/main/kotlin/com/example/reserve/email/outbox/EmailOutbox.kt
git commit -m "feat 이메일 아웃박스 상태 enum과 엔티티 추가"
```

---

## Task 2: 아웃박스 리포지토리 (SKIP LOCKED 선점 조회)

**Files:**
- Create: `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxRepository.kt`
- Test: `src/test/kotlin/com/example/reserve/email/outbox/EmailOutboxTest.kt`

- [ ] **Step 1: 실패 테스트 작성 (선점 조회가 발송 대기 행만 반환)**

`EmailOutboxTest.kt` (신규):

```kotlin
package com.example.reserve.email.outbox

import com.example.reserve.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

class EmailOutboxTest(
    @Autowired private val emailOutboxRepository: EmailOutboxRepository,
) : IntegrationTestSupport() {

    @Test
    fun `findDueForDispatch는 next_attempt_at이 지난 PENDING 행만 반환한다`() {
        val now = LocalDateTime.now()
        // 발송 대기(지금 발송 가능)
        emailOutboxRepository.save(EmailOutbox.pending("due", now.minusSeconds(1)))
        // 아직 미래 (백오프 대기 중)
        emailOutboxRepository.save(EmailOutbox.pending("future", now.plusMinutes(10)))

        val due = emailOutboxRepository.findDueForDispatch(now, 100)

        assertThat(due).hasSize(1)
        assertThat(due[0].payload).isEqualTo("due")
    }
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

Run: `./gradlew test --tests "com.example.reserve.email.outbox.EmailOutboxTest"`
Expected: FAIL — `findDueForDispatch` 미정의로 컴파일 에러

- [ ] **Step 3: 리포지토리 구현**

`EmailOutboxRepository.kt`:

```kotlin
package com.example.reserve.email.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EmailOutboxRepository : JpaRepository<EmailOutbox, Long> {

    // 발송 대기 행을 선점 조회한다.
    // FOR UPDATE SKIP LOCKED: 다중 워커가 동시에 읽어도 서로 잠긴 행을 건너뛰어 중복 발송/대기를 방지한다.
    // JPQL은 SKIP LOCKED를 표현하지 못하므로 native 쿼리를 사용한다.
    @Query(
        value = """
            SELECT * FROM email_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findDueForDispatch(
        @Param("now") now: LocalDateTime,
        @Param("batchSize") batchSize: Int,
    ): List<EmailOutbox>
}
```

> 주의: 일부 Hibernate 버전은 native `LIMIT :param` 바인딩에 문제가 있다. 아래 테스트가 실패하면 `LIMIT`을 상수로 바꾸거나 `batchSize`를 SQL에 직접 문자열 결합(Int라 인젝션 위험 없음)한다.

- [ ] **Step 4: 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.example.reserve.email.outbox.EmailOutboxTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxRepository.kt \
        src/test/kotlin/com/example/reserve/email/outbox/EmailOutboxTest.kt
git commit -m "feat 아웃박스 SKIP LOCKED 선점 조회 리포지토리 추가"
```

---

## Task 3: 이메일 데이터 팩토리 + 발송 서비스 단순화

**Files:**
- Modify: `src/main/kotlin/com/example/reserve/email/dto/ReservationEmailData.kt`
- Modify: `src/main/kotlin/com/example/reserve/email/EmailService.kt`

- [ ] **Step 1: `ReservationEmailData.from()` 정적 팩토리 추가**

`ReservationEmailData.kt`의 `data class` 본문에 companion 추가(파일 상단 import에 `Member`, `PerformanceSchedule`, `Reserve` 추가):

```kotlin
package com.example.reserve.email.dto

import com.example.reserve.member.Member
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.reserve.Reserve
import com.example.reserve.reserve.ReserveStatus
import java.time.LocalDateTime

// 이메일 발송용 데이터 (JPA 엔티티 포함 금지 — LAZY 로딩 안전, JSON 직렬화 대상)
data class ReservationEmailData(
    val toEmail: String,
    val memberName: String,
    val reservationNumber: String,
    val status: ReserveStatus,
    val totalAmount: Long,
    val rewardDiscountAmount: Long,
    val finalAmount: Long,
    val seatNumbers: List<String>,
    val performanceTitle: String,
    val performanceType: String,
    val venueName: String,
    val venueLocation: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val reservedAt: LocalDateTime?,
    val cancelledAt: LocalDateTime?,
) {
    companion object {
        // 확정/취소 트랜잭션 안에서 호출 — LAZY 연관을 안전하게 스냅샷으로 복사한다.
        fun from(
            reserve: Reserve,
            member: Member,
            performanceSchedule: PerformanceSchedule,
            seatNumbers: List<String>,
        ): ReservationEmailData = ReservationEmailData(
            toEmail = member.email,
            memberName = member.name,
            reservationNumber = reserve.reservationNumber,
            status = reserve.status,
            totalAmount = reserve.totalAmount,
            rewardDiscountAmount = reserve.rewardDiscountAmount,
            finalAmount = reserve.finalAmount,
            seatNumbers = seatNumbers,
            performanceTitle = performanceSchedule.performance.title,
            performanceType = performanceSchedule.performance.type,
            venueName = performanceSchedule.venue.name,
            venueLocation = performanceSchedule.venue.location,
            startTime = performanceSchedule.startTime,
            endTime = performanceSchedule.endTime,
            reservedAt = reserve.createdAt,
            cancelledAt = reserve.cancelledAt,
        )
    }
}
```

- [ ] **Step 2: `EmailService` 단순화 — 이벤트 발행 제거, `@Async` 제거, 실패 전파**

`EmailService.kt` 전체를 아래로 교체:

```kotlin
package com.example.reserve.email

import com.example.reserve.config.Loggable
import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.reserve.ReserveStatus
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context
import java.time.format.DateTimeFormatter

// 이메일 발송 서비스 — 실제 SMTP 발송만 담당한다.
// 아웃박스 워커가 트랜잭션 안에서 동기 호출하므로, 실패는 삼키지 않고 전파해 워커가 재시도를 결정하게 한다.
@Service
class EmailService(
    private val mailSender: JavaMailSender,
    private val templateEngine: TemplateEngine,
) : Loggable {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    // 예약/취소 확인 이메일 발송 (실패 시 예외 전파)
    fun sendReservationEmail(data: ReservationEmailData) {
        val subject = when (data.status) {
            ReserveStatus.RESERVED -> "[예약 확인] ${data.performanceTitle} - ${data.reservationNumber}"
            ReserveStatus.CANCELLED -> "[예약 취소] ${data.performanceTitle} - ${data.reservationNumber}"
        }

        val body = buildEmailBody(data)

        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, false, "UTF-8")
        helper.setTo(data.toEmail)
        helper.setSubject(subject)
        helper.setText(body, true)

        mailSender.send(message)
        log.info { "이메일 발송 완료 - 수신: ${data.toEmail}, 예약번호: ${data.reservationNumber}" }
    }

    // Thymeleaf 템플릿으로 HTML 본문 생성
    private fun buildEmailBody(data: ReservationEmailData): String {
        val context = Context()
        context.setVariable("isReserved", data.status == ReserveStatus.RESERVED)
        context.setVariable("memberName", data.memberName)
        context.setVariable("reservationNumber", data.reservationNumber)
        context.setVariable("performanceTitle", data.performanceTitle)
        context.setVariable("performanceType", data.performanceType)
        context.setVariable("venueName", data.venueName)
        context.setVariable("venueLocation", data.venueLocation)
        context.setVariable("startTime", data.startTime.format(DATE_FORMATTER))
        context.setVariable("endTime", data.endTime.format(DATE_FORMATTER))
        context.setVariable("seatNumbers", data.seatNumbers.joinToString(", "))
        context.setVariable("totalAmount", data.totalAmount)
        context.setVariable("rewardDiscountAmount", data.rewardDiscountAmount)
        context.setVariable("finalAmount", data.finalAmount)
        context.setVariable("reservedAt", data.reservedAt?.format(DATE_FORMATTER) ?: "-")
        context.setVariable("cancelledAt", data.cancelledAt?.format(DATE_FORMATTER) ?: "-")

        return templateEngine.process("reservation-email", context)
    }
}
```

> 이 시점에는 아직 `ReserveService`가 옛 `publishReservationEmail`을 호출하므로 컴파일이 깨진다. Task 6에서 호출부를 교체하며 함께 통과시킨다. 여기서는 커밋하지 않고 Task 4~6을 진행한다.

- [ ] **Step 3: 컴파일은 Task 6에서 함께 맞춘다 (여기서 커밋하지 않음)**

---

## Task 4: 아웃박스 서비스 (enqueue + dispatchBatch)

**Files:**
- Create: `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxService.kt`
- Test: `src/test/kotlin/com/example/reserve/email/outbox/EmailOutboxTest.kt` (테스트 추가)

- [ ] **Step 1: 서비스 구현**

`EmailOutboxService.kt`:

```kotlin
package com.example.reserve.email.outbox

import com.example.reserve.config.Loggable
import com.example.reserve.email.EmailService
import com.example.reserve.email.dto.ReservationEmailData
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// 아웃박스 적재(enqueue)와 발송(dispatchBatch)을 담당한다.
@Service
class EmailOutboxService(
    private val emailOutboxRepository: EmailOutboxRepository,
    private val emailService: EmailService,
    private val objectMapper: ObjectMapper,
    @Value("\${email.outbox.batch-size:20}") private val batchSize: Int,
    @Value("\${email.outbox.max-retry:5}") private val maxRetry: Int,
) : Loggable {

    // 예약/취소 확정 트랜잭션 안에서 호출된다.
    // MANDATORY: 반드시 기존 트랜잭션에 참여해야 원자성이 보장된다(트랜잭션 밖 호출 시 예외로 오용 차단).
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(data: ReservationEmailData) {
        val payload = objectMapper.writeValueAsString(data)
        emailOutboxRepository.save(EmailOutbox.pending(payload, LocalDateTime.now()))
    }

    // 워커가 주기 호출한다. 배치를 선점한 트랜잭션 안에서 그대로 발송한다.
    // 성공 행은 삭제, 실패 행은 dirty checking 으로 재시도/DEAD 상태가 커밋된다.
    @Transactional
    fun dispatchBatch() {
        val now = LocalDateTime.now()
        val batch = emailOutboxRepository.findDueForDispatch(now, batchSize)
        if (batch.isEmpty()) return

        val sent = mutableListOf<EmailOutbox>()
        for (outbox in batch) {
            try {
                val data = objectMapper.readValue(outbox.payload, ReservationEmailData::class.java)
                emailService.sendReservationEmail(data)
                sent += outbox
            } catch (e: Exception) {
                // 한 건 실패가 배치 전체를 롤백하지 않도록 건별로 잡는다.
                val nextAttempt = now.plusSeconds(backoffSeconds(outbox.retryCount + 1))
                outbox.fail(e.message ?: e.javaClass.simpleName, maxRetry, nextAttempt)
                log.warn(e) { "이메일 발송 실패 - outboxId: ${outbox.id}, retryCount: ${outbox.retryCount}, status: ${outbox.status}" }
            }
        }
        if (sent.isNotEmpty()) emailOutboxRepository.deleteAll(sent)
    }

    // 지수 백오프(초): 2^attempt, 상한 300초
    private fun backoffSeconds(attempt: Int): Long = minOf(300L, 1L shl minOf(attempt, 8))
}
```

- [ ] **Step 2: 실패 테스트 추가 (enqueue 저장 + dispatch 발송/삭제 + 재시도 + DEAD)**

`EmailOutboxTest.kt`에 아래 테스트와 헬퍼를 추가(상단 import 보강):

```kotlin
import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.reserve.ReserveStatus
import org.mockito.BDDMockito.willThrow
import org.mockito.kotlin.any
import org.springframework.transaction.support.TransactionTemplate
import jakarta.mail.internet.MimeMessage
```

클래스 본문에 추가:

```kotlin
    @Autowired private lateinit var emailOutboxService: EmailOutboxService

    private fun sampleData(): ReservationEmailData = ReservationEmailData(
        toEmail = "user@test.com",
        memberName = "테스터",
        reservationNumber = "R-1",
        status = ReserveStatus.RESERVED,
        totalAmount = 10_000,
        rewardDiscountAmount = 0,
        finalAmount = 10_000,
        seatNumbers = listOf("A1"),
        performanceTitle = "테스트 공연",
        performanceType = "콘서트",
        venueName = "테스트홀",
        venueLocation = "서울",
        startTime = LocalDateTime.now().plusDays(7),
        endTime = LocalDateTime.now().plusDays(7).plusHours(2),
        reservedAt = LocalDateTime.now(),
        cancelledAt = null,
    )

    @Test
    fun `enqueue는 트랜잭션 안에서 PENDING 행 한 건을 저장한다`() {
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        val all = emailOutboxRepository.findAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].status).isEqualTo(EmailOutboxStatus.PENDING)
    }

    @Test
    fun `dispatchBatch는 발송 성공 행을 삭제한다`() {
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        emailOutboxService.dispatchBatch()

        assertThat(emailOutboxRepository.findAll()).isEmpty()
    }

    @Test
    fun `발송 실패 시 retryCount가 증가하고 PENDING으로 남는다`() {
        willThrow(RuntimeException("SMTP down")).given(mailSender).send(any<MimeMessage>())
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        emailOutboxService.dispatchBatch()

        val row = emailOutboxRepository.findAll().single()
        assertThat(row.status).isEqualTo(EmailOutboxStatus.PENDING)
        assertThat(row.retryCount).isEqualTo(1)
        assertThat(row.nextAttemptAt).isAfter(LocalDateTime.now())
    }

    @Test
    fun `최대 재시도 초과 시 DEAD로 전환된다`() {
        willThrow(RuntimeException("SMTP down")).given(mailSender).send(any<MimeMessage>())
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        // max-retry(기본 5)만큼 즉시 재시도되도록, 매 시도 전 next_attempt_at 을 과거로 되돌린다.
        // 트랜잭션 안에서 조회한 엔티티는 managed 상태라 dirty checking 으로 갱신이 커밋된다.
        repeat(5) {
            txTemplate.executeWithoutResult {
                emailOutboxRepository.findAll().forEach { it.nextAttemptAt = LocalDateTime.now().minusSeconds(1) }
            }
            emailOutboxService.dispatchBatch()
        }

        val row = emailOutboxRepository.findAll().single()
        assertThat(row.status).isEqualTo(EmailOutboxStatus.DEAD)
        assertThat(row.retryCount).isEqualTo(5)
    }
```

> `txTemplate`, `mailSender`는 `IntegrationTestSupport`에서 상속된다. `org.mockito.kotlin.any`가 없으면 `build.gradle`의 test 의존성에 `org.mockito.kotlin:mockito-kotlin`이 있는지 확인하고, 없으면 `org.mockito.ArgumentMatchers.any(MimeMessage::class.java)`로 대체한다.

- [ ] **Step 3: 테스트 실행 (아직 ReserveService 미수정이라 전체 컴파일은 깨질 수 있음 → Task 6 후 재실행)**

Run: `./gradlew compileKotlin`
Expected: `ReserveService`의 옛 호출부 때문에 실패 — Task 6에서 해소

- [ ] **Step 4: (커밋 보류)** Task 6에서 서비스 연결까지 맞춘 뒤 함께 커밋한다.

---

## Task 5: 아웃박스 워커 (@Scheduled 폴링)

**Files:**
- Create: `src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxWorker.kt`

- [ ] **Step 1: 워커 구현**

`EmailOutboxWorker.kt`:

```kotlin
package com.example.reserve.email.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 아웃박스 폴링 트리거. 발송 로직은 서비스에 위임한다.
// 테스트에서는 email.outbox.scheduler.enabled=false 로 비활성화하고 dispatchBatch()를 직접 호출해 결정론을 확보한다.
@Component
@ConditionalOnProperty(
    name = ["email.outbox.scheduler.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class EmailOutboxWorker(
    private val emailOutboxService: EmailOutboxService,
) {
    // fixedDelay: 직전 실행 종료 후 간격을 두어 폴링이 겹쳐 쌓이지 않게 한다.
    @Scheduled(fixedDelayString = "\${email.outbox.poll-interval-ms:1000}")
    fun poll() {
        emailOutboxService.dispatchBatch()
    }
}
```

- [ ] **Step 2: 컴파일 확인(단독)**

Run: `./gradlew compileKotlin`
Expected: `ReserveService` 호출부로 인한 실패는 남아 있음 — Task 6에서 해소. 워커 자체 문법 오류가 없는지만 확인.

- [ ] **Step 3: (커밋 보류)** Task 6에서 함께 커밋한다.

---

## Task 6: ReserveService 연결 + 설정 추가 + 옛 경로 삭제

**Files:**
- Modify: `src/main/kotlin/com/example/reserve/reserve/ReserveService.kt`
- Modify: `src/main/resources/application.properties`
- Delete: `src/main/kotlin/com/example/reserve/email/EmailConfig.kt`
- Delete: `src/main/kotlin/com/example/reserve/email/ReservationEmailEvent.kt`
- Delete: `src/main/kotlin/com/example/reserve/email/ReservationEmailEventListener.kt`

- [ ] **Step 1: `ReserveService` 의존성 및 호출부 교체**

`ReserveService.kt` import 교체 — `com.example.reserve.email.EmailService` 제거, 추가:

```kotlin
import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.email.outbox.EmailOutboxService
```

생성자 파라미터 `emailService: EmailService` → `emailOutboxService: EmailOutboxService`로 변경:

```kotlin
    private val memberService: MemberService,
    private val emailOutboxService: EmailOutboxService,
) : Loggable {
```

`confirm()`의 6번 주석/호출(기존 81줄)을 교체:

```kotlin
        // 6. 예약 확인 이메일 — 발송 의도를 같은 커밋으로 아웃박스에 적재 (실제 발송은 워커가 처리)
        emailOutboxService.enqueue(
            ReservationEmailData.from(reserve, member, performanceSchedule, request.seatNumbers)
        )
```

`cancelReserve()`의 6번 주석/호출(기존 137줄)을 교체:

```kotlin
        // 6. 취소 확인 이메일 — 발송 의도를 같은 커밋으로 아웃박스에 적재
        emailOutboxService.enqueue(
            ReservationEmailData.from(reserve, member, performanceSchedule, seatNumbers)
        )
```

- [ ] **Step 2: 옛 이벤트/스레드풀 파일 삭제**

```bash
git rm src/main/kotlin/com/example/reserve/email/EmailConfig.kt \
       src/main/kotlin/com/example/reserve/email/ReservationEmailEvent.kt \
       src/main/kotlin/com/example/reserve/email/ReservationEmailEventListener.kt
```

- [ ] **Step 3: 설정 추가**

`application.properties` 끝에 추가:

```properties
# 이메일 아웃박스 워커
email.outbox.poll-interval-ms=1000
email.outbox.batch-size=20
email.outbox.max-retry=5
```

- [ ] **Step 4: 전체 컴파일 확인**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL (옛 `publishReservationEmail`/`@Async` 참조가 모두 사라짐)

- [ ] **Step 5: 커밋 (아웃박스 서비스/워커/연결/삭제를 한 논리 단위로)**

```bash
git add src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxService.kt \
        src/main/kotlin/com/example/reserve/email/outbox/EmailOutboxWorker.kt \
        src/main/kotlin/com/example/reserve/email/EmailService.kt \
        src/main/kotlin/com/example/reserve/email/dto/ReservationEmailData.kt \
        src/main/kotlin/com/example/reserve/reserve/ReserveService.kt \
        src/main/resources/application.properties
git commit -m "feat 예약 확인 이메일을 트랜잭셔널 아웃박스로 전환"
```

---

## Task 7: 테스트 격리 (스케줄러 비활성화) + 아웃박스 테스트 정리

**Files:**
- Modify: `src/test/kotlin/com/example/reserve/support/IntegrationTestSupport.kt`

- [ ] **Step 1: 테스트에서 스케줄러 비활성화 + 아웃박스 정리**

`@SpringBootTest` 프로퍼티에 스케줄러 비활성화를 추가:

```kotlin
@SpringBootTest(properties = [
    "management.health.mail.enabled=false",
    "email.outbox.scheduler.enabled=false",
])
```

`setUpBase()`의 삭제 순서 최상단(seatRepository.deleteAll() 앞)에 아웃박스 정리를 추가한다. `EmailOutboxRepository`를 주입한다:

```kotlin
import com.example.reserve.email.outbox.EmailOutboxRepository
```
```kotlin
    @Autowired protected lateinit var emailOutboxRepository: EmailOutboxRepository
```
```kotlin
        // FK 안전 순서로 전체 삭제 (롤백 대신 수동 정리)
        emailOutboxRepository.deleteAll()
        seatRepository.deleteAll()
```

> `EmailOutboxTest`가 이미 `emailOutboxRepository`를 `@Autowired`로 별도 주입하고 있다면, 베이스로 올린 뒤 테스트의 중복 주입 파라미터를 제거해 충돌을 피한다.

- [ ] **Step 2: 아웃박스 전체 테스트 실행 → 통과 확인**

Run: `./gradlew test --tests "com.example.reserve.email.outbox.EmailOutboxTest"`
Expected: PASS (선점 조회 / enqueue / dispatch 삭제 / 재시도 / DEAD 5건)

- [ ] **Step 3: 기존 예약 테스트 회귀 확인 (이메일 경로 변경이 깨지 않았는지)**

Run: `./gradlew test`
Expected: PASS (예약/취소/동시성/멱등성 테스트가 mock 발송으로 그대로 통과)

- [ ] **Step 4: 커밋**

```bash
git add src/test/kotlin/com/example/reserve/support/IntegrationTestSupport.kt \
        src/test/kotlin/com/example/reserve/email/outbox/EmailOutboxTest.kt
git commit -m "test 아웃박스 통합 테스트와 테스트 스케줄러 격리 추가"
```

---

## Task 8: 문서/주석 동기화

**Files:**
- Modify: `src/main/kotlin/com/example/reserve/config/LoadTestSupport.kt`

- [ ] **Step 1: 스테일 주석 갱신**

`LoadTestSupport.kt`의 `LoadTestMailConfig` 주석(기존 37~39줄)을 아웃박스 구조에 맞게 교체:

```kotlin
// 운영의 Gmail SMTP 발송을 무력화한다.
// 발송(send)만 no-op 으로 두어 외부 의존/스로틀/메모리 증가를 제거하고,
// MimeMessage 생성 + Thymeleaf 렌더링 같은 아웃박스 워커 경로의 실제 CPU 비용은 그대로 측정되게 한다.
```

- [ ] **Step 2: 로드테스트에서 워커가 발송을 실제로 소진하는지 확인 (선택)**

loadtest 프로파일에서 `email.outbox.scheduler.enabled`는 기본값(true)이므로 워커가 동작하고, `NoOpMailSender`가 발송을 즉시 소진한다. 별도 조치 불필요.

- [ ] **Step 3: 커밋**

```bash
git add src/main/kotlin/com/example/reserve/config/LoadTestSupport.kt
git commit -m "docs 아웃박스 전환에 맞춰 로드테스트 메일 주석 갱신"
```

- [ ] **Step 4: 아키텍처 문서 동기화 질문 (CLAUDE.md 규칙)**

이번 변경은 이메일 발송 아키텍처(이벤트/@Async → 아웃박스)를 바꾸므로, 프로젝트에 아키텍처 문서가 있다면 반영 여부를 사용자에게 질문한다. `rules/architecture.md`가 현재 브랜치에서 삭제 상태이니 대상 문서를 먼저 확인한다.

---

## Self-Review 체크

- **스펙 커버리지**: 원자성(enqueue in-txn + MANDATORY, Task 4/6), 요청 경로 보호(@Async/CallerRuns 제거, Task 3/6), 버스트 완충(테이블 적재 + 폴링, Task 4/5), 재시도/DEAD(Task 4), 다중 인스턴스 중복 방지(SKIP LOCKED, Task 2), 인덱스/보존(Index + DELETE-on-send, Task 1/4) — 모두 태스크로 매핑됨.
- **타입 일관성**: `EmailOutbox.pending(payload, now)`, `fail(error, maxRetry, nextAttempt)`, `EmailOutboxRepository.findDueForDispatch(now, batchSize)`, `EmailOutboxService.enqueue(data)` / `dispatchBatch()`, `EmailService.sendReservationEmail(data)`, `ReservationEmailData.from(...)` — 태스크 간 시그니처 일치 확인.
- **삭제 안전성**: `@Async`/`@EnableAsync`는 이메일 경로에만 존재(grep 확인) → 삭제 안전.
- **잔여 리스크**: (1) native `LIMIT :batchSize` 바인딩 — Task 2 테스트가 검출, 실패 시 상수/문자열 결합으로 대체. (2) 락 유지 방식의 트랜잭션 길이 — batch-size=20으로 제한, 부족 시 claim-release 승격(범위 밖).
