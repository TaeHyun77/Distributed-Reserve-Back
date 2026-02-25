package com.example.kotlin.seat.dto

import com.example.kotlin.performance.dto.PerformanceResponse
import com.example.kotlin.performanceSchedule.dto.PerformanceScheduleResponse
import com.example.kotlin.seat.Seat

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