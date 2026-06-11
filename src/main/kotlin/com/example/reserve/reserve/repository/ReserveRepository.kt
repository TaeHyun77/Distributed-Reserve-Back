package com.example.reserve.reserve.repository

import com.example.reserve.reserve.Reserve
import com.example.reserve.reserve.ReserveStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ReserveRepository: JpaRepository<Reserve, Long> {
    // 예약 내역 조회 ( member, seatList fetch join 으로 N+1 방지 )
    @Query(
        """
        SELECT DISTINCT r FROM Reserve r
        JOIN FETCH r.member
        LEFT JOIN FETCH r.seatList
        WHERE r.member.username = :username AND r.status = :status
        """
    )
    fun findByMemberUsernameAndStatus(
        @Param("username") username: String,
        @Param("status") status: ReserveStatus
    ): List<Reserve>

    @Modifying
    @Query("DELETE FROM Reserve r WHERE r.performanceScheduleId = :scheduleId")
    fun deleteAllByScheduleId(@Param("scheduleId") scheduleId: Long)

    // 예약 번호로 비관적 락 조회 ( 동시 취소 직렬화 )
    // seatList/member는 트랜잭션 내 lazy 로드
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reserve r WHERE r.reservationNumber = :reserveNumber")
    fun findByReservationNumberWithLock(
        @Param("reserveNumber") reserveNumber: String
    ): Reserve?
}