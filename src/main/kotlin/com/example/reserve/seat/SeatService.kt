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
    private val seatRepository: SeatRepository
): Loggable {

    // 좌석 선점 ( 비어있는 좌석만 원자적으로 점유 )
    // 호출자의 @Transactional에 포함됨
    fun claimSeats(reserve: Reserve, performanceScheduleId: Long, seatNumbers: List<String>) {
        val claimed = seatRepository.claimSeats(reserve, performanceScheduleId, seatNumbers)
        if (claimed == seatNumbers.size) {
            return
        }

        // 선점 실패 원인 구분 ( 없는 좌석 vs 이미 점유된 좌석 )
        val existing = seatRepository.findExistingSeatNumbers(performanceScheduleId, seatNumbers).toSet()
        val notFound = seatNumbers.filter { it !in existing }
        if (notFound.isNotEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_SEAT_INFO)
        }
        throw ReserveException(HttpStatus.CONFLICT, ErrorCode.SEAT_ALREADY_RESERVED)
    }

    // 좌석 예약 해제
    fun releaseSeats(seats: List<Seat>) {
        seats.forEach { it.release() }
    }

    // 특정 공연에서 상영 중인 영화의 좌석 목록 조회
    fun getSeatList(performanceScheduleId: Long): List<SeatResponse> {
        val seatList =  seatRepository.findSeatByPerformanceScheduleId(performanceScheduleId)

        return seatList.map(SeatResponse::from)
    }
}