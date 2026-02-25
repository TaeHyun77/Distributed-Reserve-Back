package com.example.kotlin.performance.dto

import com.example.kotlin.performance.Performance
import com.example.kotlin.performanceSchedule.dto.PerformanceScheduleListResponse


data class PerformanceResponse(
    val id: Long? = null,

    val type: String,

    val title: String,

    val duration: String,

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