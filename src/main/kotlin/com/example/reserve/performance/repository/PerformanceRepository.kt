package com.example.reserve.performance.repository

import com.example.reserve.performance.Performance
import org.springframework.data.jpa.repository.JpaRepository

interface PerformanceRepository: JpaRepository<Performance, Long> {
}