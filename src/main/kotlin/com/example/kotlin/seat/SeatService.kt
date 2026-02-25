package com.example.kotlin.seat

import com.example.kotlin.config.Loggable
import com.example.kotlin.performanceSchedule.PerformanceSchedule
import com.example.kotlin.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.kotlin.reserve.Reserve
import com.example.kotlin.reserveException.ErrorCode
import com.example.kotlin.reserveException.ReserveException
import com.example.kotlin.seat.dto.SeatResponse
import com.example.kotlin.seat.repository.SeatRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SeatService(
    private val seatRepository: SeatRepository,
    private val performanceScheduleRepository: PerformanceScheduleRepository
): Loggable {

    // 특정 공연의 좌석 초기화
    @Transactional
    fun initSeats(performanceScheduleId: Long) {

        val performanceSchedule: PerformanceSchedule = performanceScheduleRepository.findById(performanceScheduleId)
            .orElseThrow { ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_PERFORMANCE_SCHEDULE) }

        val seats = mutableListOf<Seat>()

        for (row in 'A'..'E') {
            for (col in 1..5) {
                val seatNumber = "$row$col"

                val seat = Seat(
                    seatNumber = seatNumber,
                    performanceSchedule = performanceSchedule
                )
                seats.add(seat)
            }
        }
        seatRepository.saveAll(seats)
    }

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