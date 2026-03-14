package com.example.reserve.seat.repository

import com.example.reserve.seat.QSeat
import com.example.reserve.seat.Seat
import com.example.reserve.seat.dto.SeatResponse
import com.querydsl.core.types.Projections
import com.querydsl.jpa.impl.JPAQueryFactory

class SeatRepositoryImpl(
    private val queryFactory: JPAQueryFactory
): SeatRepositoryCustom {

    override
    fun findSeatByPerformanceScheduleId(
        performanceScheduleId: Long
    ): List<Seat> {
        val seat = QSeat.seat

        return queryFactory
            .selectFrom(seat)
            .where(seat.performanceSchedule.id.eq(performanceScheduleId))
            .fetch()
    }

    override
    fun findByPerformanceScheduleIdAndSeatNumber(performanceScheduleId: Long, seatNumber: String): Seat? {
        val seat = QSeat.seat

        return queryFactory
            .selectFrom(seat)
            .where(
                seat.performanceSchedule.id.eq(performanceScheduleId),
                seat.seatNumber.eq(seatNumber)
            )
            .fetchOne()
    }
}