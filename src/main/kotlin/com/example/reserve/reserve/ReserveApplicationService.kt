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

    // 결제 확정을 멱등성 선점(unique INSERT)으로 감싸 동시 진입을 차단한다.
    // 좌석 동시 확정은 confirm() 내부 조건부 UPDATE가 보장한다.
    fun confirmSeat(
        reserveRequest: ReserveRequest,
        username: String,
        idempotencyKey: String
    ): ResponseEntity<String> {
        return idempotencyService.execute(idempotencyKey, "POST") {
            reserveService.confirm(reserveRequest, username)
        }
    }
}
