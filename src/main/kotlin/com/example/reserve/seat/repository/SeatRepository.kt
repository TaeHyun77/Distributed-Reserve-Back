package com.example.reserve.seat.repository

import com.example.reserve.member.Member
import com.example.reserve.reserve.Reserve
import com.example.reserve.seat.Seat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SeatRepository: JpaRepository<Seat, Long>, SeatRepositoryCustom {
    @Modifying
    @Query("""
        UPDATE Seat s
           SET s.reserve = null,
               s.status = com.example.reserve.seat.SeatStatus.FREE,
               s.heldBy = null,
               s.heldUntil = null
         WHERE s.performanceSchedule.id = :scheduleId
    """)
    fun resetByScheduleId(@Param("scheduleId") scheduleId: Long)

    // 홀드: 빈 좌석 / 만료된 홀드 / 내 기존 홀드만 원자적으로 HELD 로 선점, 선점된 행 수 반환
    @Modifying
    @Query("""
        UPDATE Seat s
           SET s.status = com.example.reserve.seat.SeatStatus.HELD,
               s.heldBy = :member,
               s.heldUntil = :heldUntil
         WHERE s.performanceSchedule.id = :scheduleId
           AND s.seatNumber IN :seatNumbers
           AND ( s.status = com.example.reserve.seat.SeatStatus.FREE
              OR (s.status = com.example.reserve.seat.SeatStatus.HELD AND s.heldUntil < :now)
              OR (s.status = com.example.reserve.seat.SeatStatus.HELD AND s.heldBy = :member) )
    """)
    fun holdSeats(
        @Param("member") member: Member,
        @Param("scheduleId") scheduleId: Long,
        @Param("seatNumbers") seatNumbers: List<String>,
        @Param("heldUntil") heldUntil: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): Int

    // 확정: 내 유효(미만료) HELD 좌석만 원자적으로 RESERVED 로 전이, 확정된 행 수 반환
    @Modifying
    @Query("""
        UPDATE Seat s
           SET s.status = com.example.reserve.seat.SeatStatus.RESERVED,
               s.reserve = :reserve,
               s.heldBy = null,
               s.heldUntil = null
         WHERE s.performanceSchedule.id = :scheduleId
           AND s.seatNumber IN :seatNumbers
           AND s.status = com.example.reserve.seat.SeatStatus.HELD
           AND s.heldBy = :member
           AND s.heldUntil > :now
    """)
    fun confirmSeats(
        @Param("member") member: Member,
        @Param("reserve") reserve: Reserve,
        @Param("scheduleId") scheduleId: Long,
        @Param("seatNumbers") seatNumbers: List<String>,
        @Param("now") now: LocalDateTime,
    ): Int

    // 해제: 내 HELD 좌석만 FREE 로 되돌림 ( 결제창 이탈 시 즉시 반납, 멱등 )
    @Modifying
    @Query("""
        UPDATE Seat s
           SET s.status = com.example.reserve.seat.SeatStatus.FREE,
               s.heldBy = null,
               s.heldUntil = null
         WHERE s.performanceSchedule.id = :scheduleId
           AND s.seatNumber IN :seatNumbers
           AND s.status = com.example.reserve.seat.SeatStatus.HELD
           AND s.heldBy = :member
    """)
    fun releaseHeldSeats(
        @Param("member") member: Member,
        @Param("scheduleId") scheduleId: Long,
        @Param("seatNumbers") seatNumbers: List<String>,
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
