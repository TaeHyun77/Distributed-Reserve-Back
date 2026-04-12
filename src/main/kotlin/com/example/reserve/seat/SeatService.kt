package com.example.reserve.seat

import com.example.reserve.config.Loggable
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.reserve.reserve.Reserve
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.dto.SeatResponse
import com.example.reserve.seat.repository.SeatRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SeatService(
    private val seatRepository: SeatRepository,
    private val performanceScheduleRepository: PerformanceScheduleRepository
): Loggable {

    // 좌석 예약
    fun reserveSeats(seats: List<Seat>, reserve: Reserve) {
        seats.forEach { it.occupy(reserve) }
    }

    // 좌석 예약 해제
    fun releaseSeats(seats: List<Seat>) {
        seats.forEach { it.release() }
    }

    // 예약하려는 좌석의 유효성 파악
    fun getAvailableSeatsWithLock(
        seatNumbers: List<String>,
        performanceScheduleId: Long
    ): List<Seat> {

        // 좌석들에 대해 비관적 락
        val seats = seatRepository.findAllByPerformanceScheduleIdAndSeatNumbersWithLock(
            performanceScheduleId,
            seatNumbers
        )

        // 존재하는 좌석인지 체크
        val foundSeatNumbers = seats.map { it.seatNumber }.toSet()

        val notFound = seatNumbers.filter { it !in foundSeatNumbers }
        if (notFound.isNotEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_SEAT_INFO)
        }

        // 좌석들의 상태를 체크하여 통해 데드락을 방지
        val alreadyReserved = seats.filter { it.isReserved }
        if (alreadyReserved.isNotEmpty()) {
            throw ReserveException(HttpStatus.CONFLICT, ErrorCode.SEAT_ALREADY_RESERVED)
        }

        return seats
    }

    // 특정 공연에서 상영 중인 영화의 좌석 목록 조회
    fun getSeatList(performanceScheduleId: Long): List<SeatResponse> {
        val seatList =  seatRepository.findSeatByPerformanceScheduleId(performanceScheduleId)

        return seatList.map(SeatResponse::from)
    }
}