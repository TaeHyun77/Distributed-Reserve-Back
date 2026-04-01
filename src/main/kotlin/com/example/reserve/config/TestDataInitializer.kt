package com.example.reserve.config

import com.example.reserve.member.Member
import com.example.reserve.member.MemberRepository
import com.example.reserve.member.Role
import com.example.reserve.performance.repository.PerformanceRepository
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.reserve.reserve.repository.ReserveRepository
import com.example.reserve.seat.Seat
import com.example.reserve.seat.repository.SeatRepository
import com.example.reserve.venue.VenueRepository
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

@RestController
class TestDataInitializer(
        private val performanceScheduleRepository: PerformanceScheduleRepository,
        private val venueRepository: VenueRepository,
        private val performanceRepository: PerformanceRepository,
        private val seatRepository: SeatRepository,
        private val memberRepository: MemberRepository,
        private val passwordEncoder: PasswordEncoder,
        private val reserveRepository: ReserveRepository
) {

    @PostMapping("/reserve/init/load-test")
    @Transactional
    fun initLoadTestData(): ResponseEntity<Map<String, Any>> {
        if (memberRepository.existsByUsername("loadtest1")) {
            val schedule = performanceScheduleRepository.findAll().last()
            return ResponseEntity.ok(mapOf(
                    "message" to "이미 테스트 데이터가 존재합니다.",
                    "scheduleId" to (schedule.id ?: 0)
            ))
        }

        // 1. performanceSchedule 생성
        val schedule = performanceScheduleRepository.save(
                PerformanceSchedule(
                        venue = venueRepository.findAll().first(),
                        performance = performanceRepository.findAll().first(),
                        startTime = LocalDateTime.of(2026, 2, 25, 19, 0),
                        endTime = LocalDateTime.of(2026, 2, 25, 21, 0)
                )
        )

        // 2. 생성한 performanceSchedule의 좌석 생성 - 5000개
        val seats = (1..5000).map { i ->
            Seat(seatNumber = "T$i", performanceSchedule = schedule)
        }
        seatRepository.saveAll(seats)

        // 3. member 생성 - 500명
        val members = (1..500).map { i ->
            Member(
                    username = "loadtest$i",
                    password = passwordEncoder.encode("test1234"),
                    credit = 10_000_000,
                    reward = 0,
                    email = "loadtest$i@test.com",
                    name = "테스트유저$i",
                    role = Role.MEMBER
            )
        }
        memberRepository.saveAll(members)

        return ResponseEntity.ok(
                mapOf(
                        "scheduleId" to (schedule.id ?: 0),
                        "seats" to seats.size,
                        "members" to members.size
                )
        )
    }

    @PostMapping("/reserve/init/reset")
    @Transactional
    fun resetLoadTestData(@RequestParam scheduleId: Long): ResponseEntity<String> {
        seatRepository.resetByScheduleId(scheduleId)       // 1. 좌석의 reserve 참조 해제
        reserveRepository.deleteAllByScheduleId(scheduleId) // 2. 예약 삭제
        memberRepository.resetCreditForLoadTest() // 3. member의 credit 원상복구
        return ResponseEntity.ok("초기화 완료")
    }

    companion object {
        private const val REWARD_TEST_USERNAME = "@Reward123"
        private const val REWARD_TEST_PASSWORD = "reward1234"
    }

    @PostMapping("/reserve/init/reward-test")
    @Transactional
    fun initRewardTestData(): ResponseEntity<Map<String, Any>> {
        val existing = memberRepository.findByUsername(REWARD_TEST_USERNAME)

        if (existing != null) {
            existing.reward = 0
            existing.lastRewardDate = null
            return ResponseEntity.ok(mapOf(
                "message" to "리워드 테스트 유저 초기화 완료",
                "username" to REWARD_TEST_USERNAME
            ))
        }

        memberRepository.save(
            Member(
                username = REWARD_TEST_USERNAME,
                password = passwordEncoder.encode(REWARD_TEST_PASSWORD),
                credit = 300_000,
                reward = 0,
                email = "rewardtest@test.com",
                name = "리워드테스트유저",
                role = Role.MEMBER
            )
        )

        return ResponseEntity.ok(mapOf(
            "message" to "리워드 테스트 유저 생성 완료",
            "username" to REWARD_TEST_USERNAME
        ))
    }

    @PostMapping("/reserve/init/reward-test/cleanup")
    @Transactional
    fun cleanupRewardTestData(): ResponseEntity<String> {
        memberRepository.deleteByUsername(REWARD_TEST_USERNAME)
        return ResponseEntity.ok("리워드 테스트 유저 삭제 완료")
    }
}