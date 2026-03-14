package com.example.reserve.reserve

import com.example.reserve.config.Loggable
import com.example.reserve.member.Member
import com.example.reserve.member.MemberService
import com.example.reserve.performanceSchedule.PerformanceScheduleService
import com.example.reserve.reserve.dto.Refund
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserve.dto.ReserveResponse
import com.example.reserve.reserve.repository.ReserveRepository
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.SeatService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReserveService(
    private val reserveRepository: ReserveRepository,
    private val seatService: SeatService,
    private val performanceScheduleService: PerformanceScheduleService,
    private val memberService: MemberService
) : Loggable {

    @Transactional
    fun reserve(
        reserveRequest: ReserveRequest,
        username: String
    ): ReserveResponse {

        // Redis 락 키는 좌석 단위이므로, 동일 사용자가 서로 다른 좌석을 동시에 예약하면 두 요청이 병렬로 실행될 수 있습니다.
        // 이 경우 credit을 각자 읽어 중복 차감되는 문제가 발생하므로, 비관적 락으로 멤버 row를 선점하여 크레딧 정합성을 보장했습니다.
        val member = memberService.getMemberByUsernameWithLock(username)

        // performanceSchedule 조회
        val performanceSchedule = performanceScheduleService.getPerformanceSchedule(reserveRequest.performanceScheduleId)

        // 예약하려는 좌석들의 유효성 검사 및 조회
        val seats = seatService.getAvailableSeats(reserveRequest.reservedSeat, reserveRequest.performanceScheduleId)

        // 예약 비용 지불
        val (totalAmount, actualRewardDiscount, finalAmount) = calculatePayment(
            performanceSchedule.performance.price,
            reserveRequest.reservedSeat.size,
            reserveRequest.rewardDiscountAmount,
            member
        )

        if (!isPayable(finalAmount, member.credit)) { // 결제 불가
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_ENOUGH_CREDIT)
        }

        member.decreaseCreditAndReward(finalAmount, actualRewardDiscount)

        val reserve = Reserve(
            reservationNumber = reserveRequest.reservationNumber,
            totalAmount = totalAmount,
            rewardDiscountAmount = actualRewardDiscount,
            finalAmount = finalAmount,
            performanceScheduleId = performanceSchedule.id!!,
            member = member
        )

        val savedReserve = reserveRepository.saveAndFlush(reserve)
        seatService.reserveSeats(seats, savedReserve)

        return ReserveResponse.from(savedReserve, seats.map { it.seatNumber })
    }

    // 예약 비용 지불
    private fun calculatePayment(price: Long, seatCount: Int, requestedDiscount: Long, member: Member): Triple<Long, Long, Long> {
        val totalAmount = price * seatCount
        val discountAmount = minOf(requestedDiscount, totalAmount, member.reward)

        return Triple(totalAmount, discountAmount, totalAmount - discountAmount)
    }

    // 예약 취소
    @Transactional
    fun cancel(reserveNumber: String): Refund {

        val reserve = reserveRepository.findByReservationNumber(reserveNumber)
            ?: throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_EXIST_RESERVE_INFO)

        val member = reserve.member
        member.increaseCreditAndReward(reserve.finalAmount, reserve.rewardDiscountAmount)

        seatService.releaseSeats(reserve.seatList)
        reserveRepository.delete(reserve)

        return Refund(
            member.username,
            reserve.finalAmount,
            reserve.rewardDiscountAmount
        )
    }

    // 사용자의 예약 내역 반환
    @Transactional(readOnly = true)
    fun getUserReservations(username: String): List<ReserveResponse> {
        return reserveRepository.findByMemberUsername(username)
            .map(ReserveResponse::from)
    }

    // 결제 가능 여부 파악
    private fun isPayable(
        paymentAmount: Long,
        currentBalance: Long
    ): Boolean {
        return paymentAmount <= currentBalance
    }
}
