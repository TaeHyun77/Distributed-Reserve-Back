package com.example.kotlin.performanceSchedule.repository

import com.example.kotlin.performanceSchedule.PerformanceSchedule

interface PerformanceScheduleRepositoryCustom {

    fun findPerformanceScheduleByVenueIdAndPerformanceId(venueId: Long, performanceId: Long): PerformanceSchedule?

    fun findPerformanceScheduleListByVenueIdAndPerformanceId(venueId: Long, performanceId: Long): List<PerformanceSchedule>

}