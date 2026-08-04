package com.example.reserve.email.dto

import com.example.reserve.member.Member
import com.example.reserve.performanceSchedule.PerformanceSchedule
import com.example.reserve.reserve.Reserve
import com.example.reserve.reserve.ReserveStatus
import java.time.LocalDateTime

// 이메일 발송용 데이터 (JPA 엔티티 포함 금지 — LAZY 로딩 안전, 아웃박스 JSON 직렬화 대상)
data class ReservationEmailData(
    val toEmail: String,
    val memberName: String,
    val reservationNumber: String,
    val status: ReserveStatus,
    val totalAmount: Long,
    val rewardDiscountAmount: Long,
    val finalAmount: Long,
    val seatNumbers: List<String>,
    val performanceTitle: String,
    val performanceType: String,
    val venueName: String,
    val venueLocation: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val reservedAt: LocalDateTime?,
    val cancelledAt: LocalDateTime?
) {
    companion object {
        fun from(
            reserve: Reserve,
            member: Member,
            performanceSchedule: PerformanceSchedule,
            seatNumbers: List<String>
        ): ReservationEmailData = ReservationEmailData(
            toEmail = member.email,
            memberName = member.name,
            reservationNumber = reserve.reservationNumber,
            status = reserve.status,
            totalAmount = reserve.totalAmount,
            rewardDiscountAmount = reserve.rewardDiscountAmount,
            finalAmount = reserve.finalAmount,
            seatNumbers = seatNumbers,
            performanceTitle = performanceSchedule.performance.title,
            performanceType = performanceSchedule.performance.type,
            venueName = performanceSchedule.venue.name,
            venueLocation = performanceSchedule.venue.location,
            startTime = performanceSchedule.startTime,
            endTime = performanceSchedule.endTime,
            reservedAt = reserve.createdAt,
            cancelledAt = reserve.cancelledAt
        )
    }
}
