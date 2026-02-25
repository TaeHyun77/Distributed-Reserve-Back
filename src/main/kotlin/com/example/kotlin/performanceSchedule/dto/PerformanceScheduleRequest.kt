package com.example.kotlin.performanceSchedule.dto

import com.example.kotlin.performance.Performance
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import com.example.kotlin.venue.Venue
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