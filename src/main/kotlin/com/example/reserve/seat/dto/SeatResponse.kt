package com.example.reserve.seat.dto

import com.example.reserve.performance.dto.PerformanceResponse
import com.example.reserve.performanceSchedule.dto.PerformanceScheduleResponse
import com.example.reserve.seat.Seat
import java.time.LocalDateTime

data class SeatResponse(
    val seatNumber: String,

    // 선택 불가 여부 ( 확정 예약 또는 유효한 홀드 = 판매 불가 )
    val isReserved: Boolean,

    val performanceSchedule: PerformanceScheduleResponse
) {
    companion object {
        fun from(seat: Seat, now: LocalDateTime): SeatResponse {
            return SeatResponse(
                seatNumber = seat.seatNumber,
                isReserved = !seat.isSellable(now),
                performanceSchedule = PerformanceScheduleResponse(
                    performance = PerformanceResponse(
                        type = seat.performanceSchedule.performance.type,
                        title = seat.performanceSchedule.performance.title,
                        duration = seat.performanceSchedule.performance.duration,
                        price = seat.performanceSchedule.performance.price
                    ),
                    startTime = seat.performanceSchedule.startTime,
                    endTime = seat.performanceSchedule.endTime
                )
            )
        }
    }
}