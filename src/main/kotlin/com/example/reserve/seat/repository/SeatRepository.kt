package com.example.reserve.seat.repository

import com.example.reserve.seat.Seat
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SeatRepository: JpaRepository<Seat, Long>, SeatRepositoryCustom {
    @Modifying
    @Query("UPDATE Seat s SET s.reserve = null WHERE s.performanceSchedule.id = :scheduleId")
    fun resetByScheduleId(@Param("scheduleId") scheduleId: Long)

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    select s from Seat s
    where s.performanceSchedule.id = :performanceScheduleId
      and s.seatNumber in :seatNumbers
    order by s.id asc
    """)
    fun findAllByPerformanceScheduleIdAndSeatNumbersWithLock(
        performanceScheduleId: Long,
        seatNumbers: List<String>
    ): List<Seat>
}