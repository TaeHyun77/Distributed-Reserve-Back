package com.example.kotlin.performanceSchedule.repository

import com.example.kotlin.performanceSchedule.QPerformanceSchedule
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import com.querydsl.jpa.impl.JPAQueryFactory

class PerformanceScheduleRepositoryImpl(
    private val queryFactory: JPAQueryFactory
): PerformanceScheduleRepositoryCustom {

    override
    fun findPerformanceScheduleByVenueIdAndPerformanceId(
        venueId: Long,
        performanceId: Long
    ): PerformanceSchedule? {
        val performanceSchedule = QPerformanceSchedule.performanceSchedule

        return queryFactory
            .select(performanceSchedule)
            .from(performanceSchedule)
            .where(
                performanceSchedule.venue.id.eq(venueId),
                performanceSchedule.performance.id.eq(performanceId)
            )
            .fetchOne()
    }

    override
    fun findPerformanceScheduleListByVenueIdAndPerformanceId(
        venueId: Long,
        performanceId: Long
    ): List<PerformanceSchedule> {
        val performanceSchedule = QPerformanceSchedule.performanceSchedule

        return queryFactory
            .select(performanceSchedule)
            .from(performanceSchedule)
            .join(performanceSchedule.performance).fetchJoin()
            .where(
                performanceSchedule.venue.id.eq(venueId),
                performanceSchedule.performance.id.eq(performanceId)
            )
            .fetch()
    }
}