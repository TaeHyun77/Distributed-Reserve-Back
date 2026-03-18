package com.example.reserve.performanceSchedule

import com.example.reserve.performanceSchedule.dto.PerformanceScheduleRequest
import com.example.reserve.performanceSchedule.dto.PerformanceScheduleResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/reserve/performanceSchedule")
@RestController
class PerformanceScheduleController(
    private val performanceScheduleService: PerformanceScheduleService
) {
    @PostMapping("/create")
    fun createPerformanceSchedule(
        @RequestBody performanceScheduleRequest: PerformanceScheduleRequest
    ): ResponseEntity<Unit> {
        performanceScheduleService.createPerformanceSchedule(performanceScheduleRequest)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @GetMapping("/get/list/{venueId}/{performanceId}")
    fun getPerformanceScheduleList(
        @PathVariable("venueId") venueId: Long,
        @PathVariable("performanceId") performanceId: Long
    ): List<PerformanceScheduleResponse> {
        return performanceScheduleService.getPerformanceScheduleList(venueId, performanceId)
    }

    @GetMapping("/get/list/{venueId}")
    fun getPerformanceScheduleListByVenue(
        @PathVariable("venueId") venueId: Long
    ): List<PerformanceScheduleResponse> {
        return performanceScheduleService.getPerformanceScheduleList(venueId)
    }
}