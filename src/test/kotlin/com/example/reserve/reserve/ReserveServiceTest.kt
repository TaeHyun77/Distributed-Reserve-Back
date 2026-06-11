package com.example.reserve.reserve

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
 * 예약/취소 비즈니스 로직 단일스레드 시나리오 검증
 * 비즈니스 예외는 reserveService를 직접 호출해 ReserveException으로 확인하고, 멱등성 동작은 reserveApplicationService(ResponseEntity)로 확인
 */
class ReserveServiceTest : IntegrationTestSupport() {

    @Autowired private lateinit var reserveService: ReserveService
    @Autowired private lateinit var reserveApplicationService: ReserveApplicationService

    private fun request(
        scheduleId: Long,
        seatNumbers: List<String>,
        rewardDiscountAmount: Long = 0,
        reservationNumber: String = UUID.randomUUID().toString(),
    ) = ReserveRequest(
        reservationNumber = reservationNumber,
        rewardDiscountAmount = rewardDiscountAmount,
        seatNumbers = seatNumbers,
        performanceScheduleId = scheduleId,
    )

    // ---------- 예약 ----------
    @Test
    @DisplayName("예약 성공 - 좌석 점유, 크레딧 차감, RESERVED 예약 생성")
    fun `예약 성공`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1", "A2"))

        reserveService.reserve(request(scheduleId, listOf("A1", "A2")), "alice")

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

        reserveService.reserve(request(scheduleId, listOf("B1"), rewardDiscountAmount = 5_000), "bob")

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
        reserveService.reserve(request(scheduleId, listOf("C1"), rewardDiscountAmount = 999_999), "carol")

        assertThat(rewardOf("carol")).isEqualTo(0)
        assertThat(creditOf("carol")).isEqualTo(293_000) // final = 10_000 - 3_000 = 7_000 차감
    }

    @Test
    @DisplayName("예약 실패 - 잔액 부족 시 NOT_ENOUGH_CREDIT, 전체 롤백")
    fun `잔액 부족`() {
        saveMember("poor", credit = 5_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("D1"))

        val ex = assertThrows<ReserveException> {
            reserveService.reserve(request(scheduleId, listOf("D1")), "poor")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.NOT_ENOUGH_CREDIT)

        // 롤백 확인: 크레딧 그대로, 좌석 미점유, 예약 없음
        assertThat(creditOf("poor")).isEqualTo(5_000)
        assertThat(isSeatReserved(scheduleId, "D1")).isFalse()
        assertThat(reserveRepository.findByMemberUsernameAndStatus("poor", ReserveStatus.RESERVED)).isEmpty()
    }

    @Test
    @DisplayName("예약 실패 - 이미 예약된 좌석은 SEAT_ALREADY_RESERVED")
    fun `이미 예약된 좌석`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("E1"))

        reserveService.reserve(request(scheduleId, listOf("E1")), "alice")

        val ex = assertThrows<ReserveException> {
            reserveService.reserve(request(scheduleId, listOf("E1")), "bob")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED)

        assertThat(creditOf("bob")).isEqualTo(300_000) // 실패자 크레딧 미차감
        assertThat(reserveRepository.findByMemberUsernameAndStatus("bob", ReserveStatus.RESERVED)).isEmpty()
    }

    @Test
    @DisplayName("예약 실패 - 존재하지 않는 좌석은 NOT_EXIST_SEAT_INFO")
    fun `존재하지 않는 좌석`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("F1"))

        val ex = assertThrows<ReserveException> {
            reserveService.reserve(request(scheduleId, listOf("Z9")), "alice")
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
        reserveService.reserve(request(scheduleId, listOf("A1"), rewardDiscountAmount = 5_000, reservationNumber = rn), "alice")
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
        reserveService.reserve(request(scheduleId, listOf("A1"), reservationNumber = rn), "alice")

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
        reserveService.reserve(request(scheduleId, listOf("A1"), reservationNumber = rn), "alice")

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
        // 예약 자체는 시작시간을 검증하지 않으므로 성공
        reserveService.reserve(request(scheduleId, listOf("A1"), reservationNumber = rn), "alice")
        assertThat(creditOf("alice")).isEqualTo(290_000)

        val ex = assertThrows<ReserveException> { reserveService.cancelReserve(rn, "alice") }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED_AFTER_START)

        // 롤백 확인: 환불 안 됨, 예약 유지, 좌석 점유 유지
        assertThat(creditOf("alice")).isEqualTo(290_000)
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
    }

    // ---------- 멱등성 ----------
    @Test
    @DisplayName("멱등성 - 같은 키 재요청 시 예약은 1건만 생성되고 동일 응답 반환")
    fun `멱등성 키 재요청`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val req = request(scheduleId, listOf("A1"))
        val key = "idem-key-1"

        val first = reserveApplicationService.reserveSeat(req, "alice", key)
        val second = reserveApplicationService.reserveSeat(req, "alice", key)

        assertThat(first.statusCode.value()).isEqualTo(200)
        assertThat(second.statusCode.value()).isEqualTo(200)
        assertThat(second.body).isEqualTo(first.body) // 캐시된 동일 응답

        // 예약은 한 번만, 크레딧도 한 번만 차감
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
        assertThat(creditOf("alice")).isEqualTo(290_000)
    }
}
