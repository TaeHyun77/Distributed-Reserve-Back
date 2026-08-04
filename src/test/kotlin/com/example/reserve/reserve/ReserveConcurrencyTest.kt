package com.example.reserve.reserve

import com.example.reserve.reserve.dto.HoldRequest
import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * 동시성 레이스 검증 (hold → confirm 2단계)
 * 모든 스레드를 start 래치로 동시에 출발시키고, 각 작업의 예외(또는 null=성공)를 수집해 검증
 * @Transactional 롤백을 쓰지 않으므로 각 스레드는 실제 커밋되고, 락/조건부 UPDATE가 실제로 동작
 */
class ReserveConcurrencyTest : IntegrationTestSupport() {

    @Autowired private lateinit var reserveService: ReserveService
    @Autowired private lateinit var reserveApplicationService: ReserveApplicationService

    private fun holdReq(scheduleId: Long, seats: List<String>) =
        HoldRequest(seatNumbers = seats, performanceScheduleId = scheduleId)

    private fun confirmReq(scheduleId: Long, seats: List<String>) = ReserveRequest(
        reservationNumber = UUID.randomUUID().toString(),
        seatNumbers = seats,
        performanceScheduleId = scheduleId,
    )

    @Test
    @DisplayName("같은 좌석 10명 동시 hold+confirm - 정확히 1건만 성공 (조건부 UPDATE)")
    fun `같은 좌석 동시 예약`() {
        val n = 10
        repeat(n) { saveMember("user$it", credit = 300_000) }
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))

        val errors = runConcurrently(n) { i ->
            reserveService.hold(holdReq(scheduleId, listOf("A1")), "user$i")
            reserveService.confirm(confirmReq(scheduleId, listOf("A1")), "user$i")
        }

        val successCount = errors.count { it == null }
        assertThat(successCount).isEqualTo(1)
        // 실패는 모두 홀드 경합 ( 다른 사용자가 이미 선점 )
        assertThat(errors.filterNotNull().map { (it as ReserveException).errorCode })
            .containsOnly(ErrorCode.SEAT_ALREADY_HELD)
            .hasSize(n - 1)
        // 좌석은 확정되고 예약은 1건만
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
        assertThat(reserveRepository.findAll().count { it.status == ReserveStatus.RESERVED }).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 회원 동시 confirm - 크레딧 한도 내에서만 성공, 초과 차감/음수 없음 (회원 비관적 락)")
    fun `같은 회원 동시 예약 - 크레딧 직렬화`() {
        val n = 10
        // 단가 10_000, 크레딧 25_000 -> 최대 2건만 가능
        saveMember("alice", credit = 25_000)
        val seats = (1..n).map { "S$it" }
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = seats)

        // 좌석이 서로 다르므로 홀드는 모두 성공, 확정만 크레딧으로 직렬화된다
        val errors = runConcurrently(n) { i ->
            reserveService.hold(holdReq(scheduleId, listOf(seats[i])), "alice")
            reserveService.confirm(confirmReq(scheduleId, listOf(seats[i])), "alice")
        }

        val successCount = errors.count { it == null }
        assertThat(successCount).isEqualTo(2)
        // 나머지는 잔액 부족 (lost update가 있었다면 더 많이 성공하거나 크레딧이 음수가 됨)
        assertThat(errors.filterNotNull().map { (it as ReserveException).errorCode })
            .containsOnly(ErrorCode.NOT_ENOUGH_CREDIT)
        assertThat(creditOf("alice")).isEqualTo(5_000)
        assertThat(reserveRepository.findAll().count { it.status == ReserveStatus.RESERVED }).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 예약 2건 동시 취소 - 1건만 성공, 환불 1회 (예약 비관적 락 + 상태 가드)")
    fun `같은 예약 동시 취소`() {
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        val rn = UUID.randomUUID().toString()
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")
        reserveService.confirm(
            ReserveRequest(reservationNumber = rn, seatNumbers = listOf("A1"), performanceScheduleId = scheduleId),
            "alice",
        )
        assertThat(creditOf("alice")).isEqualTo(290_000)

        val errors = runConcurrently(2) { reserveService.cancelReserve(rn, "alice") }

        val successCount = errors.count { it == null }
        assertThat(successCount).isEqualTo(1)
        assertThat(errors.filterNotNull().map { (it as ReserveException).errorCode })
            .containsOnly(ErrorCode.ALREADY_CANCELLED)
        // 환불은 정확히 1회 (이중 환불이면 310_000이 됨)
        assertThat(creditOf("alice")).isEqualTo(300_000)
        assertThat(isSeatReserved(scheduleId, "A1")).isFalse()
    }

    @Test
    @DisplayName("같은 멱등성 키 10건 동시 confirm - 예약은 1건만 생성 (unique INSERT 상호배제)")
    fun `같은 멱등성 키 동시 진입`() {
        val n = 10
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        reserveService.hold(holdReq(scheduleId, listOf("A1")), "alice")
        // 동일 요청 + 동일 키
        val req = ReserveRequest(
            reservationNumber = UUID.randomUUID().toString(),
            seatNumbers = listOf("A1"),
            performanceScheduleId = scheduleId,
        )
        val key = "concurrent-idem-key"

        runConcurrently(n) { reserveApplicationService.confirmSeat(req, "alice", key) }

        // 멱등성으로 실제 실행은 1번뿐 -> 예약 1건, 크레딧 1회 차감
        assertThat(reserveRepository.findAll().count { it.status == ReserveStatus.RESERVED }).isEqualTo(1)
        assertThat(creditOf("alice")).isEqualTo(290_000)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
    }
}
