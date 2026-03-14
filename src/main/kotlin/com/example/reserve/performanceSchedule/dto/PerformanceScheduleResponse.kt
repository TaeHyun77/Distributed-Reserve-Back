package com.example.reserve.performanceSchedule.dto

import com.example.reserve.performance.dto.PerformanceResponse
import com.example.reserve.performanceSchedule.PerformanceSchedule
import java.time.LocalDateTime

data class PerformanceScheduleResponse(
    val id: Long? = null,

    val performance: PerformanceResponse,

    val startTime: LocalDateTime,

    val endTime: LocalDateTime
) {
    companion object {
        fun from(performanceSchedule: PerformanceSchedule): PerformanceScheduleResponse {
            return PerformanceScheduleResponse(
                id = performanceSchedule.id,
                performance = PerformanceResponse.from(performanceSchedule.performance),
                startTime = performanceSchedule.startTime,
                endTime = performanceSchedule.endTime
            )
        }
    }
}