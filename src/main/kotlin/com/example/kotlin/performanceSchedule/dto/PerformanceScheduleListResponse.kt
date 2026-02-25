package com.example.kotlin.performanceSchedule.dto

import java.time.LocalDateTime

data class PerformanceScheduleListResponse(
    val venueId: Long,

    val performanceId: Long,

    val startTime: LocalDateTime
)