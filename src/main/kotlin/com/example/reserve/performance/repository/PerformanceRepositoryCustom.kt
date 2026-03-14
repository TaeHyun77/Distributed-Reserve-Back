package com.example.reserve.performance.repository

import com.example.reserve.performance.Performance

interface PerformanceRepositoryCustom {

    fun findPerformancesByVenueId(venueId: Long): List<Performance>

}