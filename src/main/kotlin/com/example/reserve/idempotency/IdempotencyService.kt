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

        // 1. 기존 이력 확인 ( 완료/처리중 replay 빠른 경로 )
        val existing = idempotencyRepository.findByIdempotencyKey(idempotencyKey)
        if (existing != null) {
            if (existing.expiresAt.isAfter(LocalDateTime.now())) {
                return resolveExisting(idempotencyKey, existing)
            }
            idempotencyRepository.delete(existing) // 만료 → 재선점 허용
        }

        // 2. 선점 + 실행 + 완료를 단일 트랜잭션으로 ( 커밋 1회 )
        return try {
            val successJson = idempotencyExecutor.insertRunComplete(idempotencyKey, httpMethod, task)
            log.info { "멱등성 키 저장 (성공) - 키: $idempotencyKey" }

            ResponseEntity.ok(successJson)

        } catch (e: DataIntegrityViolationException) {
            // 동시 동일키 경합 / 레이스 : 선행자가 이미 선점 또는 완료 → 재조회 후 캐시 응답
            log.info { "멱등성 키 선점 경합 감지 - 키: $idempotencyKey" }
            val winner = idempotencyRepository.findByIdempotencyKey(idempotencyKey)
                ?: throw e
            resolveExisting(idempotencyKey, winner)

        } catch (e: ReserveException) {
            // 비즈니스 에러: 단일 tx 롤백으로 예약/멱등행이 모두 원복됨 → 캐시 없이 동일 응답 형식으로 반환 ( 재시도 가능 )
            log.error(e) { "멱등성 처리 비즈니스 에러 - 키: $idempotencyKey, 에러: ${e.errorCode.name}" }
            ResponseEntity.status(e.status.value()).body(e.errorCode.name)
        }
        // 그 외 일시적 예외: 롤백되어 그대로 전파 → 재시도 가능 ( 캐시하지 않음 )
    }

    // 이미 존재하는 이력에 대한 응답 결정
    private fun resolveExisting(
        idempotencyKey: String,
        idempotency: Idempotency
    ): ResponseEntity<String> {
        return when (idempotency.status) {
            // COMPLETED : 중복 요청 → 캐시된 동일 응답
            IdempotencyStatus.COMPLETED -> {
                log.info { "동일한 중복 요청 감지 - 키: $idempotencyKey" }

                ResponseEntity
                    .status(idempotency.statusCode!!)
                    .body(idempotency.responseBody!!)
            }

            // 단일 커밋 설계에선 커밋된 PENDING 이 외부에 노출되지 않으므로 사실상 도달하지 않음 ( 방어적 처리 )
            IdempotencyStatus.PENDING -> {
                log.info { "처리 중인 중복 요청 감지 - 키: $idempotencyKey" }

                ResponseEntity.status(409).body("PROCESSING")
            }
        }
    }
}
