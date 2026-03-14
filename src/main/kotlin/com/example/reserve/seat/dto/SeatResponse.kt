package com.example.reserve.seat.dto

import com.example.reserve.performance.dto.PerformanceResponse
import com.example.reserve.performanceSchedule.dto.PerformanceScheduleResponse
import com.example.reserve.seat.Seat

data class SeatResponse(
    val seatNumber: String,

    val isReserved: Boolean,

    val performanceSchedule: PerformanceScheduleResponse
) {
    companion object {
        fun from(seat: Seat): SeatResponse {
            return SeatResponse(
                seatNumber = seat.seatNumber,
                isReserved = seat.isReserved,
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