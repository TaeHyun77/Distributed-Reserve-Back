package com.example.reserve.seat

import com.example.reserve.config.Loggable
import com.example.reserve.seat.dto.SeatResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RequestMapping("/reserve/seat")
@RestController
class SeatController(
    private val seatService: SeatService
): Loggable {

    @GetMapping("/get/list/{performanceScheduleId}")
    fun getSeatList(
        @PathVariable("performanceScheduleId") performanceScheduleId: Long
    ): List<SeatResponse> {

        return seatService.getSeatList(performanceScheduleId)
    }
}


