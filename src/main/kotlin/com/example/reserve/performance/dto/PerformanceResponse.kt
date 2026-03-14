package com.example.reserve.performance.dto

import com.example.reserve.performance.Performance
import com.example.reserve.performanceSchedule.dto.PerformanceScheduleListResponse


data class PerformanceResponse(
    val id: Long? = null,

    val type: String,

    val title: String,

    val duration: Int,

    val price: Long,

    val performanceScheduleList: List<PerformanceScheduleListResponse> = emptyList()
) {
    companion object {
        fun from(performance: Performance): PerformanceResponse {
            return PerformanceResponse(
                id = performance.id,
                type = performance.type,
                title = performance.title,
                duration = performance.duration,
                price = performance.price,
                performanceScheduleList = performance.performanceScheduleList.map { ps ->
                    PerformanceScheduleListResponse(
                        venueId = ps.venue.id!!,
                        performanceId = ps.performance.id!!,
                        startTime = ps.startTime
                    )
                }
            )
        }
    }
}