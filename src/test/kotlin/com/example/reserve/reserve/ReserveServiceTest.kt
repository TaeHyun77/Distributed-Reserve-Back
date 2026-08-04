package com.example.reserve.reserve

import com.example.reserve.reserve.dto.HoldRequest
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.util.UUID

/**
 * 예약/취소 비즈니스 로직 단일스레드 시나리오 검증 (hold → confirm 2단계)
 * 비즈니스 예외는 reserveService를 직접 호출해 ReserveException으로 확인하고, 멱등성 동작은 reserveApplicationService(ResponseEntity)로 확인
 */
class ReserveServiceTest : IntegrationTestSupport() {

    @Autowired private lateinit var reserveService: ReserveService
    @Autowired private lateinit var reserveApplicationService: ReserveApplicationService

    private fun holdReq(scheduleId: Long, seats: List<String>) =
        HoldRequest(seatNumbers = seats, performanceScheduleId = scheduleId)

    private fun confirmReq(
        scheduleId: Long,
        seats: List<String>,
        rewardDiscountAmount: Long = 0,
        reservationNumber: String = UUID.randomUUID().toString(),
    ) = ReserveRequest(
        reservationNumber = reservationNumber,
        rewardDiscountAmount = rewardDiscountAmount,
        seatNumbers = seats,
        performanceScheduleId = scheduleId,
    )

    // 홀드 후 확정 ( 예약 성공 시나리오 셋업 )
    private fun holdAndConfirm(
        scheduleId: Long,
        seats: List<String>,
        username: String,
        rewardDiscountAmount: Long = 0,
        reservationNumber: String = UUID.randomUUID().toString(),
    ) {
        reserveService.hold(holdReq(scheduleId, seats), username)
        reserveService.confirm(confirmReq(scheduleId, seats, rewardDiscountAmount, reservationNumber), username)
    }

    // ---------- 예약(확정) ----------
    @Test
    @DisplayName("예약 성공 - 좌석 확정, 크레딧 차감, RESERVED 예약 생성")
    fun `예약 성공`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1", "A2"))

        holdAndConfirm(scheduleId, listOf("A1", "A2"), "alice")

        assertThat(creditOf("alice")).isEqualTo(280_000)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
        assertThat(isSeatReserved(scheduleId, "A2")).isTrue()

