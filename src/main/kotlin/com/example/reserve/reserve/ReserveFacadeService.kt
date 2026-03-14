package com.example.reserve.reserve

import com.example.reserve.idempotency.IdempotencyService
import com.example.reserve.redis.lock.RedisLockUtil
import com.example.reserve.reserve.dto.Refund
import com.example.reserve.reserve.dto.ReserveRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
class ReserveFacadeService(
    private val redisLockUtil: RedisLockUtil,
    private val idempotencyService: IdempotencyService,
    private val reserveService: ReserveService
) {

    fun reserveSeat(
        reserveRequest: ReserveRequest,
        username: String,
        idempotencyKey: String
    ): ResponseEntity<String> {

        // 여러 좌석에 대한 lock key
        val lockKeys: List<String> = reserveRequest.reservedSeat
            .map { "lock:${reserveRequest.performanceScheduleId}:seat:$it" }

        // key에 대한 ( 좌석들에 대한 ) Lock을 걸고 예약 실행 후 멱등성 저장
        // 이 과정을 하나의 트랜잭션에서 처리하도록 합니다.
        return redisLockUtil.acquireMultiLockAndRun(lockKeys) {
            idempotencyService.execute(idempotencyKey, "POST") {
                reserveService.reserve(reserveRequest, username)
            }
        }
    }

    fun cancelReserve(reserveNumber: String): Refund {
        return redisLockUtil.acquireLockAndRun("reserve:$reserveNumber:cancel") {
            reserveService.cancel(reserveNumber)
        }
    }
}
