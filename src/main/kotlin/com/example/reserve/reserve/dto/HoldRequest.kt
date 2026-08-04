package com.example.reserve.reserve.dto

import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import org.springframework.http.HttpStatus

// 좌석 홀드/해제 요청 ( 결제창 이동·이탈 시점 ). 홀드는 결제가 아니므로 리워드 금액이 없다.
data class HoldRequest(
    val seatNumbers: List<String>,

    val performanceScheduleId: Long,
) {
    fun validate() {
        if (seatNumbers.isEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.EMPTY_SEAT_NUMBERS)
        }
        if (seatNumbers.size != seatNumbers.toSet().size) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.DUPLICATED_SEAT_NUMBERS)
        }
    }
}
