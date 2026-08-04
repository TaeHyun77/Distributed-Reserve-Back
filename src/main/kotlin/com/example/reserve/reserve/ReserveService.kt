package com.example.reserve.reserve

import com.example.reserve.config.Loggable
import com.example.reserve.email.EmailService
import com.example.reserve.member.Member
import com.example.reserve.member.MemberService
import com.example.reserve.performanceSchedule.PerformanceScheduleService
import com.example.reserve.reserve.dto.HoldRequest
import com.example.reserve.reserve.dto.HoldResponse
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
import java.time.LocalDateTime

@Service
class ReserveService(
    private val reserveRepository: ReserveRepository,
    private val seatService: SeatService,
    private val performanceScheduleService: PerformanceScheduleService,
    private val memberService: MemberService,
    private val emailService: EmailService
) : Loggable {

    companion object {
        // 좌석 홀드 유효시간(분) — 결제창 이동 순간부터 이 시간 안에 confirm 해야 한다
        const val HOLD_TTL_MINUTES = 5L
    }

    // 1단계: 좌석 홀드 ( 결제창 이동 시점 ). 크레딧 차감 없음, 회원 락 없음.
    @Transactional
    fun hold(request: HoldRequest, username: String): HoldResponse {
        val member = memberService.getMemberByUsername(username)
        val heldUntil = LocalDateTime.now().plusMinutes(HOLD_TTL_MINUTES)

        seatService.holdSeats(member, request.performanceScheduleId, request.seatNumbers, heldUntil)

        return HoldResponse(request.seatNumbers, heldUntil)
    }

    // 2단계: 결제 확정 ( 결제창 최종 결제 시점 ). 결제 후 내 유효 홀드를 RESERVED 로 확정.
    @Transactional
    fun confirm(request: ReserveRequest, username: String): ReserveResponse {
        // 1. 공연 스케줄 정보 조회
        val performanceSchedule = performanceScheduleService.getPerformanceSchedule(request.performanceScheduleId)

        // 2. 결제 대상 사용자 비관적 락 ( credit/reward 동시성 방지 )
        val member = memberService.getMemberByUsernameWithLock(username)

        // 3. 결제 처리
        val (totalAmount, actualRewardDiscount, finalAmount) = processPayment(
            performanceSchedule.performance.price,
            request.seatNumbers.size,
            request.rewardDiscountAmount,
            member
        )

        // 4. 예약 생성 ( IDENTITY → 즉시 INSERT로 reserve_id 확보 )
        val reserve = reserveRepository.save(
            Reserve(
                reservationNumber = request.reservationNumber,
                totalAmount = totalAmount,
                rewardDiscountAmount = actualRewardDiscount,
                finalAmount = finalAmount,
                performanceScheduleId = request.performanceScheduleId,
                member = member,
                status = ReserveStatus.RESERVED
            )
        )

        // 5. 좌석 확정 ( 내 유효 홀드만 원자적 RESERVED — 만료/탈취 시 예외 → 결제 롤백 )
        seatService.confirmSeats(reserve, member, request.performanceScheduleId, request.seatNumbers)

        // 6. 예약 확인 이메일 발송 (커밋 후 비동기 발송)
        emailService.publishReservationEmail(reserve, member, performanceSchedule, request.seatNumbers)

        return ReserveResponse.from(reserve, request.seatNumbers)
    }

    // 홀드 즉시 해제 ( 결제창 이탈·취소 시점 ). 내 홀드만 FREE 로 되돌림.
    @Transactional
    fun release(request: HoldRequest, username: String) {
        val member = memberService.getMemberByUsername(username)
        seatService.releaseHeldSeats(member, request.performanceScheduleId, request.seatNumbers)
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
