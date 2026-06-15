package com.example.reserve.support

import com.example.reserve.idempotency.IdempotencyRepository
import com.example.reserve.member.Member
import com.example.reserve.member.MemberRepository
import com.example.reserve.member.Role
import com.example.reserve.performance.Performance
import com.example.reserve.performance.repository.PerformanceRepository
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.reserve.reserve.repository.ReserveRepository
import com.example.reserve.seat.Seat
import com.example.reserve.seat.repository.SeatRepository
import com.example.reserve.venue.Venue
import com.example.reserve.venue.VenueRepository
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.LocalDateTime
import java.util.Properties

/**
 * 통합 테스트 베이스
 *
 * - 실제 MySQL 컨테이너로 비관적 락/조건부 UPDATE/멱등성을 충실히 검증
 * - 동시성 테스트는 스레드별 실제 커밋이 필요하므로 @Transactional 롤백을 쓰지 않고, 매 테스트 전 수동으로 전체 삭제 진행
 * - JavaMailSender는 mock으로 대체해 실제 SMTP 연결을 차단
 */
// actuator(부하 테스트용 의존성) 의 mail HealthContributor 가 테스트의 mock JavaMailSender 와 충돌해
// 컨텍스트 로딩이 실패하므로, 테스트에서만 mail health 체크를 끈다 ( 운영/loadtest 는 실제 sender 라 무영향 ).
@SpringBootTest(properties = ["management.health.mail.enabled=false"])
abstract class IntegrationTestSupport {

    companion object {
        @JvmStatic
        @ServiceConnection
        val mysql: MySQLContainer<*> = MySQLContainer(DockerImageName.parse("mysql:8.0")).apply { start() }
    }

    @Autowired protected lateinit var venueRepository: VenueRepository
    @Autowired protected lateinit var performanceRepository: PerformanceRepository
    @Autowired protected lateinit var performanceScheduleRepository: PerformanceScheduleRepository
    @Autowired protected lateinit var seatRepository: SeatRepository
    @Autowired protected lateinit var memberRepository: MemberRepository
    @Autowired protected lateinit var reserveRepository: ReserveRepository
    @Autowired protected lateinit var idempotencyRepository: IdempotencyRepository

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    // 실제 Gmail SMTP 연결 차단
    @MockitoBean protected lateinit var mailSender: JavaMailSender

    protected val txTemplate: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    @BeforeEach
    fun setUpBase() {
        // mock 메일 발송이 NPE 없이 동작하도록 매 호출마다 빈 MimeMessage 반환
        given(mailSender.createMimeMessage()).willAnswer { MimeMessage(Session.getInstance(Properties())) }

        // FK 안전 순서로 전체 삭제 (롤백 대신 수동 정리)
        seatRepository.deleteAll()
        reserveRepository.deleteAll()
        idempotencyRepository.deleteAll()
        performanceScheduleRepository.deleteAll()
        performanceRepository.deleteAll()
        venueRepository.deleteAll()
        memberRepository.deleteAll()
    }

    // --- 픽스처 헬퍼 ---
    protected fun saveMember(
        username: String,
        credit: Long = 300_000,
        reward: Long = 0,
    ): Member = memberRepository.save(
        Member(
            username = username,
            password = "encoded-password",
            name = "테스터-$username",
            role = Role.MEMBER,
            email = "$username@test.com",
            credit = credit,
            reward = reward,
        )
    )

    /**
     * 공연장 + 공연 + 일정 + 좌석을 한 번에 생성하고 scheduleId를 반환
     * 기본 startTime은 미래(취소 가능)로 설정
     */
    protected fun saveScheduleWithSeats(
        price: Long = 10_000,
        seatNumbers: List<String> = listOf("A1"),
        startTime: LocalDateTime = LocalDateTime.now().plusDays(7),
    ): Long {
        val venue = venueRepository.save(Venue(name = "테스트 공연장", location = "서울"))
        val performance = performanceRepository.save(
            Performance(type = "콘서트", title = "테스트 공연", duration = 120, price = price)
        )
        val schedule = performanceScheduleRepository.save(
            PerformanceSchedule(
                venue = venue,
                performance = performance,
                startTime = startTime,
                endTime = startTime.plusHours(2),
            )
        )
        seatRepository.saveAll(seatNumbers.map { Seat(seatNumber = it, performanceSchedule = schedule) })
        return schedule.id!!
    }

    // 좌석 점유 여부 (트랜잭션 안에서 조회해 lazy 접근 안전)
    protected fun isSeatReserved(scheduleId: Long, seatNumber: String): Boolean =
        txTemplate.execute {
            seatRepository.findByPerformanceScheduleIdAndSeatNumber(scheduleId, seatNumber)?.isReserved ?: false
        }!!

    protected fun creditOf(username: String): Long = memberRepository.findByUsername(username)!!.credit

    protected fun rewardOf(username: String): Long = memberRepository.findByUsername(username)!!.reward
}
