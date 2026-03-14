package com.example.reserve.performanceSchedule.repository

import com.example.reserve.performanceSchedule.PerformanceSchedule
import org.springframework.data.jpa.repository.JpaRepository

interface PerformanceScheduleRepository: JpaRepository<PerformanceSchedule, Long>, PerformanceScheduleRepositoryCustom {
}