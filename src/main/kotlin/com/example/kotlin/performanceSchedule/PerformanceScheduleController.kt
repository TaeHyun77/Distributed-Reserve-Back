package com.example.kotlin.performanceSchedule

import com.example.kotlin.performanceSchedule.dto.PerformanceScheduleRequest
import com.example.kotlin.performanceSchedule.dto.PerformanceScheduleResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/performanceSchedule")
@RestController
class PerformanceScheduleController(
    private val performanceScheduleService: PerformanceScheduleService
) {

    @PostMapping("/create")
    fun createPerformanceSchedule(
        @RequestBody performanceScheduleRequest: PerformanceScheduleRequest
    ) {
        performanceScheduleService.createPerformanceSchedule(performanceScheduleRequest)
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