package com.example.reserve.reserve

import com.example.reserve.reserve.dto.HoldRequest
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.seat.SeatService
import com.example.reserve.seat.SeatStatus
import com.example.reserve.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.util.UUID

/**
 * 좌석 임시 점유(2단계 예약) 동작 검증
 * - hold: FREE → HELD (조건부 UPDATE, 크레딧 미차감)
 * - confirm: HELD(내 것, 미만료) → RESERVED (크레딧 차감 + 예약 생성)
 * - release: HELD(내 것) → FREE
 * - 만료: heldUntil < now 인 HELD 는 판매가능으로 간주
 */
class ReserveHoldTest : IntegrationTestSupport() {

    @Autowired private lateinit var reserveService: ReserveService
    @Autowired private lateinit var seatService: SeatService

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

    // ---------- hold ----------
    @Test
    @DisplayName("hold 성공 - FREE 좌석이 HELD 로 전이하고 크레딧은 차감되지 않는다")
    fun `hold 성공`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1", "A2"))

        val res = reserveService.hold(holdReq(scheduleId, listOf("A1", "A2")), "alice")

        assertThat(res.heldUntil).isAfter(LocalDateTime.now())
        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.HELD)
        assertThat(seatStatusOf(scheduleId, "A2")).isEqualTo(SeatStatus.HELD)
        // 홀드는 결제가 아니므로 크레딧 불변, 확정 예약도 없음
        assertThat(creditOf("alice")).isEqualTo(300_000)
        assertThat(reserveRepository.findAll()).isEmpty()
    }

    @Test
    @DisplayName("hold 실패 - 이미 다른 사용자가 점유한 좌석은 SEAT_ALREADY_HELD")
    fun `이미 점유된 좌석 hold`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))

        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")

        val ex = assertThrows<ReserveException> {
            reserveService.hold(holdReq(scheduleId, listOf("A1")), "bob")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.SEAT_ALREADY_HELD)
    }

    @Test
    @DisplayName("hold 재요청(버튼 중복) - 본인 홀드는 성공하고 heldUntil 이 갱신된다")
    fun `본인 홀드 재요청`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))

        val first = reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")
        val second = reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")

        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.HELD)
        assertThat(second.heldUntil).isAfterOrEqualTo(first.heldUntil)
    }

    @Test
    @DisplayName("같은 좌석 10명 동시 hold - 정확히 1명만 성공 (조건부 UPDATE)")
    fun `같은 좌석 동시 hold`() {
        val n = 10
        repeat(n) { saveMember("user$it", credit = 300_000) }
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))

        val errors = runConcurrently(n) { i ->
            reserveService.hold(holdReq(scheduleId, listOf("A1")), "user$i")
        }

        assertThat(errors.count { it == null }).isEqualTo(1)
        assertThat(errors.filterNotNull().map { (it as ReserveException).errorCode })
            .containsOnly(ErrorCode.SEAT_ALREADY_HELD)
            .hasSize(n - 1)
        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.HELD)
    }

    // ---------- confirm ----------
    @Test
    @DisplayName("confirm 성공 - 내 HELD 좌석이 RESERVED 로 확정되고 크레딧이 차감된다")
    fun `confirm 성공`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1", "A2"))
        reserveService.hold(holdReq(scheduleId, listOf("A1", "A2")), "alice")

        reserveService.confirm(confirmReq(scheduleId, listOf("A1", "A2")), "alice")

        assertThat(creditOf("alice")).isEqualTo(280_000)
        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.RESERVED)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).hasSize(1)
    }

    @Test
    @DisplayName("confirm 실패 - 홀드가 만료되면 HOLD_EXPIRED_OR_NOT_OWNED 이고 크레딧은 원복된다")
    fun `만료된 홀드 confirm`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val member = memberRepository.findByUsername("alice")!!
        // 이미 만료된(과거) 홀드를 실제 holdSeats 로 생성
        txTemplate.execute {
            seatService.holdSeats(member, scheduleId, listOf("A1"), LocalDateTime.now().minusMinutes(1))
        }

        val ex = assertThrows<ReserveException> {
            reserveService.confirm(confirmReq(scheduleId, listOf("A1")), "alice")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.HOLD_EXPIRED_OR_NOT_OWNED)
        // 좌석 확정 실패 → 크레딧 롤백
        assertThat(creditOf("alice")).isEqualTo(300_000)
        assertThat(reserveRepository.findByMemberUsernameAndStatus("alice", ReserveStatus.RESERVED)).isEmpty()
    }

    @Test
    @DisplayName("confirm 실패 - 남이 홀드한 좌석은 confirm 할 수 없다")
    fun `남의 홀드 confirm`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")

        val ex = assertThrows<ReserveException> {
            reserveService.confirm(confirmReq(scheduleId, listOf("A1")), "bob")
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.HOLD_EXPIRED_OR_NOT_OWNED)
        assertThat(creditOf("bob")).isEqualTo(300_000)
    }

    // ---------- release ----------
    @Test
    @DisplayName("release - 내 홀드를 즉시 해제하면 다른 사용자가 바로 hold 할 수 있다")
    fun `release 후 재점유`() {
        saveMember("alice", credit = 300_000)
        saveMember("bob", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")

        reserveService.release(holdReq(scheduleId, listOf("A1")), "alice")
        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.FREE)

        reserveService.hold(holdReq(scheduleId, listOf("A1")), "bob")
        assertThat(seatStatusOf(scheduleId, "A1")).isEqualTo(SeatStatus.HELD)
    }

    // ---------- 읽기 경로(만료 반영) ----------
    @Test
    @DisplayName("좌석맵 - 유효 홀드는 선택 불가, 만료된 홀드는 선택 가능으로 노출된다")
    fun `좌석맵 만료 반영`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1", "A2"))
        val member = memberRepository.findByUsername("alice")!!
        // A1: 유효 홀드, A2: 만료 홀드
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")
        txTemplate.execute {
            seatService.holdSeats(member, scheduleId, listOf("A2"), LocalDateTime.now().minusMinutes(1))
        }

        val seatMap = seatService.getSeatList(scheduleId).associateBy { it.seatNumber }
        assertThat(seatMap.getValue("A1").isReserved).isTrue()   // 유효 홀드 → 선택 불가
        assertThat(seatMap.getValue("A2").isReserved).isFalse()  // 만료 홀드 → 선택 가능
    }
}
