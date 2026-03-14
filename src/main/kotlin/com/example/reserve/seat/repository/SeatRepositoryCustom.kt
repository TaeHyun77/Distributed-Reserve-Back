package com.example.reserve.seat.repository

import com.example.reserve.seat.Seat
import com.example.reserve.seat.dto.SeatResponse

interface SeatRepositoryCustom {

    fun findSeatByPerformanceScheduleId(performanceScheduleId: Long): List<Seat>

    fun findByPerformanceScheduleIdAndSeatNumber(performanceScheduleId: Long, seatNumber: String): Seat?
}