package com.example.reserve.seat.repository

import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.reserve.Reserve
import com.example.reserve.seat.Seat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SeatRepository: JpaRepository<Seat, Long>, SeatRepositoryCustom {
    @Modifying
    @Query("UPDATE Seat s SET s.reserve = null WHERE s.performanceSchedule.id = :scheduleId")
    fun resetByScheduleId(@Param("scheduleId") scheduleId: Long)

    // 비어있는 좌석만 원자적으로 선점, 선점된 행 수 반환
    @Modifying
    @Query("""
        UPDATE Seat s SET s.reserve = :reserve
        WHERE s.performanceSchedule.id = :scheduleId
          AND s.seatNumber IN :seatNumbers
          AND s.reserve IS NULL
    """)
    fun claimSeats(
        @Param("reserve") reserve: Reserve,
        @Param("scheduleId") scheduleId: Long,
        @Param("seatNumbers") seatNumbers: List<String>
    ): Int

    // 요청 좌석 중 실제 존재하는 좌석번호 조회 ( 선점 실패 원인 구분용 )
    @Query("""
        SELECT s.seatNumber FROM Seat s
        WHERE s.performanceSchedule.id = :scheduleId
          AND s.seatNumber IN :seatNumbers
    """)
    fun findExistingSeatNumbers(
        @Param("scheduleId") scheduleId: Long,
        @Param("seatNumbers") seatNumbers: List<String>
    ): List<String>
}
