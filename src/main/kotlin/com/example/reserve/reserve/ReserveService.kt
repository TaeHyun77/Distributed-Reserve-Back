package com.example.reserve.reserve

import com.example.reserve.config.Loggable
import com.example.reserve.email.EmailService
import com.example.reserve.email.dto.ReservationEmailData
import com.example.reserve.member.Member
import com.example.reserve.member.MemberService
import com.example.reserve.performanceSchedule.PerformanceScheduleService
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
    private val memberService: MemberService,
    private val emailService: EmailService
) : Loggable {

    @Transactional
    fun reserve(reserveRequest: ReserveRequest, username: String): ReserveResponse {
        // 1. 예약하려는 사용자 비관적 락 잡음
        val member = memberService.getMemberByUsernameWithLock(username)

        // 2. 예약하려는 공연 스케쥴 조회
        val performanceSchedule = performanceScheduleService.getPerformanceSchedule(reserveRequest.performanceScheduleId)

        // 3. 예약하려는 좌석들의 유효성 검사 및 비관적 락 잡기
        val seats = seatService.getAvailableSeatsWithLock(
            reserveRequest.reservedSeat,
            reserveRequest.performanceScheduleId
        )

        // 4. 결제 처리
        val (totalAmount, actualRewardDiscount, finalAmount) = processPayment(
                performanceSchedule.performance.price,
                reserveRequest.reservedSeat.size,
                reserveRequest.rewardDiscountAmount,
                member
        )

        // 5. 예약 생성 및 좌석 상태 변경
        val savedReserve = createReserve(reserveRequest, totalAmount, actualRewardDiscount, finalAmount, performanceSchedule.id!!, member)
        seatService.reserveSeats(seats, savedReserve)

        // 예약 확인 이메일 비동기 발송
        emailService.sendEmailAsync(savedReserve, member, reserveRequest.performanceScheduleId, seats.map { it.seatNumber })

        return ReserveResponse.from(savedReserve, seats.map { it.seatNumber })
    }

    private fun processPayment(price: Long, seatCount: Int, requestedDiscount: Long, member: Member): Triple<Long, Long, Long> {
        val (totalAmount, actualRewardDiscount, finalAmount) = calculatePayment(price, seatCount, requestedDiscount, member)

        if (!isPayable(finalAmount, member.credit)) {
            throw ReserveException(HttpStatus.BAD_REQUEST, ErrorCode.NOT_ENOUGH_CREDIT)
        }

        member.decreaseCreditAndReward(finalAmount, actualRewardDiscount)
        return Triple(totalAmount, actualRewardDiscount, finalAmount)
    }

    private fun createReserve(
            reserveRequest: ReserveRequest,
            totalAmount: Long,
            actualRewardDiscount: Long,
            finalAmount: Long,
            performanceScheduleId: Long,
            member: Member
    ): Reserve {
        val reserve = Reserve(
                reservationNumber = reserveRequest.reservationNumber,
                totalAmount = totalAmount,
                rewardDiscountAmount = actualRewardDiscount,
                finalAmount = finalAmount,
                performanceScheduleId = performanceScheduleId,
                member = member,
                status = ReserveStatus.RESERVED
        )
        return reserveRepository.save(reserve)
    }

    // 예약 비용 지불
    private fun calculatePayment(price: Long, seatCount: Int, requestedDiscount: Long, member: Member): Triple<Long, Long, Long> {
        val totalAmount = price * seatCount
        val discountAmount = minOf(requestedDiscount, totalAmount, member.reward)

        return Triple(totalAmount, discountAmount, totalAmount - discountAmount)
    }

    // 예약 취소
    @Transactional
    fun cancelReserve(reserveNumber: String, username: String) {
        val reserve = reserveRepository.findByReservationNumberWithFetch(reserveNumber)
            ?: throw ReserveException(HttpStatus.NOT_FOUND, ErrorCode.NOT_EXIST_RESERVE_INFO)

        // 1. 예약 취소 + 좌석 상태 변경
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

    // 결제 가능 여부 파악
    private fun isPayable(
        paymentAmount: Long,
        currentBalance: Long
    ): Boolean {
        return paymentAmount <= currentBalance
    }
}
