package com.example.reserve.reserve

import com.example.reserve.reserve.dto.ReserveRequest
import com.example.reserve.reserveException.ErrorCode
import com.example.reserve.reserveException.ReserveException
import com.example.reserve.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 동시성 레이스 검증
 * 모든 스레드를 start 래치로 동시에 출발시키고, 각 작업의 예외(또는 null=성공)를 수집해 검증
 * @Transactional 롤백을 쓰지 않으므로 각 스레드는 실제 커밋되고, 락/조건부 UPDATE가 실제로 동작
 */
class ReserveConcurrencyTest : IntegrationTestSupport() {

    @Autowired private lateinit var reserveService: ReserveService
    @Autowired private lateinit var reserveApplicationService: ReserveApplicationService

    @Test
    @DisplayName("같은 좌석 10명 동시 예약 - 정확히 1건만 성공 (조건부 UPDATE)")
    fun `같은 좌석 동시 예약`() {
        val n = 10
        repeat(n) { saveMember("user$it", credit = 300_000) }
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))

        val errors = runConcurrently(n) { i ->
            reserveService.reserve(
                ReserveRequest(
                    reservationNumber = UUID.randomUUID().toString(),
                    seatNumbers = listOf("A1"),
                    performanceScheduleId = scheduleId,
                ),
                "user$i",
            )
        }

        val successCount = errors.count { it == null }
        assertThat(successCount).isEqualTo(1)
        // 실패는 모두 좌석 선점 충돌
        assertThat(errors.filterNotNull().map { (it as ReserveException).errorCode })
            .containsOnly(ErrorCode.SEAT_ALREADY_RESERVED)
            .hasSize(n - 1)
        // 좌석은 점유되고 예약은 1건만
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
        assertThat(reserveRepository.findAll().count { it.status == ReserveStatus.RESERVED }).isEqualTo(1)
    }

    @Test
    @DisplayName("같은 회원 동시 예약 - 크레딧 한도 내에서만 성공, 초과 차감/음수 없음 (회원 비관적 락)")
    fun `같은 회원 동시 예약 - 크레딧 직렬화`() {
        val n = 10
        // 단가 10_000, 크레딧 25_000 -> 최대 2건만 가능
        saveMember("alice", credit = 25_000)
        val seats = (1..n).map { "S$it" }
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = seats)

        val errors = runConcurrently(n) { i ->
            reserveService.reserve(
                ReserveRequest(
                    reservationNumber = UUID.randomUUID().toString(),
                    seatNumbers = listOf(seats[i]),
                    performanceScheduleId = scheduleId,
                ),
                "alice",
            )
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
        reserveService.reserve(
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
    @DisplayName("같은 멱등성 키 10건 동시 진입 - 예약은 1건만 생성 (unique INSERT 상호배제)")
    fun `같은 멱등성 키 동시 진입`() {
        val n = 10
        saveMember("alice", credit = 300_000)
        val scheduleId = saveScheduleWithSeats(price = 10_000, seatNumbers = listOf("A1"))
        // 동일 요청 + 동일 키
        val req = ReserveRequest(
            reservationNumber = UUID.randomUUID().toString(),
            seatNumbers = listOf("A1"),
            performanceScheduleId = scheduleId,
        )
        val key = "concurrent-idem-key"

        runConcurrently(n) { reserveApplicationService.reserveSeat(req, "alice", key) }

        // 멱등성으로 실제 실행은 1번뿐 -> 예약 1건, 크레딧 1회 차감
        assertThat(reserveRepository.findAll().count { it.status == ReserveStatus.RESERVED }).isEqualTo(1)
        assertThat(creditOf("alice")).isEqualTo(290_000)
        assertThat(isSeatReserved(scheduleId, "A1")).isTrue()
    }

    /**
     * count개의 작업을 동시에 출발시켜 실행하고, 인덱스별 throwable(성공 시 null)을 반환한다.
     */
    private fun runConcurrently(count: Int, block: (Int) -> Unit): List<Throwable?> {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val done = CountDownLatch(count)
        val errors = arrayOfNulls<Throwable>(count)
        try {
            repeat(count) { i ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        block(i)
                    } catch (t: Throwable) {
                        errors[i] = t
                    } finally {
                        done.countDown()
                    }
                }
            }
            ready.await()
            start.countDown() // 동시 출발
            check(done.await(60, TimeUnit.SECONDS)) { "동시 작업이 제한 시간 내에 끝나지 않음" }
        } finally {
            executor.shutdownNow()
        }
        return errors.toList()
    }
}
