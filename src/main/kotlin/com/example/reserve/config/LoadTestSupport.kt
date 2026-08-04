package com.example.reserve.config

import com.example.reserve.member.Member
import com.example.reserve.member.MemberRepository
import com.example.reserve.member.Role
import com.example.reserve.performance.Performance
import com.example.reserve.performance.repository.PerformanceRepository
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.reserve.reserve.ReserveStatus
import com.example.reserve.reserve.repository.ReserveRepository
import com.example.reserve.seat.repository.SeatRepository
import com.example.reserve.venue.Venue
import com.example.reserve.venue.VenueRepository
import jakarta.mail.internet.MimeMessage
import jakarta.persistence.EntityManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.sql.PreparedStatement
import java.time.LocalDateTime

// ============================================================================
// 부하 테스트 전용 지원 ( @Profile("loadtest") → loadtest 프로파일에서만 빈 등록 )
// 운영 코드 경로(예약/취소 로직)는 건드리지 않고, 시드/리셋/메일무력화만 여기에 격리한다.
// ============================================================================

// 운영의 Gmail SMTP 발송을 무력화한다.
// 발송(send)만 no-op 으로 두어 외부 의존/스로틀/메모리 증가를 제거하고,
// MimeMessage 생성 + Thymeleaf 렌더링 같은 아웃박스 워커 경로의 실제 CPU 비용은 그대로 측정되게 한다.
@Configuration
@Profile("loadtest")
class LoadTestMailConfig {

    private class NoOpMailSender : JavaMailSenderImpl() {
        override fun doSend(mimeMessages: Array<out MimeMessage>, originalMessages: Array<out Any>?) {
            // no-op: 실제 전송하지 않는다
        }
    }

    @Bean
    fun javaMailSender(): JavaMailSender = NoOpMailSender()
}

