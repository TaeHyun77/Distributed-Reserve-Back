package com.example.reserve.reserve.dto

import java.util.UUID

data class ReserveRequest (
    val reservationNumber: String = UUID.randomUUID().toString(),

    val rewardDiscountAmount: Long = 0L,

    val seatNumbers: List<String>,

    val performanceScheduleId: Long,
)