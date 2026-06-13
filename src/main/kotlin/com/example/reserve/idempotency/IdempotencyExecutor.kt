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

    // PENDING 선점 + task 실행 + COMPLETED 확정을 단일 트랜잭션으로 묶어 예약 1건당 커밋을 1회로 단축
    // ( 기존: PENDING 을 별도 트랜잭션으로 선커밋(commit 1) 후 task+COMPLETED(commit 2) → 커밋 2회 )
    //
    // 동시 동일키는 unique INSERT 가 직렬화 : 선행 tx가 커밋(또는 롤백)할 때까지 후행 INSERT 가 블록되고
    // , 선행이 커밋하면 후행은 중복키 예외 → 호출측에서 캐시 응답으로 귀결된다.
    // ( 선행이 실패해 롤백되면 PENDING 행도 함께 사라지므로 후행이 정상 선점·실행한다 = 실패는 키를 막지 않음 )
    // PENDING 행은 커밋 전까지 외부 트랜잭션에 보이지 않으므로, 노출되는 멱등 상태는 항상 COMPLETED 뿐
    @Transactional
    fun insertRunComplete(idempotencyKey: String, httpMethod: String, task: () -> Any): String {
        // saveAndFlush 로 즉시 INSERT → 중복키면 여기서 DataIntegrityViolationException 발생 ( 이후 task 미실행 )
        val idempotency = idempotencyRepository.saveAndFlush(
            Idempotency(
                idempotencyKey = idempotencyKey,
                httpMethod = httpMethod,
                status = IdempotencyStatus.PENDING,
                expiresAt = LocalDateTime.now().plusMinutes(Idempotency.EXPIRATION_MINUTES)
            )
        )

        // task(reserve())는 @Transactional(REQUIRED)로 이 트랜잭션에 합류 → 예약과 멱등 확정이 함께 단일 커밋
        val json = objectMapper.writeValueAsString(task())

        // 같은 트랜잭션 내 managed 엔티티 → 변경 감지로 COMPLETED UPDATE가 커밋 시 함께 flush 됨
        idempotency.status = IdempotencyStatus.COMPLETED
        idempotency.statusCode = 200
        idempotency.responseBody = json
        idempotency.expiresAt = LocalDateTime.now().plusMinutes(Idempotency.EXPIRATION_MINUTES)

        return json
    }
}
