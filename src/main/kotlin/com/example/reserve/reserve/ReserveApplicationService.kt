package com.example.reserve.reserve

import com.example.reserve.idempotency.IdempotencyService
import com.example.reserve.reserve.dto.ReserveRequest
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
class ReserveApplicationService(
    private val idempotencyService: IdempotencyService,
    private val reserveService: ReserveService
) {

    fun reserveSeat(
        reserveRequest: ReserveRequest,
        username: String,
        idempotencyKey: String
    ): ResponseEntity<String> {

        // 멱등성 선점(unique INSERT)으로 동시 진입을 차단하고 예약 실행
        // 좌석 동시 점유는 reserve() 내부 조건부 UPDATE가 보장한다
        return idempotencyService.execute(idempotencyKey, "POST") {
            reserveService.reserve(reserveRequest, username)
        }
    }
}
