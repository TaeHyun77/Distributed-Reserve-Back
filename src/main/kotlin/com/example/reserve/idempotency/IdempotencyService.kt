package com.example.reserve.idempotency

import com.example.reserve.config.Loggable
import com.example.reserve.reserveException.ReserveException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository,
    private val objectMapper: ObjectMapper
) : Loggable {

    companion object {
        private const val EXPIRATION_MINUTES = 10L
    }

    fun execute(
        idempotencyKey: String,
        httpMethod: String,
        task: () -> Any
    ): ResponseEntity<String> {

        // 기존 이력 확인
        val savedIdempotency = idempotencyRepository.findByIdempotencyKey(idempotencyKey)

        // 기존 이력이 존재하고
        if (savedIdempotency != null) {
            // 만료되지 않았다면 ( 중복 요청 )
            if (savedIdempotency.expiresAt.isAfter(LocalDateTime.now())) {
                log.info { "동일한 중복 요청 감지 - 키: $idempotencyKey" }

                // 저장되었던 값 반환
                return ResponseEntity
                    .status(savedIdempotency.statusCode)
                    .body(savedIdempotency.responseBody)
            }
            // 만료된 키 삭제 후 재실행 ( 만료되었을 시 )
            idempotencyRepository.delete(savedIdempotency)
        }

        // 기존 이력이 존재하지 않는다면
        return try {
            // 로직 실행
            val response = task()
            val successJson = objectMapper.writeValueAsString(response)

            // 성공 결과 저장
            recordResponse(idempotencyKey, httpMethod, successJson, 200)
            log.info { "멱등성 키 저장 (성공) - 키: $idempotencyKey" }

            ResponseEntity.ok(successJson)

        } catch (e: Exception) {
            // 일시적 에러는 캐싱하지 않고 그대로 던짐
            if (isTransientError(e)) {
                throw e
            }

            handleException(idempotencyKey, httpMethod, e)
        }
    }

    private fun handleException(
        idempotencyKey: String,
        httpMethod: String,
        e: Exception
    ): ResponseEntity<String> {
        val (status, errorCode) = when (e) {
            is ReserveException -> e.status.value() to e.errorCode.name
            else -> 500 to "INTERNAL_SERVER_ERROR"
        }

        recordResponse(idempotencyKey, httpMethod, errorCode, status)
        log.error(e) { "멱등성 키 저장 (실패) - 키: $idempotencyKey, 에러: $errorCode" }

        return ResponseEntity
            .status(status)
            .body(errorCode)
    }


    private fun recordResponse(key: String, method: String, body: String, status: Int) {
        idempotencyRepository.save(
            Idempotency(
                idempotencyKey = key,
                httpMethod = method,
                responseBody = body,
                statusCode = status,
                expiresAt = LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES)
            )
        )
    }

    private fun isTransientError(e: Exception): Boolean {
        return e !is ReserveException  // ReserveException은 비즈니스 에러 → 캐싱
    }
}
