package com.example.kotlin.performanceSchedule.repository

import com.example.kotlin.performanceSchedule.QPerformanceSchedule
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import com.querydsl.jpa.impl.JPAQueryFactory

class PerformanceScheduleRepositoryImpl(
    private val queryFactory: JPAQueryFactory
): PerformanceScheduleRepositoryCustom {

    override
    fun findPerformanceScheduleList(
        venueId: Long,
        performanceId: Long?
    ): List<PerformanceSchedule> {
        val ps = QPerformanceSchedule.performanceSchedule

        val query = queryFactory
            .select(ps)
            .from(ps)
            .join(ps.performance).fetchJoin()
            .where(ps.venue.id.eq(venueId))

        if (performanceId != null) {
            query.where(ps.performance.id.eq(performanceId))
        }

        return query.fetch()
    }
}
