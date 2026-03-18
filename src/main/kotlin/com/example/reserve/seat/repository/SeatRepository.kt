package com.example.reserve.seat.repository

import com.example.reserve.seat.Seat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SeatRepository: JpaRepository<Seat, Long>, SeatRepositoryCustom {
    @Modifying
    @Query("UPDATE Seat s SET s.reserve = null WHERE s.performanceSchedule.id = :scheduleId")
    fun resetByScheduleId(@Param("scheduleId") scheduleId: Long)
}