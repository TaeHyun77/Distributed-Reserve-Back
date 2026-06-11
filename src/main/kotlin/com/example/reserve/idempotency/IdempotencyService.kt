package com.example.reserve.idempotency

import com.example.reserve.config.Loggable
import com.example.reserve.reserveException.ReserveException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository,
    private val idempotencyExecutor: IdempotencyExecutor
) : Loggable {

    fun execute(
        idempotencyKey: String,
        httpMethod: String,
        task: () -> Any
    ): ResponseEntity<String> {

        // 1. 기존 이력 확인
        val existing = idempotencyRepository.findByIdempotencyKey(idempotencyKey)
        if (existing != null) { // 기존 이력이 존재한다면
            // 만료된 키는 삭제 후 재선점 ( 크래시로 PENDING 잔존 시에도 만료 후 회수 )
            if (existing.expiresAt.isAfter(LocalDateTime.now())) {
                return resolveExisting(idempotencyKey, existing)
            }
            idempotencyRepository.delete(existing)
        }

        // 2. PENDING 선점 ( unique INSERT = 동시 진입 상호배제 )
        val pending = try {
            idempotencyRepository.saveAndFlush(
                Idempotency(
                    idempotencyKey = idempotencyKey,
                    httpMethod = httpMethod,
                    status = IdempotencyStatus.PENDING,
                    expiresAt = LocalDateTime.now().plusMinutes(Idempotency.EXPIRATION_MINUTES)
                )
            )
        } catch (e: DataIntegrityViolationException) {
            // 경쟁 요청이 먼저 선점 → 재조회 후 처리
            log.info { "멱등성 키 선점 경합 감지 - 키: $idempotencyKey" }
            val winner = idempotencyRepository.findByIdempotencyKey(idempotencyKey) ?: throw e // 극히 드문 레이스(직후 삭제됨) → 일시적 에러로 재전파
            return resolveExisting(idempotencyKey, winner)
        }

        // 3. 선점 성공 → task + COMPLETED 전환을 한 트랜잭션으로 원자화 (별도 빈 위임)
        return try {
            val successJson = idempotencyExecutor.runAndComplete(pending, task)
            log.info { "멱등성 키 저장 (성공) - 키: $idempotencyKey" }

            ResponseEntity.ok(successJson)

        } catch (e: Exception) {
            // 일시적 에러는 캐싱하지 않고 선점 해제 후 그대로 던짐 ( 재시도 가능 )
            if (isTransientError(e)) {
                idempotencyRepository.delete(pending)
                throw e
            }

            handleException(pending, e)
        }
    }

    // 이미 존재하는 이력에 대한 응답 결정
    private fun resolveExisting(
        idempotencyKey: String,
        idempotency: Idempotency
    ): ResponseEntity<String> {
        return when (idempotency.status) {
            // COMPLETED : 중복 요청
            IdempotencyStatus.COMPLETED -> {
                log.info { "동일한 중복 요청 감지 - 키: $idempotencyKey" }

                ResponseEntity
                    .status(idempotency.statusCode!!)
                    .body(idempotency.responseBody!!)
            }

            // 아직 처리 중 → 409 즉시 반환 ( 클라이언트 재시도 )
            IdempotencyStatus.PENDING -> {
                log.info { "처리 중인 중복 요청 감지 - 키: $idempotencyKey" }
                ResponseEntity.status(409).body("PROCESSING")
            }
        }
    }

    private fun handleException(
        pending: Idempotency,
        e: Exception
    ): ResponseEntity<String> {
        val (status, errorCode) = when (e) {
            is ReserveException -> e.status.value() to e.errorCode.name
            else -> 500 to "INTERNAL_SERVER_ERROR"
        }

        complete(pending, status, errorCode)
        log.error(e) { "멱등성 키 저장 (실패) - 키: ${pending.idempotencyKey}, 에러: $errorCode" }

        return ResponseEntity
            .status(status)
            .body(errorCode)
    }

    // PENDING → COMPLETED 로 응답 확정
    private fun complete(pending: Idempotency, status: Int, body: String) {
        pending.status = IdempotencyStatus.COMPLETED
        pending.statusCode = status
        pending.responseBody = body
        pending.expiresAt = LocalDateTime.now().plusMinutes(Idempotency.EXPIRATION_MINUTES)

        idempotencyRepository.save(pending)
    }

    private fun isTransientError(e: Exception): Boolean {
        return e !is ReserveException  // ReserveException은 비즈니스 에러 → 캐싱
    }
}