        val reserves = reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)
        assertThat(reserves).hasSize(1)
        assertThat(reserves[0].totalAmount).isEqualTo(20_000)
        assertThat(reserves[0].finalAmount).isEqualTo(20_000)
        assertThat(reserves[0].rewardDiscountAmount).isEqualTo(0)
    }

    @Test
    @DisplayName("예약 성공 - 리워드 할인 적용 시 크레딧/리워드 모두 차감")
    fun `리워드 할인 적용`() {
        saveMember("bob", credit = 300_000, reward = 5_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("B1"))

        holdAndConfirm(scheduleId, listOf("B1"), "bob", rewardDiscountAmount = 5_000)

        // total=10_000, rewardDiscount=min(5_000,10_000,5_000)=5_000, final=5_000
        assertThat(creditOf("bob")).isEqualTo(295_000)
        assertThat(rewardOf("bob")).isEqualTo(0)

        val reserve = reserveRepository.findByMemberUsernameAndStatus("bob", ReserveStatus.RESERVED).single()
        assertThat(reserve.rewardDiscountAmount).isEqualTo(5_000)
        assertThat(reserve.finalAmount).isEqualTo(5_000)
    }

    @Test
    @DisplayName("예약 성공 - 요청 리워드가 보유분을 초과하면 보유분까지만 사용")
    fun `리워드 할인 캡 적용`() {
        saveMember("carol", credit = 300_000, reward = 3_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("C1"))

        // 999_999 요청해도 보유 3_000까지만
        holdAndConfirm(scheduleId, listOf("C1"), "carol", rewardDiscountAmount = 999_999)

        assertThat(rewardOf("carol")).isEqualTo(0)
        assertThat(creditOf("carol")).isEqualTo(293_000) // final = 10_000 - 3_000 = 7_000 차감
    }

    @Test
    @DisplayName("확정 실패 - 잔액 부족 시 NOT_ENOUGH_CREDIT, 결제 롤백")
    fun `잔액 부족`() {
        saveMember("poor", credit = 5_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("D1"))

        reserveService.hold(holdReq(scheduleId, listOf("D1")), "poor")
        val ex = assertThrows<ReserveException> {
            reserveService.confirm(confirmReq(scheduleId, listOf("D1")), "poor")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.NOT_ENOUGH_CREDIT)

        // 롤백 확인: 크레딧 그대로, 확정 안 됨, 예약 없음
        assertThat(creditOf("poor")).isEqualTo(5_000)
        assertThat(isSeatReserved(scheduleId, "D1")).isFalse()
        assertThat(reserveRepository.findByMemberUsernameAndStatus("poor", ReserveStatus.RESERVED)).isEmpty()
    }

    @Test
    @DisplayName("hold 실패 - 이미 확정된 좌석은 SEAT_ALREADY_HELD")
    fun `이미 확정된 좌석`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("E1"))

        holdAndConfirm(scheduleId, listOf("E1"), "alice")

        val ex = assertThrows<ReserveException> {
            reserveService.hold(holdReq(scheduleId, listOf("E1")), "bob")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.SEAT_ALREADY_HELD)

        assertThat(creditOf("bob")).isEqualTo(300_000) // 실패자 크레딧 미차감
        assertThat(reserveRepository.findByMemberUsernameAndStatus("bob", ReserveStatus.RESERVED)).isEmpty()
    }

    @Test
    @DisplayName("hold 실패 - 존재하지 않는 좌석은 NOT_EXIST_SEAT_INFO")
    fun `존재하지 않는 좌석`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("F1"))

        val ex = assertThrows<ReserveException> {
            reserveService.hold(holdReq(scheduleId, listOf("Z9")), "alice")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.NOT_EXIST_SEAT_INFO)
        assertThat(creditOf("alice")).isEqualTo(300_000)
    }

    // ---------- 취소 ----------
    @Test
    @DisplayName("취소 성공 - 상태 CANCELLED, 좌석 release, 크레딧/리워드 환불")
    fun `취소 성공`() {
        saveMember("alice", credit = 300_000, reward = 5_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val rn = UUID.randomUUID().toString()
        holdAndConfirm(scheduleId, listOf("A1"), "alice", rewardDiscountAmount = 5_000, reservationNumber = rn)
        // 예약 후: credit 295_000, reward 0
        assertThat(creditOf("alice")).isEqualTo(295_000)

        reserveService.cancelReserve(rn, "alice")

        // 환불 확인
        assertThat(creditOf("alice")).isEqualTo(300_000)
        assertThat(rewardOf("alice")).isEqualTo(5_000)
        // 좌석 해제
        assertThat(isSeatReserved(scheduleId, "A1")).isFalse()
        // 상태 CANCELLED + cancelledAt
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).isEmpty()
        val cancelled = reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.CANCELLED).single()
        assertThat(cancelled.cancelledAt).isNotNull()
    }

    @Test
    @DisplayName("취소 실패 - 이미 취소된 예약은 ALREADY_CANCELLED, 이중 환불 없음")
    fun `중복 취소`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val rn = UUID.randomUUID().toString()
        holdAndConfirm(scheduleId, listOf("A1"), "alice", reservationNumber = rn)

        reserveService.cancelReserve(rn, "alice")
        assertThat(creditOf("alice")).isEqualTo(300_000)

        val ex = assertThrows<ReserveException> { reserveService.cancelReserve(rn, "alice") }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.ALREADY_CANCELLED)
        // 이중 환불 없음
        assertThat(creditOf("alice")).isEqualTo(300_000)
    }

    @Test
    @DisplayName("취소 실패 - 타인 예약 취소 시 UNAUTHORIZED_ACCESS")
    fun `타인 예약 취소`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val rn = UUID.randomUUID().toString()
        holdAndConfirm(scheduleId, listOf("A1"), "alice", reservationNumber = rn)

        val ex = assertThrows<ReserveException> { reserveService.cancelReserve(rn, "bob") }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED_ACCESS)

        // 예약 유지, alice 크레딧 미환불
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
        assertThat(creditOf("alice")).isEqualTo(290_000)
    }

    @Test
    @DisplayName("취소 실패 - 존재하지 않는 예약번호는 NOT_EXIST_RESERVE_INFO")
    fun `존재하지 않는 예약 취소`() {
        saveMember("alice", credit = 300_000)

        val ex = assertThrows<ReserveException> { reserveService.cancelReserve("no-such-number", "alice") }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.NOT_EXIST_RESERVE_INFO)
    }

    @Test
    @DisplayName("취소 실패 - 공연 시작 이후에는 CANCEL_NOT_ALLOWED_AFTER_START, 환불 없음")
    fun `공연 시작 후 취소 차단`() {
        saveMember("alice", credit = 300_000)
        // 이미 시작한(과거) 공연
        val scheduleId = saveScheduleWithSeats(
            price = 10_000,
            seatNumbers = listOf("A1"),
            startTime = LocalDateTime.now().minusDays(1),
        )
        val rn = UUID.randomUUID().toString()
        // 예약(확정) 자체는 시작시간을 검증하지 않으므로 성공
        holdAndConfirm(scheduleId, listOf("A1"), "alice", reservationNumber = rn)
        assertThat(creditOf("alice")).isEqualTo(290_000)

        val ex = assertThrows<ReserveException> { reserveService.cancelReserve(rn, "alice") }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED_AFTER_START)

        // 롤백 확인: 환불 안 됨, 예약 유지, 좌석 확정 유지
        assertThat(creditOf("alice")).isEqualTo(290_000)
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
    }

    // ---------- 멱등성 ----------
    @Test
    @DisplayName("멱등성 - 같은 키로 confirm 재요청 시 예약은 1건만 생성되고 동일 응답 반환")
    fun `멱등성 키 재요청`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")
        val req = confirmReq(scheduleId, listOf("A1"))
        val key = "idem-key-1"

        val first = reserveApplicationService.confirmSeat(req, "alice", key)
        val second = reserveApplicationService.confirmSeat(req, "alice", key)

        assertThat(first.statusCode.value()).isEqualTo(200)
        assertThat(second.statusCode.value()).isEqualTo(200)
        assertThat(second.body).isEqualTo(first.body) // 캐시된 동일 응답

        // 예약은 한 번만, 크레딧도 한 번만 차감
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
        assertThat(creditOf("alice")).isEqualTo(290_000)
    }
}
