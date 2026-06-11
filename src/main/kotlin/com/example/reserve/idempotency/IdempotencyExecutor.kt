package com.example.reserve.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class IdempotencyExecutor(
    private val idempotencyRepository: IdempotencyRepository,
    private val objectMapper: ObjectMapper
) {

    // task 실행과 COMPLETED 전환을 한 트랜잭션으로 원자화
    // reserve()의 @Transactional(REQUIRED)이 이 트랜잭션에 합류 → 예약과 멱등성 확정이 함께 커밋
    @Transactional
    fun runAndComplete(pending: Idempotency, task: () -> Any): String {
        val response = task()
        val json = objectMapper.writeValueAsString(response)

        pending.status = IdempotencyStatus.COMPLETED
        pending.statusCode = 200
        pending.responseBody = json
        pending.expiresAt = LocalDateTime.now().plusMinutes(Idempotency.EXPIRATION_MINUTES)
        idempotencyRepository.save(pending)   // detached → merge → UPDATE (같은 트랜잭션)

        return json
    }
}
