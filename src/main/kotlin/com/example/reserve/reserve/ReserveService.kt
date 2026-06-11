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

        // 2. 예약하려는 사용자 비관적 락 잡음 ( credit/reward 등 사용자 자원에 대한 동시성 문제 방지 )
        val member = memberService.getMemberByUsernameWithLock(username)

        // 3. 결제 처리
        val (totalAmount, actualRewardDiscount, finalAmount) = processPayment(
                performanceSchedule.performance.price,
                reserveRequest.seatNumbers.size,
                reserveRequest.rewardDiscountAmount,
                member
        )

        // 4. 예약 생성 ( IDENTITY → 즉시 INSERT로 reserve_id 확보 )
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

        // 5. 좌석 선점 ( 조건부 UPDATE, 락 보유 구간 최소화를 위해 말미에 실행 )
        seatService.claimSeats(reserve, reserveRequest.performanceScheduleId, reserveRequest.seatNumbers)

        // 6. 예약 확인 이메일 발송 (커밋 후 비동기 발송)
        emailService.publishReservationEmail(reserve, member, performanceSchedule, reserveRequest.seatNumbers)

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
        // 1. 예약 정보 조회 + 비관적 락 ( 동시 취소 직렬화 → 이중 환불 방지 )
        val reserve = reserveRepository.findByReservationNumberWithLock(reserveNumber)
            ?: throw ReserveException(HttpStatus.NOT_FOUND, ErrorCode.NOT_EXIST_RESERVE_INFO)

        // 2. 본인 검증 ( 예약자 == 요청자 )
        if (reserve.member.username != username) {
            throw ReserveException(HttpStatus.FORBIDDEN, ErrorCode.UNAUTHORIZED_ACCESS)
        }

        // 3. 취소 가능 시점 검증 ( 공연 시작 이후 차단, 환불·멤버 락 전에 빠른 실패 )
        val performanceSchedule = performanceScheduleService.getPerformanceSchedule(reserve.performanceScheduleId)
        performanceSchedule.validateCancellable()

        // 4. 예약자(소유자) 비관적 락 후 크레딧 및 리워드 환불
        val member = memberService.getMemberByUsernameWithLock(reserve.member.username)
        member.increaseCreditAndReward(reserve.finalAmount, reserve.rewardDiscountAmount)

        // 5. 예약 취소 + 좌석 상태 변경 ( 이미 취소면 ALREADY_CANCELLED, 락 덕분에 원자적 )
        val seatNumbers = reserve.seatList.map { it.seatNumber }
        reserve.cancel()
        seatService.releaseSeats(reserve.seatList)

        // 6. 취소 확인 이메일 발송 (커밋 후 비동기 발송)
        emailService.publishReservationEmail(reserve, member, performanceSchedule, seatNumbers)
    }

    // 사용자의 예약 내역 반환
    @Transactional
    fun getUserReservations(username: String): List<ReserveResponse> {
        return reserveRepository.findByMemberUsernameAndStatus(username, ReserveStatus.RESERVED)
            .map(ReserveResponse::from)
    }
}
