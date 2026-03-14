package com.example.reserve.performance.repository

import com.example.reserve.performance.Performance
import com.example.reserve.performanceSchedule.QPerformanceSchedule
import com.querydsl.jpa.impl.JPAQueryFactory

class PerformanceRepositoryImpl(
    private val queryFactory: JPAQueryFactory
): PerformanceRepositoryCustom {

    // 특정 영화관의 영화 목록 리스트 반환
    override fun findPerformancesByVenueId(venueId: Long): List<Performance> {
        val performanceSchedule = QPerformanceSchedule.performanceSchedule

        return queryFactory
            .select(performanceSchedule.performance)
            .from(performanceSchedule)
            .join(performanceSchedule.performance.performanceScheduleList).fetchJoin()
            .where(performanceSchedule.venue.id.eq(venueId))
            .distinct()
            .fetch()
    }
}