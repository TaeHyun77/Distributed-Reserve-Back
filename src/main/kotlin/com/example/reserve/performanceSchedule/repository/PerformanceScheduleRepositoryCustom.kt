package com.example.reserve.performanceSchedule.repository

import com.example.reserve.performanceSchedule.PerformanceSchedule

interface PerformanceScheduleRepositoryCustom {

    fun findPerformanceScheduleList(venueId: Long, performanceId: Long? = null): List<PerformanceSchedule>

    // 공연 일정 단건 조회 (performance + venue fetch join)
    fun findByIdWithPerformanceAndVenue(id: Long): PerformanceSchedule?
}
