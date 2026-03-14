package com.example.reserve.performanceSchedule.repository

import com.example.reserve.performanceSchedule.QPerformanceSchedule
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.querydsl.jpa.impl.JPAQueryFactory

class PerformanceScheduleRepositoryImpl(
    private val queryFactory: JPAQueryFactory
): PerformanceScheduleRepositoryCustom {

    // 공연 정보 목록 반환
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
