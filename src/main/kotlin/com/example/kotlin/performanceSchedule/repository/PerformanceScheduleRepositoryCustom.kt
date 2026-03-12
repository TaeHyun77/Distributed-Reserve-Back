package com.example.kotlin.performanceSchedule.repository

import com.example.kotlin.performanceSchedule.PerformanceSchedule

interface PerformanceScheduleRepositoryCustom {

    fun findPerformanceScheduleList(venueId: Long, performanceId: Long? = null): List<PerformanceSchedule>
}
