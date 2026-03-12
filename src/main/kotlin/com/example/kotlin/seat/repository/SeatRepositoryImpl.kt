package com.example.kotlin.seat.repository

import com.example.kotlin.seat.QSeat
import com.example.kotlin.seat.Seat
import com.example.kotlin.seat.dto.SeatResponse
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