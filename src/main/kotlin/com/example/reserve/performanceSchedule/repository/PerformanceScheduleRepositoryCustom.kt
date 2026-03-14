package com.example.reserve.performanceSchedule.repository

import com.example.reserve.performanceSchedule.PerformanceSchedule

interface PerformanceScheduleRepositoryCustom {

    fun findPerformanceScheduleList(venueId: Long, performanceId: Long? = null): List<PerformanceSchedule>
}
