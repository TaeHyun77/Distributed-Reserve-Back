package com.example.reserve.reserve.dto

import java.util.UUID

data class ReserveRequest (
    val reservationNumber: String = UUID.randomUUID().toString(),

    val rewardDiscountAmount: Long = 0L,

    val reservedSeat: List<String>,

    val performanceScheduleId: Long,
)