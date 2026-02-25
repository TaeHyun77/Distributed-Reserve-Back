package com.example.kotlin.performance

import com.example.kotlin.performance.dto.PerformanceRequest
import com.example.kotlin.performance.dto.PerformanceResponse
import com.example.kotlin.performance.repository.PerformanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PerformanceService(
    private val performanceRepository: PerformanceRepository,
) {

    @Transactional
    fun createPerformance(performanceRequest: PerformanceRequest) {
        performanceRepository.save(performanceRequest.toEntity())
    }

    @Transactional
    fun getPerformanceList(venueId: Long): List<PerformanceResponse> {
        val performanceList = performanceRepository.findPerformancesByVenueId(venueId)

        return performanceList.map(PerformanceResponse::from)
    }
}