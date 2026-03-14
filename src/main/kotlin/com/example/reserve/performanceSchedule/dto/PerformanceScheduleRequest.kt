package com.example.reserve.performanceSchedule.dto

import com.example.reserve.performance.Performance
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.venue.Venue
import java.time.LocalDateTime

data class PerformanceScheduleRequest(
    val venueId: Long,

    val performanceId: Long,

    val startTime: LocalDateTime,

    val endTime: LocalDateTime
) {
    fun toEntity(venue: Venue, performance: Performance): PerformanceSchedule {
        return PerformanceSchedule(
            venue = venue,
            performance = performance,
            startTime = this.startTime,
            endTime = this.endTime
        )
    }
}