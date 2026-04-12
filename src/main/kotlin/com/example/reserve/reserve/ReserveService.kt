package com.example.reserve.reserve

import com.example.reserve.config.Loggable
import com.example.reserve.email.EmailService
import com.example.reserve.member.Member
import com.example.reserve.member.MemberService
import com.example.reserve.performanceSchedule.PerformanceScheduleService
import com.example.reserve.reserve.dto.PaymentResult
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserve.dto.ReserveResponse
import com.example.reserve.reserve.repository.ReserveRepository
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.Seat
import com.example.reserve.seat.SeatService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReserveService(
    private val reserveRepository: ReserveRepository,
    private val seatService: SeatService,
    private val performanceScheduleService: PerformanceScheduleService,
    private val memberService: MemberService,
    private val emailService: EmailService
) : Loggable {

    @Transactional
    fun reserve(reserveRequest: ReserveRequest, username: String): ReserveResponse {
        // 1. 예약하려는 공연 스케쥴 정보 조회
        val performanceSchedule = performanceScheduleService.getPerformanceSchedule(reserveRequest.performanceScheduleId)

        // 2. 예약하려는 좌석들의 유효성 검사 및 비관적 락 잡기 ( redis lock 만료 케이스 보안 )
        val seats: List<Seat> = seatService.getAvailableSeatsWithLock(
            reserveRequest.seatNumbers,
            reserveRequest.performanceScheduleId
        )

        // 3. 예약하려는 사용자 비관적 락 잡음
        val member = memberService.getMemberByUsernameWithLock(username)

        // 4. 결제 처리
        val (totalAmount, actualRewardDiscount, finalAmount) = processPayment(
                performanceSchedule.performance.price,
                reserveRequest.seatNumbers.size,
                reserveRequest.rewardDiscountAmount,
                member
        )

        // 5. 예약 생성 및 좌석 상태 변경 ( dirty-checking )
        val reserve = reserveRepository.save(
            Reserve(
                reservationNumber = reserveRequest.reservationNumber,
                totalAmount = totalAmount,
                rewardDiscountAmount = actualRewardDiscount,
                finalAmount = finalAmount,
                performanceScheduleId = reserveRequest.performanceScheduleId,
                member = member,
                status = ReserveStatus.RESERVED
            )
        )
        seatService.reserveSeats(seats, reserve)

        // 예약 확인 이메일 비동기 발송
        emailService.sendEmailAsync(reserve, member, reserveRequest.performanceScheduleId, reserveRequest.seatNumbers)

        return ReserveResponse.from(reserve, reserveRequest.seatNumbers)
    }

    // 결제 처리
    private fun processPayment(
        price: Long, seatCount: Int, requestedDiscount: Long, member: Member
    ): PaymentResult {
        val totalAmount = price * seatCount
        val rewardDiscount = minOf(requestedDiscount, totalAmount, member.reward)
        val finalAmount = totalAmount - rewardDiscount

        // 사용자의 금액 부족 시 예외
        if (finalAmount > member.credit) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_ENOUGH_CREDIT)
        }

        // 사용자의 크레딧 및 리워드 차감
        member.decreaseCreditAndReward(finalAmount, rewardDiscount)
        return PaymentResult(totalAmount, rewardDiscount, finalAmount)
    }

    // 예약 취소
    @Transactional
    fun cancelReserve(reserveNumber: String, username: String) {
        // 1. 에약 정보 조회 ( seat도 fetch join으로 함께 조회 )
        val reserve = reserveRepository.findByReservationNumberWithFetch(reserveNumber)
            ?: throw ReserveException(HttpStatus.NOT_FOUND, ErrorCode.NOT_EXIST_RESERVE_INFO)

        // 2. 예약 취소 + 좌석 상태 변경
        val seatNumbers = reserve.seatList.map { it.seatNumber }
        reserve.cancel()
        seatService.releaseSeats(reserve.seatList)

        // 2. 사용자 조회 시 비관적 락 잡고, 크레딧 및 리워드 환불
        val member = memberService.getMemberByUsernameWithLock(username)
        member.increaseCreditAndReward(reserve.finalAmount, reserve.rewardDiscountAmount)

        // 3. 취소 확인 이메일 발송
        emailService.sendEmailAsync(reserve, member, reserve.performanceScheduleId, seatNumbers)
    }

    // 사용자의 예약 내역 반환
    @Transactional
    fun getUserReservations(username: String): List<ReserveResponse> {
        return reserveRepository.findByMemberUsernameAndStatus(username, ReserveStatus.RESERVED)
            .map(ReserveResponse::from)
    }
}
