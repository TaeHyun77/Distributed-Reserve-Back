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
    fun findByMemberUsernameAndStatus(username: String, status: ReserveStatus): List<Reserve>

    @Modifying
    @Query("DELETE FROM Reserve r WHERE r.performanceScheduleId = :scheduleId")
    fun deleteAllByScheduleId(@Param("scheduleId") scheduleId: Long)

    @Query("""
      SELECT r FROM Reserve r
      LEFT JOIN FETCH r.seatList
      WHERE r.reservationNumber = :reserveNumber
    """)
    fun findByReservationNumberWithFetch(
        @Param("reserveNumber") reserveNumber: String
    ): Reserve?
}