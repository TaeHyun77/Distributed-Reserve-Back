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

    // 공연 일정 단건 조회 - performance + venue를 fetch join하여 LAZY 초기화 쿼리 방지
    override fun findByIdWithPerformanceAndVenue(id: Long): PerformanceSchedule? {
        val ps = QPerformanceSchedule.performanceSchedule

        return queryFactory
            .selectFrom(ps)
            .join(ps.performance).fetchJoin()
            .join(ps.venue).fetchJoin()
            .where(ps.id.eq(id))
            .fetchOne()
    }
}
