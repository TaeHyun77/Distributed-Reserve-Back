package com.example.reserve.performance

import com.example.reserve.performance.dto.PerformanceRequest
import com.example.reserve.performance.dto.PerformanceResponse
import com.example.reserve.performance.repository.PerformanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PerformanceService(
    private val performanceRepository: PerformanceRepository,
) {

    // 공연 생성
    @Transactional
    fun createPerformance(performanceRequest: PerformanceRequest) {
        performanceRepository.save(performanceRequest.toEntity())
    }
}