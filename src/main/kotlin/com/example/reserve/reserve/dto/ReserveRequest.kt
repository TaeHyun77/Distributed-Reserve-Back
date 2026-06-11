package com.example.reserve.reserve.dto

import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import org.springframework.http.HttpStatus
import java.util.UUID

data class ReserveRequest (
    val reservationNumber: String = UUID.randomUUID().toString(),

    val rewardDiscountAmount: Long = 0L,

    val seatNumbers: List<String>,

    val performanceScheduleId: Long,
) {
    // 입력 검증 ( 경계에서 fail-fast → 멱등성 선점 전에 차단 )
    fun validate() {
        if (seatNumbers.isEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.EMPTY_SEAT_NUMBERS)
        }
        if (seatNumbers.size != seatNumbers.toSet().size) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.DUPLICATED_SEAT_NUMBERS)
        }
        if (rewardDiscountAmount < 0) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REWARD_DISCOUNT_AMOUNT)
        }
    }
}