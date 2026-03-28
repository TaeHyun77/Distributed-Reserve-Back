package com.example.reserve.email.dto

import com.example.reserve.reserve.ReserveStatus
import java.time.LocalDateTime

// 이메일 발송용 데이터 (JPA 엔티티 포함 금지 — LAZY 로딩 안전)
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
)
