package com.example.reserve.performance

import com.example.reserve.performance.dto.PerformanceRequest
import com.example.reserve.performance.dto.PerformanceResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/performance")
@RestController
class PerformanceController(
    private val performanceService: PerformanceService
) {

    // 공연 생성
    @PostMapping("/create")
    fun createPerformance(@RequestBody performanceRequest: PerformanceRequest) {
        return performanceService.createPerformance(performanceRequest)
    }

    // 공연 목록 반환
    @GetMapping("/get/list/{venueId}")
    fun getPerformanceList(
        @PathVariable("venueId") venueId: Long
    ): List<PerformanceResponse> {

        return performanceService.getPerformanceList(venueId)
    }
}