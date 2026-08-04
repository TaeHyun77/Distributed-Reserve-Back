package com.example.reserve.email.outbox

import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.reserve.ReserveStatus
import com.example.reserve.support.IntegrationTestSupport
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime

class EmailOutboxTest(
    @Autowired private val emailOutboxService: EmailOutboxService
) : IntegrationTestSupport() {

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
        cancelledAt = null
    )

    @Test
    fun `findDueForDispatch는 발송 시각이 지난 행만 반환한다`() {
        val now = LocalDateTime.now()
        emailOutboxRepository.save(EmailOutbox.pending("due", now.minusSeconds(1)))
        emailOutboxRepository.save(EmailOutbox.pending("future", now.plusMinutes(10)))

        val due = emailOutboxRepository.findDueForDispatch(now, 100)

        assertThat(due).hasSize(1)
        assertThat(due[0].payload).isEqualTo("due")
    }

    @Test
    fun `enqueue는 트랜잭션 안에서 발송 대기 행 한 건을 저장한다`() {
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        assertThat(emailOutboxRepository.findAll()).hasSize(1)
    }

    @Test
    fun `dispatchBatch는 발송 성공 행을 삭제한다`() {
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        emailOutboxService.dispatchBatch()

        assertThat(emailOutboxRepository.findAll()).isEmpty()
    }

    @Test
    fun `발송 실패 시 행이 남고 다음 시도 시각이 미래로 밀린다`() {
        willThrow(RuntimeException("SMTP down")).given(mailSender).send(any(MimeMessage::class.java))
        txTemplate.executeWithoutResult { emailOutboxService.enqueue(sampleData()) }

        emailOutboxService.dispatchBatch()

        val row = emailOutboxRepository.findAll().single()
        assertThat(row.nextAttemptAt).isAfter(LocalDateTime.now())
    }
}
