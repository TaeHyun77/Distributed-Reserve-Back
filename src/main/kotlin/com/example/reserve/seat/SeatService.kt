package com.example.reserve.seat

import com.example.reserve.config.Loggable
import com.example.reserve.member.Member
import com.example.reserve.reserve.Reserve
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.dto.SeatResponse
import com.example.reserve.seat.repository.SeatRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class SeatService(
    private val seatRepository: SeatRepository
): Loggable {

    // 좌석 홀드 ( 빈 좌석/만료 홀드/내 홀드만 원자적으로 HELD )
    // 호출자의 @Transactional에 포함됨
    fun holdSeats(member: Member, scheduleId: Long, seatNumbers: List<String>, heldUntil: LocalDateTime) {
        val held = seatRepository.holdSeats(member, scheduleId, seatNumbers, heldUntil, LocalDateTime.now())
        if (held == seatNumbers.size) {
            return
        }
        // 선점 실패 원인 구분 ( 없는 좌석 vs 남이 점유 중 )
        throwClaimCause(scheduleId, seatNumbers, ErrorCode.SEAT_ALREADY_HELD)
    }

    // 좌석 확정 ( 내 유효 홀드만 원자적으로 RESERVED )
    fun confirmSeats(reserve: Reserve, member: Member, scheduleId: Long, seatNumbers: List<String>) {
        val confirmed = seatRepository.confirmSeats(member, reserve, scheduleId, seatNumbers, LocalDateTime.now())
        if (confirmed == seatNumbers.size) {
            return
        }
        // 확정 실패 원인 구분 ( 없는 좌석 vs 만료·탈취된 홀드 )
        throwClaimCause(scheduleId, seatNumbers, ErrorCode.HOLD_EXPIRED_OR_NOT_OWNED)
    }

    // 홀드 즉시 해제 ( 내 홀드만, 멱등 — 이미 확정됐거나 내 홀드가 아니면 no-op )
    fun releaseHeldSeats(member: Member, scheduleId: Long, seatNumbers: List<String>) {
        seatRepository.releaseHeldSeats(member, scheduleId, seatNumbers)
    }

    // 좌석 해제 ( 취소 시 확정 좌석을 FREE 로 복귀 )
    fun releaseSeats(seats: List<Seat>) {
        seats.forEach { it.release() }
    }

    // 특정 공연 스케줄의 좌석 목록 조회 ( 만료된 홀드는 판매가능으로 노출 )
    fun getSeatList(performanceScheduleId: Long): List<SeatResponse> {
        val now = LocalDateTime.now()
        return seatRepository.findSeatByPerformanceScheduleId(performanceScheduleId)
            .map { SeatResponse.from(it, now) }
    }

    // 부분 선점 실패 원인 구분: 요청 좌석 중 없는 좌석이 있으면 NOT_EXIST_SEAT_INFO, 아니면 전달된 충돌 코드
    private fun throwClaimCause(scheduleId: Long, seatNumbers: List<String>, conflictCode: ErrorCode): Nothing {
        val existing = seatRepository.findExistingSeatNumbers(scheduleId, seatNumbers).toSet()
        val notFound = seatNumbers.filter { it !in existing }
        if (notFound.isNotEmpty()) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_SEAT_INFO)
        }
        throw ReserveException(HttpStatus.CONFLICT, conflictCode)
    }
}