// 분산 부하 시나리오용 대량 시드 / 런 사이 리셋 엔드포인트.
// 경로를 /reserve/init/** 아래에 두어 SecurityConfig 의 기존 permitAll 을 그대로 활용한다.
@RestController
@Profile("loadtest")
class LoadTestDataController(
    private val venueRepository: VenueRepository,
    private val performanceRepository: PerformanceRepository,
    private val performanceScheduleRepository: PerformanceScheduleRepository,
    private val seatRepository: SeatRepository,
    private val memberRepository: MemberRepository,
    private val reserveRepository: ReserveRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val entityManager: EntityManager,
) {
    companion object {
        private const val LOAD_VENUE = "LoadTest Venue"
        private const val LOAD_PERF = "LoadTest Performance"
        private const val SEAT_CHUNK = 5000
        private const val DEFAULT_CREDIT = 1_000_000_000_000L // 크레딧 고갈로 인한 인위적 실패 방지
    }

    // 멱등 시드: 유저 loadtest1..N (대량 크레딧), 좌석 T1..M, 전용 스케줄 1개.
    // 좌석/유저는 누락분만 생성하므로 수를 늘려 재호출하면 증분 시드된다.
    @PostMapping("/reserve/init/bulk")
    @Transactional
    fun seed(
        @RequestParam(defaultValue = "2000") users: Int,
        @RequestParam(defaultValue = "100000") seats: Int,
        @RequestParam(defaultValue = "$DEFAULT_CREDIT") credit: Long,
    ): Map<String, Any> {
        val venue = venueRepository.findAll().firstOrNull { it.name == LOAD_VENUE }
            ?: venueRepository.save(Venue(name = LOAD_VENUE, location = "LoadTest"))

        val performance = performanceRepository.findAll().firstOrNull { it.title == LOAD_PERF }
            ?: performanceRepository.save(
                Performance(type = "loadtest", title = LOAD_PERF, duration = 120, price = 10_000)
            )

        val schedule = performanceScheduleRepository.findAll().firstOrNull { it.performance.title == LOAD_PERF }
            ?: performanceScheduleRepository.save(
                PerformanceSchedule(
                    venue = venue,
                    performance = performance,
                    startTime = LocalDateTime.now().plusDays(30),
                    endTime = LocalDateTime.now().plusDays(30).plusHours(2),
                )
            )
        val scheduleId = schedule.id!!

        // 유저: 누락분만 생성. throwaway 계정이라 BCrypt cost 를 낮춰(4) 시드/로그인을 가속한다.
        // ( BCrypt 는 해시에 cost 가 박히므로 로그인 검증도 자동으로 빨라진다 — 인증 코드 무수정 )
        val encoder = BCryptPasswordEncoder(4)
        val existingUsers = entityManager
            .createQuery("SELECT m.username FROM Member m WHERE m.username LIKE 'loadtest%'", String::class.java)
            .resultList.toHashSet()
        val newMembers = (1..users)
            .map { "loadtest$it" }
            .filter { it !in existingUsers }
            .map {
                Member(
                    username = it,
                    password = encoder.encode("test1234"),
                    name = it,
                    role = Role.MEMBER,
                    email = "$it@loadtest.local",
                    credit = credit,
                    reward = 0,
                )
            }
        if (newMembers.isNotEmpty()) memberRepository.saveAll(newMembers)

        // 좌석 INSERT 전에 스케줄/유저를 DB 로 flush ( FK 참조 보장 )
        entityManager.flush()

        // 좌석: 누락분만 batch INSERT.
        // Seat 은 IDENTITY PK 라 JPA 가 INSERT 를 배치하지 못한다 → JdbcTemplate + rewriteBatchedStatements 로 가속.
        // 컬럼명은 실제 생성된 스키마 기준 ( naming strategy 가 @JoinColumn 명시명까지 snake_case 화 ):
        // performance_schedule_id, seat_number, reserve_id.
        val existingSeats = (entityManager
            .createQuery("SELECT COUNT(s) FROM Seat s WHERE s.performanceSchedule.id = :sid")
            .setParameter("sid", scheduleId)
            .singleResult as Long)
        var newSeats = 0L
        if (existingSeats < seats) {
            val sql = "INSERT INTO seat (performance_schedule_id, seat_number, reserve_id) VALUES (?, ?, NULL)"
            ((existingSeats + 1)..seats.toLong()).chunked(SEAT_CHUNK).forEach { chunk ->
                jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
                    override fun setValues(ps: PreparedStatement, i: Int) {
                        ps.setLong(1, scheduleId)
                        ps.setString(2, "T${chunk[i]}")
                    }

                    override fun getBatchSize(): Int = chunk.size
                })
                newSeats += chunk.size
            }
        }

        return mapOf(
            "scheduleId" to scheduleId,
            "users" to users,
            "newMembers" to newMembers.size,
            "seats" to seats,
            "newSeats" to newSeats,
        )
    }

    // 런 사이 상태 초기화: 좌석 해제 + 예약/멱등/리프레시 삭제 + 크레딧 복구.
    // 좌석 자체는 유지하므로 빠르다 ( 재시드 불필요 ).
    @PostMapping("/reserve/init/bulk/reset")
    @Transactional
    fun reset(
        @RequestParam scheduleId: Long,
        @RequestParam(defaultValue = "$DEFAULT_CREDIT") credit: Long,
    ): Map<String, Any> {
        seatRepository.resetByScheduleId(scheduleId)        // 좌석 reserve 참조 해제
        reserveRepository.deleteAllByScheduleId(scheduleId) // 예약 삭제
        val idem = entityManager.createQuery("DELETE FROM Idempotency i").executeUpdate()
        val refresh = entityManager.createQuery("DELETE FROM Refresh r").executeUpdate()
        entityManager.createQuery("UPDATE Member m SET m.credit = :c, m.reward = 0 WHERE m.username LIKE 'loadtest%'")
            .setParameter("c", credit)
            .executeUpdate()

        return mapOf(
            "scheduleId" to scheduleId,
            "idempotencyDeleted" to idem,
            "refreshDeleted" to refresh,
        )
    }

    // 오버셀(이중 예약) 검증: 부하 후 정합성 단언용.
    // 정상 불변식: reservedSeats(좌석에 박힌 점유 수) == reserveCount(RESERVED 예약 수) 이고 inventory 이하.
    // POST 로 둔다: SecurityConfig 의 permitAll 이 /reserve/init/** 를 POST 에만 허용하므로 ( read-only 지만 POST )
    @PostMapping("/reserve/init/bulk/verify")
    @Transactional(readOnly = true)
    fun verify(@RequestParam scheduleId: Long): Map<String, Any> {
        val inventory = entityManager
            .createQuery("SELECT COUNT(s) FROM Seat s WHERE s.performanceSchedule.id = :sid")
            .setParameter("sid", scheduleId).singleResult as Long
        val reservedSeats = entityManager
            .createQuery("SELECT COUNT(s) FROM Seat s WHERE s.performanceSchedule.id = :sid AND s.reserve IS NOT NULL")
            .setParameter("sid", scheduleId).singleResult as Long
        val reserveCount = entityManager
            .createQuery("SELECT COUNT(r) FROM Reserve r WHERE r.performanceScheduleId = :sid AND r.status = :st")
            .setParameter("sid", scheduleId).setParameter("st", ReserveStatus.RESERVED).singleResult as Long

        // 오버셀: 재고 초과 점유 또는 (예약수 ≠ 점유좌석수) = 정합성 깨짐
        val oversell = reservedSeats > inventory || reserveCount != reservedSeats
        return mapOf(
            "scheduleId" to scheduleId,
            "inventory" to inventory,
            "reservedSeats" to reservedSeats,
            "reserveCount" to reserveCount,
            "oversell" to oversell,
        )
    }
}
