package com.example.reserve.performance

import com.example.reserve.performance.dto.PerformanceRequest
import com.example.reserve.performance.dto.PerformanceResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/reserve/performance")
@RestController
class PerformanceController(
    private val performanceService: PerformanceService
) {

    // 공연 생성
    @PostMapping("/create")
    fun createPerformance(@RequestBody performanceRequest: PerformanceRequest): ResponseEntity<Unit> {
        performanceService.createPerformance(performanceRequest)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }
}