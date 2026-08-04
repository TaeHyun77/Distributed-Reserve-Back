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
    @Value("\${email.outbox.retry-delay-seconds:60}") private val retryDelaySeconds: Long,
) : Loggable {

    // MANDATORY: 반드시 기존 트랜잭션에 참여해야 원자성이 보장되도록
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(data: ReservationEmailData) {
        val payload = objectMapper.writeValueAsString(data)
        emailOutboxRepository.save(EmailOutbox.pending(payload, LocalDateTime.now()))
    }

    // 워커가 주기 호출한다. 배치를 선점한 트랜잭션 안에서 그대로 발송
    // 성공 행은 삭제하고, 실패 행은 다음 시도 시각을 미뤄 재시도하도록 함
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
                // 한 건 실패가 배치 전체를 롤백하지 않도록 건별로 잡고, 다음 시도를 뒤로 미룸
                outbox.retryAfter(now.plusSeconds(retryDelaySeconds))
                log.warn(e) { "이메일 발송 실패 - outboxId: ${outbox.id}, 다음 시도: ${outbox.nextAttemptAt}" }
            }
        }
        if (sent.isNotEmpty()) emailOutboxRepository.deleteAll(sent)
    }
}
