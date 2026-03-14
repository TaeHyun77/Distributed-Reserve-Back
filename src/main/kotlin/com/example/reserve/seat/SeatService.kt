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
    @Transactional
    fun reserveSeats(seats: List<Seat>, reserve: Reserve) {
        seats.forEach { it.occupy(reserve) }
    }

    // 좌석 예약 해제
    @Transactional
    fun releaseSeats(seats: List<Seat>) {
        seats.forEach { it.release() }
    }

    // 예약하려는 좌석의 유효성 파악
    fun getAvailableSeats(
        seats: List<String>,
        performanceScheduleId: Long
    ): List<Seat> {
        return seats.map { seatNumber ->
            val seat = seatRepository.findByPerformanceScheduleIdAndSeatNumber(performanceScheduleId, seatNumber)
                ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_SEAT_INFO)

            if (seat.isReserved) {
                throw ReserveException(HttpStatus.CONFLICT, ErrorCode.SEAT_ALREADY_RESERVED)
            }
            seat
        }
    }

    // 특정 공연에서 상영 중인 영화의 좌석 목록 조회
    fun getSeatList(performanceScheduleId: Long): List<SeatResponse> {
        val seatList =  seatRepository.findSeatByPerformanceScheduleId(performanceScheduleId)

        return seatList.map(SeatResponse::from)
    }
}