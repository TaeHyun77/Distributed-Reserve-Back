package com.example.reserve.config

import com.example.reserve.performance.Performance
import com.example.reserve.performance.repository.PerformanceRepository
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.performanceSchedule.repository.PerformanceScheduleRepository
import com.example.reserve.seat.Seat
import com.example.reserve.seat.repository.SeatRepository
import com.example.reserve.venue.Venue
import com.example.reserve.venue.VenueRepository
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
class DataInitController(
    private val venueRepository: VenueRepository,
    private val performanceRepository: PerformanceRepository,
    private val performanceScheduleRepository: PerformanceScheduleRepository,
    private val seatRepository: SeatRepository
) : Loggable {

    @PostMapping("/api/init")
    @Transactional
    fun initData(): ResponseEntity<Map<String, Any>> {
        if (venueRepository.count() > 0) {
            return ResponseEntity.ok(mapOf("message" to "이미 초기 데이터가 존재합니다."))
        }

        // 공연장 3개 생성
        val venues = venueRepository.saveAll(listOf(
            Venue(name = "서울 아트홀", location = "서울특별시"),
            Venue(name = "부산 문화센터", location = "부산광역시"),
            Venue(name = "대구 콘서트홀", location = "대구광역시")
        ))

        // 공연 9개 생성 (공연장당 3개씩 매핑)
        val performances = performanceRepository.saveAll(listOf(
            Performance(type = "뮤지컬", title = "오페라의 유령", duration = 150, price = 150000),
            Performance(type = "연극", title = "햄릿", duration = 120, price = 80000),
            Performance(type = "콘서트", title = "봄날의 선율", duration = 90, price = 120000),
            Performance(type = "뮤지컬", title = "레미제라블", duration = 170, price = 170000),
            Performance(type = "콘서트", title = "재즈 나이트", duration = 120, price = 90000),
            Performance(type = "연극", title = "맥베스", duration = 110, price = 70000),
            Performance(type = "콘서트", title = "클래식 갈라", duration = 120, price = 130000),
            Performance(type = "뮤지컬", title = "위키드", duration = 140, price = 160000),
            Performance(type = "연극", title = "로미오와 줄리엣", duration = 130, price = 85000)
        ))

        // 공연 일정 생성
        val baseTime = LocalDateTime.of(2026, 4, 1, 19, 0)

        val scheduleData = listOf(
            Triple(venues[0], performances[0], baseTime),
            Triple(venues[0], performances[1], baseTime.plusDays(1)),
            Triple(venues[0], performances[2], baseTime.plusDays(2)),
            Triple(venues[1], performances[3], baseTime.plusDays(4)),
            Triple(venues[1], performances[4], baseTime.plusDays(5)),
            Triple(venues[1], performances[5], baseTime.plusDays(6)),
            Triple(venues[2], performances[6], baseTime.plusDays(9)),
            Triple(venues[2], performances[7], baseTime.plusDays(10)),
            Triple(venues[2], performances[8], baseTime.plusDays(11))
        )

        val schedules = performanceScheduleRepository.saveAll(
            scheduleData.map { (venue, performance, startTime) ->
                PerformanceSchedule(
                    venue = venue,
                    performance = performance,
                    startTime = startTime,
                    endTime = startTime.plusHours(2)
                )
            }
        )

        // 각 공연 일정마다 좌석 25개 생성 (A1~E5)
        val allSeats = schedules.flatMap { schedule ->
            ('A'..'E').flatMap { row ->
                (1..5).map { col ->
                    Seat(seatNumber = "$row$col", performanceSchedule = schedule)
                }
            }
        }
        seatRepository.saveAll(allSeats)

        log.info { "초기 데이터 생성 완료: 공연장 ${venues.size}개, 공연 ${performances.size}개, 일정 ${schedules.size}개, 좌석 ${allSeats.size}개" }

        return ResponseEntity.ok(mapOf(
            "message" to "초기 데이터 생성 완료",
            "venues" to venues.size,
            "performances" to performances.size,
            "schedules" to schedules.size,
            "seats" to allSeats.size
        ))
    }
}
